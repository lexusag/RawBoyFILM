package app.rawboy.camera

import app.rawboy.auto.looks.Look
import app.rawboy.auto.looks.Looks

/** RawBoy Camera (flavour "standard"): the looks on the bundled film LUTs, as RawBoy Auto has them. */
internal object Edition {
    val looks: List<Look> = Looks.ALL
    val defaultLookId: String = Looks.PORTRA_400.id

    fun look(id: String): Look = Looks.byId(id)

    /** Pictures/<FOLDER> (JPEGs) and Pictures/<FOLDER>/RAW (DNGs); JPEGs are named IMG_…-<JPEG_SUFFIX>.jpg. */
    const val FOLDER = "RawBoy Camera"
    const val JPEG_SUFFIX = "camera"

    /** Extra lines above "Open-source licences" in Settings. */
    val credits: List<String> = emptyList()
}
