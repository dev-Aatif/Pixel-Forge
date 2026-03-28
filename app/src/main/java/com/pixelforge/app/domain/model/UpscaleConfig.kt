package com.pixelforge.app.domain.model

/**
 * Configuration for an upscale operation.
 */
data class UpscaleConfig(
    val modelType: ModelType = ModelType.PHOTO,
    val scaleFactor: Int = 4,
    val tileSize: Int = 0, // 0 = auto
    val useGpu: Boolean = true
)
