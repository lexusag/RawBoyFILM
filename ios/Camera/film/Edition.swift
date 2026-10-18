// SPDX-License-Identifier: GPL-3.0-or-later
import RawBoyFilmEngine

/**
 * RawBoy Film (target RawBoyFilm, GPLv3): the looks are spektrafilm film stocks (RawBoyFilmEngine). Installs next to
 * RawBoy Camera (app.rawboy.camera.film) and saves to its own albums. Mirrors Android's film Edition.kt.
 */
enum Edition {
    static let looks: [Look] = FilmLooks.all
    static let defaultLookId = FilmLooks.portra400.id

    static func look(_ id: String) -> Look { FilmLooks.byId(id) }

    /** Photos albums for the JPEGs and the DNGs; JPEGs are named IMG_…-<jpegSuffix>.jpg. */
    static let album = "RawBoy Film"
    static let rawAlbum = "RawBoy Film RAW"
    static let jpegSuffix = "film"

    /**
     * Shown above "Open-source licences" in Settings. spektrafilm asks for credit wherever it's natural, as a factual
     * reference (not product branding); the GPL asks for the licence and where the source is.
     */
    static let credits: [String] = [
        "Film simulation powered by spektrafilm by Andrea Volpato (github.com/andreavolpato/spektrafilm). "
            + "Film LUTs derived from spektrafilm, CC BY-SA 4.0.",
        "RawBoy Film is free software under the GNU General Public License v3 or later. "
            + "Source code: github.com/lexusag/RawBoyFILM.",
    ]
}
