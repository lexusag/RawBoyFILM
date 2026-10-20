import Foundation
#if FILM_EDITION
import RawBoyFilmEngine
#else
import RawBoyEngine
#endif

/** Which raw the camera records (both at 12 MP). */
enum RawFormat: String, CaseIterable {
    /** The sensor's own Bayer data: one exposure, like a camera's raw; noisy in low light on the small lenses. */
    case bayer
    /**
     * Apple ProRAW: several frames merged by the camera, demosaiced and linear. Much cleaner in low light (tested on
     * the iPhone 17 Pro Max), so the default. 12 MP like Bayer; 48 MP costs 4x the decode and memory for photos
     * saved at 3000 px by default.
     */
    case proRaw
}

enum FlashMode: String { case off, on }

/** The look controls, camera choice and output settings. */
struct CameraSettings: Equatable {
    var lookId = Edition.defaultLookId
    var strength: Float = LookApplier.defaultStrength
    var brightness: Float = 0
    var warmth: Float = 0
    /** JPEG quality, minQuality...100. */
    var jpegQuality = 95
    /** Keep the raw as DNG in Photos › RawBoy Camera RAW. */
    var keepDng = false
    /** Save photos at smallLongSide px on the long side instead of full size. */
    var smallExport = true
    /**
     * Keep the camera (facing, lens), look and sliders between launches. Off: each launch starts on the back
     * W lens with the default look and sliders. Output settings and calibration are always kept.
     */
    var rememberLast = false
    /** A live RGB histogram in a corner of the viewfinder. */
    var showHistogram = false
    /**
     * Per camera (lensKey): gain from the linearised preview to raw units (1.0 = sensor clip), learned
     * from each shot (raw key / viewfinder key), so the viewfinder's auto exposure matches the final.
     */
    var previewScales: [String: Float] = [:]
    /** Selfie camera instead of the back cameras. */
    var front = false
    /** Lens label within the current side: UW, W, T. */
    var lens = "W"
    var flash = FlashMode.off
    var rawFormat = RawFormat.proRaw

    /** The lens's key for per-lens values: the label, prefixed with "F" on the front side, "P" for ProRAW. */
    var lensKey: String { Self.lensKey(front, lens, rawFormat) }
    var previewScale: Float { previewScales[lensKey] ?? Self.defaultScale }
    /** The long side to save at, or nil for full size. */
    var maxLongSide: Int? { smallExport ? Self.smallLongSide : nil }

    static let minQuality = 70
    static let smallLongSide = 3000
    /** First guess before a lens's first shot: an sRGB preview's middle grey (0.18) at a camera raw's key (~0.06). */
    static let defaultScale: Float = 0.35
    static let minScale: Float = 1.0 / 32
    static let maxScale: Float = 32

    static func lensKey(_ front: Bool, _ lens: String, _ format: RawFormat) -> String {
        (format == .proRaw ? "P" : "") + (front ? "F" : "") + lens
    }
}

enum CameraSettingsStore {
    private static let d = UserDefaults.standard

    static func load() -> CameraSettings {
        let def = CameraSettings()
        var s = CameraSettings()
        s.jpegQuality = min(max(d.object(forKey: "camera.jpegQuality") as? Int ?? def.jpegQuality, CameraSettings.minQuality), 100)
        s.keepDng = d.object(forKey: "camera.keepDng") as? Bool ?? def.keepDng
        s.smallExport = d.object(forKey: "camera.smallExport") as? Bool ?? def.smallExport
        s.rememberLast = d.bool(forKey: "camera.rememberLast")
        s.showHistogram = d.object(forKey: "camera.showHistogram") as? Bool ?? def.showHistogram
        s.previewScales = parseScales(d.string(forKey: "camera.previewScales"))
        s.flash = d.string(forKey: "camera.flash").flatMap(FlashMode.init(rawValue:)) ?? def.flash
        s.rawFormat = d.string(forKey: "camera.rawFormat").flatMap(RawFormat.init(rawValue:)) ?? def.rawFormat
        if !s.rememberLast { return s }
        s.lookId = d.string(forKey: "camera.look") ?? def.lookId
        s.strength = min(max(d.object(forKey: "camera.strength") as? Float ?? def.strength, 0), 100)
        s.brightness = min(max(d.object(forKey: "camera.brightness") as? Float ?? 0, -100), 100)
        s.warmth = min(max(d.object(forKey: "camera.warmth") as? Float ?? 0, -100), 100)
        s.front = d.bool(forKey: "camera.front")
        s.lens = d.string(forKey: "camera.lens") ?? def.lens
        return s
    }

    /** Saves everything, so switching rememberLast on later starts from the current state. */
    static func save(_ s: CameraSettings) {
        d.set(s.lookId, forKey: "camera.look")
        d.set(s.strength, forKey: "camera.strength")
        d.set(s.brightness, forKey: "camera.brightness")
        d.set(s.warmth, forKey: "camera.warmth")
        d.set(s.jpegQuality, forKey: "camera.jpegQuality")
        d.set(s.keepDng, forKey: "camera.keepDng")
        d.set(s.smallExport, forKey: "camera.smallExport")
        d.set(s.rememberLast, forKey: "camera.rememberLast")
        d.set(s.showHistogram, forKey: "camera.showHistogram")
        d.set(s.front, forKey: "camera.front")
        d.set(s.lens, forKey: "camera.lens")
        d.set(s.flash.rawValue, forKey: "camera.flash")
        d.set(s.rawFormat.rawValue, forKey: "camera.rawFormat")
        d.set(s.previewScales.map { "\($0.key)=\($0.value)" }.joined(separator: ";"), forKey: "camera.previewScales")
    }

    /** "W=0.4;T=0.5;FW=0.3" -> map; malformed entries are skipped. */
    private static func parseScales(_ text: String?) -> [String: Float] {
        var out: [String: Float] = [:]
        for entry in (text ?? "").split(separator: ";") {
            let parts = entry.split(separator: "=")
            guard parts.count == 2, let v = Float(parts[1]), v > 0 else { continue }
            out[String(parts[0])] = min(max(v, CameraSettings.minScale), CameraSettings.maxScale)
        }
        return out
    }
}
