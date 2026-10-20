import Combine
import Foundation

public enum SkinState: Equatable {
    case notComputed
    case computing
    case ready(SkinMask)
    case failed

    public var mask: SkinMask? { if case let .ready(m) = self { m } else { nil } }

    public static func == (a: SkinState, b: SkinState) -> Bool {
        switch (a, b) {
        case (.notComputed, .notComputed), (.computing, .computing), (.failed, .failed): true
        case let (.ready(x), .ready(y)): x.data.withUnsafeBufferPointer { $0.baseAddress } == y.data.withUnsafeBufferPointer { $0.baseAddress }
        default: false
        }
    }
}

public struct BlemishState: Equatable, Sendable {
    public var working = false
    /** Spots currently healed in the image. */
    public var healed = 0
    /** Candidates found on this photo. */
    public var detected = 0
}

/**
 * One open photo and everything the develop pipeline needs for it: the decoded raw,
 * its preview and whole-image textures, skin mask, blemish healing, the 100% view's
 * tiles, LUTs and export. Holds no UI state; apps keep their own and pass the
 * current DevelopParams in through [setParams].
 *
 * Main-actor bound; heavy work runs in detached tasks.
 */
@MainActor
public final class PhotoSession: ObservableObject {
    // GPUs on every supported iPhone take 16k textures; 2048 matches Android's preview.
    public nonisolated static let previewMaxDimension = 2048
    public nonisolated static let baseMaxDimension = 512
    public nonisolated static let bloomTailMaxDimension = 64

    /** What [open] found in the file. */
    public struct Opened {
        public let description: String
        public let defaults: DevelopParams
        public let params: DevelopParams
    }

    @Published public private(set) var preview: PreviewImage?
    /** ~512 px copy of the whole image: the wide blur behind high-pass contrast. */
    @Published public private(set) var base: PreviewImage?
    /** Skin mask for the current photo; computed the first time Skin retouch is used. */
    @Published public private(set) var skin: SkinState = .notComputed
    /** Automatic blemish removal for the current photo. */
    @Published public private(set) var blemish = BlemishState()
    /** Highlight-boosted small copies that bloom scatters light from. */
    @Published public private(set) var bloomSources: BloomSources?
    /** Centre of the 100% view in full-resolution pixels; nil = fit to view. */
    @Published public private(set) var zoomCenter: (Float, Float)?
    /** Full-resolution region around the zoom centre, uploaded for the 100% view. */
    @Published public private(set) var detail: DetailTile?
    @Published public private(set) var lensInfo = LensInfo.none()
    /** The loaded LUT for the selected id (nil while loading or when none is selected). */
    @Published public private(set) var lutData: LutData?
    /** The loaded film stock for the selected id (nil while loading or when none is selected). */
    @Published public private(set) var filmData: FilmData?

    /** Soft failures to show the user (e.g. skin detection failing). */
    private let onMessage: (String) -> Void
    private let lutCacheSize: Int
    /** Loads LUTs; apps with their own LUT cache pass it so a LUT is loaded only once. */
    private let lutLoader: ((String) async throws -> LutData)?
    /** Loads film stocks (DevelopParams.film) with both LUTs; nil = the session can't show film looks. */
    private let filmLoader: ((String) async throws -> FilmData)?
    private var selectedFilm: String?

    private var skinTask: Task<Void, Never>?
    private var blemishTask: Task<Void, Never>?
    /** (x, y, radius, score) per candidate, most obvious first; detected once per photo. */
    private var blemishSpots: [Float]?
    private var selectedLut: String?
    /** Recently used LUTs, most recent last, so switching back and forth is instant. */
    private var lutCache: [(String, LutData)] = []
    private var image: RawImage?
    private var params = DevelopParams()
    private var viewWidth = 0
    private var viewHeight = 0
    /** Painted masks by layer id (see brushRaster). */
    private var brushes: [String: BrushRaster] = [:]
    private var guide: BrushGuide?
    private var guideSource: [UInt16]?
    private var tileTask: Task<Void, Never>?
    private var lensCache: (key: [AnyHashable], crop: CropRect, transform: LensTransform)?

