package com.pixelforge.app.domain.model

/**
 * Available AI model types for upscaling.
 */
enum class ModelType(val modelName: String, val displayName: String) {
    PHOTO("realesrgan-x4plus", "Photo (High Quality)")
}
