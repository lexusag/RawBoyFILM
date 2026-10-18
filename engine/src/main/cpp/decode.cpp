#include "rawboy.h"

namespace rawboy {

namespace {

bool looksLikeTiff(const uint8_t* d, size_t size) {
    return size > 8 && ((d[0] == 'I' && d[1] == 'I' && d[2] == 42 && d[3] == 0) ||
                        (d[0] == 'M' && d[1] == 'M' && d[2] == 0 && d[3] == 42));
}

}  // namespace

bool decodeAny(const uint8_t* data, size_t size, DecodedImage& out, std::string& error, float extraBlack) {
    // JPEGs first: LibRaw would refuse them (or misread a camera's embedded preview structure).
    if (looksLikeJpeg(data, size)) return decodeJpeg(data, size, out, error);
    // LibRaw first: it handles every camera raw and most DNGs, and its path carries
    // RawBoy's own lens-correction handling. DNGs it can't read (DNG 1.7 JPEG XL,
    // and other newer encodings) fall back to the Adobe DNG SDK.
    bool ok = decodeWithLibRaw(data, size, out, error, extraBlack);
    std::string dngError;
#if RAWBOY_WITH_DNG_SDK
    if (!ok && looksLikeTiff(data, size)) {
        out = DecodedImage();
        ok = decodeWithDngSdk(data, size, out, dngError);
    }
#endif
    if (!ok && !dngError.empty() && dngError != "not a DNG") error = dngError + "; " + error;
    if (!ok && looksLikeTiff(data, size)) {
        // A TIFF no raw decoder takes: an ordinary image (scans, exports from other editors).
        DecodedImage image;
        std::string tiffError;
        if (decodeTiff(data, size, image, tiffError)) {
            out = std::move(image);
            return true;
        }
        error += "; as an image TIFF: " + tiffError;
    }
    return ok;
}

}  // namespace rawboy