    public init(lutCacheSize: Int = 4, lutLoader: ((String) async throws -> LutData)? = nil,
                filmLoader: ((String) async throws -> FilmData)? = nil, onMessage: @escaping (String) -> Void) {
        self.lutCacheSize = lutCacheSize
        self.lutLoader = lutLoader
        self.filmLoader = filmLoader
        self.onMessage = onMessage
    }

    public var hasImage: Bool { image != nil }

    /** The open photo, for exports run elsewhere; nil when nothing is open. */
    public var rawImage: RawImage? { image }

    /** ISO of the open photo, 0 if unknown or nothing is open. */
    public var iso: Float { image?.iso ?? 0 }

    /** Whether the open photo carries the camera's own exposure (see AutoTone.cameraMetered). */
    public var cameraMetered: Bool {
        image.map { AutoTone.cameraMetered(make: $0.make, baselineExposure: $0.baselineExposure) } ?? false
    }

    /** The skin mask, computed now if it hasn't been (for face metering); nil if there is none or it failed. */
    public func skinMask() async -> SkinMask? {
        ensureSkinMask(force: true)
        await skinTask?.value
        return skin.mask
    }

    /**
     * Decodes [url] and swaps it in. [initialParams] maps the file's as-shot defaults
     * to the params to start with (e.g. carrying edits over from the previous photo).
     * Throws on failure; the previous photo stays open then.
     */
    public func open(_ url: URL, initialParams: (DevelopParams) -> DevelopParams) async throws -> Opened {
        let loaded = try await Task.detached(priority: .userInitiated) { try Self.load(url) }.value
        exitZoom()
        image?.close()
        image = loaded.image
        lensInfo = loaded.image.lens

        let defaults = Self.asShotDefaults(loaded.image)
        preview = loaded.preview
        base = loaded.base
        bloomSources = loaded.bloom
        skinTask?.cancel()
        skin = .notComputed
        blemishTask?.cancel()
        blemishSpots = nil
        blemish = BlemishState()
        brushes.removeAll()
        guide = nil
        guideSource = nil
        params = initialParams(defaults)
        ensureSkinMask()
        // Camera and exposure (ISO, shutter, aperture, focal length); the decoder's summary if none of it is known.
        let line = loaded.image.shotInfo.line()
        return Opened(description: line.isEmpty ? loaded.image.description : line, defaults: defaults, params: params)
    }

    /** Applies new slider values: starts skin detection / blemish healing and keeps the zoom tile valid. */
    public func setParams(_ newParams: DevelopParams) {
        let before = params
        params = newParams
        if newParams.skinRetouch > 0 || newParams.blemishRemoval > 0 { ensureSkinMask() }
        if newParams.blemishRemoval != before.blemishRemoval { scheduleBlemishes() }
        // A 90 degree turn or a new crop changes the output shape, and a flip moves the content, so the zoom position
        // no longer applies.
        let flipChanged = newParams.flipHorizontal != before.flipHorizontal || newParams.flipVertical != before.flipVertical
        if newParams.rotation != before.rotation || newParams.crop != before.crop || flipChanged { exitZoom() } else { recheckZoomTile() }
    }

    /** Uniforms for [p] with this photo's current textures; [maskView]: the layer whose mask is shown in red. */
    public func shaderParams(_ p: DevelopParams, defaults: DevelopParams, lut: LutData? = nil, useSelectedLut: Bool = true,
                             maskView: String? = nil, film: FilmData? = nil) -> ShaderParams {
        ShaderParams.from(p, asShot: defaults, lut: lut ?? (useSelectedLut ? lutData : nil), lens: lensFor(p), base: base,
                          bloomSources: bloomSources, skinMask: skin.mask, maskView: maskView,
                          brushFor: { [unowned self] in self.brushRaster($0) }, film: film ?? (useSelectedLut ? filmData : nil))
    }

