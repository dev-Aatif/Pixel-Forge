#include "realesrgan.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <mutex>
#include <vector>

#include <android/log.h>
#include "cpu.h"
#include "gpu.h"

#define TAG "PixelForge-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace pixelforge {

namespace {

void ensureVulkanInstanceInitialized() {
#if NCNN_VULKAN
    static std::once_flag gpuInitOnce;
    std::call_once(gpuInitOnce, []() {
        const int ret = ncnn::create_gpu_instance();
        LOGI("ncnn::create_gpu_instance() ret=%d, gpu_count=%d", ret, ncnn::get_gpu_count());
    });
#endif
}

struct CancelResetGuard {
    std::atomic<bool>& flag;
    ~CancelResetGuard() { flag.store(false); }
};

void unpremultiplyTileRgba(const unsigned char* inData, int inW, int inH, int inStrideBytes,
                           int px0, int py0, int tileW, int tileH,
                           std::vector<unsigned char>& dstRgb) {
    dstRgb.resize(static_cast<size_t>(tileW) * tileH * 3);
    std::vector<unsigned char> alphaTile(static_cast<size_t>(tileW) * tileH);

    for (int y = 0; y < tileH; ++y) {
        const unsigned char* srcRow = inData + static_cast<size_t>(py0 + y) * inStrideBytes + static_cast<size_t>(px0) * 4;
        for (int x = 0; x < tileW; ++x) {
            const unsigned char a = srcRow[x * 4 + 3];
            const size_t idx = static_cast<size_t>(y) * tileW + x;
            alphaTile[idx] = a;
            if (a == 255) {
                dstRgb[idx * 3 + 0] = srcRow[x * 4 + 0];
                dstRgb[idx * 3 + 1] = srcRow[x * 4 + 1];
                dstRgb[idx * 3 + 2] = srcRow[x * 4 + 2];
            } else if (a > 0) {
                dstRgb[idx * 3 + 0] = static_cast<unsigned char>(std::min(255, (srcRow[x * 4 + 0] * 255 + (a / 2)) / a));
                dstRgb[idx * 3 + 1] = static_cast<unsigned char>(std::min(255, (srcRow[x * 4 + 1] * 255 + (a / 2)) / a));
                dstRgb[idx * 3 + 2] = static_cast<unsigned char>(std::min(255, (srcRow[x * 4 + 2] * 255 + (a / 2)) / a));
            } else {
                dstRgb[idx * 3 + 0] = 0;
                dstRgb[idx * 3 + 1] = 0;
                dstRgb[idx * 3 + 2] = 0;
            }
        }
    }

    // Bleed nearest non-transparent pixel colors into 1px transparent fringes to prevent dark edge halos.
    std::vector<unsigned char> bled = dstRgb;
    for (int y = 0; y < tileH; ++y) {
        for (int x = 0; x < tileW; ++x) {
            const size_t idx = static_cast<size_t>(y) * tileW + x;
            if (alphaTile[idx] > 0) continue;
            int sumR = 0, sumG = 0, sumB = 0, count = 0;
            for (int dy = -1; dy <= 1; ++dy) {
                const int ny = y + dy;
                if (ny < 0 || ny >= tileH) continue;
                for (int dx = -1; dx <= 1; ++dx) {
                    const int nx = x + dx;
                    if (nx < 0 || nx >= tileW) continue;
                    const size_t nIdx = static_cast<size_t>(ny) * tileW + nx;
                    if (alphaTile[nIdx] > 0) {
                        sumR += dstRgb[nIdx * 3 + 0];
                        sumG += dstRgb[nIdx * 3 + 1];
                        sumB += dstRgb[nIdx * 3 + 2];
                        count++;
                    }
                }
            }
            if (count > 0) {
                bled[idx * 3 + 0] = static_cast<unsigned char>(sumR / count);
                bled[idx * 3 + 1] = static_cast<unsigned char>(sumG / count);
                bled[idx * 3 + 2] = static_cast<unsigned char>(sumB / count);
            }
        }
    }
    dstRgb.swap(bled);
}

} // namespace

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
    if (useGpu) {
        ensureVulkanInstanceInitialized();
        gpuAvailable = (ncnn::get_gpu_count() > 0);
    }
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

bool RealESRGAN::hasTransparency(const unsigned char* inData, int width, int height, int strideBytes) {
    for (int y = 0; y < height; ++y) {
        const unsigned char* row = inData + static_cast<size_t>(y) * strideBytes;
        for (int x = 0; x < width; ++x) {
            if (row[x * 4 + 3] < 252) {
                return true;
            }
        }
    }
    return false;
}

