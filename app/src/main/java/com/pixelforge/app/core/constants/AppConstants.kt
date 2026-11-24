package com.pixelforge.app.core.constants

/**
 * Application-wide constants.
 */
object AppConstants {
    const val APP_NAME = "PixelForge"
    const val DATABASE_NAME = "pixelforge_db"
    const val DATABASE_VERSION = 2

    // Processing defaults
    const val DEFAULT_SCALE_FACTOR = 4
    const val DEFAULT_TILE_SIZE = 0 // 0 = adaptive auto
    const val DEFAULT_USE_GPU = true
    const val DEFAULT_USE_NPU = true
    const val DEFAULT_FACE_ENHANCE = true
    const val DEFAULT_MAX_RESOLUTION = 1600
    const val DEFAULT_EXPORT_FORMAT = "PNG"

    // Model identifiers
    const val MODEL_FAST_PHOTO = "realesr-general-x4v3"
    const val MODEL_ANIME_ART = "realesr-animevideov3"
    const val MODEL_ULTRA_PHOTO = "realesrgan-x4plus"
    const val MODEL_FACE_RESTORE = "gfpgan-v1.4"

    // Optional model download URLs (on-demand download for heavy models)
    const val URL_ULTRA_PHOTO_PARAM =
        "https://huggingface.co/edgetools/realesrgan/resolve/main/realesrgan-x4plus.param"
    const val URL_ULTRA_PHOTO_BIN =
        "https://huggingface.co/edgetools/realesrgan/resolve/main/realesrgan-x4plus.bin"
    const val URL_FACE_RESTORE_ONNX =
        "https://huggingface.co/OwlMaster/AllFilesRope/resolve/main/GFPGANv1.4.onnx"

    // Image limits
    const val MAX_INPUT_DIMENSION = 4096
    const val MAX_OUTPUT_DIMENSION = 16384

    // Export
    const val DEFAULT_JPEG_QUALITY = 95

    // WorkManager & Notifications
    const val UPSCALE_WORK_NAME = "upscale_work"
    const val UPSCALE_WORK_TAG = "upscale"
    const val UPSCALE_NOTIFICATION_CHANNEL_ID = "upscale_progress"
    const val UPSCALE_NOTIFICATION_ID = 1001
    const val UPDATE_NOTIFICATION_CHANNEL_ID = "app_updates"
    const val UPDATE_NOTIFICATION_ID = 1002
    const val EXTRA_SHOW_UPDATE_DIALOG = "extra_show_update_dialog"

    // App Updater & Project Links
    const val UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    const val DEVELOPER_NAME = "Aatif"
    const val DEVELOPER_HANDLE = "dev-Aatif"
    const val DEVELOPER_EMAIL = "Aatiff.P@proton.me"
    const val URL_GITHUB_PROFILE = "https://github.com/dev-Aatif"
    const val URL_GITHUB_REPO = "https://github.com/dev-Aatif/Pixel-Forge"
    const val URL_GITHUB_RELEASES = "https://github.com/dev-Aatif/Pixel-Forge/releases"
    const val URL_GITHUB_RELEASES_API = "https://api.github.com/repos/dev-Aatif/Pixel-Forge/releases/latest"
    const val URL_GITHUB_ISSUES = "https://github.com/dev-Aatif/Pixel-Forge/issues"
    const val URL_GITHUB_LICENSE = "https://github.com/dev-Aatif/Pixel-Forge/blob/main/LICENSE"

    const val KEY_INPUT_URI = "input_uri"
    const val KEY_MODEL_NAME = "model_name"
    const val KEY_SCALE_FACTOR = "scale_factor"
    const val KEY_TILE_SIZE = "tile_size"
    const val KEY_USE_GPU = "use_gpu"
    const val KEY_USE_NPU = "use_npu"
    const val KEY_FACE_ENHANCE = "face_enhance"
    const val KEY_EXPORT_FORMAT = "export_format"
    const val KEY_JPEG_QUALITY = "jpeg_quality"
    const val KEY_MAX_RESOLUTION = "max_resolution"
    const val KEY_OUTPUT_URI = "output_uri"
    const val KEY_RECORD_ID = "record_id"
    const val KEY_BACKEND_USED = "backend_used"
    const val KEY_PROGRESS = "progress"
    const val KEY_STATUS_MESSAGE = "status_message"
    const val KEY_ERROR_MESSAGE = "error_message"
}
