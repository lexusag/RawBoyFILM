# Adobe DNG SDK + the libjxl bundled with it, as static libraries (target dng_sdk). Included by the engine's
# CMakeLists (Android) and web/native (WebAssembly). Inputs: RAWBOY_DNG_SDK_ROOT (contains dng_sdk/source) and
# RAWBOY_DNG_PLATFORM (the SDK's platform define(s), e.g. qAndroid=1 or qWeb=1).

# --- libjxl: the copy bundled with the DNG SDK (the version the SDK was built against) ---
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
foreach(option TOOLS JPEGLI DOXYGEN MANPAGES BENCHMARK EXAMPLES JNI SJPEG OPENEXR PLUGINS VIEWERS DEVTOOLS
               TRANSCODE_JPEG)
    set(JPEGXL_ENABLE_${option} OFF CACHE BOOL "" FORCE)
endforeach()
set(JPEGXL_ENABLE_SKCMS ON CACHE BOOL "" FORCE)
set(JPEGXL_BUNDLE_LIBPNG OFF CACHE BOOL "" FORCE)
set(RAWBOY_LIBJXL_DIR "${RAWBOY_DNG_SDK_ROOT}/libjxl/libjxl"
    CACHE PATH "libjxl source tree (defaults to the copy bundled with the DNG SDK)")
add_subdirectory("${RAWBOY_LIBJXL_DIR}" libjxl EXCLUDE_FROM_ALL)

# --- DNG SDK itself, read-only and without the XMP toolkit ---
set(DNG_SRC "${RAWBOY_DNG_SDK_ROOT}/dng_sdk/source")
file(GLOB DNG_SDK_SOURCES "${DNG_SRC}/*.cpp")
# dng_validate: command-line tool; dng_xmp_sdk: XMP toolkit wrapper; dng_update_meta: metadata writing.
list(FILTER DNG_SDK_SOURCES EXCLUDE REGEX ".*/(dng_validate|dng_xmp_sdk|dng_update_meta|dng_jxl)\\.cpp$")

# dng_jxl.cpp uses XMP in two places without checking qDNGUseXMP (embedding and
# parsing XMP boxes in JPEG XL previews). Guard them in a patched copy.
file(READ "${DNG_SRC}/dng_jxl.cpp" DNG_JXL)
string(REGEX REPLACE "([ \t]*if \\(includeXMP && metadata->GetXMP \\(\\)\\))" "#if qDNGUseXMP\n\\1" DNG_JXL "${DNG_JXL}")
string(REGEX REPLACE "(\\} // xmp\n)" "\\1#endif\n" DNG_JXL "${DNG_JXL}")
string(REGEX REPLACE "(\tif \\(fInfo\\)\n\t\t\\{\n\n\t\ttry\n\t\t\t\\{\n\n\t\t\tdng_xmp xmp)" "#if qDNGUseXMP\n\\1" DNG_JXL "${DNG_JXL}")
string(REGEX REPLACE "(// Not XMP -- ignore\\.\n\n\t\t\t\\}\n\n\t\t\\}\n)" "\\1#endif\n" DNG_JXL "${DNG_JXL}")
string(REGEX MATCHALL "#if qDNGUseXMP" DNG_JXL_GUARDS "${DNG_JXL}")
list(LENGTH DNG_JXL_GUARDS DNG_JXL_GUARD_COUNT)
if(NOT DNG_JXL_GUARD_COUNT EQUAL 2)
    message(FATAL_ERROR "RawBoy: could not patch dng_jxl.cpp (DNG SDK version changed?)")
endif()
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/dng_jxl_no_xmp.cpp" "${DNG_JXL}")
list(APPEND DNG_SDK_SOURCES "${CMAKE_CURRENT_BINARY_DIR}/dng_jxl_no_xmp.cpp")

add_library(dng_sdk STATIC ${DNG_SDK_SOURCES})
target_include_directories(dng_sdk PUBLIC "${DNG_SRC}")
target_compile_definitions(dng_sdk PUBLIC
    ${RAWBOY_DNG_PLATFORM}
    qDNGValidateTarget=0
    # The XMP toolkit is not needed to develop pixels; leave it out.
    qDNGUseXMP=0
    # Lossy (8-bit JPEG) DNG needs libjpeg (bundled with the SDK); not built yet.
    qDNGUseLibJPEG=0)
target_compile_options(dng_sdk PRIVATE -w -O3 -fexceptions -frtti)
target_link_libraries(dng_sdk PUBLIC jxl-static jxl_threads-static z)
