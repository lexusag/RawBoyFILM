import Foundation

/**
 * Finds LUTs: bundled ones in the engine's `luts` resource folder (copied from <repo>/luts), and the user's own
 * in the app's Documents/LUTs folder, which the Files app shows (UIFileSharingEnabled), so LUTs can be dropped in
 * from anywhere. (Android asks for a folder grant instead.)
 */
public enum LutRepository {
    private static let assetDir = "luts"
    private static let userDirName = "LUTs"
    private static let assetPrefix = "asset:"
    private static let filePrefix = "file:"
    private static let extensions: Set<String> = ["cube", "png"]

    private final class BundleToken {}
    public static var resourceBundle: Bundle { Bundle(for: BundleToken.self) }

    /** Documents/LUTs, created on first use. */
    public static var userFolder: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dir = docs.appendingPathComponent(userDirName, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    public static func library() -> LutLibrary {
        LutLibrary(bundled: bundled(), user: scanUserFolder(), userFolderGranted: true)
    }

    public static func open(_ id: String) throws -> Data {
        let url: URL
        if id.hasPrefix(assetPrefix) {
            guard let base = resourceBundle.resourceURL else { throw LutError(message: "No bundled LUTs") }
            url = base.appendingPathComponent(String(RetiredLooks.lut(id).dropFirst(assetPrefix.count)))
        } else if id.hasPrefix(filePrefix) {
            url = userFolder.appendingPathComponent(String(id.dropFirst(filePrefix.count)))
        } else {
            throw LutError(message: "Unknown LUT \(id)")
        }
        do {
            return try Data(contentsOf: url)
        } catch {
            throw LutError(message: "Cannot open LUT")
        }
    }

    private static func bundled() -> [LutEntry] {
        guard let dir = resourceBundle.resourceURL?.appendingPathComponent(assetDir),
              let names = try? FileManager.default.contentsOfDirectory(atPath: dir.path) else { return [] }
        return names
            .filter { extensions.contains(($0 as NSString).pathExtension.lowercased()) }
            .map { LutEntry(id: assetPrefix + "\(assetDir)/\($0)", name: displayName($0), bundled: true) }
            .sorted { $0.name.lowercased() < $1.name.lowercased() }
    }

    private static func scanUserFolder() -> [LutEntry] {
        guard let names = try? FileManager.default.contentsOfDirectory(atPath: userFolder.path) else { return [] }
        return names
            .filter { extensions.contains(($0 as NSString).pathExtension.lowercased()) }
            .map { LutEntry(id: filePrefix + $0, name: displayName($0), bundled: false) }
            .sorted { $0.name.lowercased() < $1.name.lowercased() }
    }

    private static func displayName(_ fileName: String) -> String {
        (fileName as NSString).deletingPathExtension.trimmingCharacters(in: .whitespaces)
    }
}
