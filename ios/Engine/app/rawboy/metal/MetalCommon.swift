import Foundation
import Metal

/** Linear Rec.2020 half-float RGB pixels, row 0 = top of the image. */
public struct PreviewImage: @unchecked Sendable {
    public let width: Int
    public let height: Int
    public let pixels: [UInt16]
    /** Size of the full-resolution image this preview was made from. */
    public let fullWidth: Int
    public let fullHeight: Int

    public init(width: Int, height: Int, pixels: [UInt16], fullWidth: Int, fullHeight: Int) {
        self.width = width
        self.height = height
        self.pixels = pixels
        self.fullWidth = fullWidth
        self.fullHeight = fullHeight
    }
}

/**
 * Where the bound texture ([input], in full-resolution pixels) and the rendered
 * quad ([output]) sit in the full-resolution image. They differ when lens
 * correction moves pixels, and in tiled export. Spatial effects (denoise radius,
 * grain) are sized in full-resolution pixels.
 */
struct SampleGeometry {
    let textureWidth: Int
    let textureHeight: Int
    let input: PixelRect
    let output: PixelRect
    /** Full-resolution image size, which sets the grain size. */
    let imageWidth: Int
    let imageHeight: Int
    /** Smallest grain cell in full-resolution pixels: more than 1 when the export is saved smaller. */
    var minGrainPx: Float = 1

    /** Grain cell in full-resolution pixels: ~1/3200 of the long edge (fine-grain film). */
    var grainSize: Float { max(minGrainPx, Float(max(imageWidth, imageHeight)) / 3200) }
}

public struct MetalError: LocalizedError {
    public let message: String
    public var errorDescription: String? { message }
}

/**
 * The device, the develop shader's pipelines (generated from <repo>/shaders by ios/shaders/gen.sh) and the one
 * sampler every texture uses (linear, clamp to edge, as Android's textures). Shared by every renderer.
 */
final class MetalContext: @unchecked Sendable {
    static let shared: MetalContext = {
        do {
            return try MetalContext()
        } catch {
            fatalError("Metal unavailable: \(error)")
        }
    }()

    /** Vertex buffer index of the quad: clear of the vertex uniform block. */
    static let quadBufferIndex = 30
    /** Preview drawables: the shader writes display-encoded values itself, so no sRGB conversion. */
    static let previewPixelFormat = MTLPixelFormat.bgra8Unorm

    let device: MTLDevice
    let queue: MTLCommandQueue
    let library: MTLLibrary
    let previewPipeline: MTLRenderPipelineState
    let exportPipeline: MTLRenderPipelineState
    let sampler: MTLSamplerState
    let quad: MTLBuffer

    private init() throws {
        guard let device = MTLCreateSystemDefaultDevice(), let queue = device.makeCommandQueue() else {
            throw MetalError(message: "no Metal device")
        }
        self.device = device
        self.queue = queue
        library = try device.makeDefaultLibrary(bundle: Bundle(for: MetalContext.self))
        previewPipeline = try Self.pipeline(device, library, fragment: "developFragment", format: Self.previewPixelFormat)
        exportPipeline = try Self.pipeline(device, library, fragment: "developExportFragment", format: .rgba16Uint)
        let s = MTLSamplerDescriptor()
        s.minFilter = .linear
        s.magFilter = .linear
        s.sAddressMode = .clampToEdge
        s.tAddressMode = .clampToEdge
        s.rAddressMode = .clampToEdge
        guard let sampler = device.makeSamplerState(descriptor: s) else { throw MetalError(message: "no sampler") }
        self.sampler = sampler
        let vertices: [Float] = [-1, -1, 1, -1, -1, 1, 1, 1]
        guard let quad = device.makeBuffer(bytes: vertices, length: vertices.count * 4, options: .storageModeShared)
        else { throw MetalError(message: "no quad buffer") }
        self.quad = quad
    }

