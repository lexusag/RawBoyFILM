# /// script
# requires-python = ">=3.11"
# dependencies = ["spectral_film_lut==0.18.12"]
# ///
"""
Generates RawBoy's bundled film LUTs (<repo>/luts/*.cube) with spectral_film_lut
(https://github.com/JanLohse/spectral_film_lut, MIT), which simulates each film stock
from its datasheet: negative (or slide) exposure, then printing and viewing.

RawBoy applies LUTs to the final sRGB image, after AgX. Film simulated on that image
directly would stack the film's S-curve on AgX's and crush the shadows, so each LUT
node is first taken back through RawBoy's AgX (the inverse of `develop()` in
shaders/develop.glsl, at default contrast and levels) to scene-linear light, and the
film is exposed on that. Colour negatives are then scanned and inverted, as a lab
does (an RA-4 print was far too contrasty and saturated on top of a finished photo);
B&W negatives are printed on paper, whose grade sets the contrast; slides are viewed
directly. Each channel's black and white points are set like a scanner's, so the LUT
keeps black and white, and exposure is calibrated so AgX's middle grey keeps its
brightness. The look then replaces AgX's tone curve with the film's, and LUT
Strength blends between the two.

Contrast (scan gamma, or push/pull for slides and B&W) and saturation are set per
stock in STOCKS. They were fitted on test photos in RawBoy Auto to the global
contrast (Oklab L std) and colour (mean chroma) of the old HaldCLUT looks these
replaced, keeping the earlier, datasheet-neutral values where those were already
punchier (Ektar, Velvia, most B&W).

    uv run tools/film_luts/generate.py                      # all stocks
    uv run tools/film_luts/generate.py "Yellow Portrait 400" # only these files
"""

import os
import sys
from pathlib import Path

import colour
import numpy as np
from scipy.ndimage import median_filter

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from spectral_film_lut.film_loader import load_filmstocks  # noqa: E402
from spectral_film_lut.utils import film_conversion  # noqa: E402

LUT_DIR = Path(__file__).resolve().parents[2] / "luts"
LUT_SIZE = 33

SCAN = "scan"  # scan and invert the negative instead of printing it

# (output file name without .cube, our own name with no film brand or product names, negative or slide stock, print stock / SCAN / None
# for slides, contrast, saturation). Contrast is the inversion gamma for SCAN (1.6 is
# about AgX's own, 3.0 is print-like), else push/pull in stops; saturation is
# spectral_film_lut's sat_adjust (Oklab, 1 = as simulated). Names are
# spectral_film_lut's own, typos included ("Trix-X").
STOCKS = [
    # Colour
    ("Yellow Portrait 400", "Kodak Portra 400", SCAN, 2.2, 0.9),
    ("Yellow Portrait 800", "Kodak Portra 800", SCAN, 2.27, 0.96),
    ("Yellow Vivid 100", "Kodak Ektar 100", SCAN, 2.0, 1.04),
    ("Yellow Warm 200", "Kodak Gold 200", SCAN, 2.98, 0.93),
    ("Green Everyday 400", "Fuji Superia X-Tra 400", SCAN, 2.15, 0.9),
    ("Green Pastel 400", "Fuji Pro 400H", SCAN, 2.0, 0.875),
    ("Yellow Cine 500T", "Kodak Vision3 500T 5219", SCAN, 2.46, 0.99),
    ("Green Vivid Slide 50", "Fuji Velvia 50", None, 0.0, 1.12),
    # Black and white: more development and harder paper grades for Push and Hard
    ("Yellow Mono 400", "Kodak Tri-X 400", "Kodak Polymax Fine-Art Paper Grade 2", 0.0, 1.0),
    ("Yellow Mono Push", "Kodak Trix-X 400 Dev 9", "Kodak Polymax Fine-Art Paper Grade 2", 0.0, 1.0),
    ("Yellow Mono Hard", "Kodak Trix-X 400 Dev 11", "Kodak Polymax Fine-Art Paper Grade 3", 0.0, 1.0),
    ("Yellow Cine Mono", "Kodak 5222", "Kodak 2302", 0.0, 1.0),
    ("Yellow Cine Mono Soft", "Kodak 5222 Dev 4", "Kodak Polymax Fine-Art Paper Grade 1", 0.12, 1.0),
]
# Kodak Aerochrome III was tried for an infrared look: from RGB there is no infrared to
# turn foliage red, so it only rendered everything cyan.

