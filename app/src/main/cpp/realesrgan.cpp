#include "realesrgan.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include <android/log.h>
#include "cpu.h"
#include "gpu.h"

#define TAG "PixelForge-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace pixelforge {

RealESRGAN::RealESRGAN()
    : scale_(4),
      tileSize_(0),
      useGpu_(false),
      allowFp16Arithmetic_(false),
      loaded_(false) {
}

RealESRGAN::~RealESRGAN() {
    release();
}

int RealESRGAN::configureNetOptions(bool useGpu, bool allowFp16Arithmetic) {
    bool gpuAvailable = false;
#if NCNN_VULKAN
    gpuAvailable = useGpu && (ncnn::get_gpu_count() > 0);
#endif
    useGpu_ = gpuAvailable;
    allowFp16Arithmetic_ = allowFp16Arithmetic;

    net_.opt.lightmode = true;
    net_.opt.use_vulkan_compute = gpuAvailable;
    net_.opt.use_fp16_packed = true;
    net_.opt.use_fp16_storage = true;
    // Always disable FP16 arithmetic for Real-ESRGAN models on mobile Vulkan GPUs
    // (matches upstream Real-ESRGAN-ncnn-vulkan) to prevent NaN/black/green-block artifacts.
    net_.opt.use_fp16_arithmetic = false;
    net_.opt.use_packing_layout = true;
    net_.opt.use_winograd_convolution = true;
    net_.opt.use_sgemm_convolution = true;
    net_.opt.num_threads = std::min(8, std::max(1, ncnn::get_cpu_count()));

    LOGI("NCNN opts: gpu=%d (requested=%d), fp16_arith=%d, threads=%d",
         gpuAvailable ? 1 : 0, useGpu ? 1 : 0,
         net_.opt.use_fp16_arithmetic ? 1 : 0, net_.opt.num_threads);
    return 0;
}

int RealESRGAN::finalizeLoad(int scale, int tileSize) {
    scale_ = scale;
    tileSize_ = tileSize;
    loaded_ = true;
    LOGI("Model loaded successfully (scale=%d, requestedTile=%d)", scale_, tileSize_);
    return 0;
}

int RealESRGAN::load(AAssetManager* mgr, const std::string& modelName,
                     int scale, int tileSize, bool useGpu, bool allowFp16Arithmetic) {
    release();
    configureNetOptions(useGpu, allowFp16Arithmetic);

    std::string paramPath = "models/v1/" + modelName + ".param";
    std::string binPath   = "models/v1/" + modelName + ".bin";

    int ret = net_.load_param(mgr, paramPath.c_str());
    if (ret != 0) {
        LOGE("Failed to load param asset: %s (ret=%d)", paramPath.c_str(), ret);
        return -1;
    }

    ret = net_.load_model(mgr, binPath.c_str());
    if (ret != 0) {
        LOGE("Failed to load model asset: %s (ret=%d)", binPath.c_str(), ret);
        net_.clear();
        return -2;
    }

    return finalizeLoad(scale, tileSize);
}

int RealESRGAN::loadFromFiles(const std::string& paramPath, const std::string& binPath,
                              int scale, int tileSize, bool useGpu, bool allowFp16Arithmetic) {
    release();
    configureNetOptions(useGpu, allowFp16Arithmetic);

    int ret = net_.load_param(paramPath.c_str());
    if (ret != 0) {
        LOGE("Failed to load param file: %s (ret=%d)", paramPath.c_str(), ret);
        return -1;
    }

    ret = net_.load_model(binPath.c_str());
    if (ret != 0) {
        LOGE("Failed to load model file: %s (ret=%d)", binPath.c_str(), ret);
        net_.clear();
        return -2;
    }

    return finalizeLoad(scale, tileSize);
}

int RealESRGAN::autoTileSize(int /*inWidth*/, int /*inHeight*/) const {
    if (useGpu_) {
        // 224px keeps GPU VRAM well under mobile driver limits for both compact & 23-RRDB models
        return 224;
    }
    return 160; // CPU cache-friendly tile
}

bool RealESRGAN::hasTransparency(const unsigned char* inData, int width, int height) {
    const size_t total = static_cast<size_t>(width) * height;
    for (size_t i = 0; i < total; ++i) {
        if (inData[i * 4 + 3] < 252) {
            return true;
        }
    }
    return false;
}