    /**
     * The painted mask of [layer] brought up to its strokes (incrementally while a stroke grows), or nil without
     * strokes. Rasters are the preview's size and live as long as the photo.
     */
    public func brushRaster(_ layer: MaskLayer) -> BrushRaster? {
        guard let preview, !layer.strokes.isEmpty else {
            brushes[layer.id] = nil
            return nil
        }
        var r = brushes[layer.id]
        if r == nil || r!.width != preview.width || r!.height != preview.height {
            r = BrushRaster(width: preview.width, height: preview.height) { [unowned self] in
                MainActor.assumeIsolated { self.brushGuide(preview) }
            }
            brushes[layer.id] = r
        }
        r!.sync(layer.strokes)
        return r
    }

    /** Forgets a deleted layer's painted mask. */
    public func dropBrush(_ layerId: String) { brushes[layerId] = nil }

    /** Auto mask guide, made from the preview the first time an Auto mask stroke needs it (blemish healing replaces the preview). */
    private func brushGuide(_ preview: PreviewImage) -> BrushGuide {
        let sameSource = guideSource.map { $0.withUnsafeBufferPointer { $0.baseAddress } == preview.pixels.withUnsafeBufferPointer { $0.baseAddress } } ?? false
        if let guide, sameSource { return guide }
        let g = BrushGuide.of(preview)
        guide = g
        guideSource = preview.pixels
        return g
    }

    public func setViewSize(_ width: Int, _ height: Int) {
        viewWidth = width
        viewHeight = height
        if let c = zoomCenter { setZoomCenter(c.0, c.1) }
    }

    /** Fit-view position -> full-resolution output pixel, or nil outside the photo. */
    public func fitToImage(_ viewX: Float, _ viewY: Float, frameWidth: Int) -> (Float, Float)? {
        guard image != nil, viewWidth > 0, viewHeight > 0 else { return nil }
        let lens = lensFor(params)
        return ZoomMath.fitToImage(px: viewX, py: viewY, viewW: viewWidth, viewH: viewHeight, imgW: lens.outputWidth,
                                   imgH: lens.outputHeight, frame: frameWidth)
    }

    /**
     * White balance picker at a point of the preview (fit or 100% view; [frameWidth] as for [fitToImage]): the decoded
     * pixels shown there, found through crop, rotation and lens correction, averaged before any develop step.
     * Nil when the point is outside the photo or nothing is open.
     */
    public func pickWhite(_ viewX: Float, _ viewY: Float, frameWidth: Int, defaults: DevelopParams) async -> WhitePicker.Pick? {
        guard let img = image, viewWidth > 0, viewHeight > 0 else { return nil }
        let lens = lensFor(params)
        let w = lens.outputWidth
        let h = lens.outputHeight
        let r: [Float]
        if let z = zoomCenter {
            r = ZoomMath.zoomedRect(cx: z.0, cy: z.1, viewW: viewWidth, viewH: viewHeight, imgW: w, imgH: h)
        } else {
            r = ZoomMath.fitRect(viewW: viewWidth, viewH: viewHeight, imgW: w, imgH: h, frame: frameWidth)
        }
        let outX = (viewX - r[0]) / r[2] * Float(w)
        let outY = (viewY - r[1]) / r[3] * Float(h)
        guard outX >= 0, outX <= Float(w), outY >= 0, outY <= Float(h) else { return nil }
        let src = lens.source(Double(outX), Double(outY))
        let b = WhitePicker.box(Float(src[0]), Float(src[1]), img.width, img.height)
        let kelvin = defaults.temperature
        let tint = defaults.tint
        return await Task.detached(priority: .userInitiated) {
            let bw = b.x1 - b.x0
            let bh = b.y1 - b.y0
            var halves = [UInt16](repeating: 0, count: bw * bh * 3)
            img.copyTile(x: b.x0, y: b.y0, w: bw, h: bh, out: &halves)
            let rgb = halves.map { Float(Float16(bitPattern: $0)) }
            return WhitePicker.pick(rgb, asShotKelvin: kelvin, asShotTint: tint)
        }.value
    }

