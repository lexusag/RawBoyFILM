import Foundation

public enum ExportFormat: String, CaseIterable, Sendable {
    case jpeg = "JPEG"
    case tiff = "TIFF"

    public var fileExtension: String { self == .jpeg ? "jpg" : "tif" }
    public var uti: String { self == .jpeg ? "public.jpeg" : "public.tiff" }
}

/**
 * JPEG encoders. system is iOS's (ImageIO, 8-bit input).
 * jpegli is Google's jpegli: 4:4:4, 16-bit input, adaptive quantisation; slower, better per byte.
 */
public enum JpegEncoder: String, Sendable { case system, jpegli }

public struct ExportSettings: Equatable, Sendable {
    public var format: ExportFormat = .jpeg
    public var jpegQuality: Int = 92  // 1..100
    public var tiffBits: Int = 16  // 8 or 16
    public var jpegEncoder: JpegEncoder = .system
    /** Long side of the saved image in pixels, area-downscaled from full resolution; nil = full size. */
    public var maxLongSide: Int?

    public init(format: ExportFormat = .jpeg, jpegQuality: Int = 92, tiffBits: Int = 16,
                jpegEncoder: JpegEncoder = .system, maxLongSide: Int? = nil) {
        self.format = format
        self.jpegQuality = jpegQuality
        self.tiffBits = tiffBits
        self.jpegEncoder = jpegEncoder
        self.maxLongSide = maxLongSide
    }
}

public enum ExportSettingsStore {
    private static let defaults = UserDefaults.standard

    public static func load() -> ExportSettings {
        let d = ExportSettings()
        return ExportSettings(
            format: defaults.string(forKey: "export.format").flatMap(ExportFormat.init(rawValue:)) ?? d.format,
            jpegQuality: (defaults.object(forKey: "export.jpegQuality") as? Int ?? d.jpegQuality).clamped(1, 100),
            tiffBits: defaults.integer(forKey: "export.tiffBits") == 8 ? 8 : 16)
    }

    public static func save(_ settings: ExportSettings) {
        defaults.set(settings.format.rawValue, forKey: "export.format")
        defaults.set(settings.jpegQuality, forKey: "export.jpegQuality")
        defaults.set(settings.tiffBits, forKey: "export.tiffBits")
    }
}
