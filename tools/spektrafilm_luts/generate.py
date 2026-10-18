# SPDX-License-Identifier: GPL-3.0-or-later
# /// script
# requires-python = "~=3.13"
# dependencies = ["spektrafilm @ git+https://github.com/andreavolpato/spektrafilm@500bc42"]
# ///
"""
Bakes RawBoy Camera's film stocks with spektrafilm (https://github.com/andreavolpato/spektrafilm,
Andrea Volpato; code GPLv3, profiles and LUTs CC BY-SA 4.0) into <repo>/luts/spektrafilm/.

Each stock is spektrafilm's own 2-LUT bundle (its LUT creator, unchanged):

    film LUT   Apple Log (BT.2020 primaries) -> normalised film CMY density   (exposure, density curves, DIR couplers)
    print LUT  normalised film CMY density -> sRGB                            (enlarger, paper, scan; slides: scan only)

RawBoy's decoded image is linear Rec.2020 with middle grey at 0.18 after exposure, which is what spektrafilm's
film expects (the bundle's input gain is 1), so the shader only encodes Apple Log before the film LUT. Between the
two LUTs it adds grain in density space; before the film it adds halation and in-emulsion scatter. Those are
spektrafilm's spatial effects, which a LUT can't hold (lut_mode turns them off), so their parameters go to
stocks.json for the shader (shaders/film/film.glsl).

Each bundle's .cube headers, bundle.json and SPEKTRAFILM_LICENSE.txt are copied as the LUT creator writes them;
CHANGELOG.txt records what RawBoy changed (only file names and the slide-film scan, see below).

    uv run tools/spektrafilm_luts/generate.py                 # all stocks
    uv run tools/spektrafilm_luts/generate.py portra400       # only these ids
"""

import json
import shutil
import sys
import tempfile
from pathlib import Path

import numpy as np

from spektrafilm.runtime.params_builder import digest_params, init_params
from spektrafilm_lut_creator.builders import BundleBuilder
from spektrafilm_lut_creator.bundles import BundleSpec

OUT_DIR = Path(__file__).resolve().parents[2] / "luts" / "spektrafilm"
SPEKTRAFILM_COMMIT = "500bc42"
RESOLUTION = 33
INPUT = "applelog"
OUTPUT = "srgb"
DEFAULT_PAPER = "kodak_portra_endura"
CINE_PRINT = "kodak_2383"

# (RawBoy id, display name (RawBoy's own, no film brand or product names), spektrafilm film profile, print profile; slides are scanned, the print is unused)
STOCKS = [
    ("portra400", "Yellow Portrait 400", "kodak_portra_400", DEFAULT_PAPER),
    ("portra800", "Yellow Portrait 800", "kodak_portra_800", DEFAULT_PAPER),
    ("ektar100", "Yellow Vivid 100", "kodak_ektar_100", DEFAULT_PAPER),
    ("gold200", "Yellow Warm 200", "kodak_gold_200", DEFAULT_PAPER),
    ("fuji400h", "Green Pastel 400", "fujifilm_pro_400h", DEFAULT_PAPER),
    ("xtra400", "Green Everyday 400", "fujifilm_xtra_400", DEFAULT_PAPER),
    ("vision250d", "Yellow Cine 250D", "kodak_vision3_250d", CINE_PRINT),
    ("vision500t", "Yellow Cine 500T", "kodak_vision3_500t", CINE_PRINT),
    ("velvia100", "Green Vivid Slide 100", "fujifilm_velvia_100", DEFAULT_PAPER),
    ("provia100", "Green Slide 100", "fujifilm_provia_100f", DEFAULT_PAPER),
    ("ektachrome100", "Yellow Slide 100", "kodak_ektachrome_100", DEFAULT_PAPER),
    ("kodachrome64", "Yellow Classic 64", "kodak_kodachrome_64", DEFAULT_PAPER),
]


class _SlideBuilder(BundleBuilder):
    """Slides are viewed (scanned) directly, not printed: spektrafilm's io.scan_film."""

    def _make_pipeline(self, spec, in_entry, out_entry, print_stock):
        from spektrafilm.runtime.pipeline import SimulationPipeline

        params = super()._make_pipeline(spec, in_entry, out_entry, print_stock)._params
        params.io.scan_film = True
        return SimulationPipeline(params)


def spatial_params(film: str, paper: str) -> tuple[dict, bool]:
    """spektrafilm's grain and halation settings for [film], after its per-stock overrides (digest_params)."""
    p = digest_params(init_params(film, paper))
    g = p.film_render.grain
    h = p.film_render.halation
    density_max = np.nanmax(p.film.data.density_curves, axis=0)
    return {
        "grain": {
            "particleAreaUm2": g.agx_particle_area_um2,
            "particleScale": list(g.agx_particle_scale),
            "densityMin": list(g.density_min),
            "densityMax": [round(float(v), 5) for v in density_max],
            "uniformity": list(g.uniformity),
            "blurPx": g.blur,
        },
        "halation": {
            "strength": list(h.halation_strength),
            "firstSigmaUm": list(h.halation_first_sigma_um),
            "bounces": h.halation_n_bounces,
            "bounceDecay": h.halation_bounce_decay,
            "scatterTailUm": list(h.scatter_tail_um),
            "scatterTailWeight": list(h.scatter_tail_weight),
        },
    }, bool(p.film.is_positive)