    /** Double tap: zoom to 100% at the tapped point of the fit view, or back out. */
    public func toggleZoom(_ viewX: Float, _ viewY: Float, frameWidth: Int) {
        guard image != nil else { return }
        if zoomCenter != nil {
            exitZoom()
            return
        }
        guard let p = fitToImage(viewX, viewY, frameWidth: frameWidth) else { return }
        setZoomCenter(p.0, p.1)
    }

    /** Drag in the 100% view; [dx], [dy] are view pixels = full-resolution pixels. */
    public func pan(_ dx: Float, _ dy: Float) {
        guard let c = zoomCenter else { return }
        setZoomCenter(c.0 - dx, c.1 - dy)
    }

    public func exitZoom() {
        tileTask?.cancel()
        tileTask = nil
        zoomCenter = nil
        detail = nil
    }

    private func setZoomCenter(_ cx: Float, _ cy: Float) {
        guard let img = image else { return }
        let lens = lensFor(params)
        let c = ZoomMath.clampCenter(cx: cx, cy: cy, viewW: viewWidth, viewH: viewHeight, imgW: lens.outputWidth,
                                     imgH: lens.outputHeight)
        zoomCenter = c
        ensureDetail(img, c)
    }

    /** Loads a new tile only when the visible region leaves the current one. */
    private func ensureDetail(_ img: RawImage, _ c: (Float, Float)) {
        if tileTask != nil { return }
        let lens = lensFor(params)
        let view = ZoomMath.viewRect(cx: c.0, cy: c.1, viewW: viewWidth, viewH: viewHeight, imgW: lens.outputWidth,
                                     imgH: lens.outputHeight)
        // Source pixels the view needs, through rotation and lens geometry, plus the denoise halo.
        let needed = lens.sourceBounds(view, margin: spatialMargin(params))
        if let current = detail, current.rect.contains(needed) { return }

        let rect = ZoomMath.tileAround(needed, imgW: img.width, imgH: img.height)
        tileTask = Task {
            let tile = await Task.detached(priority: .userInitiated) { img.createTile(rect) }.value
            tileTask = nil
            guard !Task.isCancelled, image === img, zoomCenter != nil else { return }
            detail = tile
            // The user may have panned past this tile while it loaded.
            if let c = zoomCenter { ensureDetail(img, c) }
        }
    }

    /** Lens geometry changes can move the view's source outside the loaded tile. */
    private func recheckZoomTile() {
        guard let img = image, let c = zoomCenter else { return }
        ensureDetail(img, c)
    }

    /** Runs skin segmentation once per photo, when Skin retouch is first non-zero (or when [force]d). */
    private func ensureSkinMask(force: Bool = false) {
        let p = params
        if (!force && p.skinRetouch <= 0 && p.blemishRemoval <= 0) || skin != .notComputed { return }
        guard let img = image, let base else { return }
        skin = .computing
        skinTask = Task {
            do {
                let mask = try await Task.detached(priority: .userInitiated) {
                    try SkinSegmenter.segment(base, fullWidth: img.width, fullHeight: img.height)
                }.value
                guard image === img, !Task.isCancelled else { return }
                skin = .ready(mask)
            } catch {
                // Fail softly, never crash: the photo is treated as having no face.
                guard image === img, !Task.isCancelled else { return }
                onMessage("Skin detection failed: \(error.localizedDescription)")
                skin = .failed
            }
            recheckZoomTile()  // the skin smoothing radius widens the tiles the view needs
            scheduleBlemishes()
        }
    }

