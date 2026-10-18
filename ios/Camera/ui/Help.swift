#if FILM_EDITION
import RawBoyFilmEngine
#else
import RawBoyEngine
#endif

/** RawBoy Camera's and RawBoy Film's "?" sheet. Keep it in step with the Readme's RawBoy Camera section. */
let cameraHelp = [
    HelpSection("Shooting", [
        HelpItem("Shutter, volume buttons or Camera Control", "Take a photo. It is saved as a JPEG developed with the look (and the DNG, if RAW is on)."),
        HelpItem("Tap the viewfinder", "Lock focus and exposure at that point. AF LOCK ✕ or a tap elsewhere releases it."),
        HelpItem("Press and hold", "See the photo without the look."),
        HelpItem("UW · W · T", "Switch between the ultrawide, wide and telephoto lenses. The button next to the shutter switches to the selfie camera."),
        HelpItem("Thumbnail", "Opens Photos."),
    ]),
    HelpSection("Look", [
        HelpItem("Look menu", "The film look the viewfinder shows and the photo gets."),
        HelpItem("Strength, Brightness, Warmth", "How much of the look, brighter or darker, warmer or cooler. Double-tap a slider to reset it."),
    ]),
    HelpSection("Top bar", [
        HelpItem("RAW", "Keep the DNG next to the JPEG (lit) or not (struck through)."),
        HelpItem("Flash", "Turns the flash on or off."),
        HelpItem("Settings", "JPEG quality, Smaller photos, Histogram (live red, green and blue levels in a corner of the viewfinder), Remember camera & settings and the licences."),
    ]),
]
