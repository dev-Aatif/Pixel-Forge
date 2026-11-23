package com.pixelforge.app.domain.model

/**
 * Configuration for an upscale operation.
 */
data class UpscaleConfig(
    val modelType: ModelType = ModelType.FAST_PHOTO,
    val scaleFactor: Int = 4,
    val tileSize: Int = 0, // 0 = adaptive auto
    val useGpu: Boolean = true,
    val useNpu: Boolean = true,
    val faceEnhance: Boolean = true,
    val exportFormat: String = "PNG",
    val jpegQuality: Int = 95,
    val maxResolution: Int = 1600
)
