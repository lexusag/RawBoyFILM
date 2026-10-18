import Foundation
import os

/** Everything learned from a photo once; enough to render it small and to export it. */
public struct Analysis: @unchecked Sendable {
    public let defaults: DevelopParams
    public let autoBase: DevelopParams
    public let stats: SceneStats
    public let lens: LensInfo
    public let base: PreviewImage
    public let bloom: BloomSources
    public let skin: SkinMask?
}

/** The automatic develop of one raw, shared by RawBoy Auto and RawBoy Camera. */
public enum AutoAnalysis {
    /** Category of the per-photo stats log, used for recalibrating the auto develop. */
    private static let log = Logger(subsystem: "app.rawboy", category: "RawBoyAuto")

    /**
     * Decodes [url], keeps its small whole-image copies and works out the auto develop.
     * Blocking and memory hungry (a full-resolution decode): call from a background task, one at a time.
     */
    public static func analyze(_ url: URL) throws -> Analysis {
        // The raw is closed before segmentation, which needs only the 512 px copy.
        let img = try RawImage.open(url: url)
        let copies = copiesOf(img)
        let info = Info.of(img, auto: true)
        img.close()
        return analyze(copies.0, copies.1, info, url.lastPathComponent)
    }

    /**
     * The same for an already decoded [image] (kept open, e.g. to export it next). Blocking.
     * [noiseIsoScale]: see AutoTone.develop. [fallbackIso] is used when the file has no ISO (e.g. from the capture).
     * [detectSkin] false skips skin detection (face metering, skin retouch): the camera saw no face.
     * [meter]: see SceneAnalyzer.analyze.
     */
    public static func analyze(_ image: RawImage, label: String, noiseIsoScale: Float = 1, fallbackIso: Float = 0,
                               detectSkin: Bool = true, meter: MeterArea? = nil) -> Analysis {
        let start = Date()
        let (base, bloom) = copiesOf(image)
        log.notice("small copies: \(Int(Date().timeIntervalSince(start) * 1000)) ms")
        var info = Info.of(image)
        if info.iso <= 0 { info = Info(lens: info.lens, iso: fallbackIso, defaults: info.defaults) }
        return analyze(base, bloom, info, label, noiseIsoScale, detectSkin, meter)
    }

    /**
     * [cameraMetered]: see AutoTone.cameraMetered; [noise]: the measured noise. Only Auto uses them (Camera meters the
     * scene live itself and keeps its ISO-based denoise, which has no time cost per shot).
     */
    private struct Info {
        let lens: LensInfo
        let iso: Float
        let defaults: DevelopParams
        var cameraMetered = false
        var noise = NoiseLevel.none

        static func of(_ img: RawImage, auto: Bool = false) -> Info {
            Info(lens: img.lens, iso: img.iso, defaults: PhotoSession.asShotDefaults(img),
                 cameraMetered: auto && AutoTone.cameraMetered(make: img.make, baselineExposure: img.baselineExposure),
                 noise: auto ? img.measureNoise() : .none)
        }
    }

    /**
     * The auto develop alone, for a photo already open elsewhere (RawBoy Pro's Auto button): [base] is its 512 px copy,
     * [skin] its skin mask (nil: no face metering), [noise] its measured noise. Fast; no decode or segmentation.
     */
    public static func autoBase(_ base: PreviewImage, skin: SkinMask?, iso: Float, defaults: DevelopParams,
                                cameraMetered: Bool, noise: NoiseLevel = .none) -> DevelopParams {
        develop(base, skin, iso, defaults, 1, cameraMetered, nil, noise).1
    }

    private static func develop(_ base: PreviewImage, _ skin: SkinMask?, _ iso: Float, _ defaults: DevelopParams,
                                _ noiseIsoScale: Float, _ cameraMetered: Bool,
                                _ meter: MeterArea?, _ noise: NoiseLevel = .none) -> (SceneStats, DevelopParams) {
        let stats = SceneAnalyzer.analyze(base, skin: skin, iso: iso, meter: meter)
        return (stats, AutoTone.develop(stats, asShot: defaults, noiseIsoScale: noiseIsoScale, cameraMetered: cameraMetered,
                                        noise: noise))
    }

    private static func copiesOf(_ img: RawImage) -> (PreviewImage, BloomSources) {
        (img.createPreview(maxDimension: PhotoSession.baseMaxDimension), PhotoSession.bloomSourcesOf(img))
    }

    private static func analyze(_ base: PreviewImage, _ bloom: BloomSources, _ info: Info, _ label: String,
                                _ noiseIsoScale: Float = 1, _ detectSkin: Bool = true,
                                _ meter: MeterArea? = nil) -> Analysis {
        let start = Date()
        // Segmentation can fail; the photo is then just treated as having no face.
        let skin = detectSkin ? try? SkinSegmenter.segment(base, fullWidth: base.fullWidth, fullHeight: base.fullHeight) : nil
        log.notice("skin detection: \(Int(Date().timeIntervalSince(start) * 1000)) ms")
        let defaults = info.defaults
        let (stats, auto) = develop(base, skin, info.iso, defaults, noiseIsoScale, info.cameraMetered, meter, info.noise)
        log.notice("""
            analysis \(label): key=\(stats.key) p1=\(stats.p1) p5=\(stats.p5) p50=\(stats.p50) p98=\(stats.p98) \
            p99=\(stats.p99) clipped=\(stats.clipped) chroma=\(stats.chroma) face=\(stats.faceArea) \
            skin=\(stats.skinLuminance) iso=\(info.iso) cameraMetered=\(info.cameraMetered) \
            noise=\(info.noise.luma)/\(info.noise.chroma) -> exposure=\(auto.exposure) \
            hl=\(auto.highlights) sh=\(auto.shadows) contrast=\(auto.contrast) \
            denoise=\(auto.denoise)/\(auto.colorDenoise.map { "\($0)" } ?? "-") \
            wb=\(Int(defaults.temperature))K/\(Int(defaults.tint)) -> \(Int(auto.temperature))K/\(Int(auto.tint))
            """)
        return Analysis(defaults: defaults, autoBase: auto, stats: stats, lens: info.lens, base: base, bloom: bloom,
                        skin: skin)
    }
}