    private static func pipeline(_ device: MTLDevice, _ library: MTLLibrary, fragment: String,
                                 format: MTLPixelFormat) throws -> MTLRenderPipelineState {
        let d = MTLRenderPipelineDescriptor()
        d.vertexFunction = library.makeFunction(name: "developVertex")
        d.fragmentFunction = library.makeFunction(name: fragment)
        d.colorAttachments[0].pixelFormat = format
        let v = MTLVertexDescriptor()
        v.attributes[0].format = .float2
        v.attributes[0].offset = 0
        v.attributes[0].bufferIndex = quadBufferIndex
        v.layouts[quadBufferIndex].stride = 8
        d.vertexDescriptor = v
        return try device.makeRenderPipelineState(descriptor: d)
    }

    func makeTexture(_ format: MTLPixelFormat, _ width: Int, _ height: Int,
                     usage: MTLTextureUsage = .shaderRead) -> MTLTexture? {
        let d = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: format, width: max(1, width),
                                                         height: max(1, height), mipmapped: false)
        d.usage = usage
        d.storageMode = .shared
        return device.makeTexture(descriptor: d)
    }
}

/** A std140 uniform block written by name, using the offsets SPIRV-Cross reported (DevelopLayout). */
struct UniformBlock {
    private(set) var bytes: [UInt8]
    private let layout: [String: DevelopLayout.Uniform]

    init(size: Int, layout: [String: DevelopLayout.Uniform]) {
        bytes = [UInt8](repeating: 0, count: size)
        self.layout = layout
    }

    private mutating func write<T>(_ value: T, at offset: Int) {
        withUnsafeBytes(of: value) { src in
            bytes.withUnsafeMutableBytes { dst in
                dst.baseAddress!.advanced(by: offset).copyMemory(from: src.baseAddress!, byteCount: src.count)
            }
        }
    }

    private func slot(_ name: String) -> DevelopLayout.Uniform? { layout[name] }

    mutating func float(_ name: String, _ v: Float) { if let u = slot(name) { write(v, at: u.offset) } }
    mutating func int(_ name: String, _ v: Int) { if let u = slot(name) { write(Int32(v), at: u.offset) } }

    mutating func vec2(_ name: String, _ x: Float, _ y: Float) {
        guard let u = slot(name) else { return }
        write(x, at: u.offset)
        write(y, at: u.offset + 4)
    }

    mutating func vec3(_ name: String, _ x: Float, _ y: Float, _ z: Float) {
        guard let u = slot(name) else { return }
        for (k, v) in [x, y, z].enumerated() { write(v, at: u.offset + k * 4) }
    }

    mutating func vec3(_ name: String, _ v: [Float], _ k: Float = 1) { vec3(name, v[0] * k, v[1] * k, v[2] * k) }

    mutating func vec4(_ name: String, _ x: Float, _ y: Float, _ z: Float, _ w: Float) {
        guard let u = slot(name) else { return }
        for (k, v) in [x, y, z, w].enumerated() { write(v, at: u.offset + k * 4) }
    }

    /** Arrays of [count] vec4 (flat), or of floats (each in its own 16-byte slot, as std140 lays them out). */
    mutating func array(_ name: String, _ values: [Float], components: Int) {
        guard let u = slot(name) else { return }
        let count = values.count / components
        for i in 0..<count {
            for c in 0..<components { write(values[i * components + c], at: u.offset + i * u.arrayStride + c * 4) }
        }
    }

    /** One or more column-major mat3 (9 floats each), columns [matrixStride] apart. */
    mutating func mat3(_ name: String, _ m: [Float]) {
        guard let u = slot(name) else { return }
        let stride = max(u.arrayStride, 3 * u.matrixStride)
        for i in 0..<(m.count / 9) {
            for col in 0..<3 {
                for row in 0..<3 { write(m[i * 9 + col * 3 + row], at: u.offset + i * stride + col * u.matrixStride + row * 4) }
            }
        }
    }
}

/** The develop shader's uniforms for one draw: Android's DevelopProgram.bind, into two uniform blocks. */
struct DevelopProgram {
    private static let fullUvRect: [Float] = [0, 0, 1, 1]

