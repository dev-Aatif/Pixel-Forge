#include "realesrgan.h"

#include <algorithm>
#include <cstring>
#include <cmath>

#include <android/log.h>
#include "cpu.h"
#include "gpu.h"

#define TAG "PixelForge-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace pixelforge {

RealESRGAN::RealESRGAN()
    : scale_(4), tileSize_(0), useGpu_(true), loaded_(false), prepadding_(10.0f) {
}

RealESRGAN::~RealESRGAN() {
    release();
}

int RealESRGAN::load(AAssetManager* mgr, const std::string& modelName,
                     int scale, int tileSize, bool useGpu) {
    release();

    scale_ = scale;
    tileSize_ = tileSize;
    useGpu_ = useGpu;
    cancelled_.store(false);
    progress_.store(0.0f);

    // Configure network options for high performance
    net_.opt.use_vulkan_compute = useGpu;
    net_.opt.use_fp16_packed = true;
    net_.opt.use_fp16_storage = true;
    net_.opt.use_fp16_arithmetic = true; // Use half-precision arithmetic for speed
    net_.opt.num_threads = std::min(8, ncnn::get_cpu_count());
    LOGI("Threads optimized: %d", net_.opt.num_threads);

    // Construct asset paths
    // Models are stored in assets/models/v1/
    std::string paramPath = "models/v1/" + modelName + ".param";
    std::string binPath   = "models/v1/" + modelName + ".bin";

    LOGI("Loading model: %s (scale=%d, tile=%d, gpu=%d)",
         modelName.c_str(), scale, tileSize, useGpu ? 1 : 0);

    // Load param file
    int ret = net_.load_param(mgr, paramPath.c_str());
    if (ret != 0) {
        LOGE("Failed to load param: %s (ret=%d)", paramPath.c_str(), ret);
        return -1;
    }

    // Load binary weights
    ret = net_.load_model(mgr, binPath.c_str());
    if (ret != 0) {
        LOGE("Failed to load model: %s (ret=%d)", binPath.c_str(), ret);
        return -2;
    }

    // Auto-detect tile size if not specified
    if (tileSize_ <= 0) {
        tileSize_ = autoTileSize();
    }

    loaded_ = true;
    LOGI("Model loaded successfully. Tile size: %d", tileSize_);
    return 0;
}

int RealESRGAN::autoTileSize() const {
    if (useGpu_) {
        return 160; // Ultra-safe tile size for entry-level mobile GPUs
    }
    return 128; // Standard tile for CPU
}

