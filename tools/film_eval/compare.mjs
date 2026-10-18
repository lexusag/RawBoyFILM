// Compares the standard apps' film formation (shaders/film_form.glsl, RawBoy's own grain, halation and scatter) with
// RawBoy Film's (shaders/film/film.glsl, spektrafilm's) on real raws, through RawBoy Web's Auto develop. Output only:
// it measures the two renders, it never copies anything between them (see CLAUDE.md, GPL projects).
//
//   cd web && npm run dev            # in another terminal; restart it after editing engine files (hot updates give
//                                    # modules ?t= URLs, and the patch below would reach a stale copy)
//   node tools/film_eval/compare.mjs <raw-folder> <out-dir> [stock ...] [--url http://localhost:5173/] [--size 2000]
//
// For each raw and stock it renders, at --size px (0 = full resolution), both formations three ways: grain and
// halation off, grain only, halation/scatter only. Then it prints, per stock (and writes report.json + the JPEGs):
// - formation dE2000 (both with grain and halation off; the same LUTs, so it should be ~0),
// - grain: std of (grain only - off) in sRGB levels, by output luminance band,
// - halation: mean added red/green/blue in the 1% of pixels it changes most, and the share of pixels it changes.
// The film edition's shader is assembled in the page from the dev server's copy of shaders/film/film.glsl; it never
// enters a build. Needs playwright-core (kept outside the repo, see CLAUDE.md), the installed Chrome, numpy and PIL
// for the measurements (python3).
import { readdirSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, basename, resolve, dirname } from 'node:path';
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const require = createRequire(join(process.env.HOME, 'node_modules', 'x.js'));
const { chromium } = require('playwright-core');

const args = process.argv.slice(2);
const opt = (name, def) => {
  const i = args.indexOf(name);
  if (i < 0) return def;
  const v = args[i + 1];
  args.splice(i, 2);
  return v;
};
const url = opt('--url', 'http://localhost:5173/');
const size = Number(opt('--size', '2000'));
const [dir, out, ...stockArgs] = args;
const stocks = stockArgs.length ? stockArgs : ['portra400', 'ektar100', 'vision500t', 'velvia100'];
const repo = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
mkdirSync(out, { recursive: true });
const spektra = JSON.parse(readFileSync(join(repo, 'luts/spektrafilm/stocks.json'), 'utf8')).stocks;
const files = readdirSync(dir).filter((f) => /\.(dng|raf|cr2|cr3|nef|arw|orf|rw2)$/i.test(f)).sort();

const browser = await chromium.launch({ channel: 'chrome', headless: true });

/** A page whose develop shader is [edition]'s: 'standard' (film_form.glsl) or 'film' (film/film.glsl). */
async function openPage(edition) {
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.log(`${edition}: ${e.message}`));
  await page.goto(url);
  await page.waitForTimeout(2000);
  await page.evaluate(async ({ edition, spektra, repo }) => {
    const i = document.createElement('input');
    i.type = 'file';
    i.id = 'film-eval';
    document.body.appendChild(i);
    if (edition !== 'film') return;
    // RawBoy Film's assembly (Shaders.kt with a non-empty FILM).
    const { Shaders } = await import('/src/engine/gl/Shaders.ts');
    const { DevelopProgram } = await import('/src/engine/gl/GlCommon.ts');
    const text = async (f) => (await import(/* @vite-ignore */ `/@fs${repo}/shaders/${f}?raw`)).default;
    const [develop, film, body] = await Promise.all([text('develop.glsl'), text('film/film.glsl'), text('main_body.glsl')]);
    Shaders.fragment = (forExport) => {
      let s = '#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\nprecision highp sampler3D;\n';
      s += '#define RAWBOY_FILM 1\n';
      s += forExport ? 'out uvec4 fragColor;\n' : 'out vec4 fragColor;\n';
      s += develop + film + body;
      s += '    if (uLutAmount > 0.0) c = clamp(applyLut(c), 0.0, 1.0);\n';
      s += forExport ? '    fragColor = uvec4(uvec3(c * 65535.0 + 0.5), 65535u);\n' : '    fragColor = vec4(c, 1.0);\n';
      return s + '}\n';
    };
    // The app may have compiled the shared developer already: give it a program from the patched source.
    const { sharedDeveloper } = await import('/src/engine/gl/ThumbnailRenderer.ts');
    const developer = sharedDeveloper();
    developer.program.release();
    developer.program = new DevelopProgram(developer.gl, true);
    // RawBoy Film's uniforms (GlCommon.kt bindFilm), from stocks.json, after the standard binding.
    const bindFilm = DevelopProgram.prototype.bindFilm;
    DevelopProgram.prototype.bindFilm = function (p, g) {
      bindFilm.call(this, p, g);
      const f = p.film;
      if (!f) return;
      const s = spektra.find((x) => x.id === f.stock.id);
      const gl = this.gl;
      const v3 = (n, v, k = 1) => gl.uniform3f(this.loc(n), v[0] * k, v[1] * k, v[2] * k);
      const optics = p.halation * p.filmAmount;
      v3('uHalation', s.halation.strength, optics);
      gl.uniform1f(this.loc('uHalationSigmaUm'), s.halation.firstSigmaUm[0]);
      gl.uniform1f(this.loc('uHalationDecay'), s.halation.bounceDecay);
      v3('uScatterTailUm', s.halation.scatterTailUm);
      v3('uScatterWeight', s.halation.scatterTailWeight, Math.min(optics, 1));
      v3('uGrainParticleUm2', s.grain.particleScale, s.grain.particleAreaUm2);
      v3('uGrainDMin', s.grain.densityMin);
      v3('uGrainDMax', s.grain.densityMax);
      v3('uGrainUniformity', s.grain.uniformity);
      gl.uniform1f(this.loc('uGrainBlurPx'), s.grain.blurPx);
    };
  }, { edition, spektra, repo });
  return page;
}

