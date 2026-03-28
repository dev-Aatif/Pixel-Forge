cmake_minimum_required(VERSION 3.22.1)
project("pixelforge-native" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ============================================================
# NCNN - Prebuilt library
# The user must download ncnn-YYYYMMDD-android-vulkan.zip from:
#   https://github.com/Tencent/ncnn/releases
# and extract per-ABI folders so that this path resolves:
#   app/src/main/jniLibs/<ABI>/libncnn.a   (static lib)
#   app/src/main/cpp/ncnn-include/         (headers)
#
# Alternatively, set ncnn_DIR to the cmake config directory.
# ============================================================

# Option 1: Use find_package if ncnn cmake config is available
set(ncnn_DIR "${CMAKE_SOURCE_DIR}/ncnn/${ANDROID_ABI}/lib/cmake/ncnn" CACHE PATH "ncnn cmake config dir")

find_package(ncnn REQUIRED)

# ============================================================
# Source files
# ============================================================
add_library(pixelforge-native SHARED
    realesrgan.cpp
    realesrgan_jni.cpp
)

# ============================================================
# Include directories
# ============================================================
target_include_directories(pixelforge-native PRIVATE
    ${CMAKE_SOURCE_DIR}
)

# ============================================================
# Link libraries
# ============================================================
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(android-lib android)

target_link_libraries(pixelforge-native
    ncnn
    ${log-lib}
    ${jnigraphics-lib}
    ${android-lib}
)
