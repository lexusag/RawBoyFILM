#include "rawboy.h"

#include <cstring>
#include "color_math.h"

#include <libraw/libraw.h>

#ifdef __ANDROID__
#include <android/log.h>
#endif

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <ctime>
#include <exception>
#include <memory>
#include <mutex>
#include <vector>

namespace rawboy {
namespace {

/** EXIF essentials from LibRaw's parsed metadata (see PhotoMetadata). */
void readMetadata(const libraw_data_t& d, PhotoMetadata& m) {
    m.make = d.idata.make;
    m.model = d.idata.model;
    m.lensMake = d.lens.LensMake;
    m.lensModel = d.lens.Lens;
    // For Canon, LibRaw fills artist from the makernote's owner name, not EXIF Artist: leave it out, as Android's
    // ExifInterface copy would (it would put the owner's name into every export).
    if (m.make != "Canon") m.artist = d.other.artist;
    m.iso = d.other.iso_speed;
    if (d.other.shutter > 0) m.exposureTime = d.other.shutter;
    // ApertureValue 0 (unknown, e.g. a manual lens) comes out as 2^0 = f/1.0; such lenses report no focal length either.
    if (d.other.aperture > 0 && !(d.other.aperture == 1.0f && d.other.focal_len <= 0)) m.fNumber = d.other.aperture;
    if (d.other.focal_len > 0) m.focalLength = d.other.focal_len;
    if (d.lens.FocalLengthIn35mmFormat > 0) m.focalLength35mm = d.lens.FocalLengthIn35mmFormat;
    if (d.other.timestamp > 0) {
        // LibRaw turned the file's local date and time into time_t with mktime; localtime gives it back.
        std::tm t{};
        const std::time_t ts = d.other.timestamp;
        if (localtime_r(&ts, &t)) {
            char buf[32];
            std::snprintf(buf, sizeof buf, "%04d:%02d:%02d %02d:%02d:%02d", t.tm_year + 1900, t.tm_mon + 1, t.tm_mday,
                          t.tm_hour, t.tm_min, t.tm_sec);
            m.dateTimeOriginal = buf;
        }
    }
    const libraw_gps_info_t& g = d.other.parsed_gps;
    if (d.other.parsed_gps.gpsparsed && (g.latref == 'N' || g.latref == 'S') && (g.longref == 'E' || g.longref == 'W')) {
        m.hasGps = true;
        for (int i = 0; i < 3; ++i) {
            m.gpsLatitude[i] = g.latitude[i];
            m.gpsLongitude[i] = g.longitude[i];
        }
        m.gpsLatitudeRef = g.latref;
        m.gpsLongitudeRef = g.longref;
        m.gpsAltitude = g.altitude;
        m.gpsBelowSeaLevel = g.altref == 1;
    }
}

// The as-shot white chromaticity from the camera WB multipliers and the
// XYZ->camera matrix. Leaves (x, y) untouched (D65) if anything is missing.
/**
 * XYZ -> camera from the DNG's own matrices, as LibRaw picks them for its colour conversion: the D65 (else a
 * daylight-type) ColorMatrix, times its CameraCalibration. color.cam_xyz only comes from LibRaw's camera table, so
 * for DNGs from cameras it doesn't list (phones) it was zero and the as-shot white fell back to D65: a Pixel shot
 * under tungsten (camera: 2850 K) came out as 6500 K, and its auto white balance went a different way than the
 * camera app's viewfinder. AnalogBalance multiplies in too (DNG: AB x CC x CM): Apple ProRAW stores pre-balanced
 * data with AsShotNeutral 1 1 1 and its as-shot white only in AnalogBalance; without it every iPhone shot read as a
 * strong magenta (xy 0.36, 0.24). False when the file has no DNG matrix.
 */
bool dngCamFromXyz(const libraw_colordata_t& color, Mat3& out) {
    int pick = -1;
    for (int pass = 0; pass < 2 && pick < 0; ++pass)
        for (int i = 0; i < 2; ++i) {
            const int ill = color.dng_color[i].illuminant;
            const bool daylight = ill == LIBRAW_WBI_Daylight || ill == LIBRAW_WBI_D55 || ill == LIBRAW_WBI_D75 ||
                                  ill == LIBRAW_WBI_D50 || ill == LIBRAW_WBI_Flash;
            if (pass == 0 ? ill == LIBRAW_WBI_D65 : daylight) {
                pick = i;
                break;
            }
        }
    if (pick < 0) return false;
    const auto& d = color.dng_color[pick];
    double magnitude = 0;
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c) magnitude += std::fabs(d.colormatrix[r][c]);
    if (magnitude == 0) return false;
    // A missing CameraCalibration is all zeros: identity then.
    const bool hasCal = d.calibration[0][0] != 0 && d.calibration[1][1] != 0 && d.calibration[2][2] != 0;
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c) {
            double v = 0;
            for (int k = 0; k < 3; ++k) v += (hasCal ? d.calibration[r][k] : (r == k ? 1.0 : 0.0)) * d.colormatrix[k][c];
            // LibRaw leaves AnalogBalance at 1 when the tag is absent.
            const float ab = color.dng_levels.analogbalance[r];
            out.m[r][c] = (ab > 0 ? ab : 1.0) * v;
        }
    return true;
}

