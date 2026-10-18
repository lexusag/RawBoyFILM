# RawBoy iOS: the engine's native code as two dynamic libraries, packaged into frameworks by build.sh.
# Sources are used in place from engine/src/main/cpp; only the C APIs (rawboy_native.cpp, rawboy_jpeg.cpp) are
# iOS-specific. Configure with CMAKE_SYSTEM_NAME=iOS and the SDK in CMAKE_OSX_SYSROOT (build.sh does).
#   rawboy_native  LibRaw (+ Adobe DNG SDK) and JPEG decoders, lens opcodes, blemish healing, downsample/copyTile
#   rawboy_jpeg    jpegli (export encoder, and rb_jpeg_decode for rawboy_native) + the export band operations
# Two libraries, as on Android: jpegli and the DNG SDK's libjxl bundle different Highway versions. Each exports
# only its rb_* C API (-exported_symbol), the counterpart of Android's --exclude-libs,ALL.
# RawBoy Film (GPLv3) builds rawboy_native without the DNG SDK, as RawBoyNativeFilm (build.sh's film pass):
#   -DRAWBOY_USE_DNG_SDK=OFF -DRAWBOY_NATIVE_NAME=RawBoyNativeFilm
cmake_minimum_required(VERSION 3.22.1)
project(rawboy_ios LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)

set(ENGINE_CPP "${CMAKE_CURRENT_SOURCE_DIR}/../../engine/src/main/cpp")

# --- LibRaw, as in the engine's CMakeLists, but without OpenMP (Apple's clang has none; same as the web) ---
set(RAWBOY_LIBRAW_VERSION "0.21.3" CACHE STRING "LibRaw release tag")
FetchContent_Declare(libraw URL https://github.com/LibRaw/LibRaw/archive/refs/tags/${RAWBOY_LIBRAW_VERSION}.tar.gz)
FetchContent_MakeAvailable(libraw)
file(GLOB_RECURSE LIBRAW_SOURCES CONFIGURE_DEPENDS "${libraw_SOURCE_DIR}/src/*.cpp")
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX ".*_ph\\.cpp$")
add_library(libraw_static STATIC ${LIBRAW_SOURCES})
target_include_directories(libraw_static PUBLIC "${libraw_SOURCE_DIR}")
target_compile_definitions(libraw_static PUBLIC LIBRAW_NODLL USE_ZLIB)
target_compile_options(libraw_static PRIVATE -w -O3)
target_link_libraries(libraw_static PUBLIC z)

# --- jpegli: the engine's fetch and library targets (the Android .so is skipped outside Android) ---
include("${ENGINE_CPP}/jpegli.cmake")

# --- Adobe DNG SDK (optional): same setup as the engine's CMakeLists, iOS platform define ---
set(RAWBOY_DNG_SDK_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/dng_sdk"
    CACHE PATH "Adobe DNG SDK root (directory containing dng_sdk/source)")
# OFF for RawBoy Film (GPLv3): the SDK's licence terms aren't GPL-compatible.
option(RAWBOY_USE_DNG_SDK "Build with the Adobe DNG SDK when it is present" ON)
set(RAWBOY_WITH_DNG_SDK 0)
if(RAWBOY_USE_DNG_SDK AND EXISTS "${RAWBOY_DNG_SDK_ROOT}/dng_sdk/source/dng_host.cpp")
    message(STATUS "RawBoy: building with Adobe DNG SDK from ${RAWBOY_DNG_SDK_ROOT}")
    set(RAWBOY_WITH_DNG_SDK 1)
    set(RAWBOY_DNG_PLATFORM qiPhone=1)
    include("${ENGINE_CPP}/dng_sdk.cmake")
endif()

set(RAWBOY_IOS_LINK_OPTIONS -Wl,-exported_symbol,_rb_* -Wl,-dead_strip)

# --- rawboy_native: decoders ---
set(NATIVE_SOURCES
    rawboy_native.cpp
    ${ENGINE_CPP}/blemish.cpp
    ${ENGINE_CPP}/decode.cpp
    ${ENGINE_CPP}/decoder_libraw.cpp
    ${ENGINE_CPP}/decoder_display.cpp
    ${ENGINE_CPP}/display_inverse.cpp
    ${ENGINE_CPP}/display_profile.cpp
    ${ENGINE_CPP}/exif_reader.cpp
    ${ENGINE_CPP}/image_ops.cpp
    ${ENGINE_CPP}/lens_opcodes.cpp
    ${ENGINE_CPP}/noise.cpp
    ${ENGINE_CPP}/orientation.cpp)
if(RAWBOY_WITH_DNG_SDK)
    list(APPEND NATIVE_SOURCES ${ENGINE_CPP}/decoder_dng.cpp)
endif()
add_library(rawboy_native SHARED ${NATIVE_SOURCES})
target_include_directories(rawboy_native PRIVATE "${ENGINE_CPP}")
target_compile_definitions(rawboy_native PRIVATE RAWBOY_WITH_DNG_SDK=${RAWBOY_WITH_DNG_SDK})
target_compile_options(rawboy_native PRIVATE -O3 -Wall -fvisibility=hidden)
# rb_jpeg_decode (jpeg_decode.h) for JPEG input comes from RawBoyJpeg.
target_link_libraries(rawboy_native PRIVATE libraw_static rawboy_jpeg)
if(RAWBOY_WITH_DNG_SDK)
    target_link_libraries(rawboy_native PRIVATE dng_sdk)
endif()
target_link_options(rawboy_native PRIVATE ${RAWBOY_IOS_LINK_OPTIONS})
# Framework name: RawBoyNative, or RawBoyNativeFilm for RawBoy Film (its own name, so both can sit in one build).
set(RAWBOY_NATIVE_NAME "RawBoyNative" CACHE STRING "Framework name of rawboy_native")
set_target_properties(rawboy_native PROPERTIES OUTPUT_NAME ${RAWBOY_NATIVE_NAME} PREFIX "" SUFFIX ""
    INSTALL_NAME_DIR "@rpath/${RAWBOY_NATIVE_NAME}.framework" BUILD_WITH_INSTALL_NAME_DIR ON)

# --- rawboy_jpeg: jpegli + band ops ---
add_library(rawboy_jpeg SHARED rawboy_jpeg.cpp ${ENGINE_CPP}/jpeg_decode.cpp)
target_include_directories(rawboy_jpeg PRIVATE "${ENGINE_CPP}")
target_compile_options(rawboy_jpeg PRIVATE -O3 -Wall -fvisibility=hidden)
target_link_libraries(rawboy_jpeg PRIVATE rawboy_jpegli)
target_link_options(rawboy_jpeg PRIVATE ${RAWBOY_IOS_LINK_OPTIONS})
set_target_properties(rawboy_jpeg PROPERTIES OUTPUT_NAME RawBoyJpeg PREFIX "" SUFFIX ""
    INSTALL_NAME_DIR "@rpath/RawBoyJpeg.framework" BUILD_WITH_INSTALL_NAME_DIR ON)
