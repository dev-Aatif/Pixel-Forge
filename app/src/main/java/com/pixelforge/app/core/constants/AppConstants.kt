package com.pixelforge.app.core.constants

/**
 * Application-wide constants.
 */
object AppConstants {
    const val APP_NAME = "PixelForge"
    const val DATABASE_NAME = "pixelforge_db"
    const val DATABASE_VERSION = 1

    // Processing defaults
    const val DEFAULT_SCALE_FACTOR = 4
    const val DEFAULT_TILE_SIZE = 0 // 0 = auto
    const val DEFAULT_USE_GPU = true

    // Model names (matching asset filenames without extension)
    const val MODEL_PHOTO = "realesrgan-x4plus"

    // Image limits
    const val MAX_INPUT_DIMENSION = 4096
    const val MAX_OUTPUT_DIMENSION = 16384

    // Export
    const val DEFAULT_JPEG_QUALITY = 95
    const val DEFAULT_PNG_COMPRESSION = 9

    // WorkManager
    const val UPSCALE_WORK_NAME = "upscale_work"
    const val UPSCALE_WORK_TAG = "upscale"
    const val KEY_INPUT_URI = "input_uri"
    const val KEY_MODEL_NAME = "model_name"
    const val KEY_SCALE_FACTOR = "scale_factor"
    const val KEY_TILE_SIZE = "tile_size"
    const val KEY_USE_GPU = "use_gpu"
    const val KEY_OUTPUT_URI = "output_uri"
    const val KEY_PROGRESS = "progress"
    const val KEY_ERROR_MESSAGE = "error_message"
}