/** Develops the page's raw with film look [stock] (grain and halation in %), as a JPEG. */
async function render(page, stock, grain, halation) {
  const b64 = await page.evaluate(async ({ stock, grain, halation, size }) => {
    const { analyzeFile } = await import('/src/engine/auto/AutoAnalysis.ts');
    const { applyLook, NO_ADJUSTMENTS } = await import('/src/engine/auto/looks/LookApplier.ts');
    const { filmLookById } = await import('/src/engine/auto/looks/FilmLooks.ts');
    const { FilmStocks } = await import('/src/engine/film/FilmStocks.ts');
    const { ShaderParams } = await import('/src/engine/gl/ShaderParams.ts');
    const { LensTransform } = await import('/src/engine/lens/LensTransform.ts');
    const { RawImage } = await import('/src/engine/raw/RawImage.ts');
    const { exportImage } = await import('/src/engine/export/Exporter.ts');
    const file = document.getElementById('film-eval').files[0];
    if (window.__evalFile !== file) {
      window.__evalFile = file;
      window.__analysis = await analyzeFile(file, file.name);
    }
    const a = window.__analysis;
    const look = { ...filmLookById(stock), filmGrain: grain, halation };
    const params = applyLook(a.autoBase, look, 100, NO_ADJUSTMENTS, a.stats.hasFace);
    const film = await FilmStocks.load(stock);
    const img = await RawImage.open(file);
    try {
      const shader = ShaderParams.from(params, a.defaults, null, new LensTransform(img.lens, params), a.base, a.bloom, a.skin, 1, 1, null, () => null, film);
      const settings = { format: 'jpeg', jpegQuality: 98, tiffBits: 16, maxLongSide: size > 0 ? size : null };
      const u8 = new Uint8Array(await (await exportImage(img, shader, settings, () => {})).arrayBuffer());
      let bin = '';
      for (let i = 0; i < u8.length; i += 0x8000) bin += String.fromCharCode(...u8.subarray(i, i + 0x8000));
      return btoa(bin);
    } finally {
      img.close();
    }
  }, { stock, grain, halation, size });
  return Buffer.from(b64, 'base64');
}

const pages = { standard: await openPage('standard'), film: await openPage('film') };
const renders = [];
for (const f of files) {
  for (const page of Object.values(pages)) await page.setInputFiles('#film-eval', join(dir, f));
  for (const stock of stocks) {
    for (const [edition, page] of Object.entries(pages)) {
      for (const [variant, g, h] of [['off', 0, 0], ['grain', 100, 0], ['halation', 0, 100]]) {
        const name = `${basename(f).replace(/\.[^.]+$/, '')}-${stock}-${edition}-${variant}.jpg`;
        writeFileSync(join(out, name), await render(page, stock, g, h));
      }
    }
    renders.push({ raw: basename(f).replace(/\.[^.]+$/, ''), stock });
    console.log(f, stock);
  }
}
await browser.close();

