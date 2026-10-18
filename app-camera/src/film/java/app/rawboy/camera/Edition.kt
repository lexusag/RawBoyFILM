// SPDX-License-Identifier: GPL-3.0-or-later
package app.rawboy.camera

import app.rawboy.auto.looks.FilmLooks
import app.rawboy.auto.looks.Look

/**
 * RawBoy Film (flavour "film", GPLv3): the looks are spektrafilm film stocks (engine edition "film"). Installs next to
 * RawBoy Camera (applicationId app.rawboy.camera.film) and saves to its own folder.
 */
internal object Edition {
    val looks: List<Look> = FilmLooks.ALL
    val defaultLookId: String = FilmLooks.PORTRA_400.id

    fun look(id: String): Look = FilmLooks.byId(id)

    /** Pictures/<FOLDER> (JPEGs) and Pictures/<FOLDER>/RAW (DNGs); JPEGs are named IMG_…-<JPEG_SUFFIX>.jpg. */
    const val FOLDER = "RawBoy Film"
    const val JPEG_SUFFIX = "film"

    /**
     * Shown above "Open-source licences" in Settings. spektrafilm asks for credit wherever it's natural, as a
     * factual reference (not product branding); the GPL asks for the licence and where the source is.
     */
    val credits: List<String> = listOf(
        "Film simulation powered by spektrafilm by Andrea Volpato (github.com/andreavolpato/spektrafilm). " +
            "Film LUTs derived from spektrafilm, CC BY-SA 4.0.",
        "RawBoy Film is free software under the GNU General Public License v3 or later. " +
            "Source code: github.com/lexusag/RawBoyFILM.",
    )
}