void RealESRGAN::upscaleAlphaBilinear(const unsigned char* inData, int inW, int inH, int inStrideBytes,
                                      unsigned char* outData, int outW, int outH, int scale) {
    const float invScale = 1.0f / static_cast<float>(scale);
    for (int y = 0; y < outH; ++y) {
        float sy = (static_cast<float>(y) + 0.5f) * invScale - 0.5f;
        sy = std::clamp(sy, 0.0f, static_cast<float>(inH - 1));
        int y0 = static_cast<int>(std::floor(sy));
        int y1 = std::min(y0 + 1, inH - 1);
        float fy = sy - y0;

        const unsigned char* row0 = inData + static_cast<size_t>(y0) * inStrideBytes;
        const unsigned char* row1 = inData + static_cast<size_t>(y1) * inStrideBytes;
        unsigned char* dstRow = outData + static_cast<size_t>(y) * outW * 4;

        for (int x = 0; x < outW; ++x) {
            float sx = (static_cast<float>(x) + 0.5f) * invScale - 0.5f;
            sx = std::clamp(sx, 0.0f, static_cast<float>(inW - 1));
            int x0 = static_cast<int>(std::floor(sx));
            int x1 = std::min(x0 + 1, inW - 1);
            float fx = sx - x0;

            float a00 = row0[x0 * 4 + 3];
            float a10 = row0[x1 * 4 + 3];
            float a01 = row1[x0 * 4 + 3];
            float a11 = row1[x1 * 4 + 3];

            float top = a00 + (a10 - a00) * fx;
            float bot = a01 + (a11 - a01) * fx;
            float val = top + (bot - top) * fy;
            const unsigned char alpha = static_cast<unsigned char>(std::clamp(std::lround(val), 0L, 255L));

            // Re-premultiply straight RGB output to match Android ARGB_8888 premultiplied convention
            unsigned char* px = dstRow + static_cast<size_t>(x) * 4;
            if (alpha == 0) {
                px[0] = 0;
                px[1] = 0;
                px[2] = 0;
                px[3] = 0;
            } else if (alpha < 255) {
                px[0] = static_cast<unsigned char>((px[0] * alpha + 127) / 255);
                px[1] = static_cast<unsigned char>((px[1] * alpha + 127) / 255);
                px[2] = static_cast<unsigned char>((px[2] * alpha + 127) / 255);
                px[3] = alpha;
            } else {
                px[3] = 255;
            }
        }
    }
}

