// DNG decoding through the Adobe DNG SDK (handles JPEG XL-compressed DNG 1.7).
// Compiled only when the SDK is present in third_party/dng_sdk (required for release builds).
// This product includes DNG technology under license by Adobe.
// Licences: licenses/THIRD_PARTY_NOTICES.txt.

#include "rawboy.h"
#include "color_math.h"

#include "dng_auto_ptr.h"
#include "dng_color_spec.h"
#include "dng_exceptions.h"
#include "dng_exif.h"
#include "dng_host.h"
#include "dng_image.h"
#include "dng_info.h"
#include "dng_negative.h"
#include "dng_orientation.h"
#include "dng_pixel_buffer.h"
#include "dng_rect.h"
#include "dng_stream.h"
#include "dng_xy_coord.h"

#include <algorithm>
#include <cmath>
#include <cstdio>

namespace rawboy {

namespace {

double value(const dng_urational& r) { return r.d ? static_cast<double>(r.n) / r.d : NAN; }
double value(const dng_srational& r) { return r.d ? static_cast<double>(r.n) / r.d : NAN; }

/** EXIF essentials from the SDK's parsed EXIF (see PhotoMetadata). */
void readMetadata(const dng_exif& e, PhotoMetadata& m) {
    m.make = e.fMake.Get();
    m.model = e.fModel.Get();
    m.lensMake = e.fLensMake.Get();
    m.lensModel = e.fLensName.Get();
    m.artist = e.fArtist.Get();
    m.copyright = e.fCopyright.Get();
    m.iso = static_cast<float>(e.fISOSpeedRatings[0]);
    m.exposureTime = value(e.fExposureTime);
    m.fNumber = value(e.fFNumber);
    m.exposureBias = value(e.fExposureBiasValue);
    m.focalLength = value(e.fFocalLength);
    if (e.fFocalLengthIn35mmFilm > 0) m.focalLength35mm = e.fFocalLengthIn35mmFilm;
    const dng_date_time_info& dt = e.fDateTimeOriginal;
    if (dt.IsValid()) {
        const dng_date_time& t = dt.DateTime();
        char buf[32];
        std::snprintf(buf, sizeof buf, "%04u:%02u:%02u %02u:%02u:%02u", t.fYear, t.fMonth, t.fDay, t.fHour, t.fMinute,
                      t.fSecond);
        m.dateTimeOriginal = buf;
        m.subSecTimeOriginal = dt.Subseconds().Get();
        m.offsetTimeOriginal = dt.OffsetTime().Get();
    }
    const char latRef = e.fGPSLatitudeRef.Get()[0];
    const char lonRef = e.fGPSLongitudeRef.Get()[0];
    if (e.fGPSVersionID != 0 && (latRef == 'N' || latRef == 'S') && (lonRef == 'E' || lonRef == 'W')) {
        m.hasGps = true;
        for (int i = 0; i < 3; ++i) {
            m.gpsLatitude[i] = value(e.fGPSLatitude[i]);
            m.gpsLongitude[i] = value(e.fGPSLongitude[i]);
            if (std::isnan(m.gpsLatitude[i])) m.gpsLatitude[i] = 0;
            if (std::isnan(m.gpsLongitude[i])) m.gpsLongitude[i] = 0;
        }
        m.gpsLatitudeRef = latRef;
        m.gpsLongitudeRef = lonRef;
        m.gpsAltitude = value(e.fGPSAltitude);
        m.gpsBelowSeaLevel = e.fGPSAltitudeRef == 1;
    }
}

}  // namespace

bool decodeWithDngSdk(const uint8_t* data, size_t size, DecodedImage& out, std::string& error) {
    try {
        dng_host host;
        dng_stream stream(data, static_cast<uint32>(size));

        dng_info info;
        info.Parse(host, stream);
        info.PostParse(host);
        if (!info.IsValidDNG()) {
            error = "not a DNG";
            return false;
        }

        AutoPtr<dng_negative> negative(host.Make_dng_negative());
        negative->Parse(host, stream, info);
        negative->PostParse(host, stream, info);
        negative->ReadStage1Image(host, stream, info);
        negative->BuildStage2Image(host);
        negative->BuildStage3Image(host);  // demosaiced, linear camera RGB

        const dng_image* stage3 = negative->Stage3Image();
        const uint32 planes = stage3->Planes();
        if (planes != 1 && planes != 3) {
            error = "DNG SDK: unsupported plane count " + std::to_string(planes);
            return false;
        }

        dng_rect area = negative->DefaultCropArea() & stage3->Bounds();
        if (area.IsEmpty()) area = stage3->Bounds();
        const uint32 w = area.W();
        const uint32 h = area.H();
        const size_t samples = static_cast<size_t>(w) * h * planes;

        // Camera-native values normalised to 0..1.
        std::vector<float> cam(samples);
        if (stage3->PixelType() == ttFloat) {
            dng_pixel_buffer buffer(area, 0, planes, ttFloat, pcInterleaved, cam.data());
            stage3->Get(buffer);
        } else {
            std::vector<uint16_t> tmp(samples);
            dng_pixel_buffer buffer(area, 0, planes, ttShort, pcInterleaved, tmp.data());
            stage3->Get(buffer);
            const float black = negative->Stage3BlackLevel() / 65535.0f;
            const float scale = 1.0f / (1.0f - black);
            for (size_t i = 0; i < samples; ++i) cam[i] = (tmp[i] / 65535.0f - black) * scale;
        }

        out.baselineExposure = static_cast<float>(negative->BaselineExposure());
        const float exposure = static_cast<float>(std::pow(2.0, negative->BaselineExposure()));

        out.width = static_cast<int>(w);
        out.height = static_cast<int>(h);
        out.rgb.resize(static_cast<size_t>(w) * h * 3);

        if (planes == 1) {
            out.clipLevel = exposure;
            for (size_t i = 0, n = static_cast<size_t>(w) * h; i < n; ++i) {
                const uint16_t v = floatToHalf(std::min(cam[i], 1.0f) * exposure);
                out.rgb[i * 3] = out.rgb[i * 3 + 1] = out.rgb[i * 3 + 2] = v;
            }
        } else {
            AutoPtr<dng_color_spec> spec(negative->MakeColorSpec(dng_camera_profile_id()));

            dng_xy_coord white = D65_xy_coord();
            if (negative->HasCameraNeutral()) {
                white = spec->NeutralToXY(negative->CameraNeutral());
            } else if (negative->HasCameraWhiteXY()) {
                white = negative->CameraWhiteXY();
            }
            spec->SetWhiteXY(white);

            // camera -> XYZ(D50 PCS) -> XYZ(D65) -> linear Rec.2020
            const dng_matrix& camToPcs = spec->CameraToPCS();
            Mat3 camToXyzD50{};
            for (int r = 0; r < 3; ++r)
                for (int c = 0; c < 3; ++c) camToXyzD50.m[r][c] = camToPcs[r][c];
            const Mat3 d50ToD65 = bradfordAdaptation(xyToXYZ(0.3457, 0.3585), xyToXYZ(0.3127, 0.3290));
            const Mat3 camToRec2020 = multiply(kXyzToRec2020, multiply(d50ToD65, camToXyzD50));

            float m[9];
            for (int r = 0; r < 3; ++r)
                for (int c = 0; c < 3; ++c) m[r * 3 + c] = static_cast<float>(camToRec2020.m[r][c]) * exposure;

            // Blown highlights are blended to neutral in white balanced camera RGB, where the camera white is 1 and
            // channel c clips at 1 / white[c]. Clipping each channel at the camera white instead cut every channel
            // with a high multiplier at a fraction of its range: in an infrared photo (blue x3.7) bright light lost
            // its blue and blown highlights went flat yellow.
            const dng_vector& camWhite = spec->CameraWhite();
            const float camW[3] = {static_cast<float>(camWhite[0]), static_cast<float>(camWhite[1]),
                                   static_cast<float>(camWhite[2])};
            const float clip[3] = {1.0f / camW[0], 1.0f / camW[1], 1.0f / camW[2]};
            out.clipLevel = (clip[0] + clip[1] + clip[2]) / 3.0f * exposure;

            for (size_t i = 0, n = static_cast<size_t>(w) * h; i < n; ++i) {
                float c[3] = {cam[i * 3] * clip[0], cam[i * 3 + 1] * clip[1], cam[i * 3 + 2] * clip[2]};
                blendClipped(c, clip);
                const float r = c[0] * camW[0];
                const float g = c[1] * camW[1];
                const float b = c[2] * camW[2];
                out.rgb[i * 3] = floatToHalf(m[0] * r + m[1] * g + m[2] * b);
                out.rgb[i * 3 + 1] = floatToHalf(m[3] * r + m[4] * g + m[5] * b);
                out.rgb[i * 3 + 2] = floatToHalf(m[6] * r + m[7] * g + m[8] * b);
            }
            out.whiteX = white.x;
            out.whiteY = white.y;
        }

        out.decoder = "Adobe DNG SDK";
        out.camera = negative->ModelName().Get();
        if (const dng_exif* exif = negative->GetExif()) {
            out.lensName = exif->fLensName.Get();
            out.iso = static_cast<float>(exif->fISOSpeedRatings[0]);
            readMetadata(*exif, out.metadata);
        }
        // The SDK applies OpcodeList2/3 itself, so no lens data is passed on to the GPU.
        out.sensorWidth = out.width;
        out.sensorHeight = out.height;
        out.orientation = static_cast<int>(negative->Orientation().GetTIFF());
        applyOrientation(out, out.orientation);
        return true;
    } catch (const dng_exception& e) {
        error = "DNG SDK error " + std::to_string(e.ErrorCode());
    } catch (const std::bad_alloc&) {
        error = "DNG SDK: out of memory";
    } catch (...) {
        error = "DNG SDK: unknown error";
    }
    return false;
}

}  // namespace rawboy
