cmake_minimum_required(VERSION 3.22.1)
project("pixelforge-native" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ============================================================
# OpenMP Support
# ============================================================
find_package(OpenMP REQUIRED)

# ============================================================
# NCNN - Direct Link Configuration
# We bypass find_package and link the static library and its
# dependencies directly to ensure simple, robust building.
# ============================================================

# Assuming arm64-v8a is provided in jniLibs
set(NCNN_DIR "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

# Main ncnn static library
add_library(ncnn STATIC IMPORTED)
set_target_properties(ncnn PROPERTIES
    IMPORTED_LOCATION "${NCNN_DIR}/lib/libncnn.a"
)

# Vulkan/GLSL support libraries (dependencies of static ncnn)
add_library(glslang STATIC IMPORTED)
set_target_properties(glslang PROPERTIES
    IMPORTED_LOCATION "${NCNN_DIR}/lib/libglslang.a"
)

add_library(SPIRV STATIC IMPORTED)
set_target_properties(SPIRV PROPERTIES
    IMPORTED_LOCATION "${NCNN_DIR}/lib/libSPIRV.a"
)

add_library(OSDependent STATIC IMPORTED)
set_target_properties(OSDependent PROPERTIES
    IMPORTED_LOCATION "${NCNN_DIR}/lib/libOSDependent.a"
)

add_library(GenericCodeGen STATIC IMPORTED)
set_target_properties(GenericCodeGen PROPERTIES
    IMPORTED_LOCATION "${NCNN_DIR}/lib/libGenericCodeGen.a"
)

add_library(MachineIndependent STATIC IMPORTED)
set_target_properties(MachineIndependent PROPERTIES
    IMPORTED_LOCATION "${NCNN_DIR}/lib/libMachineIndependent.a"
)

add_library(glslang-default-resource-limits STATIC IMPORTED)
set_target_properties(glslang-default-resource-limits PROPERTIES
    IMPORTED_LOCATION "${NCNN_DIR}/lib/libglslang-default-resource-limits.a"
)

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
    "${NCNN_DIR}/include/ncnn"
)

# ============================================================
# Link libraries
# ============================================================
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(android-lib android)
find_library(vulkan-lib vulkan)

target_link_libraries(pixelforge-native
    ncnn
    glslang
    SPIRV
    OSDependent
    GenericCodeGen
    MachineIndependent
    glslang-default-resource-limits
    OpenMP::OpenMP_CXX
    ${log-lib}
    ${jnigraphics-lib}
    ${android-lib}
    ${vulkan-lib}
    atomic
)
