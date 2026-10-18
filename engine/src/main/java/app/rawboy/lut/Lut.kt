package app.rawboy.lut

import java.nio.FloatBuffer

/** A LUT the user can pick: bundled with the app or found in the user's LUT folder. */
data class LutEntry(
    /** Stable id stored in DevelopParams: "asset:<path>" or "doc:<document uri>". */
    val id: String,
    val name: String,
    val bundled: Boolean,
)

/** What the LUTs tab shows. */
data class LutLibrary(
    val bundled: List<LutEntry> = emptyList(),
    val user: List<LutEntry> = emptyList(),
    /** True once the user has granted access to their LUT folder. */
    val userFolderGranted: Boolean = false,
)

/**
 * A 3D LUT ready for the GPU: size^3 RGB float triplets, red varying fastest
 * (the .cube and HaldCLUT order, and what glTexImage3D expects).
 * Input and output are display-referred sRGB in 0..1.
 */
class LutData(val id: String, val size: Int, val data: FloatBuffer)