void estimateAsShotWhite(const libraw_colordata_t& color, double& x, double& y) {
    float mul[3] = {color.cam_mul[0], color.cam_mul[1], color.cam_mul[2]};
    if (!(mul[0] > 0 && mul[1] > 0 && mul[2] > 0)) {
        mul[0] = color.pre_mul[0];
        mul[1] = color.pre_mul[1];
        mul[2] = color.pre_mul[2];
    }
    if (!(mul[0] > 0 && mul[1] > 0 && mul[2] > 0)) return;

    Mat3 camFromXyz{};
    double magnitude = 0;
    if (!dngCamFromXyz(color, camFromXyz)) {
        for (int r = 0; r < 3; ++r)
            for (int c = 0; c < 3; ++c) camFromXyz.m[r][c] = color.cam_xyz[r][c];
    }
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c) magnitude += std::fabs(camFromXyz.m[r][c]);
    Mat3 xyzFromCam{};
    if (magnitude == 0 || !invert(camFromXyz, xyzFromCam)) return;

    const Vec3 neutral = {1.0 / mul[0], 1.0 / mul[1], 1.0 / mul[2]};
    const Vec3 xyz = multiply(xyzFromCam, neutral);
    const double sum = xyz[0] + xyz[1] + xyz[2];
    if (!(sum > 0)) return;
    const double cx = xyz[0] / sum;
    const double cy = xyz[1] / sum;
    if (cx > 0.2 && cx < 0.6 && cy > 0.2 && cy < 0.5) {
        x = cx;
        y = cy;
    }
}

constexpr Mat3 kSrgbToRec2020 = {{{0.6274040, 0.3292820, 0.0433136},
                                  {0.0690970, 0.9195400, 0.0113612},
                                  {0.0163916, 0.0880132, 0.8955950}}};

/** dcraw/LibRaw flip code -> TIFF orientation (inverse of LibRaw's "50132467" table). */
int flipToTiffOrientation(int flip) {
    static const int kTable[8] = {1, 2, 4, 3, 5, 8, 6, 7};
    return (flip >= 0 && flip < 8) ? kTable[flip] : 1;
}

/** Evaluates a set of GainMaps along image rows with per-column lookups precomputed. */
/** A gain map and which of its gain planes applies to a channel. */
struct MapPlane {
    const GainMap* map;
    uint32_t mapPlane;
};

class GainEvaluator {
public:
    GainEvaluator(std::vector<MapPlane> maps, int width) : maps_(std::move(maps)) {
        for (const MapPlane& mp : maps_) {
            const GainMap* m = mp.map;
            Columns c;
            c.h0.resize(width);
            c.t.resize(width);
            for (int x = 0; x < width; ++x) {
                const double fh = std::clamp((double(x) / width - m->originH) / m->spacingH, 0.0,
                                             double(m->pointsH - 1));
                const uint32_t h0 = std::min(static_cast<uint32_t>(fh), m->pointsH > 1 ? m->pointsH - 2 : 0);
                c.h0[x] = h0;
                c.t[x] = static_cast<float>(fh - h0);
            }
            cols_.push_back(std::move(c));
        }
    }

    bool empty() const { return maps_.empty(); }

    /** Fills row[] (one value per map grid column) for image row fraction v. */
    void prepareRow(double rowFract, std::vector<std::vector<float>>& rows) const {
        rows.resize(maps_.size());
        for (size_t i = 0; i < maps_.size(); ++i) {
            const GainMap& m = *maps_[i].map;
            rows[i].resize(m.pointsH);
            for (uint32_t h = 0; h < m.pointsH; ++h) rows[i][h] = m.at(rowFract, m.originH + h * m.spacingH, maps_[i].mapPlane);
        }
    }

    float gain(const std::vector<std::vector<float>>& rows, int x) const {
        float sum = 0;
        for (size_t i = 0; i < maps_.size(); ++i) {
            const auto& r = rows[i];
            const uint32_t h0 = cols_[i].h0[x];
            const uint32_t h1 = std::min<uint32_t>(h0 + 1, static_cast<uint32_t>(r.size() - 1));
            sum += r[h0] + (r[h1] - r[h0]) * cols_[i].t[x];
        }
        return sum / maps_.size();
    }

private:
    struct Columns {
        std::vector<uint32_t> h0;
        std::vector<float> t;
    };
    std::vector<MapPlane> maps_;
    std::vector<Columns> cols_;
};

/** Average of same-sized maps as a GPU shading grid. */
ShadingGrid averageGrid(const std::vector<MapPlane>& maps) {
    ShadingGrid g;
    if (maps.empty()) return g;
    const GainMap& first = *maps[0].map;
    g.pointsV = first.pointsV;
    g.pointsH = first.pointsH;
    g.spacingV = first.spacingV;
    g.spacingH = first.spacingH;
    g.originV = first.originV;
    g.originH = first.originH;
    g.gains.assign(size_t(g.pointsV) * g.pointsH, 0.0f);
    int used = 0;
    for (const MapPlane& mp : maps) {
        const GainMap* m = mp.map;
        if (m->pointsV != g.pointsV || m->pointsH != g.pointsH) continue;
        const uint32_t plane = std::min(mp.mapPlane, m->mapPlanes - 1);
        for (size_t k = 0; k < g.gains.size(); ++k) g.gains[k] += m->gains[k * m->mapPlanes + plane];
        ++used;
    }
    for (float& v : g.gains) v /= used;
    return g;
}

/**
 * Hot and warm pixel removal on the CFA data, before demosaicing spreads a single bright photosite into a coloured
 * speck. Long exposures and high ISO show them (GFX 50R at ISO 6400: ~15 pixels 1000+ DN and a few thousand at
 * 5–20 sigma over a ~12 DN noise floor, mostly vertical pairs), and a dark shot pushed several stops shows them all.
 *
 * A pixel is hot when it exceeds the second highest of its same-colour neighbours (5×5 window: 8 for Bayer red and
 * blue, 12 for green, so one hot neighbour doesn't hide it) by kHotSigma × the image's own noise at that level, and
 * at most one adjacent photosite is raised too (its defective partner): light, even a highlight a couple of pixels
 * across, spills into several of them through the lens and microlenses. Pixels next to a hot one are tested again
 * at kPartnerSigma, as the partner is often weaker. A hot pixel is replaced by the median of its same-colour
 * neighbours. Returns the number of pixels replaced.
 */
