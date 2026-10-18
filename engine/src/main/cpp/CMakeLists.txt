cmake_minimum_required(VERSION 3.22.1)
project(rawboy LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)

# -----------------------------------------------------------------------------
# LibRaw: decodes every camera-native raw (CR2/CR3/NEF/ARW/RAF/...) and is the
# DNG fallback when the Adobe DNG SDK is not present.
# -----------------------------------------------------------------------------
set(RAWBOY_LIBRAW_VERSION "0.21.3" CACHE STRING "LibRaw release tag")
FetchContent_Declare(libraw
    URL https://github.com/LibRaw/LibRaw/archive/refs/tags/${RAWBOY_LIBRAW_VERSION}.tar.gz)
FetchContent_MakeAvailable(libraw)   # LibRaw ships no CMakeLists, so this only downloads it

file(GLOB_RECURSE LIBRAW_SOURCES CONFIGURE_DEPENDS "${libraw_SOURCE_DIR}/src/*.cpp")
# *_ph.cpp are stubs for builds without pre/post-processing; they duplicate real symbols.
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX ".*_ph\\.cpp$")
add_library(libraw_static STATIC ${LIBRAW_SOURCES})
target_include_directories(libraw_static PUBLIC "${libraw_SOURCE_DIR}")
target_compile_definitions(libraw_static PUBLIC LIBRAW_NODLL USE_ZLIB)
target_compile_options(libraw_static PRIVATE -w -O3)
# OpenMP: LibRaw's AHD demosaic (and other steps) run their loops on all cores. PUBLIC because
# libraw_types.h switches LIBRAW_USE_OPENMP on _OPENMP, and that changes LibRaw's memory-manager
# layout: every file including LibRaw's headers must agree. libomp is linked statically (NDK).
target_compile_options(libraw_static PUBLIC -fopenmp)
target_link_options(libraw_static PUBLIC -fopenmp -static-openmp)
target_link_libraries(libraw_static PUBLIC z)

# -----------------------------------------------------------------------------
# Adobe DNG SDK (optional): enables JPEG XL-compressed DNG (DNG 1.7) and Adobe's
# reference colour pipeline. Download from Adobe and unpack into
# <repo>/third_party/dng_sdk so that dng_sdk/source/dng_host.cpp exists.
# See third_party/README.md.
# -----------------------------------------------------------------------------
set(RAWBOY_DNG_SDK_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/dng_sdk"
    CACHE PATH "Adobe DNG SDK root (directory containing dng_sdk/source)")

# OFF in the film edition (GPLv3; engine flavour "film" passes it): the SDK's licence terms aren't GPL-compatible.
option(RAWBOY_USE_DNG_SDK "Build with the Adobe DNG SDK when it is present" ON)

set(RAWBOY_WITH_DNG_SDK 0)
if(RAWBOY_USE_DNG_SDK AND EXISTS "${RAWBOY_DNG_SDK_ROOT}/dng_sdk/source/dng_host.cpp")
    message(STATUS "RawBoy: building with Adobe DNG SDK from ${RAWBOY_DNG_SDK_ROOT}")
    set(RAWBOY_WITH_DNG_SDK 1)

    set(RAWBOY_DNG_PLATFORM qAndroid=1)
    include("${CMAKE_CURRENT_SOURCE_DIR}/dng_sdk.cmake")
endif()

# -----------------------------------------------------------------------------
# RawBoy JNI library
# -----------------------------------------------------------------------------
set(RAWBOY_SOURCES
    jni_bridge.cpp
    blemish.cpp
    decode.cpp
    image_ops.cpp
    decoder_libraw.cpp
    decoder_display.cpp
    display_inverse.cpp
    display_profile.cpp
    exif_reader.cpp
    lens_opcodes.cpp
    noise.cpp
    orientation.cpp)
if(RAWBOY_WITH_DNG_SDK)
    list(APPEND RAWBOY_SOURCES decoder_dng.cpp)
endif()

add_library(rawboy SHARED ${RAWBOY_SOURCES})
target_compile_definitions(rawboy PRIVATE RAWBOY_WITH_DNG_SDK=${RAWBOY_WITH_DNG_SDK})
target_compile_options(rawboy PRIVATE -O3 -Wall)
target_link_libraries(rawboy PRIVATE libraw_static log jnigraphics)
if(RAWBOY_WITH_DNG_SDK)
    target_link_libraries(rawboy PRIVATE dng_sdk)
endif()

# JPEG encoder/decoder library (librawboyjpeg.so); the decoders call its rb_jpeg_decode
include(jpegli.cmake)
target_link_libraries(rawboy PRIVATE rawboyjpeg)
