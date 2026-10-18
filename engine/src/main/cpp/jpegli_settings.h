#pragma once

// RawBoy's jpegli encoder settings, shared by the JNI encoder (jpeg_jpegli.cpp) and the web build.

#include "lib/jpegli/encode.h"

namespace rawboy {

/** Configures [cinfo] (created, destination set) for a [width] x [height] 16-bit RGB JPEG at [quality], 4:4:4. */
inline void configureJpegli(jpeg_compress_struct* cinfo, int width, int height, int quality) {
    cinfo->image_width = static_cast<JDIMENSION>(width);
    cinfo->image_height = static_cast<JDIMENSION>(height);
    cinfo->input_components = 3;
    cinfo->in_color_space = JCS_RGB;
    jpegli_set_defaults(cinfo);
    jpegli_set_quality(cinfo, quality, TRUE);
    // 4:4:4: full-resolution colour, so fine coloured detail doesn't bleed.
    for (int c = 0; c < cinfo->num_components; ++c) {
        cinfo->comp_info[c].h_samp_factor = 1;
        cinfo->comp_info[c].v_samp_factor = 1;
    }
    cinfo->optimize_coding = TRUE;
    jpegli_set_input_format(cinfo, JPEGLI_TYPE_UINT16, JPEGLI_NATIVE_ENDIAN);
}

}  // namespace rawboy