int removeHotPixels(LibRaw& raw) {
    constexpr float kHotSigma = 5.0f;      // excess over the 2nd highest same-colour neighbour, in noise sigmas
    constexpr float kPartnerSigma = 3.0f;  // the same for pixels next to a hot one
    constexpr float kMaxSpill = 0.25f;     // adjacent pixels raised by more than this × the excess: real detail
    constexpr float kSpillNoise = 2.0f;    // plus this many sigmas
    constexpr int kPeriod = 48;            // colour pattern period: Bayer (8×2), X-Trans (6×6), Leaf (16×16)
    constexpr int kBins = 36;              // noise level bins, half a stop each
    constexpr int kMaxDiff = 4096;         // noise histogram range (DN)

    const auto& s = raw.imgdata.sizes;
    const auto& color = raw.imgdata.color;
    uint16_t* data = raw.imgdata.rawdata.raw_image;
    // Linear (demosaiced) raws and Fuji's rotated SuperCCD layout are left alone.
    if (!data || raw.imgdata.idata.filters == 0 || raw.is_fuji_rotated()) return 0;
    const int w = s.width, h = s.height;
    if (w < 16 || h < 16) return 0;
    const int pitch = s.raw_pitch / 2;
    uint16_t* base = data + static_cast<size_t>(s.top_margin) * pitch + s.left_margin;  // visible area

    // LibRaw numbers the second green of RGB sensors 3; it's the same colour here. Without it, a green pixel's
    // nearest same-colour neighbours (the diagonals) were missing, and fine diagonal detail (hair) looked hot.
    const bool mergeGreens = raw.imgdata.idata.colors == 3;
    auto colourAt = [&](int y, int x) {
        const int c = raw.COLOR(y, x) & 3;
        return mergeGreens && c == 3 ? 1 : c;
    };

    // Neighbourhood layout per pattern position (visible coordinates, as LibRaw's COLOR takes them).
    struct Layout {
        int colour = 0;
        std::vector<int> same;                    // same colour within 5×5, centre excluded
        std::vector<std::pair<int, int>> adj;     // adjacent pixels (offset, colour) whose colour is on the ring
        std::vector<std::pair<int, int>> ring;    // Chebyshev distance 2 (offset, colour), their background
    };
    std::vector<Layout> layouts(kPeriod * kPeriod);
    for (int py = 0; py < kPeriod; ++py)
        for (int px = 0; px < kPeriod; ++px) {
            // Evaluated away from the image edge so pattern lookups never see negative coordinates.
            const int cy = kPeriod + py, cx = kPeriod + px;
            Layout& l = layouts[py * kPeriod + px];
            l.colour = colourAt(cy, cx);
            bool ringHas[4] = {false, false, false, false};
            for (int dy = -2; dy <= 2; ++dy)
                for (int dx = -2; dx <= 2; ++dx) {
                    const int c = colourAt(cy + dy, cx + dx);
                    if (std::max(std::abs(dx), std::abs(dy)) == 2) {
                        l.ring.emplace_back(dy * pitch + dx, c);
                        ringHas[c] = true;
                    }
                    if ((dx || dy) && c == l.colour) l.same.push_back(dy * pitch + dx);
                }
            for (int dy = -1; dy <= 1; ++dy)
                for (int dx = -1; dx <= 1; ++dx) {
                    const int c = colourAt(cy + dy, cx + dx);
                    if ((dx || dy) && ringHas[c]) l.adj.emplace_back(dy * pitch + dx, c);
                }
            if (l.same.size() < 4) return 0;  // unknown layout
        }
    // Channel sensitivities: spill is compared white balanced, or the red and blue around a green specular (raw
    // values about half of green's in daylight) would look unlit.
    float mul[4];
    const bool camMul = color.cam_mul[0] > 0 && color.cam_mul[1] > 0 && color.cam_mul[2] > 0;
    for (int c = 0; c < 4; ++c) {
        const float m = camMul ? color.cam_mul[c] : color.pre_mul[c];
        mul[c] = m > 0 ? m : (camMul ? color.cam_mul[1] : color.pre_mul[1]);
        if (!(mul[c] > 0)) mul[c] = 1;
    }
    const float clipLevel = 0.9f * float(color.maximum ? color.maximum : 65535);
    auto layoutAt = [&](int y, int x) -> const Layout& { return layouts[(y % kPeriod) * kPeriod + x % kPeriod]; };
    auto blackAt = [&](int y, int x, int c) {
        float b = float(color.black) + float(color.cblack[c]);
        if (color.cblack[4] && color.cblack[5])
            b += color.cblack[6 + (y % color.cblack[4]) * color.cblack[5] + x % color.cblack[5]];
        return b;
    };
    auto levelBin = [](float level) {
        return std::clamp(static_cast<int>(2.0f * std::log2(std::max(level, 0.0f) + 1.0f)), 0, kBins - 1);
    };

    // Noise model: sigma per signal level from the differences between each pixel and the mean of its
    // same-colour neighbours, on a sparse grid (stride 5 is coprime with every pattern period).
    std::vector<uint32_t> hist(static_cast<size_t>(kBins) * kMaxDiff, 0);
    std::vector<uint32_t> binCount(kBins, 0);
    float spreadFactor[2] = {0, 0};  // sum of 1/n over samples -> variance factor of (v - mean)
    for (int y = 2; y < h - 2; y += 5)
        for (int x = 2; x < w - 2; x += 5) {
            const Layout& l = layoutAt(y, x);
            const uint16_t* p = base + static_cast<size_t>(y) * pitch + x;
            float sum = 0;
            for (int o : l.same) sum += p[o];
            const float mean = sum / l.same.size();
            const int b = levelBin(mean - blackAt(y, x, l.colour));
            const int d = std::min(kMaxDiff - 1, static_cast<int>(std::fabs(p[0] - mean) + 0.5f));
            ++hist[static_cast<size_t>(b) * kMaxDiff + d];
            ++binCount[b];
            spreadFactor[0] += 1.0f / l.same.size();
            spreadFactor[1] += 1.0f;
        }
    if (spreadFactor[1] == 0) return 0;
    // |v - mean| has variance sigma² (1 + 1/n); its median is 0.6745 × that sigma.
    const float madToSigma = 1.4826f / std::sqrt(1.0f + spreadFactor[0] / spreadFactor[1]);
    float sigma[kBins];
    int firstValid = -1;
    for (int b = 0; b < kBins; ++b) {
        sigma[b] = -1;
        if (binCount[b] < 200) continue;
        uint32_t acc = 0;
        int d = 0;
        while (d < kMaxDiff - 1 && (acc += hist[static_cast<size_t>(b) * kMaxDiff + d]) * 2 < binCount[b]) ++d;
        sigma[b] = std::max(1.0f, d * madToSigma);
        if (firstValid < 0) firstValid = b;
    }
    if (firstValid < 0) return 0;
    for (int b = 0; b < kBins; ++b)
        if (sigma[b] < 0) sigma[b] = b < firstValid ? sigma[firstValid] : sigma[b - 1];
    const float minSigma = *std::min_element(sigma, sigma + kBins);

    // The replacement for a hot pixel at (y, x), or -1. Reads only the original data.
    auto check = [&](int y, int x, float threshold) -> int {
        const Layout& l = layoutAt(y, x);
        const uint16_t* p = base + static_cast<size_t>(y) * pitch + x;
        const int v = p[0];
        const int n = static_cast<int>(l.same.size());
        uint16_t nb[24];
        int top1 = 0, top2 = 0;
        for (int i = 0; i < n; ++i) {
            const int q = p[l.same[i]];
            nb[i] = static_cast<uint16_t>(q);
            if (q > top1) {
                top2 = top1;
                top1 = q;
            } else if (q > top2) {
                top2 = q;
            }
        }
        const float excess2 = float(v - top2);
        if (excess2 <= threshold * minSigma) return -1;  // cheap reject for almost every pixel
        std::nth_element(nb, nb + n / 2, nb + n);
        const int median = nb[n / 2];
        const float noise = sigma[levelBin(median - blackAt(y, x, l.colour))];
        if (excess2 <= threshold * noise) return -1;
        // A clipped neighbour means a highlight: a hot pixel's neighbours are dark.
        for (const auto& [o, c] : l.adj)
            if (p[o] >= clipLevel) return -1;
        // Spill: adjacent pixels' rise over their colour's background on the ring around them (its lower median,
        // as a small highlight lights part of the ring too), white balanced. One raised neighbour is allowed, a
        // defective partner; light, even a highlight a few pixels across, raises more. The noise margin keeps
        // noise from deciding for weak warm pixels.
        uint16_t ring[4][16];
        int ringCount[4] = {0, 0, 0, 0};
        for (const auto& [o, c] : l.ring) ring[c][ringCount[c]++] = p[o];
        float background[4];
        for (int c = 0; c < 4; ++c) {
            if (!ringCount[c]) continue;
            const int k = (ringCount[c] - 1) / 2;
            std::nth_element(ring[c], ring[c] + k, ring[c] + ringCount[c]);
            background[c] = ring[c][k];
        }
        const float lightLimit = kMaxSpill * (v - median);
        int raised = 0;
        for (const auto& [o, c] : l.adj) {
            const float gain = mul[c] / mul[l.colour];  // white balance, which scales that pixel's noise too
            if ((p[o] - background[c]) * gain > lightLimit + kSpillNoise * noise * std::max(1.0f, gain) &&
                ++raised > 1)
                return -1;
        }
        return median;
    };
    struct Fix {
        int y, x;
        uint16_t value;
    };
    std::mutex fixesLock;
    std::vector<Fix> fixes;
    parallelRows(h - 4, [&](int rowBegin, int rowEnd) {
        std::vector<Fix> local;
        for (int y = rowBegin + 2; y < rowEnd + 2; ++y)
            for (int x = 2; x < w - 2; ++x)
                if (const int r = check(y, x, kHotSigma); r >= 0) local.push_back({y, x, static_cast<uint16_t>(r)});
        std::lock_guard<std::mutex> lock(fixesLock);
        fixes.insert(fixes.end(), local.begin(), local.end());
    });
    // Defects come in pairs, the partner often weaker: its adjacent pixels get a lower threshold.
    auto key = [&](int y, int x) { return static_cast<int64_t>(y) * w + x; };
    std::vector<int64_t> found;
    for (const Fix& f : fixes) found.push_back(key(f.y, f.x));
    std::sort(found.begin(), found.end());
    std::vector<Fix> partners;
    for (const Fix& f : fixes)
        for (int dy = -1; dy <= 1; ++dy)
            for (int dx = -1; dx <= 1; ++dx) {
                const int y = f.y + dy, x = f.x + dx;
                if ((!dx && !dy) || y < 2 || y >= h - 2 || x < 2 || x >= w - 2) continue;
                if (std::binary_search(found.begin(), found.end(), key(y, x))) continue;
                if (const int r = check(y, x, kPartnerSigma); r >= 0) partners.push_back({y, x, static_cast<uint16_t>(r)});
            }
    std::sort(partners.begin(), partners.end(), [&](const Fix& a, const Fix& b) { return key(a.y, a.x) < key(b.y, b.x); });
    partners.erase(std::unique(partners.begin(), partners.end(),
                               [&](const Fix& a, const Fix& b) { return key(a.y, a.x) == key(b.y, b.x); }),
                   partners.end());
    fixes.insert(fixes.end(), partners.begin(), partners.end());
    for (const Fix& f : fixes) base[static_cast<size_t>(f.y) * pitch + f.x] = f.value;
    return static_cast<int>(fixes.size());
}