    /**
     * Encodes uniforms and the input [image] texture, plus every whole-image texture in [inputs], and draws the quad.
     * [flipY]: true puts output row 0 at the top of the render target (Metal's row 0); see OffscreenDeveloper.
     */
    static func draw(_ encoder: MTLRenderCommandEncoder, pipeline: MTLRenderPipelineState, params p: ShaderParams,
                     geometry g: SampleGeometry, image: MTLTexture, inputs: DevelopTextures, scaleX: Float = 1,
                     scaleY: Float = 1, flipY: Bool, uvRect: [Float] = fullUvRect,
                     overrides: [String: MTLTexture] = [:],
                     configure: ((inout UniformBlock) -> Void)? = nil) {
        let ctx = MetalContext.shared
        var v = UniformBlock(size: DevelopLayout.vertexBlockSize, layout: DevelopLayout.vertexUniforms)
        v.vec2("uScale", scaleX, scaleY)
        v.float("uFlipY", flipY ? 1 : 0)
        v.vec4("uUvRect", uvRect[0], uvRect[1], uvRect[2], uvRect[3])
        var f = fragmentUniforms(p, g)
        configure?(&f)

        encoder.setRenderPipelineState(pipeline)
        encoder.setVertexBuffer(ctx.quad, offset: 0, index: MetalContext.quadBufferIndex)
        v.bytes.withUnsafeBytes {
            encoder.setVertexBytes($0.baseAddress!, length: $0.count, index: DevelopLayout.vertexBufferIndex)
        }
        f.bytes.withUnsafeBytes {
            encoder.setFragmentBytes($0.baseAddress!, length: $0.count, index: DevelopLayout.fragmentBufferIndex)
        }
        let textures: [String: MTLTexture] = [
            "uImage": image, "uLut": inputs.lut, "uShading": inputs.shading, "uBase": inputs.base,
            "uBloomHalo": inputs.bloomHalo, "uBloomTail": inputs.bloomTail, "uSkinMask": inputs.skinMask,
            "uBrushMask": inputs.brush, "uFilmLut": inputs.filmLut, "uPrintLut": inputs.printLut,
        ].merging(overrides) { $1 }
        for (name, index) in DevelopLayout.textures {
            encoder.setFragmentTexture(textures[name], index: index)
            encoder.setFragmentSamplerState(ctx.sampler, index: index)
        }
        encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
    }

    private static func fragmentUniforms(_ p: ShaderParams, _ g: SampleGeometry) -> UniformBlock {
        var u = UniformBlock(size: DevelopLayout.fragmentBlockSize, layout: DevelopLayout.fragmentUniforms)
        u.mat3("uWhiteBalance", p.whiteBalance)
        u.float("uExposure", p.exposure)
        u.float("uContrast", p.contrast)
        u.float("uSaturation", p.saturation)
        u.float("uGamutSoft", p.gamutSoft)
        u.int("uToneMapper", p.toneMapper)
        u.float("uDisplaySource", p.displaySource)
        u.float("uShadows", p.shadows)
        u.float("uHighlights", p.highlights)
        u.float("uBlacks", p.blacks)
        u.float("uWhites", p.whites)
        u.float("uDenoise", p.denoise)
        u.float("uDenoiseWide", p.denoiseWide)
        u.float("uColorDenoise", p.colorDenoise)
        u.float("uColorDenoiseWide", p.colorDenoiseWide)
        u.float("uGrain", p.grain)
        u.float("uGrainSize", g.grainSize * p.grainSizeScale)
        u.float("uGrainRoughness", p.grainRoughness)
        u.float("uGrainColor", p.grainColor)
        u.float("uSharpen", p.sharpening)
        u.float("uLocalContrast", p.localContrast)
        u.float("uClarity", p.base != nil ? p.highpassContrast : 0)
        let skin = p.skinMask
        u.float("uRetouch", skin?.hasSkin == true ? p.skinRetouch : 0)
        u.float("uSkinSigma", skin?.sigmaPx ?? 3)
        let bloomReady = p.bloomHalo != nil && p.bloomTail != nil
        u.float("uBloom", bloomReady ? p.bloom : 0)
        u.vec2("uBloomHaloTexel", 1 / Float(p.bloomHalo?.width ?? 1), 1 / Float(p.bloomHalo?.height ?? 1))
        u.vec2("uBloomTailTexel", 1 / Float(p.bloomTail?.width ?? 1), 1 / Float(p.bloomTail?.height ?? 1))
        u.vec2("uBaseTexel", 1 / Float(p.base?.width ?? 1), 1 / Float(p.base?.height ?? 1))
        u.vec2("uImageSize", Float(g.imageWidth), Float(g.imageHeight))
        u.float("uLutSize", Float(p.lut?.size ?? 2))
        u.float("uLutAmount", p.lut != nil ? p.lutAmount : 0)
        u.float("uCurveOn", p.curve != nil ? 1 : 0)
        if let curve = p.curve { u.array("uCurve", curve, components: 3) }
        u.vec2("uTexel", 1 / Float(g.textureWidth), 1 / Float(g.textureHeight))
        u.float("uPixelScale", Float(g.textureWidth) / Float(g.input.width))
        u.vec2("uInOrigin", Float(g.input.x), Float(g.input.y))
        u.vec2("uInSpan", Float(g.input.width), Float(g.input.height))
        u.vec2("uOutOrigin", Float(g.output.x), Float(g.output.y))
        u.vec2("uOutSpan", Float(g.output.width), Float(g.output.height))
        bindLens(&u, p.lens)
        bindLayers(&u, p.layers)
        bindFilm(&u, p, g)
        return u
    }