const measure = String.raw`
import json, sys
import numpy as np
from PIL import Image
out = sys.argv[1]
renders = json.loads(sys.argv[2])

def load(raw, stock, edition, variant):
    return np.asarray(Image.open(f'{out}/{raw}-{stock}-{edition}-{variant}.jpg').convert('RGB')).astype(np.float64)

def lab(rgb):
    c = rgb / 255.0
    c = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    m = np.array([[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722], [0.0193, 0.1192, 0.9505]])
    xyz = c @ m.T / np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > 216 / 24389, np.cbrt(xyz), (24389 / 27 * xyz + 16) / 116)
    return np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])], -1)

def de2000(a, b):
    L1, a1, b1 = a[..., 0], a[..., 1], a[..., 2]
    L2, a2, b2 = b[..., 0], b[..., 1], b[..., 2]
    C1, C2 = np.hypot(a1, b1), np.hypot(a2, b2)
    Cm = (C1 + C2) / 2
    G = 0.5 * (1 - np.sqrt(Cm ** 7 / (Cm ** 7 + 25 ** 7)))
    a1p, a2p = (1 + G) * a1, (1 + G) * a2
    C1p, C2p = np.hypot(a1p, b1), np.hypot(a2p, b2)
    h1p, h2p = np.degrees(np.arctan2(b1, a1p)) % 360, np.degrees(np.arctan2(b2, a2p)) % 360
    dLp, dCp = L2 - L1, C2p - C1p
    dh = h2p - h1p
    dh = np.where(np.abs(dh) > 180, dh - 360 * np.sign(dh), dh)
    dh = np.where(C1p * C2p == 0, 0, dh)
    dHp = 2 * np.sqrt(C1p * C2p) * np.sin(np.radians(dh / 2))
    Lm, Cmp = (L1 + L2) / 2, (C1p + C2p) / 2
    hs = h1p + h2p
    hm = np.where(C1p * C2p == 0, hs, np.where(np.abs(h1p - h2p) > 180, np.where(hs < 360, hs + 360, hs - 360) / 2, hs / 2))
    T = 1 - 0.17 * np.cos(np.radians(hm - 30)) + 0.24 * np.cos(np.radians(2 * hm)) + 0.32 * np.cos(np.radians(3 * hm + 6)) - 0.2 * np.cos(np.radians(4 * hm - 63))
    Sl = 1 + 0.015 * (Lm - 50) ** 2 / np.sqrt(20 + (Lm - 50) ** 2)
    Sc, Sh = 1 + 0.045 * Cmp, 1 + 0.015 * Cmp * T
    Rt = -2 * np.sqrt(Cmp ** 7 / (Cmp ** 7 + 25 ** 7)) * np.sin(np.radians(60 * np.exp(-(((hm - 275) / 25) ** 2))))
    return np.sqrt((dLp / Sl) ** 2 + (dCp / Sc) ** 2 + (dHp / Sh) ** 2 + Rt * (dCp / Sc) * (dHp / Sh))

BANDS = [(0, 40), (40, 90), (90, 150), (150, 256)]
report = []
for r in renders:
    raw, stock = r['raw'], r['stock']
    row = {'raw': raw, 'stock': stock}
    off = {e: load(raw, stock, e, 'off') for e in ('standard', 'film')}
    d = de2000(lab(off['standard']), lab(off['film']))
    row['formation_dE'] = {'median': round(float(np.median(d)), 3), 'p99': round(float(np.percentile(d, 99)), 3)}
    for e in ('standard', 'film'):
        y = off[e] @ np.array([0.2126, 0.7152, 0.0722])
        g = load(raw, stock, e, 'grain') - off[e]
        row[f'grain_{e}'] = {f'{lo}-{hi}': [round(float(v), 2) for v in g[(y >= lo) & (y < hi)].std(axis=0)] if ((y >= lo) & (y < hi)).sum() > 1000 else None for lo, hi in BANDS}
        h = load(raw, stock, e, 'halation') - off[e]
        n = np.abs(h).sum(-1)
        top = n >= np.percentile(n, 99)
        row[f'halation_{e}'] = {'top1pct_mean_rgb': [round(float(v), 2) for v in h[top].mean(axis=0)], 'share_over_2_levels': round(float((n > 2).mean()), 4)}
    report.append(row)
    print(json.dumps(row))
json.dump(report, open(f'{out}/report.json', 'w'), indent=1)
`;
execFileSync('python3', ['-c', measure, out, JSON.stringify(renders)], { stdio: 'inherit' });
