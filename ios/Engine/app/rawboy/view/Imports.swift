import Foundation
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/**
 * Raw files the apps work on, copied into Application Support/Imports: iOS lends access to picked files (Photos,
 * Files, "Open in") only briefly, so each one is copied once and the copy is deleted when the photo leaves the app.
 * (Android keeps content URIs with persisted grants instead.)
 */
public enum Imports {
    public static var folder: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("Imports", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /** Copies [source] (security-scoped URLs are opened as needed) into its own folder, keeping the file name. */
    public static func copy(_ source: URL) throws -> URL {
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        let dir = folder.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let target = dir.appendingPathComponent(source.lastPathComponent)
        try FileManager.default.copyItem(at: source, to: target)
        return target
    }

    /** Deletes an imported copy (and its folder); files outside Imports are left alone. */
    public static func delete(_ url: URL) {
        let dir = url.deletingLastPathComponent()
        guard dir.deletingLastPathComponent().standardizedFileURL == folder.standardizedFileURL else { return }
        try? FileManager.default.removeItem(at: dir)
    }

    public static func displayName(_ url: URL) -> String { url.lastPathComponent }

    /**
     * The raw files behind photos picked in the Photos picker, copied in. Photos without a raw representation are
     * skipped and counted in the returned [skipped], unless [acceptDisplay] (RawBoy Auto): then their HEIC, JPEG, PNG
     * or TIFF is taken (the raw is still preferred).
     */
    public static func load(_ results: [PHPickerResult], acceptDisplay: Bool = false) async -> (urls: [URL], skipped: Int) {
        var urls: [URL] = []
        var skipped = 0
        for result in results {
            let provider = result.itemProvider
            let types = provider.registeredTypeIdentifiers.compactMap { UTType($0) }
            let raw = types.first { $0.conforms(to: .rawImage) }
            let display = acceptDisplay ? types.first {
                $0.conforms(to: .heif) || $0.conforms(to: .jpeg) || $0.conforms(to: .png) || $0.conforms(to: .tiff)
            } : nil
            guard let type = (raw ?? display)?.identifier else {
                skipped += 1
                continue
            }
            let url: URL? = await withCheckedContinuation { continuation in
                provider.loadFileRepresentation(forTypeIdentifier: type) { file, _ in
                    // The file only exists during this callback: copy it now.
                    continuation.resume(returning: file.flatMap { try? copy($0) })
                }
            }
            if let url { urls.append(url) } else { skipped += 1 }
        }
        return (urls, skipped)
    }

    /**
     * Debug builds: files to import at launch, from the RAWBOY_IMPORT environment variable (paths separated by ":"),
     * so the Simulator can be driven from the command line (`SIMCTL_CHILD_RAWBOY_IMPORT=... xcrun simctl launch`).
     */
    public static func launchFiles() -> [URL] {
        #if DEBUG
        guard let list = ProcessInfo.processInfo.environment["RAWBOY_IMPORT"] else { return [] }
        return list.split(separator: ":").compactMap { try? copy(URL(fileURLWithPath: String($0))) }
        #else
        return []
        #endif
    }
}

/** The system Photos picker, limited to images; the raw representation is pulled out by Imports.load. */
public struct PhotoPicker: UIViewControllerRepresentable {
    let selectionLimit: Int
    let onPick: ([PHPickerResult]) -> Void

    /** [selectionLimit]: 0 = any number. */
    public init(selectionLimit: Int = 0, onPick: @escaping ([PHPickerResult]) -> Void) {
        self.selectionLimit = selectionLimit
        self.onPick = onPick
    }

    public func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration()
        config.filter = .images
        config.selectionLimit = selectionLimit
        config.preferredAssetRepresentationMode = .current  // keep the raw; don't transcode to JPEG
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }

    public func updateUIViewController(_ controller: PHPickerViewController, context: Context) {}

    public func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    public final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let onPick: ([PHPickerResult]) -> Void

        init(onPick: @escaping ([PHPickerResult]) -> Void) { self.onPick = onPick }

        public func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            onPick(results)
        }
    }
}

/** Raw file types for the Files importer: every raw UTI plus DNG; some providers report raws as plain data. */
public let rawFileTypes: [UTType] = [.rawImage, UTType("com.adobe.raw-image") ?? .rawImage, .data]

/** RawBoy Auto's Files importer: raws plus JPEG, HEIC, PNG and TIFF (decoded as display sources). */
public let photoFileTypes: [UTType] = rawFileTypes + [.jpeg, .heic, .heif, .png, .tiff]