    /**
     * Film look: shaders/film_form.glsl (RawBoyEngine) or film/film.glsl (RawBoyFilmEngine); each program's layout has
     * only its own uniforms (the others are skipped by name). Everything 0 = off without one.
     */
    private static func bindFilm(_ u: inout UniformBlock, _ p: ShaderParams, _ g: SampleGeometry) {
        guard let f = p.film else {
            u.float("uFilmAmount", 0)
            return
        }
        let s = f.stock
        let amount = p.filmAmount
        u.float("uFilmAmount", amount)
        u.float("uFilmLutSize", Float(f.film.size))
        u.float("uPrintLutSize", Float(f.print.size))
        u.vec3("uFilmDMin", s.densityMin)
        u.vec3("uFilmDMax", s.densityMax)
        u.float("uFilmUmPerPx", FilmStock.frameWidthUm / Float(max(g.imageWidth, g.imageHeight)))
        // Halation and scatter happen before the film, so Strength fades them with the film render.
        let optics = p.halation * amount
        u.vec3("uHalation", s.halation.strength, optics)
        u.float("uHalationSigmaUm", s.halation.firstSigmaUm)
        u.float("uHalationDecay", s.halation.bounceDecay)
        u.vec3("uScatterTailUm", s.halation.scatterTailUm)
        u.vec3("uScatterWeight", s.halation.scatterTailWeight, min(optics, 1))
        u.float("uFilmGrain", p.filmGrain)
        u.vec3("uGrainParticleUm2", s.grain.particleScale, s.grain.particleAreaUm2)
        u.vec3("uGrainDMin", s.grain.densityMin)
        u.vec3("uGrainDMax", s.grain.densityMax)
        u.vec3("uGrainUniformity", s.grain.uniformity)
        u.float("uGrainBlurPx", s.grain.blurPx)
        let form = s.formation
        u.vec3("uFormGrainRms", form.grainRms)
        u.vec3("uFormHalation", form.halation, optics)
        u.float("uFormHaloUm", form.haloRadiusUm)
        u.vec3("uFormScatter", form.scatter, min(optics, 1))
        u.float("uFormScatterUm", form.scatterUm)
    }

    private static func bindLayers(_ u: inout UniformBlock, _ l: LayerUniforms) {
        let n = Masks.maxLayers
        u.int("uLayerCount", l.count)
        u.array("uMaskGeom", l.geom, components: 4)
        u.array("uMaskShape", l.shape, components: 4)
        u.array("uLayerTone", l.tone, components: 4)
        u.array("uLayerColor", l.color, components: 4)
        u.mat3("uLayerWb", l.whiteBalance)
        u.int("uMaskView", l.view)
        // Painted masks: slot i of the 2x2 brush atlas (see BrushTexture); zero size = no strokes.
        var slots = [Float](repeating: 0, count: n * 4)
        var w = 1
        var h = 1
        for (i, r) in l.brush.enumerated() {
            guard let r else { continue }
            slots[i * 4] = Float(i % 2) * 0.5
            slots[i * 4 + 1] = Float(i / 2) * 0.5
            slots[i * 4 + 2] = 0.5
            slots[i * 4 + 3] = 0.5
            w = r.width
            h = r.height
        }
        u.array("uBrushSlot", slots, components: 4)
        u.vec2("uBrushTexel", 1 / Float(2 * w), 1 / Float(2 * h))
    }