/**
 * Defective column segments on Bayer sensors: one colour of a column (every other row) sits at a constant offset
 * from its neighbours, often from some row down to the sensor edge. The Pentax 645D has them (CCD): ~+18 DN on a
 * red column from a third of the way down, a faint line at normal exposure and a bright one in a shot pushed a few
 * stops. Positions differ per body and the DNG lists no bad pixels, so they're found in the image.
 *
 * d = pixel − mean of its same-colour neighbours two columns away. Its median over blocks of kBlock same-parity rows
 * is ~0 everywhere but on a defect. A block is flagged when that median is kSigma × the block's spread across
 * columns and the column's other colour (the other row parity, which real image detail moves just as much) is
 * < kOtherRatio of it. kMinBlocks flagged blocks of one sign in a column make a run; its ends are refined to the
 * row, and the run's median d is subtracted. Returns the number of segments corrected.
 */
int removeColumnDefects(LibRaw& raw) {
    constexpr int kBlock = 64;            // same-parity rows per block
    constexpr float kSigma = 6.0f;        // block median vs. the spread of block medians across columns
    constexpr float kOtherRatio = 0.25f;  // the other row parity's block median must stay below this × ours
    constexpr int kMinBlocks = 4;         // flagged blocks in a run
    constexpr int kMaxGap = 3;            // unflagged blocks bridged inside a run (bright detail, clipping)
    constexpr int kRefineBlocks = 4;      // how far outside the run its ends are searched for

    const auto& s = raw.imgdata.sizes;
    const auto& color = raw.imgdata.color;
    uint16_t* data = raw.imgdata.rawdata.raw_image;
    if (!data || raw.imgdata.idata.filters == 0 || raw.imgdata.idata.filters == 9 || raw.is_fuji_rotated()) return 0;
    const int w = s.width, h = s.height;
    if (w < 64 || h < 4 * kBlock * kMinBlocks) return 0;
    // Bayer only: the same colour two columns away, and a 2-row period.
    for (int y = 0; y < 4; ++y)
        for (int x = 0; x < 16; ++x)
            if (raw.COLOR(y, x) != raw.COLOR(y, x + 2) || raw.COLOR(y, x) != raw.COLOR(y + 2, x)) return 0;
    const int pitch = s.raw_pitch / 2;
    uint16_t* base = data + static_cast<size_t>(s.top_margin) * pitch + s.left_margin;  // visible area
    const int clipLevel = static_cast<int>(0.9f * float(color.maximum ? color.maximum : 65535));

    auto px = [&](int y, int x) { return static_cast<int>(base[static_cast<size_t>(y) * pitch + x]); };
    // d at (y, x), or NAN near clipping.
    auto diff = [&](int y, int x) {
        const int v = px(y, x), l = px(y, x - 2), r = px(y, x + 2);
        if (v >= clipLevel || l >= clipLevel || r >= clipLevel) return NAN;
        return float(v) - 0.5f * float(l + r);
    };
    auto median = [](std::vector<float>& v) {
        if (v.empty()) return 0.0f;
        std::nth_element(v.begin(), v.begin() + v.size() / 2, v.end());
        return v[v.size() / 2];
    };

    // Block medians of d per row parity: meds[p][b * w + x].
    const int blocks = (h / 2) / kBlock;
    std::vector<float> meds[2] = {std::vector<float>(static_cast<size_t>(blocks) * w, 0.0f),
                                  std::vector<float>(static_cast<size_t>(blocks) * w, 0.0f)};
    std::vector<float> spread[2] = {std::vector<float>(blocks, 1.0f), std::vector<float>(blocks, 1.0f)};
    parallelRows(2 * blocks, [&](int jobBegin, int jobEnd) {
        std::vector<float> v, all;
        v.reserve(kBlock);
        for (int job = jobBegin; job < jobEnd; ++job) {
            const int p = job % 2, b = job / 2;
            float* m = meds[p].data() + static_cast<size_t>(b) * w;
            for (int x = 2; x < w - 2; ++x) {
                v.clear();
                for (int i = 0; i < kBlock; ++i)
                    if (const float d = diff(2 * (b * kBlock + i) + p, x); !std::isnan(d)) v.push_back(d);
                m[x] = v.size() >= kBlock / 2 ? median(v) : 0.0f;
            }
            // Spread across columns: MAD of the block medians (~noise / sqrt(kBlock)).
            all.assign(m + 2, m + w - 2);
            const float centre = median(all);
            for (float& a : all) a = std::fabs(a - centre);
            spread[p][b] = std::max(0.5f, 1.4826f * median(all));
        }
    });

    struct Run {
        int p, x, b0, b1;
        float offset;
    };
    std::vector<Run> runs;
    std::vector<int> flagged;
    for (int p = 0; p < 2; ++p)
        for (int x = 2; x < w - 2; ++x)
            for (int sign = -1; sign <= 1; sign += 2) {
                flagged.clear();
                for (int b = 0; b < blocks; ++b) {
                    const float m = meds[p][static_cast<size_t>(b) * w + x] * sign;
                    const float other = meds[1 - p][static_cast<size_t>(b) * w + x];
                    if (m > kSigma * spread[p][b] && std::fabs(other) < kOtherRatio * m) flagged.push_back(b);
                }
                if (static_cast<int>(flagged.size()) < kMinBlocks) continue;
                for (size_t i = 0; i < flagged.size();) {
                    size_t j = i;
                    while (j + 1 < flagged.size() && flagged[j + 1] - flagged[j] <= kMaxGap + 1) ++j;
                    const int count = static_cast<int>(j - i + 1), span = flagged[j] - flagged[i] + 1;
                    if (count >= kMinBlocks && count * 5 >= span * 3) {
                        std::vector<float> v;
                        for (size_t k = i; k <= j; ++k) v.push_back(meds[p][static_cast<size_t>(flagged[k]) * w + x]);
                        runs.push_back({p, x, flagged[i], flagged[j], median(v)});
                    }
                    i = j + 1;
                }
            }
    if (runs.empty()) return 0;

    // The columns two away see the defect in their own reference, as about −offset/2: keep the strongest of such.
    std::sort(runs.begin(), runs.end(), [](const Run& a, const Run& b) { return std::fabs(a.offset) > std::fabs(b.offset); });
    std::vector<Run> kept;
    for (const Run& r : runs) {
        const bool ghost = std::any_of(kept.begin(), kept.end(), [&](const Run& k) {
            return k.p == r.p && std::abs(k.x - r.x) == 2 && (k.offset > 0) != (r.offset > 0) && k.b0 <= r.b1 &&
                   r.b0 <= k.b1;
        });
        if (!ghost) kept.push_back(r);
    }

    for (const Run& r : kept) {
        const int rows = (h - r.p + 1) / 2;  // rows of this parity; index i is image row 2i + p
        const float sign = r.offset > 0 ? 1.0f : -1.0f, half = 0.5f * std::fabs(r.offset);
        auto score = [&](int i) {  // > 0 where the row looks defective
            const float d = diff(2 * i + r.p, r.x);
            return std::isnan(d) ? 0.0f : sign * d - half;
        };
        // Start: the row maximising the summed score from it to the run's first block's end.
        int start = 0;
        if (r.b0 > 0) {
            const int lo = std::max(0, (r.b0 - kRefineBlocks) * kBlock), hi = (r.b0 + 1) * kBlock;
            float acc = 0, best = -INFINITY;
            for (int i = hi - 1; i >= lo; --i)
                if ((acc += score(i)) > best) best = acc, start = i;
        }
        // End (exclusive): the same from the run's last block on.
        int end = rows;
        if (r.b1 < blocks - 1) {
            const int lo = r.b1 * kBlock, hi = std::min(rows, (r.b1 + 1 + kRefineBlocks) * kBlock);
            float acc = 0, best = -INFINITY;
            for (int i = lo; i < hi; ++i)
                if ((acc += score(i)) > best) best = acc, end = i + 1;
        }
        std::vector<float> v;
        for (int i = start; i < end; ++i)
            if (const float d = diff(2 * i + r.p, r.x); !std::isnan(d)) v.push_back(d);
        const float offset = median(v);
        for (int i = start; i < end; ++i) {
            uint16_t& q = base[static_cast<size_t>(2 * i + r.p) * pitch + r.x];
            if (q >= clipLevel) continue;
            q = static_cast<uint16_t>(std::clamp(std::lround(q - offset), 0L, 65535L));
        }
    }
    return static_cast<int>(kept.size());
}