void RealESRGAN::upscaleAlphaBilinear(const unsigned char* inData, int inW, int inH,
                                      unsigned char* outData, int outW, int outH, int scale) {
    const float invScale = 1.0f / static_cast<float>(scale);
    for (int y = 0; y < outH; ++y) {
        float sy = (static_cast<float>(y) + 0.5f) * invScale - 0.5f;
        sy = std::clamp(sy, 0.0f, static_cast<float>(inH - 1));
        int y0 = static_cast<int>(std::floor(sy));
        int y1 = std::min(y0 + 1, inH - 1);
        float fy = sy - y0;

        for (int x = 0; x < outW; ++x) {
            float sx = (static_cast<float>(x) + 0.5f) * invScale - 0.5f;
            sx = std::clamp(sx, 0.0f, static_cast<float>(inW - 1));
            int x0 = static_cast<int>(std::floor(sx));
            int x1 = std::min(x0 + 1, inW - 1);
            float fx = sx - x0;

            float a00 = inData[(static_cast<size_t>(y0) * inW + x0) * 4 + 3];
            float a10 = inData[(static_cast<size_t>(y0) * inW + x1) * 4 + 3];
            float a01 = inData[(static_cast<size_t>(y1) * inW + x0) * 4 + 3];
            float a11 = inData[(static_cast<size_t>(y1) * inW + x1) * 4 + 3];

            float top = a00 + (a10 - a00) * fx;
            float bot = a01 + (a11 - a01) * fx;
            float val = top + (bot - top) * fy;
            outData[(static_cast<size_t>(y) * outW + x) * 4 + 3] =
                static_cast<unsigned char>(std::clamp(std::lround(val), 0L, 255L));
        }
    }
}

