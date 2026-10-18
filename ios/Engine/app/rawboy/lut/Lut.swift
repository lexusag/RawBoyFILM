import Foundation

/** A LUT the user can pick: bundled with the app or found in the user's LUT folder. */
public struct LutEntry: Equatable, Hashable, Sendable {
    /** Stable id stored in DevelopParams: "asset:<path>" or "file:<name in Documents/LUTs>". */
    public let id: String
    public let name: String
    public let bundled: Bool
}

/** What the LUTs tab shows. */
public struct LutLibrary: Equatable, Sendable {
    public var bundled: [LutEntry] = []
    public var user: [LutEntry] = []
    /** Always true on iOS: the user's folder is the app's own Documents/LUTs (visible in the Files app). */
    public var userFolderGranted: Bool = true

    public init(bundled: [LutEntry] = [], user: [LutEntry] = [], userFolderGranted: Bool = true) {
        self.bundled = bundled
        self.user = user
        self.userFolderGranted = userFolderGranted
    }
}

/**
 * A 3D LUT ready for the GPU: size^3 RGB float triplets, red varying fastest
 * (the .cube and HaldCLUT order, and what a 3D texture expects).
 * Input and output are display-referred sRGB in 0..1.
 */
public final class LutData: @unchecked Sendable {
    public let id: String
    public let size: Int
    public let data: [Float]

    public init(id: String, size: Int, data: [Float]) {
        self.id = id
        self.size = size
        self.data = data
    }
}