def bake(rawboy_id: str, name: str, film: str, paper: str, work: Path) -> dict:
    spatial, positive = spatial_params(film, paper)
    spec = BundleSpec(
        film_profile=film,
        print_profiles=(paper,),
        input_color_space=INPUT,
        output_color_space=OUTPUT,
        topology="2lut",
        resolution=RESOLUTION,
    )
    builder = (_SlideBuilder if positive else BundleBuilder)(spec)
    bundle_dir = Path(builder.write(builder.build(), work))
    meta = json.loads((bundle_dir / "bundle.json").read_text())
    if meta["input_exposure"]["gain"] != 1.0 and abs(meta["input_exposure"]["gain"] - 1.0) > 1e-6:
        raise RuntimeError(f"{film}: input gain {meta['input_exposure']['gain']}, the shader assumes 1")

    luts = {l["role"]: l for l in meta["luts"]}
    film_file = f"{rawboy_id}.film.cube"
    print_file = f"{rawboy_id}.print.cube"
    shutil.copyfile(bundle_dir / luts["film"]["path"], OUT_DIR / film_file)
    shutil.copyfile(bundle_dir / luts["print"]["path"], OUT_DIR / print_file)
    shutil.copyfile(bundle_dir / "bundle.json", OUT_DIR / f"{rawboy_id}.bundle.json")
    shutil.copyfile(bundle_dir / "SPEKTRAFILM_LICENSE.txt", OUT_DIR / "SPEKTRAFILM_LICENSE.txt")

    wire = meta["wires"]["cmy_film"]
    return {
        "id": rawboy_id,
        "name": name,
        "film": film,
        "print": None if positive else paper,
        "positive": positive,
        "filmLut": film_file,
        "printLut": print_file,
        "densityMin": wire["d_min"],
        "densityMaxWire": wire["d_max"],
        **spatial,
    }


CHANGELOG = """Changes RawBoy made to the spektrafilm LUTs in this folder
==========================================================

The LUTs are derived from spektrafilm by Andrea Volpato
(https://github.com/andreavolpato/spektrafilm), licensed CC BY-SA 4.0
(SPEKTRAFILM_LICENSE.txt, unmodified). Modified by the RawBoy authors; the
modified files are licensed CC BY-SA 4.0 as well.

- Baked with spektrafilm's own LUT creator at commit {commit} (spektrafilm
  {version}), 2-LUT topology, {res}^3, Apple Log in, sRGB out. Cube contents
  and headers are unchanged.
- Files renamed: <id>.film.cube, <id>.print.cube, <id>.bundle.json.
- Slide films (positive: {slides}) are baked with spektrafilm's io.scan_film
  option, so their "print" LUT scans the slide instead of printing it.
- stocks.json collects each bundle's density wire and spektrafilm's grain and
  halation parameters for RawBoy's shader; it is generated, not hand edited.
"""


def main() -> None:
    wanted = set(sys.argv[1:])
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    index_path = OUT_DIR / "stocks.json"
    index = json.loads(index_path.read_text()) if wanted and index_path.exists() else {"stocks": []}
    by_id = {s["id"]: s for s in index["stocks"]}
    with tempfile.TemporaryDirectory() as tmp:
        for rawboy_id, name, film, paper in STOCKS:
            if wanted and rawboy_id not in wanted:
                continue
            print(f"--- {rawboy_id}: {film} on {paper}")
            by_id[rawboy_id] = bake(rawboy_id, name, film, paper, Path(tmp))
    order = [s[0] for s in STOCKS]
    stocks = [by_id[i] for i in order if i in by_id]
    version = json.loads((OUT_DIR / f"{stocks[0]['id']}.bundle.json").read_text())["provenance"]["spektrafilm_version"]
    index = {
        "source": "https://github.com/andreavolpato/spektrafilm",
        "commit": SPEKTRAFILM_COMMIT,
        "spektrafilmVersion": version,
        "license": "CC BY-SA 4.0, see SPEKTRAFILM_LICENSE.txt and CHANGELOG.txt",
        "input": "Apple Log (BT.2020 primaries), input gain 1: linear 0.18 = middle grey",
        "output": "sRGB",
        "stocks": stocks,
    }
    index_path.write_text(json.dumps(index, indent=2) + "\n")
    slides = ", ".join(s["name"] for s in stocks if s["positive"]) or "none"
    (OUT_DIR / "CHANGELOG.txt").write_text(
        CHANGELOG.format(commit=SPEKTRAFILM_COMMIT, version=version, res=RESOLUTION, slides=slides)
    )


if __name__ == "__main__":
    main()