    /**
     * Heals the blemishes the slider asks for, ~300 ms after it settles: spots are
     * detected once per photo, then the most obvious ones (by score) are healed in the
     * full-resolution image and the previews rebuilt from it.
     */
    private func scheduleBlemishes() {
        blemishTask?.cancel()
        blemishTask = Task {
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled, let img = image else { return }
            let level = params.blemishRemoval
            var wanted: [Float] = []
            if level > 0 {
                guard let mask = skin.mask, mask.hasSkin else { return }  // runs again when the mask is ready
                let spots: [Float]
                if let known = blemishSpots {
                    spots = known
                } else {
                    blemish.working = true
                    spots = await Task.detached(priority: .userInitiated) { img.detectBlemishes(mask) }.value
                    blemishSpots = spots
                }
                wanted = blemishesToHeal(spots, level: level)
            }
            let count = wanted.count / 4
            if count == blemish.healed {
                blemish.working = false
                return
            }
            blemish.working = true
            // Not cancelled midway: the full-resolution image and its previews must stay in step.
            let rebuilt = await Task.detached(priority: .userInitiated) {
                img.healBlemishes(wanted)
                return (img.createPreview(maxDimension: Self.previewMaxDimension),
                        img.createPreview(maxDimension: Self.baseMaxDimension), Self.bloomSourcesOf(img))
            }.value
            guard image === img else { return }
            preview = rebuilt.0
            base = rebuilt.1
            bloomSources = rebuilt.2
            detail = nil  // the 100% tile shows old pixels; reload it
            recheckZoomTile()
            blemish = BlemishState(working: false, healed: count, detected: (blemishSpots?.count ?? 0) / 4)
        }
    }

    /** Source pixels needed around a region for the spatial effects (see ShaderParams.spatialMargin). */
    private func spatialMargin(_ params: DevelopParams) -> Int {
        var retouch = 0
        if params.skinRetouch > 0, let mask = skin.mask, mask.hasSkin { retouch = Int(mask.sigmaPx * 2.4) + 4 }
        let wide = ShaderParams.wideDenoise(max(params.denoise, params.effectiveColorDenoise)) > 0 ? ShaderParams.wideDenoiseMargin : 0
        return max(ZoomMath.halo, retouch, wide)
    }

    /** Lens geometry for [params] on the current image; cached while the lens sliders are unchanged. */
    public func lensFor(_ params: DevelopParams) -> LensTransform {
        let info = lensInfo
        let key: [AnyHashable] = [
            ObjectIdentifier(info), params.lensProfile, params.lensDistortion, params.vignetteAmount,
            params.vignetteMidpoint, params.rotation, params.flipHorizontal, params.flipVertical, params.straighten,
        ]
        let crop = params.crop
        if let c = lensCache {
            if c.key == key && c.crop == crop { return c.transform }
            // Only the crop changed: the auto scale is computed on the full frame, so reuse it.
            if c.key == key {
                let t = LensTransform(info: info, params: params, knownZoom: c.transform.zoom)
                lensCache = (key, crop, t)
                return t
            }
        }
        let t = LensTransform(info: info, params: params)
        lensCache = (key, crop, t)
        return t
    }

    /**
     * Shows LUT [id] (nil = none) once it is loaded. On failure [onError] runs, unless
     * another LUT was selected meanwhile.
     */
    public func selectLut(_ id: String?, onError: @escaping (String) -> Void) {
        if id == selectedLut && (id == nil || lutData?.id == id) { return }  // already shown
        selectedLut = id
        guard let id else { return }
        Task {
            do {
                let data = try await loadLut(id)
                if selectedLut == id { lutData = data }
            } catch {
                if selectedLut == id { onError("Could not load LUT: \(error.localizedDescription)") }
            }
        }
    }

    /** Shows film stock [id] (nil = none) once it is loaded, as [selectLut]. Needs a filmLoader. */
    public func selectFilm(_ id: String?, onError: @escaping (String) -> Void) {
        if id == selectedFilm && (id == nil || filmData?.stock.id == id) { return }
        selectedFilm = id
        guard let id, let filmLoader else { return }
        Task {
            do {
                let data = try await filmLoader(id)
                if selectedFilm == id { filmData = data }
            } catch {
                if selectedFilm == id { onError("Could not load film: \(error.localizedDescription)") }
            }
        }
    }