# --- RawBoy's AgX, from shaders/develop.glsl (keep in step). GLSL mat3s are column-major,
# so each inner list is one column; .T makes them row-major for numpy.
AGX_INSET = np.array([
    [0.856627153315983, 0.137318972929847, 0.11189821299995],
    [0.0951212405381588, 0.761241990602591, 0.0767994186031903],
    [0.0482516061458583, 0.101439036467562, 0.811302368396859],
]).T
AGX_OUTSET = np.array([
    [1.1271005818144368, -0.1413297634984383, -0.14132976349843826],
    [-0.11060664309660323, 1.157823702216272, -0.11060664309660294],
    [-0.016493938717834573, -0.016493938717834257, 1.2519364065950405],
]).T
REC2020_TO_REC709 = np.array([
    [1.6604910021, -0.1245504745, -0.0181507634],
    [-0.5876411388, 1.1328998971, -0.1005788980],
    [-0.0728498633, -0.0083494226, 1.1187296614],
]).T
AGX_MIN_EV = -12.47393
AGX_MAX_EV = 4.026069
SIGMOID_X = np.linspace(0.0, 1.0, 4097)


def agx_sigmoid(x):
    x2 = x * x
    x4 = x2 * x2
    return (15.5 * x4 * x2 - 40.14 * x4 * x + 31.96 * x4
            - 6.868 * x2 * x + 0.4298 * x2 + 0.1191 * x - 0.00232)


SIGMOID_Y = agx_sigmoid(SIGMOID_X)
assert np.all(np.diff(SIGMOID_Y) > 0), "AgX sigmoid must be monotonic to invert"


def agx_forward(rec2020):
    """Scene-linear Rec.2020 -> RawBoy's sRGB output (contrast 1, no levels)."""
    c = np.log2(np.maximum(rec2020 @ AGX_INSET.T, 1e-10))
    c = np.clip((c - AGX_MIN_EV) / (AGX_MAX_EV - AGX_MIN_EV), 0.0, 1.0)
    c = agx_sigmoid(c) @ AGX_OUTSET.T
    c = np.clip(np.maximum(c, 0.0) ** 2.2 @ REC2020_TO_REC709.T, 0.0, 1.0)
    return colour.models.eotf_inverse_sRGB(c)


def agx_inverse(srgb):
    """RawBoy's sRGB output -> scene-linear Rec.2020 (inverse of agx_forward)."""
    c = colour.models.eotf_sRGB(np.asarray(srgb, np.float64))
    c = c @ np.linalg.inv(REC2020_TO_REC709).T
    c = np.maximum(c, 0.0) ** (1 / 2.2)
    c = c @ np.linalg.inv(AGX_OUTSET).T
    c = np.interp(np.clip(c, SIGMOID_Y[0], SIGMOID_Y[-1]), SIGMOID_Y, SIGMOID_X)
    c = np.exp2(c * (AGX_MAX_EV - AGX_MIN_EV) + AGX_MIN_EV)
    return c @ np.linalg.inv(AGX_INSET).T


GREY_IN = agx_forward(np.full(3, 0.18))  # AgX's rendering of middle grey
LUMA_709 = np.array([0.2126, 0.7152, 0.0722])


# --- Film simulation


def settings(stocks, negative, print_name, contrast=2.0, saturation=1.0):
    if print_name == SCAN:
        tone = dict(inversion=True, custom_inversion=False, inversion_gamma=contrast)
    else:
        tone = dict(push_pull=contrast)
    return tone | dict(
        sat_adjust=saturation,
        input_colorspace=None,  # CIE XYZ, from agx_inverse
        output_gamut="Rec. 709",
        gamma_func="sRGB",
        mode="full",
        white_clip=True,
        white_balance=1,  # slide film: balance by mid grey
        color_masking=negative.color_masking,
        apd_intermediate=True,
        adx_scaling=4.0,  # GUI default "Density 2"
        upsampling_method="SFL upsampling",
        reference_negative=stocks["Kodak Vision3 250D 5207"],
    )