    /**
     * The viewfinder's whole-image copies: textures it renders from the current frame and binds to the base and
     * bloom slots itself, instead of ShaderParams.base / bloomHalo / bloomTail (copies made from a readback lag the
     * live image; bloom then trails behind moving lights).
     */
    static func liveCopies(_ u: inout UniformBlock, _ p: ShaderParams, baseWidth: Int, baseHeight: Int,
                           tailWidth: Int, tailHeight: Int) {
        u.float("uClarity", p.highpassContrast)
        u.float("uBloom", p.bloom)
        u.vec2("uBaseTexel", 1 / Float(baseWidth), 1 / Float(baseHeight))
        u.vec2("uBloomHaloTexel", 1 / Float(baseWidth), 1 / Float(baseHeight))
        u.vec2("uBloomTailTexel", 1 / Float(tailWidth), 1 / Float(tailHeight))
    }

    private static func bindLens(_ u: inout UniformBlock, _ l: LensTransform) {
        let info = l.info
        u.mat3("uOutToImage", Mat3.toGlColumnMajor(l.outToImage))
        u.mat3("uToSensor", Mat3.toGlColumnMajor(l.toSensor))
        u.mat3("uFromSensor", Mat3.toGlColumnMajor(l.fromSensor))
        u.vec2("uImageCenter", Float(l.centerX), Float(l.centerY))
        u.float("uHalfDiagonal", Float(l.halfDiagonal))
        u.float("uZoom", Float(l.zoom))
        u.float("uManualK", Float(l.manualK))
        let warp = l.warp
        u.float("uWarpOn", warp != nil ? 1 : 0)
        u.float("uWarpPlanes", Float(l.warpPlanes))
        var k = [Float](repeating: 0, count: 18)
        for (i, v) in (warp?.coefficients ?? []).enumerated() where i < 18 { k[i] = Float(v) }
        u.array("uWarpK", k, components: 1)
        u.vec2("uWarpCenter", Float(l.warpCenterX), Float(l.warpCenterY))
        u.float("uWarpNorm", Float(l.warpNorm))
        let vig = l.vignette
        u.float("uVigOn", vig != nil ? 1 : 0)
        u.array("uVigK", (0..<5).map { Float(vig?.k[safe: $0] ?? 0) }, components: 1)
        u.vec2("uVigCenter", Float(l.vignetteCenterX), Float(l.vignetteCenterY))
        u.float("uVigNorm", Float(l.vignetteNorm))
        let shading = info.shading
        u.float("uShadingUndo", l.undoShading ? 1 : 0)
        u.vec4("uShadingGrid", Float(shading?.originV ?? 0), Float(shading?.originH ?? 0),
               Float(shading?.spacingV ?? 1), Float(shading?.spacingH ?? 1))
        u.vec2("uShadingPoints", Float(shading?.pointsV ?? 1), Float(shading?.pointsH ?? 1))
        u.vec2("uSensorSize", Float(info.sensorWidth), Float(info.sensorHeight))
        u.float("uManualVig", Float(l.manualVignette))
        u.float("uManualVigMid", Float(l.manualVignetteMidpoint))
    }
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}

/**
 * The develop shader's whole-image textures (Android's texture units 1-9), each re-uploaded only when its source
 * object changes. One set per renderer; not thread-safe.
 */