    public func loadLut(_ id: String) async throws -> LutData {
        if let lutLoader { return try await lutLoader(id) }
        if let i = lutCache.firstIndex(where: { $0.0 == id }) {
            let hit = lutCache.remove(at: i)
            lutCache.append(hit)
            return hit.1
        }
        let data = try await Task.detached(priority: .userInitiated) { try LutLoader.load(id) }.value
        lutCache.removeAll { $0.0 == id }
        lutCache.append((id, data))
        if lutCache.count > lutCacheSize { lutCache.removeFirst() }
        return data
    }

    /** Develops the full-resolution photo with [p] and writes it; returns the file (move it to Photos next). */
    public func export(_ p: DevelopParams, defaults: DevelopParams, sourceName: String, settings: ExportSettings,
                       naming: OutputNaming = OutputNaming(),
                       onProgress: @escaping @Sendable (Float) -> Void) async throws -> URL {
        guard let img = image else { throw RawImageError(message: "No photo open") }
        let lut = try await (p.film == nil ? p.lut : nil).asyncMap { try await loadLut($0) }
        var film: FilmData?
        if let id = p.film, let filmLoader { film = try await filmLoader(id) }
        let params = shaderParams(p, defaults: defaults, lut: lut, useSelectedLut: false, film: film)
        return try await Task.detached(priority: .userInitiated) {
            try Exporter().export(img, sourceName: sourceName, params: params, settings: settings, naming: naming,
                                  onProgress: onProgress)
        }.value
    }

    public func close() {
        image?.close()
        image = nil
    }

    /** The file's own white balance as slider defaults; everything else untouched. */
    public nonisolated static func asShotDefaults(_ image: RawImage) -> DevelopParams {
        let white = image.asShotWhite
        let tt = WhiteBalance.tempTintFromXY(white[0], white[1])
        var p = DevelopParams()
        p.temperature = Float(tt[0])
        p.tint = Float(tt[1])
        p.displaySource = image.displayReferred
        return p
    }

    /** The highlight-boosted halo (512 px) and tail (64 px, already scattered) copies bloom scatters light from. */
    public nonisolated static func bloomSourcesOf(_ image: RawImage) -> BloomSources {
        BloomSources(halo: image.createPreview(maxDimension: baseMaxDimension, boostHighlights: true),
                     tail: BloomTail.scatter(image.createPreview(maxDimension: bloomTailMaxDimension, boostHighlights: true)))
    }

    private struct Loaded: @unchecked Sendable {
        let image: RawImage
        let preview: PreviewImage
        let base: PreviewImage
        let bloom: BloomSources
    }

    private nonisolated static func load(_ url: URL) throws -> Loaded {
        let img = try RawImage.open(url: url)
        return Loaded(image: img, preview: img.createPreview(maxDimension: previewMaxDimension),
                      base: img.createPreview(maxDimension: baseMaxDimension), bloom: bloomSourcesOf(img))
    }
}

/**
 * The detected [spots] (x, y, radius, score; strongest first) that Blemish removal [level] 1..100 heals: those that
 * stand out at least 5 .. 3 standard deviations from the surrounding skin texture (low values: only the most obvious ones).
 */
public func blemishesToHeal(_ spots: [Float], level: Float) -> [Float] {
    let minScore = 5 - 2 * level / 100
    let count = (0..<(spots.count / 4)).filter { spots[$0 * 4 + 3] >= minScore }.count
    return Array(spots.prefix(count * 4))
}

public extension Optional {
    func asyncMap<T>(_ transform: (Wrapped) async throws -> T) async rethrows -> T? {
        guard let self else { return nil }
        return try await transform(self)
    }
}
