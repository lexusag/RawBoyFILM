#include "exif_reader.h"

#include <cmath>
#include <cstring>
#include <string>

namespace rawboy {

namespace {

class Tiff {
public:
    Tiff(const uint8_t* d, size_t n) : d_(d), n_(n), le_(n >= 2 && d[0] == 'I') {}

    bool valid() const {
        return n_ >= 8 && ((d_[0] == 'I' && d_[1] == 'I') || (d_[0] == 'M' && d_[1] == 'M')) && u16(2) == 42;
    }
    uint16_t u16(size_t o) const {
        if (o + 2 > n_) return 0;
        return le_ ? uint16_t(d_[o] | d_[o + 1] << 8) : uint16_t(d_[o] << 8 | d_[o + 1]);
    }
    uint32_t u32(size_t o) const {
        if (o + 4 > n_) return 0;
        return le_ ? uint32_t(d_[o]) | uint32_t(d_[o + 1]) << 8 | uint32_t(d_[o + 2]) << 16 | uint32_t(d_[o + 3]) << 24
                   : uint32_t(d_[o]) << 24 | uint32_t(d_[o + 1]) << 16 | uint32_t(d_[o + 2]) << 8 | uint32_t(d_[o + 3]);
    }

    struct Entry {
        uint16_t tag = 0, type = 0;
        uint32_t count = 0;
        size_t value = 0;  // offset of the value (inline or pointed to)
    };

    /** Calls fn(entry) for each entry of the IFD at [offset]. */
    template <typename Fn>
    void forEach(size_t offset, Fn&& fn) const {
        if (offset == 0 || offset + 2 > n_) return;
        const int count = u16(offset);
        for (int i = 0; i < count; ++i) {
            const size_t e = offset + 2 + size_t(i) * 12;
            if (e + 12 > n_) return;
            Entry entry;
            entry.tag = u16(e);
            entry.type = u16(e + 2);
            entry.count = u32(e + 4);
            const size_t bytes = size_t(typeSize(entry.type)) * entry.count;
            entry.value = bytes <= 4 ? e + 8 : u32(e + 8);
            if (entry.value + bytes > n_ || bytes == 0) continue;
            fn(entry);
        }
    }

