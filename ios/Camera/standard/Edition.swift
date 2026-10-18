import RawBoyEngine

/** RawBoy Camera (target RawBoyCamera): the looks on the bundled film LUTs, as RawBoy Auto has them. */
enum Edition {
    static let looks: [Look] = Looks.all
    static let defaultLookId = Looks.portra400.id

    static func look(_ id: String) -> Look { Looks.byId(id) }

    /** Photos albums for the JPEGs and the DNGs; JPEGs are named IMG_…-<jpegSuffix>.jpg. */
    static let album = "RawBoy Camera"
    static let rawAlbum = "RawBoy Camera RAW"
    static let jpegSuffix = "camera"

    /** Extra lines above "Open-source licences" in Settings. */
    static let credits: [String] = []
}