#ifndef _OPENMP
/**
 * Fuji X-Trans demosaic (LibRaw's 3-pass Markesteijn) on all cores where LibRaw has no OpenMP (web, iOS). There it
 * runs on one thread: 14.6 s for a 26 MP frame vs 2.4 s for AHD on a Bayer frame of the same size (x86, -O3).
 *
 * The frame is cut into horizontal bands with kMargin rows of context on each side; each band is demosaiced by
 * LibRaw's own xtrans_interpolate on a private copy (a LibRaw instance that only holds the band), and its inner rows
 * are written back. Margins come from a snapshot taken before any band runs, so every band sees the raw CFA data.
 * Bands start on multiples of 6, so the 6×6 X-Trans pattern (and its 3-row green phase) is the same in every band.
 * LibRaw's tiles look at most 8 + 3 rows past what they write, so 36 rows of context leave the inner rows as a
 * single pass computes them, except where LibRaw's own tile seams fall (its tiles read rows their neighbour has
 * already written, which OpenMP's order changes as well).
 */
class XTransBand : public LibRaw {
public:
    void demosaic(const LibRaw& full, ushort (*band)[4], int rows, int passes) {
        imgdata.sizes.width = full.imgdata.sizes.width;
        imgdata.sizes.height = static_cast<ushort>(rows);
        imgdata.sizes.iwidth = imgdata.sizes.width;
        imgdata.sizes.iheight = imgdata.sizes.height;
        imgdata.idata.filters = 9;
        imgdata.idata.colors = full.imgdata.idata.colors;
        std::memcpy(imgdata.idata.xtrans, full.imgdata.idata.xtrans, sizeof imgdata.idata.xtrans);
        std::memcpy(imgdata.color.rgb_cam, full.imgdata.color.rgb_cam, sizeof imgdata.color.rgb_cam);
        imgdata.image = band;
        try {
            xtrans_interpolate(passes);
        } catch (...) {
            imgdata.image = nullptr;  // the band belongs to the caller
            throw;
        }
        imgdata.image = nullptr;
    }
};

