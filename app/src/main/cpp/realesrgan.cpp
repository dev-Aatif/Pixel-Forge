#include "realesrgan.h"

#include <algorithm>
#include <cstring>
#include <cmath>

#include <android/log.h>

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

    // Configure network options
    net_.opt.use_vulkan_compute = useGpu;
    net_.opt.use_fp16_packed = true;
    net_.opt.use_fp16_storage = true;
    net_.opt.use_fp16_arithmetic = false; // Keep precision for arithmetic
    net_.opt.num_threads = 4;

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
    // Conservative defaults for mobile devices
    // Vulkan GPU typically handles larger tiles
    if (useGpu_) {
        return 200; // Good balance for mobile GPUs
    }
    return 100; // CPU mode: smaller tiles to avoid OOM
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

    // Allocate output buffer (RGBA)
    size_t outSize = (size_t)(*outWidth) * (*outHeight) * 4;
    *outData = new unsigned char[outSize];
    if (!*outData) {
        LOGE("Failed to allocate output buffer (%zu bytes)", outSize);
        return -2;
    }
    memset(*outData, 255, outSize); // Fill alpha

    LOGI("Processing: %dx%d -> %dx%d (tiles: %d)",
         inWidth, inHeight, *outWidth, *outHeight, tileSize_);

    // Calculate tile grid
    int prepadding = static_cast<int>(prepadding_);
    int tilesX = (inWidth  + tileSize_ - 1) / tileSize_;
    int tilesY = (inHeight + tileSize_ - 1) / tileSize_;
    int totalTiles = tilesX * tilesY;
    int processedTiles = 0;

    for (int ty = 0; ty < tilesY; ty++) {
        for (int tx = 0; tx < tilesX; tx++) {
            if (cancelled_.load()) {
                LOGI("Processing cancelled at tile %d/%d", processedTiles, totalTiles);
                delete[] *outData;
                *outData = nullptr;
                return -3;
            }

            // Calculate tile bounds with overlap
            int x0 = tx * tileSize_;
            int y0 = ty * tileSize_;
            int x1 = std::min(x0 + tileSize_, inWidth);
            int y1 = std::min(y0 + tileSize_, inHeight);

            // Add padding (clamp to image bounds)
            int px0 = std::max(x0 - TILE_OVERLAP, 0);
            int py0 = std::max(y0 - TILE_OVERLAP, 0);
            int px1 = std::min(x1 + TILE_OVERLAP, inWidth);
            int py1 = std::min(y1 + TILE_OVERLAP, inHeight);

            int tileW = px1 - px0;
            int tileH = py1 - py0;

            // Extract tile from input (RGBA → RGB ncnn::Mat)
            ncnn::Mat tileIn(tileW, tileH, channels);
            for (int c = 0; c < channels; c++) {
                float* channelPtr = tileIn.channel(c);
                for (int y = 0; y < tileH; y++) {
                    for (int x = 0; x < tileW; x++) {
                        int srcIdx = ((py0 + y) * inWidth + (px0 + x)) * 4 + c;
                        channelPtr[y * tileW + x] = inData[srcIdx] / 255.0f;
                    }
                }
            }

            // Process tile through network
            ncnn::Mat tileOut;
            int ret = processTile(tileIn, tileOut);
            if (ret != 0) {
                LOGE("Tile processing failed at (%d,%d), ret=%d", tx, ty, ret);
                delete[] *outData;
                *outData = nullptr;
                return -4;
            }

            // Calculate output tile bounds
            int outX0 = x0 * scale_;
            int outY0 = y0 * scale_;
            int outX1 = x1 * scale_;
            int outY1 = y1 * scale_;

            // Offset within the padded tile output
            int padLeft = (x0 - px0) * scale_;
            int padTop  = (y0 - py0) * scale_;

            // Copy tile output to final output (RGB → RGBA)
            int copyW = outX1 - outX0;
            int copyH = outY1 - outY0;

            for (int y = 0; y < copyH; y++) {
                for (int x = 0; x < copyW; x++) {
                    int dstIdx = ((outY0 + y) * (*outWidth) + (outX0 + x)) * 4;
                    for (int c = 0; c < channels; c++) {
                        float val = tileOut.channel(c)[(padTop + y) * tileOut.w + (padLeft + x)];
                        val = std::max(0.0f, std::min(1.0f, val));
                        (*outData)[dstIdx + c] = static_cast<unsigned char>(val * 255.0f + 0.5f);
                    }
                    (*outData)[dstIdx + 3] = 255; // Alpha
                }
            }

            processedTiles++;
            progress_.store(static_cast<float>(processedTiles) / totalTiles);
        }
    }

    progress_.store(1.0f);
    LOGI("Processing complete");
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