final class DevelopTextures {
    private let ctx = MetalContext.shared
    private(set) var lut: MTLTexture
    /** Film and print LUTs (Android's FilmTextures, units 8 and 9). */
    private(set) var filmLut: MTLTexture
    private(set) var printLut: MTLTexture
    private(set) var shading: MTLTexture
    private(set) var base: MTLTexture
    private(set) var bloomHalo: MTLTexture
    private(set) var bloomTail: MTLTexture
    private(set) var skinMask: MTLTexture
    private(set) var brush: MTLTexture

    private var uploadedLut: LutData?
    private var uploadedFilm: LutData?
    private var uploadedPrint: LutData?
    private var uploadedShading: [Float]?
    private var uploadedBase: [UInt16]?
    private var uploadedHalo: [UInt16]?
    private var uploadedTail: [UInt16]?
    private var uploadedMask: [Float]?
    private var brushSlotW = 0
    private var brushSlotH = 0
    private var brushSlotRaster = [BrushRaster?](repeating: nil, count: 4)
    private var brushSlotVersion = [Int](repeating: 0, count: 4)

    init() {
        let empty = { (f: MTLPixelFormat) in MetalContext.shared.makeTexture(f, 1, 1)! }
        let d = MTLTextureDescriptor()
        d.textureType = .type3D
        d.pixelFormat = .rgba16Float
        d.width = 2
        d.height = 2
        d.depth = 2
        lut = ctx.device.makeTexture(descriptor: d)!
        filmLut = ctx.device.makeTexture(descriptor: d)!
        printLut = ctx.device.makeTexture(descriptor: d)!
        shading = empty(.r16Float)
        // A neutral 1x1 gain map so the sampler is always complete.
        var one = Float16(1)
        shading.replace(region: MTLRegionMake2D(0, 0, 1, 1), mipmapLevel: 0, withBytes: &one, bytesPerRow: 2)
        base = empty(.rgba16Float)
        bloomHalo = empty(.rgba16Float)
        bloomTail = empty(.rgba16Float)
        skinMask = empty(.r16Float)
        brush = empty(.rg8Unorm)
    }

    func bind(_ p: ShaderParams) {
        bindLut(p.lut)
        if let f = p.film {
            if f.film !== uploadedFilm, let t = Self.lutTexture(f.film) { filmLut = t; uploadedFilm = f.film }
            if f.print !== uploadedPrint, let t = Self.lutTexture(f.print) { printLut = t; uploadedPrint = f.print }
        }
        bindShading(p.lens.info.shading)
        if let b = p.base { base = upload(b, into: base, uploaded: &uploadedBase) }
        if let b = p.bloomHalo { bloomHalo = upload(b, into: bloomHalo, uploaded: &uploadedHalo) }
        if let b = p.bloomTail { bloomTail = upload(b, into: bloomTail, uploaded: &uploadedTail) }
        bindMask(p.skinMask)
        bindBrush(p.layers.brush)
    }

    /** RGB half floats -> an RGBA16F texture (Metal has no 3-channel formats). */
    static func halfRgbTexture(_ width: Int, _ height: Int, _ pixels: UnsafePointer<UInt16>,
                               reuse: MTLTexture? = nil) -> MTLTexture {
        let ctx = MetalContext.shared
        let tex = reuse.flatMap { $0.width == width && $0.height == height ? $0 : nil }
            ?? ctx.makeTexture(.rgba16Float, width, height)!
        var rgba = [UInt16](repeating: 0x3C00, count: width * height * 4)  // alpha 1.0
        rgba.withUnsafeMutableBufferPointer { dst in
            for i in 0..<(width * height) {
                dst[i * 4] = pixels[i * 3]
                dst[i * 4 + 1] = pixels[i * 3 + 1]
                dst[i * 4 + 2] = pixels[i * 3 + 2]
            }
        }
        tex.replace(region: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0, withBytes: rgba,
                    bytesPerRow: width * 8)
        return tex
    }

    private func upload(_ image: PreviewImage, into tex: MTLTexture, uploaded: inout [UInt16]?) -> MTLTexture {
        if let u = uploaded, u.withUnsafeBufferPointer({ $0.baseAddress }) == image.pixels.withUnsafeBufferPointer({ $0.baseAddress }),
           tex.width == image.width, tex.height == image.height {
            return tex
        }
        uploaded = image.pixels
        return image.pixels.withUnsafeBufferPointer {
            Self.halfRgbTexture(image.width, image.height, $0.baseAddress!, reuse: tex)
        }
    }