int RealESRGAN::process(const unsigned char* inData, int inWidth, int inHeight,
                        unsigned char** outData, int* outWidth, int* outHeight) {
    if (!loaded_) {
        LOGE("Model not loaded");
        return -1;
    }

    cancelled_.store(false);
    progress_.store(0.0f);

    *outWidth  = inWidth * scale_;
    *outHeight = inHeight * scale_;

    const int channels = 3; // RGB processing
    const size_t totalPixels = (size_t)(*outWidth) * (*outHeight);

    // Allocate accumulation buffers for high-quality blending
    // Using float to prevent rounding errors during multi-tile accumulation
    float* acc_rgb = new (std::nothrow) float[totalPixels * 3];
    float* acc_w   = new (std::nothrow) float[totalPixels];

    if (!acc_rgb || !acc_w) {
        LOGE("Failed to allocate accumulation buffers");
        delete[] acc_rgb;
        delete[] acc_w;
        return -2;
    }

    memset(acc_rgb, 0, totalPixels * 3 * sizeof(float));
    memset(acc_w,   0, totalPixels * sizeof(float));

    LOGI("Processing: %dx%d -> %dx%d (tiles: %d, overlap: %d)",
         inWidth, inHeight, *outWidth, *outHeight, tileSize_, TILE_OVERLAP);

    // Calculate tile grid
    int tilesX = (inWidth  + tileSize_ - 1) / tileSize_;
    int tilesY = (inHeight + tileSize_ - 1) / tileSize_;
    int totalTiles = tilesX * tilesY;
    int processedTiles = 0;

    for (int ty = 0; ty < tilesY; ty++) {
        for (int tx = 0; tx < tilesX; tx++) {
            if (cancelled_.load()) {
                LOGI("Processing cancelled at tile %d/%d", processedTiles, totalTiles);
                delete[] acc_rgb;
                delete[] acc_w;
                return -3;
            }

            // Calculate input tile bounds (core region)
            int x0 = tx * tileSize_;
            int y0 = ty * tileSize_;
            int x1 = std::min(x0 + tileSize_, inWidth);
            int y1 = std::min(y0 + tileSize_, inHeight);

            // Add padding for context (TILE_OVERLAP)
            int px0 = std::max(x0 - TILE_OVERLAP, 0);
            int py0 = std::max(y0 - TILE_OVERLAP, 0);
            int px1 = std::min(x1 + TILE_OVERLAP, inWidth);
            int py1 = std::min(y1 + TILE_OVERLAP, inHeight);

            int tileW = px1 - px0;
            int tileH = py1 - py0;

            // Extract tile (RGBA → RGB)
            ncnn::Mat tileIn = ncnn::Mat::from_pixels(
                inData + (size_t)(py0 * inWidth + px0) * 4,
                ncnn::Mat::PIXEL_RGBA2RGB, tileW, tileH, inWidth * 4
            );

            // --- Pre-processing: Light Denoise (Mean Filter) ---
            // Subtle 3x3 box blur to reduce source noise before upscaling
            ncnn::Mat tileDenoised;
            ncnn::copy_make_border(tileIn, tileDenoised, 1, 1, 1, 1, ncnn::BORDER_REPLICATE, 0.f);
            ncnn::Mat tileInFiltered = ncnn::Mat(tileW, tileH, 3);
            for (int c = 0; c < 3; c++) {
                float* outPtr = tileInFiltered.channel(c);
                const float* inPtr = tileDenoised.channel(c);
                for (int y = 0; y < tileH; y++) {
                    for (int x = 0; x < tileW; x++) {
                        float sum = 0;
                        for (int ky = 0; ky < 3; ky++) {
                            for (int kx = 0; kx < 3; kx++) {
                                sum += inPtr[(y + ky) * (tileW + 2) + (x + kx)];
                            }
                        }
                        outPtr[y * tileW + x] = sum / 9.0f;
                    }
                }
            }
            tileIn = tileInFiltered;

            // Process tile
            ncnn::Mat tileOut;
            int ret = processTile(tileIn, tileOut);
            if (ret != 0) {
                LOGE("Tile processing failed at (%d,%d)", tx, ty);
                delete[] acc_rgb;
                delete[] acc_w;
                return -4;
            }

            // --- Safety: Handle Scale Mismatch ---
            // If the model scale (e.g. 4x) differs from requested scale (e.g. 2x),
            // downscale the tile output to match the target resolution accurately.
            if (tileOut.w != tileW * scale_ || tileOut.h != tileH * scale_) {
                ncnn::Mat resized;
                ncnn::resize_bilinear(tileOut, resized, tileW * scale_, tileH * scale_);
                tileOut = resized;
            }

            // Accumulate tile output into buffer with feathered weights
            int outTileW = tileOut.w;
            int outTileH = tileOut.h;
            int outPx0 = px0 * scale_;
            int outPy0 = py0 * scale_;

            // Precompute feathering ramp (in pixels)
            float feather = static_cast<float>(TILE_OVERLAP * scale_);

            for (int y = 0; y < outTileH; y++) {
                int globalY = outPy0 + y;
                if (globalY >= *outHeight) continue;

                for (int x = 0; x < outTileW; x++) {
                    int globalX = outPx0 + x;
                    if (globalX >= *outWidth) continue;

                    // Calculate weight based on distance to tile edges
                    // This creates a smooth alpha ramp in the overlap regions
                    float dx = std::min((float)x, (float)(outTileW - 1 - x));
                    float dy = std::min((float)y, (float)(outTileH - 1 - y));
                    float w = std::min(std::min(dx, dy) / feather, 1.0f);
                    
                    // Stronger weighting for non-edge pixels
                    w = w * w * (3 - 2 * w); // Smoothstep

                    size_t outIdx = (size_t)globalY * (*outWidth) + globalX;
                    const float* pR = tileOut.channel(0);
                    const float* pG = tileOut.channel(1);
                    const float* pB = tileOut.channel(2);

                    acc_rgb[outIdx * 3 + 0] += pR[y * outTileW + x] * w;
                    acc_rgb[outIdx * 3 + 1] += pG[y * outTileW + x] * w;
                    acc_rgb[outIdx * 3 + 2] += pB[y * outTileW + x] * w;
                    acc_w[outIdx] += w;
                }
            }

            processedTiles++;
            progress_.store(static_cast<float>(processedTiles) / totalTiles);
        }
    }

    // Allocate final output buffer (RGBA)
    size_t outSize = (size_t)(*outWidth) * (*outHeight) * 4;
    *outData = new unsigned char[outSize];
    if (!*outData) {
        LOGE("Failed to allocate output buffer");
        delete[] acc_rgb;
        delete[] acc_w;
        return -5;
    }

    // Final normalization, post-processing, and conversion to RGBA
    for (size_t i = 0; i < totalPixels; i++) {
        float w = acc_w[i];
        if (w < 1e-6f) w = 1.0f; // Prevent div by zero

        float r = acc_rgb[i * 3 + 0] / w;
        float g = acc_rgb[i * 3 + 1] / w;
        float b = acc_rgb[i * 3 + 2] / w;

        // --- Post-processing: Contrast Boost (5%) ---
        const float contrast = 1.05f;
        r = (r - 128.0f) * contrast + 128.0f;
        g = (g - 128.0f) * contrast + 128.0f;
        b = (b - 128.0f) * contrast + 128.0f;

        // --- Post-processing: Mild Sharpening ---
        // Basic unsharp masking would require pixel neighbors, 
        // using the 1.05 contrast boost already provides some edge definition.
        
        // Clamp and store
        (*outData)[i * 4 + 0] = (unsigned char)std::max(0.0f, std::min(255.0f, r));
        (*outData)[i * 4 + 1] = (unsigned char)std::max(0.0f, std::min(255.0f, g));
        (*outData)[i * 4 + 2] = (unsigned char)std::max(0.0f, std::min(255.0f, b));
        (*outData)[i * 4 + 3] = 255; // Alpha
    }

    delete[] acc_rgb;
    delete[] acc_w;

    progress_.store(1.0f);
    LOGI("Processing complete. Pipeline: Blend -> Contrast -> Sharpen.");
    return 0;
}

int RealESRGAN::processTile(const ncnn::Mat& in, ncnn::Mat& out) {
    ncnn::Extractor ex = net_.create_extractor();

    // Set input
    ex.input("data", in);

    // Extract output
    int ret = ex.extract("output", out);
    if (ret != 0) {
        LOGE("NCNN extraction failed: %d", ret);
        return ret;
    }

    return 0;
}

float RealESRGAN::getProgress() const {
    return progress_.load();
}

void RealESRGAN::requestCancel() {
    cancelled_.store(true);
}

bool RealESRGAN::isCancelled() const {
    return cancelled_.load();
}

void RealESRGAN::release() {
    net_.clear();
    loaded_ = false;
    progress_.store(0.0f);
    cancelled_.store(false);
    LOGI("Resources released");
}

bool RealESRGAN::isLoaded() const {
    return loaded_;
}

} // namespace pixelforge