    std::string ascii(const Entry& e) const {
        std::string s(reinterpret_cast<const char*>(d_ + e.value), e.count);
        s = s.substr(0, s.find('\0'));
        while (!s.empty() && s.back() == ' ') s.pop_back();
        return s;
    }
    /** Numeric value [i] of a SHORT, LONG, RATIONAL or SRATIONAL entry; NaN otherwise. */
    double number(const Entry& e, uint32_t i = 0) const {
        if (i >= e.count) return NAN;
        switch (e.type) {
            case 3: return u16(e.value + i * 2);
            case 4: return u32(e.value + i * 4);
            case 5: {
                const uint32_t den = u32(e.value + i * 8 + 4);
                return den ? double(u32(e.value + i * 8)) / den : NAN;
            }
            case 10: {
                const int32_t den = int32_t(u32(e.value + i * 8 + 4));
                return den ? double(int32_t(u32(e.value + i * 8))) / den : NAN;
            }
            default: return NAN;
        }
    }
    uint8_t byte(const Entry& e) const { return d_[e.value]; }

private:
    static int typeSize(uint16_t type) {
        switch (type) {
            case 1: case 2: case 6: case 7: return 1;
            case 3: case 8: return 2;
            case 4: case 9: case 11: return 4;
            case 5: case 10: case 12: return 8;
            default: return 0;
        }
    }
    const uint8_t* d_;
    size_t n_;
    bool le_;
};

}  // namespace

bool readExif(const uint8_t* data, size_t size, PhotoMetadata& m, int& orientation) {
    orientation = 1;
    const Tiff t(data, size);
    if (!t.valid()) return false;
    size_t exifIfd = 0, gpsIfd = 0;
    t.forEach(t.u32(4), [&](const Tiff::Entry& e) {
        switch (e.tag) {
            case 0x010F: m.make = t.ascii(e); break;
            case 0x0110: m.model = t.ascii(e); break;
            case 0x0112: {
                const int o = int(t.number(e));
                if (o >= 1 && o <= 8) orientation = o;
                break;
            }
            case 0x013B: m.artist = t.ascii(e); break;
            case 0x8298: m.copyright = t.ascii(e); break;
            case 0x8769: exifIfd = size_t(t.number(e)); break;
            case 0x8825: gpsIfd = size_t(t.number(e)); break;
        }
    });
    t.forEach(exifIfd, [&](const Tiff::Entry& e) {
        const double v = t.number(e);
        switch (e.tag) {
            case 0x829A: if (v > 0) m.exposureTime = v; break;
            case 0x829D: if (v > 0) m.fNumber = v; break;
            case 0x8827: if (v > 0) m.iso = float(v); break;
            case 0x9003: m.dateTimeOriginal = t.ascii(e); break;
            case 0x9011: m.offsetTimeOriginal = t.ascii(e); break;
            case 0x9204: if (std::isfinite(v)) m.exposureBias = v; break;
            case 0x920A: if (v > 0) m.focalLength = v; break;
            case 0x9291: m.subSecTimeOriginal = t.ascii(e); break;
            case 0xA405: if (v > 0) m.focalLength35mm = v; break;
            case 0xA433: m.lensMake = t.ascii(e); break;
            case 0xA434: m.lensModel = t.ascii(e); break;
        }
    });
    bool lat = false, lon = false;
    t.forEach(gpsIfd, [&](const Tiff::Entry& e) {
        switch (e.tag) {
            case 1: m.gpsLatitudeRef = char(t.byte(e)); break;
            case 2:
                for (uint32_t i = 0; i < 3; ++i) m.gpsLatitude[i] = t.number(e, i);
                lat = e.count == 3;
                break;
            case 3: m.gpsLongitudeRef = char(t.byte(e)); break;
            case 4:
                for (uint32_t i = 0; i < 3; ++i) m.gpsLongitude[i] = t.number(e, i);
                lon = e.count == 3;
                break;
            case 5: m.gpsBelowSeaLevel = t.byte(e) == 1; break;
            case 6: m.gpsAltitude = t.number(e); break;
        }
    });
    m.hasGps = lat && lon && (m.gpsLatitudeRef == 'N' || m.gpsLatitudeRef == 'S') &&
               (m.gpsLongitudeRef == 'E' || m.gpsLongitudeRef == 'W');
    return true;
}

namespace {

struct Box {
    uint32_t type = 0;
    size_t body = 0, end = 0;  // payload start (after the header) and end
};

uint32_t be32(const uint8_t* d) { return uint32_t(d[0]) << 24 | uint32_t(d[1]) << 16 | uint32_t(d[2]) << 8 | d[3]; }
constexpr uint32_t fourcc(const char* s) {
    return uint32_t(uint8_t(s[0])) << 24 | uint32_t(uint8_t(s[1])) << 16 | uint32_t(uint8_t(s[2])) << 8 | uint8_t(s[3]);
}

/** The first box of [type] in [begin, end), or a box with type 0. */
Box findBox(const uint8_t* d, size_t begin, size_t end, uint32_t type) {
    size_t p = begin;
    while (p + 8 <= end) {
        uint64_t size = be32(d + p);
        size_t header = 8;
        if (size == 1 && p + 16 <= end) {
            size = uint64_t(be32(d + p + 8)) << 32 | be32(d + p + 12);
            header = 16;
        } else if (size == 0) {
            size = end - p;
        }
        if (size < header || p + size > end) break;
        if (be32(d + p + 4) == type) return {type, p + header, size_t(p + size)};
        p += size;
    }
    return {};
}

/** Big-endian unsigned of [bytes] (0..8) at [p], advancing it; past [end] it reads 0 and moves [p] beyond [end]. */
uint64_t readN(const uint8_t* d, size_t& p, int bytes, size_t end) {
    if (p + bytes > end) {
        p = end + 1;
        return 0;
    }
    uint64_t v = 0;
    for (int i = 0; i < bytes; ++i) v = v << 8 | d[p++];
    return v;
}

}  // namespace

bool findHeifExif(const uint8_t* d, size_t n, const uint8_t*& tiff, size_t& tiffSize) {
    const Box meta = findBox(d, 0, n, fourcc("meta"));
    if (!meta.type || meta.body + 4 > meta.end) return false;
    const size_t inner = meta.body + 4;  // FullBox version + flags
    const Box iinf = findBox(d, inner, meta.end, fourcc("iinf"));
    const Box iloc = findBox(d, inner, meta.end, fourcc("iloc"));
    if (!iinf.type || !iloc.type) return false;

    // The Exif item's id, from the item infos (infe version 2: 16-bit ids, 3: 32-bit).
    uint32_t exifId = 0;
    {
        size_t p = iinf.body;
        if (p + 4 > iinf.end) return false;
        const int version = d[p];
        p += 4 + (version == 0 ? 2 : 4);
        while (p + 8 <= iinf.end && !exifId) {
            const Box infe = findBox(d, p, iinf.end, fourcc("infe"));
            if (!infe.type) break;
            size_t q = infe.body;
            const int v = d[q];
            q += 4;
            if (v >= 2 && q + (v == 2 ? 8 : 10) <= infe.end) {
                const uint32_t id = uint32_t(readN(d, q, v == 2 ? 2 : 4, infe.end));
                q += 2;  // protection index
                if (be32(d + q) == fourcc("Exif")) exifId = id;
            }
            p = infe.end;
        }
    }
    if (!exifId) return false;

    // Its first extent, from the item locations.
    size_t p = iloc.body;
    if (p + 8 > iloc.end) return false;
    const int version = d[p];
    p += 4;
    const int offsetSize = d[p] >> 4, lengthSize = d[p] & 15, baseSize = d[p + 1] >> 4;
    const int indexSize = version >= 1 ? d[p + 1] & 15 : 0;
    p += 2;
    const uint32_t count = uint32_t(readN(d, p, version < 2 ? 2 : 4, iloc.end));
    for (uint32_t i = 0; i < count && p <= iloc.end; ++i) {
        const uint32_t id = uint32_t(readN(d, p, version < 2 ? 2 : 4, iloc.end));
        int method = 0;
        if (version >= 1) method = int(readN(d, p, 2, iloc.end) & 15);
        readN(d, p, 2, iloc.end);  // data reference index
        const uint64_t base = readN(d, p, baseSize, iloc.end);
        const uint32_t extents = uint32_t(readN(d, p, 2, iloc.end));
        uint64_t offset = 0, length = 0;
        for (uint32_t e = 0; e < extents && p <= iloc.end; ++e) {
            if (indexSize) readN(d, p, indexSize, iloc.end);
            const uint64_t o = readN(d, p, offsetSize, iloc.end), l = readN(d, p, lengthSize, iloc.end);
            if (e == 0) offset = o, length = l;
        }
        if (p > iloc.end) return false;
        if (id != exifId) continue;
        if (method != 0) return false;  // only file offsets
        const uint64_t start = base + offset;
        if (length < 8 || start + length > n) return false;
        // The payload starts with the offset of the TIFF header within it.
        const uint64_t skip = 4 + uint64_t(be32(d + start));
        if (skip >= length) return false;
        tiff = d + start + skip;
        tiffSize = size_t(length - skip);
        return true;
    }
    return false;
}

bool findPngExif(const uint8_t* d, size_t n, const uint8_t*& tiff, size_t& tiffSize) {
    static const uint8_t kSignature[8] = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    if (n < 8 || std::memcmp(d, kSignature, 8) != 0) return false;
    for (size_t p = 8; p + 12 <= n;) {
        const size_t len = be32(d + p);
        const uint32_t type = be32(d + p + 4);
        if (p + 12 + len > n) return false;
        if (type == fourcc("eXIf")) {
            tiff = d + p + 8;
            tiffSize = len;
            return true;
        }
        if (type == fourcc("IDAT") || type == fourcc("IEND")) return false;  // eXIf must come before the image data
        p += 12 + len;
    }
    return false;
}

}  // namespace rawboy