void parallelXTrans(void* ctx) {
    constexpr int kMargin = 36;      // context rows on each side of a band; a multiple of 6
    constexpr int kMinRows = 500;    // band rows written; LibRaw needs at least LIBRAW_AHD_TILE (512) with margins
    auto& raw = *static_cast<LibRaw*>(ctx);
    const int w = raw.imgdata.sizes.width;
    const int h = raw.imgdata.sizes.height;
    const int passes = raw.imgdata.params.user_qual > 2 ? 3 : 1;  // as dcraw_process would ask
    const int threads = std::max(1, std::min(8, static_cast<int>(std::thread::hardware_concurrency())));
    const int bands = std::min(threads, h / kMinRows);
    ushort(*image)[4] = raw.imgdata.image;
    if (bands < 2) {
        XTransBand().demosaic(raw, image, h, passes);
        return;
    }
    // Band k writes rows [starts[k], starts[k + 1]).
    std::vector<int> starts(bands + 1);
    for (int k = 0; k <= bands; ++k) starts[k] = k == bands ? h : (h * k / bands) / 6 * 6;
    // The CFA rows around each inner boundary, before any band overwrites them. 4 values per pixel, as LibRaw's image.
    using Pixel = ushort[4];
    const size_t rowValues = static_cast<size_t>(w) * 4;
    const ushort* src = image[0];
    std::vector<std::vector<ushort>> seams(bands);
    for (int k = 1; k < bands; ++k)
        seams[k].assign(src + (starts[k] - kMargin) * rowValues, src + (starts[k] + kMargin) * rowValues);
    std::mutex errorLock;
    std::exception_ptr error;
    parallelRows(bands, [&](int first, int last) {
        for (int k = first; k < last; ++k) {
            try {
                const int top = k == 0 ? 0 : starts[k] - kMargin;
                const int bottom = k == bands - 1 ? h : starts[k + 1] + kMargin;
                std::vector<ushort> band(static_cast<size_t>(bottom - top) * rowValues);
                // Inner rows from the image (only this band writes them), margins from the seams.
                const int innerTop = k == 0 ? 0 : starts[k] + kMargin;
                const int innerBottom = k == bands - 1 ? h : starts[k + 1] - kMargin;
                if (k > 0) std::copy(seams[k].begin(), seams[k].end(), band.begin());
                std::copy(src + innerTop * rowValues, src + innerBottom * rowValues,
                          band.begin() + (innerTop - top) * rowValues);
                if (k < bands - 1)
                    std::copy(seams[k + 1].begin(), seams[k + 1].end(), band.begin() + (innerBottom - top) * rowValues);
                auto lib = std::make_unique<XTransBand>();  // LibRaw is large; keep it off the stack
                lib->demosaic(raw, reinterpret_cast<Pixel*>(band.data()), bottom - top, passes);
                std::copy(band.begin() + (starts[k] - top) * rowValues, band.begin() + (starts[k + 1] - top) * rowValues,
                          image[0] + starts[k] * rowValues);
            } catch (...) {
                std::lock_guard<std::mutex> lock(errorLock);
                if (!error) error = std::current_exception();
            }
        }
    });
    if (error) std::rethrow_exception(error);  // dcraw_process turns LibRaw's exceptions into its error codes
}
#endif