    private func bindLut(_ lut: LutData?) {
        guard let lut, lut !== uploadedLut, let tex = Self.lutTexture(lut) else { return }
        self.lut = tex
        uploadedLut = lut
    }

    /** A LUT as an RGBA16F 3D texture (Metal has no 3-channel formats). */
    private static func lutTexture(_ lut: LutData) -> MTLTexture? {
        let n = lut.size
        let d = MTLTextureDescriptor()
        d.textureType = .type3D
        d.pixelFormat = .rgba16Float
        d.width = n
        d.height = n
        d.depth = n
        d.storageMode = .shared
        guard let tex = MetalContext.shared.device.makeTexture(descriptor: d) else { return nil }
        var rgba = [Float16](repeating: 1, count: n * n * n * 4)
        for i in 0..<(n * n * n) {
            rgba[i * 4] = Float16(lut.data[i * 3])
            rgba[i * 4 + 1] = Float16(lut.data[i * 3 + 1])
            rgba[i * 4 + 2] = Float16(lut.data[i * 3 + 2])
        }
        tex.replace(region: MTLRegionMake3D(0, 0, 0, n, n, n), mipmapLevel: 0, slice: 0, withBytes: rgba,
                    bytesPerRow: n * 8, bytesPerImage: n * n * 8)
        return tex
    }

    private func bindShading(_ map: LensInfo.ShadingMap?) {
        guard let map, uploadedShading.map({ $0 != map.gains }) ?? true else { return }
        guard let tex = ctx.makeTexture(.r16Float, map.pointsH, map.pointsV) else { return }
        let halves = map.gains.map { Float16($0) }
        tex.replace(region: MTLRegionMake2D(0, 0, map.pointsH, map.pointsV), mipmapLevel: 0, withBytes: halves,
                    bytesPerRow: map.pointsH * 2)
        shading = tex
        uploadedShading = map.gains
    }

    private func bindMask(_ mask: SkinMask?) {
        guard let mask else { return }
        if let u = uploadedMask, u.withUnsafeBufferPointer({ $0.baseAddress }) == mask.data.withUnsafeBufferPointer({ $0.baseAddress }) {
            return
        }
        guard let tex = ctx.makeTexture(.r16Float, mask.width, mask.height) else { return }
        let halves = mask.data.map { Float16($0) }
        tex.replace(region: MTLRegionMake2D(0, 0, mask.width, mask.height), mipmapLevel: 0, withBytes: halves,
                    bytesPerRow: mask.width * 2)
        skinMask = tex
        uploadedMask = mask.data
    }

    /**
     * The layers' painted masks (BrushRaster, RG8: paint, erase) in one 2x2 atlas, one slot per packed layer.
     * Uploads only what changed.
     */
    private func bindBrush(_ rasters: [BrushRaster?]) {
        guard let any = rasters.compactMap({ $0 }).first,
              2 * max(any.width, any.height) <= 16384 else { return }
        if any.width != brushSlotW || any.height != brushSlotH {
            brushSlotW = any.width
            brushSlotH = any.height
            brush = ctx.makeTexture(.rg8Unorm, 2 * brushSlotW, 2 * brushSlotH)!
            brushSlotRaster = [nil, nil, nil, nil]
        }
        for (i, r) in rasters.enumerated() {
            guard let r, r.width == brushSlotW, r.height == brushSlotH else { continue }
            let tex = brush
            let slotW = brushSlotW
            let slotH = brushSlotH
            brushSlotVersion[i] = r.upload(brushSlotVersion[i], fresh: brushSlotRaster[i] !== r) { pixels, rect in
                let src = pixels.advanced(by: (rect.y * r.width + rect.x) * 2)
                tex.replace(region: MTLRegionMake2D((i % 2) * slotW + rect.x, (i / 2) * slotH + rect.y,
                                                    rect.width, rect.height),
                            mipmapLevel: 0, withBytes: src, bytesPerRow: r.width * 2)
            }
            brushSlotRaster[i] = r
        }
    }
}
