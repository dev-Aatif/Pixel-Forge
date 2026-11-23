#ifndef PIXELFORGE_REALESRGAN_H
#define PIXELFORGE_REALESRGAN_H

#include <atomic>
#include <string>

#include "net.h"

#include <android/asset_manager.h>

namespace pixelforge {

/**
 * Real-ESRGAN / Real-ESRGAN-AnimeVideo inference engine using NCNN.
 *
 * Features:
 * - Adaptive tile-based processing with border-replicated overlap
 * - Vulkan GPU acceleration with per-model FP16 arithmetic control
 * - AssetManager or external filesystem loading (hybrid model pack support)
 * - Alpha channel preservation (bilinear upscale when transparency is present)
 * - Atomic progress reporting and cooperative cancellation
 */
class RealESRGAN {
public:
    RealESRGAN();
    ~RealESRGAN();

    RealESRGAN(const RealESRGAN&) = delete;
    RealESRGAN& operator=(const RealESRGAN&) = delete;

    /**
     * Load model from Android assets (models/v1/<modelName>.param/.bin).
     */
    int load(AAssetManager* mgr, const std::string& modelName,
             int scale, int tileSize, bool useGpu, bool allowFp16Arithmetic);

    /**
     * Load model from filesystem paths (for on-demand downloaded models).
     */
    int loadFromFiles(const std::string& paramPath, const std::string& binPath,
                      int scale, int tileSize, bool useGpu, bool allowFp16Arithmetic);

    /**
     * Process (upscale) an RGBA_8888 pixel buffer.
     * Caller owns *outData and must free it with delete[].
     */
    int process(const unsigned char* inData, int inWidth, int inHeight,
                unsigned char** outData, int* outWidth, int* outHeight);

    float getProgress() const;
    void requestCancel();
    bool isCancelled() const;
    void release();
    bool isLoaded() const;
    bool isUsingGpu() const;

private:
    int configureNetOptions(bool useGpu, bool allowFp16Arithmetic);
    int finalizeLoad(int scale, int tileSize);
    int processTile(ncnn::Mat& in, ncnn::Mat& out);
    int autoTileSize(int inWidth, int inHeight) const;
    static bool hasTransparency(const unsigned char* inData, int width, int height);
    static void upscaleAlphaBilinear(const unsigned char* inData, int inW, int inH,
                                     unsigned char* outData, int outW, int outH, int scale);

    ncnn::Net net_;
    int scale_;
    int tileSize_;
    bool useGpu_;
    bool allowFp16Arithmetic_;
    bool loaded_;

    static constexpr int TILE_OVERLAP = 16;

    std::atomic<float> progress_{0.0f};
    std::atomic<bool> cancelled_{false};
};

} // namespace pixelforge

#endif // PIXELFORGE_REALESRGAN_H