def simulate(srgb, negative, print_film, kwargs, exp_comp):
    """Film print of RawBoy's sRGB output (..., 3), as sRGB with 3 channels."""
    xyz = colour.RGB_to_XYZ(agx_inverse(srgb), "ITU-R BT.2020")
    out = film_conversion(xyz.astype(np.float32), negative, print_film, exp_comp=exp_comp, **kwargs)
    out = np.asarray(out, np.float64)
    return np.repeat(out, 3, axis=-1) if out.shape[-1] == 1 else out


def scan(out, black, white):
    """Sets each channel's black and white points, as a scanner does."""
    return np.clip((out - black) / (white - black), 0.0, 1.0)


def endpoints(negative, print_film, kwargs, exp_comp):
    out = simulate(np.array([[[0.0] * 3, [1.0] * 3]]), negative, print_film, kwargs, exp_comp)[0]
    return out[0], out[1]


def grey_out(negative, print_film, kwargs, exp_comp):
    """Scanned output for AgX's middle grey: linear luminance and sRGB per channel."""
    black, white = endpoints(negative, print_film, kwargs, exp_comp)
    rgb = scan(simulate(GREY_IN[None, None], negative, print_film, kwargs, exp_comp)[0, 0], black, white)
    return float(colour.models.eotf_sRGB(rgb) @ LUMA_709), rgb


def calibrate(negative, print_film, kwargs):
    """Bisects exposure compensation (stops) so grey keeps its brightness."""
    target = float(colour.models.eotf_sRGB(GREY_IN) @ LUMA_709)
    lo, hi = -4.0, 4.0
    for _ in range(18):
        mid = (lo + hi) / 2
        luma, _ = grey_out(negative, print_film, kwargs, mid)
        if luma < target:
            lo = mid
        else:
            hi = mid
    return round((lo + hi) / 2, 3)


def make_table(negative, print_film, kwargs, exp_comp):
    """The LUT, oversampled 2x with a 3x3x3 median like spectral_film_lut's own export."""
    grid = colour.LUT3D(size=LUT_SIZE * 2 - 1).table
    out = simulate(grid, negative, print_film, kwargs, exp_comp)
    for c in range(3):
        out[..., c] = median_filter(out[..., c], size=3, mode="nearest")
    out = out[::2, ::2, ::2]
    return scan(out, *endpoints(negative, print_film, kwargs, exp_comp))


def main(only):
    print("Loading film stocks…", flush=True)
    stocks = load_filmstocks(lambda *a: None)
    missing = [n for _, neg, pr, *_ in STOCKS for n in (neg, pr) if n not in (None, SCAN) and n not in stocks]
    if missing:
        sys.exit(f"Unknown stocks: {missing}")

    LUT_DIR.mkdir(exist_ok=True)
    for file_name, neg_name, print_name, contrast, saturation in STOCKS:
        if only and file_name not in only:
            continue
        negative = stocks[neg_name]
        print_film = stocks[print_name] if print_name not in (None, SCAN) else None
        kwargs = settings(stocks, negative, print_name, contrast, saturation)
        exp_comp = calibrate(negative, print_film, kwargs)
        _, grey = grey_out(negative, print_film, kwargs, exp_comp)
        path = LUT_DIR / f"{file_name}.cube"
        table = make_table(negative, print_film, kwargs, exp_comp)
        colour.io.write_LUT(colour.LUT3D(table=table, name=file_name), str(path))
        print(
            f"{path.name:32s} exp_comp {exp_comp:+.2f}  contrast {contrast:.2f} sat {saturation:.2f}  "
            f"grey {GREY_IN[0]:.3f} -> {np.round(grey, 3)}",
            flush=True,
        )


if __name__ == "__main__":
    main(set(sys.argv[1:]))
