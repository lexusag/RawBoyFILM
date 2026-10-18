import Foundation

/**
 * Camera and exposure essentials of a photo (from the decoder's metadata), for the editor's info line.
 * NaN (or 0 for ISO) = unknown.
 */
public struct ShotInfo: Equatable, Sendable {
    public let camera: String
    public let exposureTime: Double
    public let fNumber: Double
    public let focalLength: Double
    public let focalLength35mm: Double
    public let iso: Double

    public init(camera: String, exposureTime: Double, fNumber: Double, focalLength: Double, focalLength35mm: Double,
                iso: Double) {
        self.camera = camera
        self.exposureTime = exposureTime
        self.fNumber = fNumber
        self.focalLength = focalLength
        self.focalLength35mm = focalLength35mm
        self.iso = iso
    }

    /** e.g. "GFX 50R · ISO 6400 · 1/60 s · f/2.8 · 63 mm (50 mm eq.)"; unknown values are left out. */
    public func line() -> String {
        var parts: [String] = []
        let name = camera.trimmingCharacters(in: .whitespaces)
        if !name.isEmpty { parts.append(name) }
        if iso > 0 { parts.append("ISO \(Int(iso.rounded()))") }
        if exposureTime.isFinite && exposureTime > 0 { parts.append(Self.formatShutter(exposureTime)) }
        if fNumber.isFinite && fNumber > 0 { parts.append("f/\(Self.trim(fNumber))") }
        if focalLength.isFinite && focalLength > 0 {
            let eq = focalLength35mm.isFinite && focalLength35mm > 0 && abs(focalLength35mm - focalLength) >= 1
            parts.append("\(Int(focalLength.rounded())) mm" + (eq ? " (\(Int(focalLength35mm.rounded())) mm eq.)" : ""))
        }
        return parts.joined(separator: " · ")
    }

    /** 1/60 s below a second (rounded to a whole denominator), 2.5 s / 30 s above. */
    public static func formatShutter(_ t: Double) -> String {
        t >= 0.95 ? "\(trim(t)) s" : "1/\(Int((1 / t).rounded())) s"
    }

    /** One decimal, without a trailing ".0". */
    private static func trim(_ v: Double) -> String {
        let s = String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), v)
        return s.hasSuffix(".0") ? String(s.dropLast(2)) : s
    }
}
