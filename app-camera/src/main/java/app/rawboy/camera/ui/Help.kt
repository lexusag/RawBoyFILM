package app.rawboy.camera.ui

import app.rawboy.view.HelpItem
import app.rawboy.view.HelpSection

/** RawBoy Camera's and RawBoy Film's "?" sheet. Keep it in step with the Readme's RawBoy Camera section. */
internal val CAMERA_HELP = listOf(
    HelpSection(
        "Shooting",
        listOf(
            HelpItem("Shutter or volume buttons", "Take a photo. It is saved as a JPEG developed with the look (and the DNG, if RAW is on)."),
            HelpItem("Tap the viewfinder", "Lock focus and exposure at that point. AF LOCK ✕ or a tap elsewhere releases it."),
            HelpItem("Press and hold", "See the photo without the look."),
            HelpItem("UW · W · T", "Switch between the ultrawide, wide and telephoto lenses. The button next to the shutter switches to the selfie camera."),
            HelpItem("Thumbnail", "Opens the last photo."),
        ),
    ),
    HelpSection(
        "Look",
        listOf(
            HelpItem("Look menu", "The film look the viewfinder shows and the photo gets."),
            HelpItem("Strength, Brightness, Warmth", "How much of the look, brighter or darker, warmer or cooler. Double-tap a slider to reset it."),
        ),
    ),
    HelpSection(
        "Top bar",
        listOf(
            HelpItem("RAW", "Keep the DNG next to the JPEG (lit) or not (struck through)."),
            HelpItem("Flash", "Cycles Off, Flash and Focus-flash (the light helps focusing, then the flash fires)."),
            HelpItem("Settings", "JPEG quality, Smaller photos, Merge 8 frames (each shot merges a burst of raws: less noise; hold still), Histogram (live red, green and blue levels in a corner of the viewfinder), Remember camera & settings and the licences."),
        ),
    ),
)
