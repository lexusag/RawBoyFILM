#pragma once

#include <cstddef>
#include <cstdint>

#include "rawboy.h"

namespace rawboy {

/**
 * Reads the EXIF essentials from a TIFF-structured EXIF block (a JPEG's APP1 after "Exif\0\0", or a HEIF Exif item
 * after its offset field) into [out] and [orientation] (1..8, 1 if absent). Returns false if the block isn't TIFF.
 */
bool readExif(const uint8_t* tiff, size_t size, PhotoMetadata& out, int& orientation);

/** The TIFF-structured EXIF block of a HEIF/HEIC file (its 'Exif' item), or false if it has none. */
bool findHeifExif(const uint8_t* data, size_t size, const uint8_t*& tiff, size_t& tiffSize);

/** The EXIF block of a PNG file (its eXIf chunk), or false if it has none. */
bool findPngExif(const uint8_t* data, size_t size, const uint8_t*& tiff, size_t& tiffSize);

}  // namespace rawboy