int RealESRGAN::process(const unsigned char* inData, int inWidth, int inHeight, int inStrideBytes,
                        unsigned char** outData, int* outWidth, int* outHeight) {
    if (!loaded_ || !inData || inWidth <= 0 || inHeight <= 0) {
        LOGE("Model not loaded or invalid input dimensions (%dx%d)", inWidth, inHeight);
        return -1;
    }

    CancelResetGuard cancelGuard{cancelled_};
    if (cancelled_.load()) {
        return -3;
    }
    progress_.store(0.0f);

    const int stride = (inStrideBytes >= inWidth * 4) ? inStrideBytes : (inWidth * 4);
    const int activeTileSize = (tileSize_ > 0) ? tileSize_ : autoTileSize(inWidth, inHeight);
    *outWidth  = inWidth * scale_;
    *outHeight = inHeight * scale_;

    const size_t outSize = static_cast<size_t>(*outWidth) * (*outHeight) * 4;
    *outData = new (std::nothrow) unsigned char[outSize];
    if (!*outData) {
        LOGE("Failed to allocate output buffer (%zu bytes)", outSize);
        return -2;
    }

    const bool keepAlpha = hasTransparency(inData, inWidth, inHeight, stride);

    const int tilesX = (inWidth  + activeTileSize - 1) / activeTileSize;
    const int tilesY = (inHeight + activeTileSize - 1) / activeTileSize;
    const int totalTiles = tilesX * tilesY;
    int processedTiles = 0;
    std::vector<unsigned char> unpremulRgb;

    LOGI("Processing %dx%d -> %dx%d (tile=%d, overlap=%d, grid=%dx%d, alpha=%d, gpu=%d)",
         inWidth, inHeight, *outWidth, *outHeight, activeTileSize, TILE_OVERLAP,
         tilesX, tilesY, keepAlpha ? 1 : 0, useGpu_ ? 1 : 0);

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

            ncnn::Mat tileIn;
            if (keepAlpha) {
                unpremultiplyTileRgba(inData, inWidth, inHeight, stride, px0, py0, tileW, tileH, unpremulRgb);
                tileIn = ncnn::Mat::from_pixels(
                    unpremulRgb.data(), ncnn::Mat::PIXEL_RGB, tileW, tileH
                );
            } else {
                tileIn = ncnn::Mat::from_pixels(
                    inData + static_cast<size_t>(py0) * stride + static_cast<size_t>(px0) * 4,
                    ncnn::Mat::PIXEL_RGBA2RGB, tileW, tileH, stride
                );
            }

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
            const int outX0 = x0 * scale_;
            const int outY0 = y0 * scale_;
            const int blendMargin = std::min(cropPad, 2 * scale_);
            const int blendLeft = (tx > 0) ? std::min(blendMargin, outX0) : 0;
            const int blendTop = (ty > 0) ? std::min(blendMargin, outY0) : 0;

            ncnn::Mat cropOut;
            ncnn::copy_cut_border(
                tileOut, cropOut,
                cropPad - blendTop, cropPad,
                cropPad - blendLeft, cropPad
            );

            const int expectedW = (x1 - x0) * scale_ + blendLeft;
            const int expectedH = (y1 - y0) * scale_ + blendTop;
            if (cropOut.empty() || cropOut.c < 3 || cropOut.w != expectedW || cropOut.h != expectedH) {
                LOGE("Unexpected cropOut shape: %dx%d c=%d (expected %dx%d)",
                     cropOut.w, cropOut.h, cropOut.c, expectedW, expectedH);
                delete[] *outData;
                *outData = nullptr;
                return -5;
            }

            const int dstX0 = outX0 - blendLeft;
            const int dstY0 = outY0 - blendTop;
            constexpr float kPi = 3.14159265f;

            // Denormalize [0.0, 1.0] -> [0, 255] with cosine overlap feathering along adjacent tile seams
            const ncnn::Mat rPlane = cropOut.channel(0);
            const ncnn::Mat gPlane = cropOut.channel(1);
            const ncnn::Mat bPlane = cropOut.channel(2);
            for (int y = 0; y < cropOut.h; ++y) {
                const float* rRow = rPlane.row(y);
                const float* gRow = gPlane.row(y);
                const float* bRow = bPlane.row(y);
                unsigned char* dstRow = *outData +
                    (static_cast<size_t>(dstY0 + y) * (*outWidth) + dstX0) * 4;
                const float wy = (y < blendTop)
                    ? 0.5f * (1.0f - std::cos(kPi * (static_cast<float>(y) + 0.5f) / static_cast<float>(blendTop)))
                    : 1.0f;
                for (int x = 0; x < cropOut.w; ++x) {
                    const float rf = std::clamp(rRow[x] * 255.f + 0.5f, 0.f, 255.f);
                    const float gf = std::clamp(gRow[x] * 255.f + 0.5f, 0.f, 255.f);
                    const float bf = std::clamp(bRow[x] * 255.f + 0.5f, 0.f, 255.f);
                    const float wx = (x < blendLeft)
                        ? 0.5f * (1.0f - std::cos(kPi * (static_cast<float>(x) + 0.5f) / static_cast<float>(blendLeft)))
                        : 1.0f;
                    const float w = wx * wy;
                    if (w >= 0.999f) {
                        dstRow[x * 4 + 0] = static_cast<unsigned char>(rf);
                        dstRow[x * 4 + 1] = static_cast<unsigned char>(gf);
                        dstRow[x * 4 + 2] = static_cast<unsigned char>(bf);
                    } else {
                        const float invW = 1.0f - w;
                        dstRow[x * 4 + 0] = static_cast<unsigned char>(std::clamp(dstRow[x * 4 + 0] * invW + rf * w, 0.f, 255.f));
                        dstRow[x * 4 + 1] = static_cast<unsigned char>(std::clamp(dstRow[x * 4 + 1] * invW + gf * w, 0.f, 255.f));
                        dstRow[x * 4 + 2] = static_cast<unsigned char>(std::clamp(dstRow[x * 4 + 2] * invW + bf * w, 0.f, 255.f));
                    }
                    dstRow[x * 4 + 3] = 255;
                }
            }

            processedTiles++;
            progress_.store(static_cast<float>(processedTiles) / static_cast<float>(totalTiles));
        }
    }

    if (keepAlpha) {
        upscaleAlphaBilinear(inData, inWidth, inHeight, stride, *outData, *outWidth, *outHeight, scale_);
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