/** LibRaw with RawBoy's processing steps hooked in (LibRaw's callbacks are protected). */
class RawBoyLibRaw : public LibRaw {
public:
    RawBoyLibRaw() {
#ifndef _OPENMP
        callbacks.interpolate_xtrans_cb = parallelXTrans;
#endif
    }
};

}  // namespace

bool decodeWithLibRaw(const uint8_t* data, size_t size, DecodedImage& out, std::string& error, float extraBlack) {
    auto raw = std::make_unique<RawBoyLibRaw>();  // LibRaw is large; keep it off the stack

    auto& p = raw->imgdata.params;
    p.output_bps = 16;
    p.gamm[0] = 1.0;        // linear output
    p.gamm[1] = 1.0;
    p.no_auto_bright = 1;   // keep scene exposure; tone mapping happens on the GPU
    p.use_camera_wb = 1;
    p.use_auto_wb = 0;
    p.use_camera_matrix = 1;
    p.output_color = 0;     // camera RGB: lens gain maps apply per camera channel
    p.user_flip = 0;        // stay in sensor orientation; we rotate after lens shading
    p.highlight = 2;        // blend clipped channels (keeps highlights neutral)
    p.user_qual = 3;        // AHD demosaic (Markesteijn 3-pass for X-Trans)

    const auto t0 = std::chrono::steady_clock::now();
    int rc = raw->open_buffer(data, size);
#if RAWBOY_WITH_DNG_SDK
    // iPhone selfie Bayer DNGs store the CFA in portrait (3024 x 4032, 1512 x 504 lossless-JPEG tiles): LibRaw 0.21.3
    // puts those tiles in the wrong places (horizontal bands, shifted sideways). Leave them to the DNG SDK (decodeAny
    // falls back to it), which decodes them as Apple's own decoder does.
    if (rc == LIBRAW_SUCCESS && raw->imgdata.idata.dng_version && raw->imgdata.idata.filters &&
        raw->imgdata.sizes.raw_height > raw->imgdata.sizes.raw_width &&
        std::strcmp(raw->imgdata.idata.make, "Apple") == 0) {
        error = "LibRaw: portrait CFA DNG, left to the DNG SDK";
        return false;
    }
#endif
    if (rc == LIBRAW_SUCCESS) rc = raw->unpack();
    const auto t1 = std::chrono::steady_clock::now();
    // The file's orientation: read it now, because dcraw_process() replaces
    // sizes.flip with user_flip (0, which keeps the image in sensor orientation).
    const int fileFlip = raw->imgdata.sizes.flip;
    const int defectColumns = rc == LIBRAW_SUCCESS ? removeColumnDefects(*raw) : 0;
    const int hotPixels = rc == LIBRAW_SUCCESS ? removeHotPixels(*raw) : 0;
    const auto t1b = std::chrono::steady_clock::now();
    if (rc == LIBRAW_SUCCESS) rc = raw->dcraw_process();
    const auto t2 = std::chrono::steady_clock::now();
    if (rc != LIBRAW_SUCCESS) {
        error = std::string("LibRaw: ") + libraw_strerror(rc);
        return false;
    }

    int err = 0;
    libraw_processed_image_t* img = raw->dcraw_make_mem_image(&err);  // sensor orientation
    if (!img) {
        error = std::string("LibRaw: ") + libraw_strerror(err);
        return false;
    }
    if (img->type != LIBRAW_IMAGE_BITMAP || img->colors != 3 || img->bits != 16) {
        LibRaw::dcraw_clear_mem(img);
        error = "LibRaw: unexpected output format";
        return false;
    }

    const int w = img->width;
    const int h = img->height;
    // Converted pixels go straight to their oriented position (one pass, no second full-size copy).
    const int orientation = flipToTiffOrientation(fileFlip);
    const bool swap = orientation >= 5 && orientation <= 8;
    out.width = swap ? h : w;
    out.height = swap ? w : h;
    out.rgb.resize(static_cast<size_t>(w) * h * 3);
    const auto* src = reinterpret_cast<const uint16_t*>(img->data);

    // camera RGB (white balanced) -> linear Rec.2020, including DNG BaselineExposure.
    const auto& color = raw->imgdata.color;
    Mat3 rgbCam{};
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c) rgbCam.m[r][c] = color.rgb_cam[r][c];
    const Mat3 camToRec2020 = multiply(kSrgbToRec2020, rgbCam);
    // DNG BaselineExposure; LibRaw reports -999 when the tag is absent (non-DNG raws).
    const float baseline = color.dng_levels.baseline_exposure;
    const bool hasBaseline = baseline > -10.0f && baseline < 10.0f;
    if (hasBaseline) out.baselineExposure = baseline;
    const float scale = (hasBaseline ? std::exp2(baseline) : 1.0f) / 65535.0f;
    float m[9];
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c) m[r * 3 + c] = static_cast<float>(camToRec2020.m[r][c]) * scale;
    // Extra black per output channel: LibRaw's output is (raw - black) x pre_mul[c] (normalised in place by
    // scale_colors) x 65535 / white range. Subtracted in float, so values may go below zero (clamped later).
    float blackOut[3] = {0, 0, 0};
    if (extraBlack != 0.0f)
        for (int c = 0; c < 3; ++c) blackOut[c] = extraBlack * 65535.0f * color.pre_mul[c];

    // Embedded DNG lens corrections. Gain maps (lens + colour shading) are applied
    // per camera channel here; the GPU can take the luminance part back out.
    LensOpcodes lens;
    parseDngOpcodes(data, size, lens);
    std::vector<MapPlane> channelMaps[3];
    const bool cfa = raw->imgdata.idata.filters != 0;
    for (const GainMap& g : lens.gainMaps) {
        if (cfa && g.list == 2) {
            // Pixel: one map per CFA site, applied to that site's colour.
            const int c = raw->COLOR(static_cast<int>(g.top), static_cast<int>(g.left));
            channelMaps[c == 3 ? 1 : std::clamp(c, 0, 2)].push_back({&g, 0});
        } else {
            // Demosaiced planes (OpcodeList3, e.g. iPhone Bayer DNGs: one map, a gain plane per colour) or linear raws.
            // Before, every such map went to its first plane's colour only: red edges on iPhone telephoto shots.
            for (uint32_t p2 = g.plane; p2 < g.plane + g.planes && p2 < 3; ++p2) channelMaps[p2].push_back({&g, p2 - g.plane});
        }
    }
    const GainEvaluator gains[3] = {GainEvaluator(channelMaps[0], w), GainEvaluator(channelMaps[1], w),
                                    GainEvaluator(channelMaps[2], w)};

    parallelRows(h, [&](int rowBegin, int rowEnd) {
        std::vector<std::vector<float>> rows[3];
        for (int y = rowBegin; y < rowEnd; ++y) {
            for (int c = 0; c < 3; ++c)
                if (!gains[c].empty()) gains[c].prepareRow(double(y) / h, rows[c]);
            const uint16_t* in = src + static_cast<size_t>(y) * w * 3;
            const OrientedRow dest = orientedRow(orientation, w, h, y);
            uint16_t* o = out.rgb.data() + dest.start;
            for (int x = 0; x < w; ++x, in += 3, o += dest.step) {
                float cam[3] = {float(in[0]) - blackOut[0], float(in[1]) - blackOut[1], float(in[2]) - blackOut[2]};
                for (int c = 0; c < 3; ++c)
                    if (!gains[c].empty()) cam[c] *= gains[c].gain(rows[c], x);
                o[0] = floatToHalf(m[0] * cam[0] + m[1] * cam[1] + m[2] * cam[2]);
                o[1] = floatToHalf(m[3] * cam[0] + m[4] * cam[1] + m[5] * cam[2]);
                o[2] = floatToHalf(m[6] * cam[0] + m[7] * cam[1] + m[8] * cam[2]);
            }
        }
    });
    LibRaw::dcraw_clear_mem(img);

    out.shading = averageGrid(channelMaps[1]);
    out.lens = std::move(lens);
    out.sensorWidth = w;
    out.sensorHeight = h;
    out.orientation = orientation; // already applied above
    const auto t3 = std::chrono::steady_clock::now();
    auto ms = [](auto a, auto b) { return static_cast<long>(std::chrono::duration_cast<std::chrono::milliseconds>(b - a).count()); };
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_DEBUG, "RawBoy",
                        "LibRaw decode: unpack %ld ms, column defects %d + hot pixels %d in %ld ms, demosaic %ld ms, "
                        "convert + orient %ld ms",
                        ms(t0, t1), defectColumns, hotPixels, ms(t1, t1b), ms(t1b, t2), ms(t2, t3));
#else
    (void)ms;
    (void)defectColumns;
    (void)hotPixels;
#endif
    out.lensName = raw->imgdata.lens.Lens;
    out.iso = raw->imgdata.other.iso_speed;

    estimateAsShotWhite(raw->imgdata.color, out.whiteX, out.whiteY);
    out.decoder = "LibRaw " + std::string(LibRaw::version());
    out.camera = std::string(raw->imgdata.idata.make) + " " + raw->imgdata.idata.model;
    readMetadata(raw->imgdata, out.metadata);
    return true;
}

}  // namespace rawboy