int RealESRGAN::process(const unsigned char* inData, int inWidth, int inHeight,
                        unsigned char** outData, int* outWidth, int* outHeight) {
    if (!loaded_) {
        LOGE("Model not loaded");
        return -1;
    }

    cancelled_.store(false);
    progress_.store(0.0f);

    const int activeTileSize = (tileSize_ > 0) ? tileSize_ : autoTileSize(inWidth, inHeight);
    *outWidth  = inWidth * scale_;
    *outHeight = inHeight * scale_;

    const size_t outSize = static_cast<size_t>(*outWidth) * (*outHeight) * 4;
    *outData = new (std::nothrow) unsigned char[outSize];
    if (!*outData) {
        LOGE("Failed to allocate output buffer (%zu bytes)", outSize);
        return -2;
    }

    const bool keepAlpha = hasTransparency(inData, inWidth, inHeight);
    if (!keepAlpha) {
        std::memset(*outData, 255, outSize);
    }

    const int tilesX = (inWidth  + activeTileSize - 1) / activeTileSize;
    const int tilesY = (inHeight + activeTileSize - 1) / activeTileSize;
    const int totalTiles = tilesX * tilesY;
    int processedTiles = 0;

    LOGI("Processing %dx%d -> %dx%d (tile=%d, overlap=%d, grid=%dx%d, alpha=%d)",
         inWidth, inHeight, *outWidth, *outHeight, activeTileSize, TILE_OVERLAP,
         tilesX, tilesY, keepAlpha ? 1 : 0);

    for (int ty = 0; ty < tilesY; ++ty) {
        for (int tx = 0; tx < tilesX; ++tx) {
            if (cancelled_.load()) {
                LOGI("Cancelled at tile %d/%d", processedTiles, totalTiles);
                delete[] *outData;
                *outData = nullptr;
                return -3;
            }

            const int x0 = tx * activeTileSize;
            const int y0 = ty * activeTileSize;
            const int x1 = std::min(x0 + activeTileSize, inWidth);
            const int y1 = std::min(y0 + activeTileSize, inHeight);

            const int px0 = std::max(x0 - TILE_OVERLAP, 0);
            const int py0 = std::max(y0 - TILE_OVERLAP, 0);
            const int px1 = std::min(x1 + TILE_OVERLAP, inWidth);
            const int py1 = std::min(y1 + TILE_OVERLAP, inHeight);

            const int tileW = px1 - px0;
            const int tileH = py1 - py0;

            ncnn::Mat tileIn = ncnn::Mat::from_pixels(
                inData + (static_cast<size_t>(py0) * inWidth + px0) * 4,
                ncnn::Mat::PIXEL_RGBA2RGB, tileW, tileH, inWidth * 4
            );

            // Real-ESRGAN NCNN models expect [0.0, 1.0] RGB input (matching upstream preproc shader)
            const float normVals[3] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};
            tileIn.substract_mean_normalize(nullptr, normVals);

            const int padTop = TILE_OVERLAP - (y0 - py0);
            const int padBottom = TILE_OVERLAP - (py1 - y1);
            const int padLeft = TILE_OVERLAP - (x0 - px0);
            const int padRight = TILE_OVERLAP - (px1 - x1);

            ncnn::Mat tileInPadded;
            ncnn::copy_make_border(
                tileIn, tileInPadded,
                padTop, padBottom, padLeft, padRight,
                ncnn::BORDER_REPLICATE, 0.f
            );

            ncnn::Mat tileOut;
            const int ret = processTile(tileInPadded, tileOut);
            if (ret != 0 || tileOut.empty()) {
                LOGE("Tile failed or empty at (%d,%d): %d", tx, ty, ret);
                delete[] *outData;
                *outData = nullptr;
                return -4;
            }

            const int cropPad = TILE_OVERLAP * scale_;
            ncnn::Mat cropOut;
            ncnn::copy_cut_border(tileOut, cropOut, cropPad, cropPad, cropPad, cropPad);

            const int expectedW = (x1 - x0) * scale_;
            const int expectedH = (y1 - y0) * scale_;
            if (cropOut.empty() || cropOut.c < 3 || cropOut.w != expectedW || cropOut.h != expectedH) {
                LOGE("Unexpected cropOut shape: %dx%d c=%d (expected %dx%d)",
                     cropOut.w, cropOut.h, cropOut.c, expectedW, expectedH);
                delete[] *outData;
                *outData = nullptr;
                return -5;
            }

            const int outX0 = x0 * scale_;
            const int outY0 = y0 * scale_;

            // Denormalize [0.0, 1.0] -> [0, 255] with rounding & clamping (matching upstream postproc shader)
            const ncnn::Mat rPlane = cropOut.channel(0);
            const ncnn::Mat gPlane = cropOut.channel(1);
            const ncnn::Mat bPlane = cropOut.channel(2);
            for (int y = 0; y < cropOut.h; ++y) {
                const float* rRow = rPlane.row(y);
                const float* gRow = gPlane.row(y);
                const float* bRow = bPlane.row(y);
                unsigned char* dstRow = *outData +
                    (static_cast<size_t>(outY0 + y) * (*outWidth) + outX0) * 4;
                for (int x = 0; x < cropOut.w; ++x) {
                    const float rf = rRow[x] * 255.f + 0.5f;
                    const float gf = gRow[x] * 255.f + 0.5f;
                    const float bf = bRow[x] * 255.f + 0.5f;
                    dstRow[x * 4 + 0] = static_cast<unsigned char>(std::clamp(rf, 0.f, 255.f));
                    dstRow[x * 4 + 1] = static_cast<unsigned char>(std::clamp(gf, 0.f, 255.f));
                    dstRow[x * 4 + 2] = static_cast<unsigned char>(std::clamp(bf, 0.f, 255.f));
                    dstRow[x * 4 + 3] = 255;
                }
            }

            processedTiles++;
            progress_.store(static_cast<float>(processedTiles) / static_cast<float>(totalTiles));
        }
    }

    if (keepAlpha) {
        upscaleAlphaBilinear(inData, inWidth, inHeight, *outData, *outWidth, *outHeight, scale_);
    }

    progress_.store(1.0f);
    return 0;
}

int RealESRGAN::processTile(ncnn::Mat& in, ncnn::Mat& out) {
    ncnn::Extractor ex = net_.create_extractor();
    ex.input("data", in);
    const int ret = ex.extract("output", out);
    if (ret != 0) {
        LOGE("NCNN extraction failed: %d", ret);
    }
    return ret;
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
}

bool RealESRGAN::isLoaded() const {
    return loaded_;
}

bool RealESRGAN::isUsingGpu() const {
    return useGpu_;
}

} // namespace pixelforge
