import Foundation
import Photos

/**
 * Adds exported files to the Photos library, in an album named after OutputNaming.folder ("RawBoy"), keeping the
 * file name. The iOS counterpart of Android's MediaStoreOutput. With only add-only access (the user declined full
 * access) the photo still lands in the library, without the album.
 */
public enum PhotoLibraryOutput {
    public struct Failure: LocalizedError {
        public let message: String
        public var errorDescription: String? { message }
    }

    /** Moves [file] into Photos (the temporary file is deleted afterwards). */
    public static func save(_ file: URL, naming: OutputNaming = OutputNaming()) async throws {
        defer { try? FileManager.default.removeItem(at: file) }
        var status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        if status == .notDetermined { status = await PHPhotoLibrary.requestAuthorization(for: .readWrite) }
        let fullAccess = status == .authorized || status == .limited
        if !fullAccess {
            var addOnly = PHPhotoLibrary.authorizationStatus(for: .addOnly)
            if addOnly == .notDetermined { addOnly = await PHPhotoLibrary.requestAuthorization(for: .addOnly) }
            guard addOnly == .authorized else { throw Failure(message: "RawBoy may not add photos (Settings › Privacy › Photos)") }
        }
        let album = fullAccess ? try await albumNamed(naming.folder) : nil
        try await PHPhotoLibrary.shared().performChanges {
            let options = PHAssetResourceCreationOptions()
            options.originalFilename = file.lastPathComponent
            let request = PHAssetCreationRequest.forAsset()
            request.addResource(with: .photo, fileURL: file, options: options)
            if let album, let placeholder = request.placeholderForCreatedAsset,
               let change = PHAssetCollectionChangeRequest(for: album) {
                change.addAssets([placeholder] as NSArray)
            }
        }
    }

    /** The album called [name], created on first use. */
    private static func albumNamed(_ name: String) async throws -> PHAssetCollection? {
        if let found = find(name) { return found }
        var id: String?
        try await PHPhotoLibrary.shared().performChanges {
            id = PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: name)
                .placeholderForCreatedAssetCollection.localIdentifier
        }
        guard let id else { return nil }
        return PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [id], options: nil).firstObject
    }

    private static func find(_ name: String) -> PHAssetCollection? {
        let options = PHFetchOptions()
        options.predicate = NSPredicate(format: "title = %@", name)
        return PHAssetCollection.fetchAssetCollections(with: .album, subtype: .albumRegular, options: options).firstObject
    }
}
