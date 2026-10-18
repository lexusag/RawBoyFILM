// jpegli JPEG decoder behind rb_jpeg_decode (see jpeg_decode.h). Built into the JPEG library on every platform.

#include "jpeg_decode.h"

#include <algorithm>
#include <cstdio>
#include <stdexcept>
#include <string>
#include <vector>

#include "lib/jpegli/decode.h"

namespace {

[[noreturn]] void errorExit(j_common_ptr cinfo) {
    char message[JMSG_LENGTH_MAX];
    (*cinfo->err->format_message)(cinfo, message);
    throw std::runtime_error(message);
}

// Rows handed to the sink at once: enough to amortise the caller's per-band work (it converts bands in parallel).
constexpr int kBandRows = 64;

struct Decoder {
    jpeg_decompress_struct cinfo{};
    jpeg_error_mgr err{};
    bool created = false;
    ~Decoder() {
        if (created) jpegli_destroy_decompress(&cinfo);
    }
};

void setError(char* error, size_t errorSize, const std::string& message) {
    if (error && errorSize > 0) std::snprintf(error, errorSize, "%s", message.c_str());
}

}  // namespace

extern "C" bool rb_jpeg_decode(const uint8_t* data, size_t size, const RBJpegSink* sink, char* error,
                               size_t errorSize) {
    Decoder dec;
    try {
        dec.cinfo.err = jpegli_std_error(&dec.err);
        dec.err.error_exit = errorExit;
        jpegli_create_decompress(&dec.cinfo);
        dec.created = true;
        jpegli_mem_src(&dec.cinfo, data, static_cast<unsigned long>(size));
        jpegli_read_header(&dec.cinfo, TRUE);
        const int components = dec.cinfo.num_components;
        if (components != 1 && components != 3) {
            setError(error, errorSize, "CMYK JPEGs are not supported");
            return false;
        }
        dec.cinfo.out_color_space = components == 1 ? JCS_GRAYSCALE : JCS_RGB;
        jpegli_set_output_format(&dec.cinfo, JPEGLI_TYPE_UINT16, JPEGLI_NATIVE_ENDIAN);
        jpegli_start_decompress(&dec.cinfo);
        const int w = static_cast<int>(dec.cinfo.output_width);
        const int h = static_cast<int>(dec.cinfo.output_height);
        if (!sink->begin(sink->context, w, h)) {
            jpegli_abort_decompress(&dec.cinfo);
            setError(error, errorSize, "cancelled");
            return false;
        }
        std::vector<uint16_t> band(static_cast<size_t>(w) * 3 * kBandRows);
        std::vector<uint16_t> gray(components == 1 ? static_cast<size_t>(w) * kBandRows : 0);
        std::vector<JSAMPROW> pointers(kBandRows);
        int y = 0;
        while (y < h) {
            const int want = std::min(kBandRows, h - y);
            uint16_t* target = components == 1 ? gray.data() : band.data();
            const size_t stride = static_cast<size_t>(w) * components;
            for (int r = 0; r < want; ++r) pointers[r] = reinterpret_cast<JSAMPROW>(target + r * stride);
            int got = 0;
            while (got < want) {
                got += static_cast<int>(jpegli_read_scanlines(&dec.cinfo, pointers.data() + got, want - got));
            }
            if (components == 1) {
                for (size_t i = 0, n = static_cast<size_t>(w) * want; i < n; ++i) {
                    band[i * 3] = band[i * 3 + 1] = band[i * 3 + 2] = gray[i];
                }
            }
            if (!sink->rows(sink->context, y, want, band.data())) {
                jpegli_abort_decompress(&dec.cinfo);
                setError(error, errorSize, "cancelled");
                return false;
            }
            y += want;
        }
        jpegli_finish_decompress(&dec.cinfo);
        return true;
    } catch (const std::exception& e) {
        setError(error, errorSize, std::string("JPEG decoder: ") + e.what());
        return false;
    }
}
