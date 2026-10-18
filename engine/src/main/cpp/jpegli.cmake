# -----------------------------------------------------------------------------
# jpegli (Google's libjpeg-compatible JPEG encoder from the JPEG XL project): better quality per
# byte than libjpeg, and it takes 16-bit samples. Built into its own small library,
# librawboyjpeg.so, with its own Highway: libjxl (DNG SDK path) bundles an older Highway, and two
# copies in one library would clash. Internal symbols are hidden; only the JNI functions and rb_jpeg_decode (for the
# decoders in librawboy, see jpeg_decode.h) are exported.
#
# The DNG SDK's libjxl has a jpegli folder too, but it's an unbuildable development snapshot.
# Pinned to the commits jpegli's own submodules use.
# -----------------------------------------------------------------------------
set(RAWBOY_JPEGLI_COMMIT "031a0077f5799a6041004267fc12b956c1f52a20" CACHE STRING "google/jpegli commit")
set(RAWBOY_HWY_COMMIT "271a9a0ed9de1232d9117f1572c3fe28f8542ec1" CACHE STRING "google/highway commit for jpegli")
set(RAWBOY_LIBJPEG_TURBO_COMMIT "8ecba3647edb6dd940463fedf38ca33a8e2a73d1" CACHE STRING
    "libjpeg-turbo commit (only its jpeglib.h API headers are used)")

FetchContent_Declare(jpegli URL https://github.com/google/jpegli/archive/${RAWBOY_JPEGLI_COMMIT}.tar.gz)
FetchContent_Declare(jpegli_hwy URL https://github.com/google/highway/archive/${RAWBOY_HWY_COMMIT}.tar.gz)
FetchContent_Declare(jpegli_turbo URL https://github.com/libjpeg-turbo/libjpeg-turbo/archive/${RAWBOY_LIBJPEG_TURBO_COMMIT}.tar.gz)
# Download only: none of their own CMake projects is used.
foreach(dep jpegli jpegli_hwy jpegli_turbo)
    FetchContent_GetProperties(${dep})
    if(NOT ${dep}_POPULATED)
        FetchContent_Populate(${dep})
    endif()
endforeach()

# libjpeg API headers, configured as jpegli's build does (libjpeg 6.2 ABI, 8-bit JSAMPLE).
set(JPEGLI_INCLUDE "${CMAKE_CURRENT_BINARY_DIR}/jpegli_include")
set(JPEG_LIB_VERSION 62)
set(BITS_IN_JSAMPLE 8)
set(MEM_SRCDST_SUPPORTED 1)
configure_file("${jpegli_turbo_SOURCE_DIR}/jconfig.h.in" "${JPEGLI_INCLUDE}/jconfig.h")
configure_file("${jpegli_turbo_SOURCE_DIR}/jpeglib.h" "${JPEGLI_INCLUDE}/jpeglib.h" COPYONLY)
configure_file("${jpegli_turbo_SOURCE_DIR}/jmorecfg.h" "${JPEGLI_INCLUDE}/jmorecfg.h" COPYONLY)

# Highway runtime (target detection and dispatch); the SIMD code itself is in headers.
set(JPEGLI_HWY_SOURCES abort aligned_allocator per_target print targets timer)
list(TRANSFORM JPEGLI_HWY_SOURCES PREPEND "${jpegli_hwy_SOURCE_DIR}/hwy/")
list(TRANSFORM JPEGLI_HWY_SOURCES APPEND ".cc")
add_library(rawboy_hwy STATIC ${JPEGLI_HWY_SOURCES})
target_include_directories(rawboy_hwy PUBLIC "${jpegli_hwy_SOURCE_DIR}")
target_compile_definitions(rawboy_hwy PUBLIC HWY_STATIC_DEFINE)
target_compile_options(rawboy_hwy PRIVATE -w -O3 -fvisibility=hidden -fvisibility-inlines-hidden)

# jpegli's library sources, as listed by its own build.
include("${jpegli_SOURCE_DIR}/lib/jpegli_lists.cmake")
set(JPEGLI_SOURCES ${JPEGLI_INTERNAL_JPEGLI_SOURCES})
list(FILTER JPEGLI_SOURCES INCLUDE REGEX "\\.cc$")
list(TRANSFORM JPEGLI_SOURCES PREPEND "${jpegli_SOURCE_DIR}/lib/")
add_library(rawboy_jpegli STATIC ${JPEGLI_SOURCES})
target_include_directories(rawboy_jpegli PUBLIC "${jpegli_SOURCE_DIR}" "${JPEGLI_INCLUDE}")
target_compile_options(rawboy_jpegli PRIVATE -w -O3 -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_libraries(rawboy_jpegli PUBLIC rawboy_hwy)

# The Android library; the web build (web/native) includes this file for rawboy_jpegli only.
if(ANDROID)
    add_library(rawboyjpeg SHARED jpeg_jpegli.cpp jpeg_decode.cpp band_ops.cpp)
    target_compile_options(rawboyjpeg PRIVATE -O3 -Wall -fvisibility=hidden)
    target_link_libraries(rawboyjpeg PRIVATE rawboy_jpegli log)
    target_link_options(rawboyjpeg PRIVATE "-Wl,--exclude-libs,ALL")
endif()
