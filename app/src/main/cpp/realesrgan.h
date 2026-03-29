#ifndef PIXELFORGE_REALESRGAN_H
#define PIXELFORGE_REALESRGAN_H

#include <string>
#include <vector>
#include <atomic>

// NCNN
#include "net.h"

#include <android/asset_manager.h>

namespace pixelforge {

/**
 * Real-ESRGAN inference engine using NCNN.
 *
 * Supports:
 * - Tile-based processing for large images
 * - Vulkan GPU acceleration (optional, runtime toggle)
 * - FP16 precision for performance
 * - Progress reporting via atomic float
 */
class RealESRGAN {
public:
    RealESRGAN();
    ~RealESRGAN();

    // Non-copyable
    RealESRGAN(const RealESRGAN&) = delete;
    RealESRGAN& operator=(const RealESRGAN&) = delete;

    /**
     * Load model from Android assets.
     *
     * @param mgr       Android AAssetManager
     * @param modelName Model name without extension (e.g., "realesrgan-x4plus")
     * @param scale     Upscale factor (2 or 4)
     * @param tileSize  Tile size for processing (0 = auto, 32-400)
     * @param useGpu    Whether to use Vulkan GPU acceleration
     * @return 0 on success, non-zero on failure
     */
    int load(AAssetManager* mgr, const std::string& modelName,
             int scale, int tileSize, bool useGpu);

    /**
     * Process an image (upscale).
     *
     * @param inData   Input pixel data (RGBA, 4 bytes per pixel)
     * @param inWidth  Input image width
     * @param inHeight Input image height
     * @param outData  Output pixel data (allocated internally, caller must free)
     * @param outWidth Output image width (set by this method)
     * @param outHeight Output image height (set by this method)
     * @return 0 on success, non-zero on failure
     */
    int process(const unsigned char* inData, int inWidth, int inHeight,
                unsigned char** outData, int* outWidth, int* outHeight);

    /**
     * Get current processing progress [0.0, 1.0].
     */
    float getProgress() const;

    /**
     * Request cancellation of current processing.
     */
    void requestCancel();

    /**
     * Check if processing was cancelled.
     */
    bool isCancelled() const;

    /**
     * Release all resources.
     */
    void release();

    /**
     * Check if a model is loaded.
     */
    bool isLoaded() const;

private:
    /**
     * Process a single tile through the network.
     */
    int processTile(const ncnn::Mat& in, ncnn::Mat& out);

    /**
     * Determine optimal tile size based on GPU memory.
     */
    int autoTileSize() const;

    ncnn::Net net_;
    int scale_;
    int tileSize_;
    bool useGpu_;
    bool loaded_;

    // Pre/post processing params
    float prepadding_;
    static constexpr int TILE_OVERLAP = 32;

    // Progress tracking
    std::atomic<float> progress_{0.0f};
    std::atomic<bool> cancelled_{false};
};

} // namespace pixelforge

#endif // PIXELFORGE_REALESRGAN_H
