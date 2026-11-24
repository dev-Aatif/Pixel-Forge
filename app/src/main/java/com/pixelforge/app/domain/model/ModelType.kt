package com.pixelforge.app.domain.model

import com.pixelforge.app.core.constants.AppConstants

/**
 * Available AI model types for upscaling.
 */
enum class ModelType(
    val modelName: String,
    val displayName: String,
    val subtitle: String,
    val description: String,
    val bestFor: String,
    val nativeScale: Int,
    val supportsNative2x: Boolean,
    val allowFp16Arithmetic: Boolean,
    val onnxAssetPath: String?,
    val isOptionalDownload: Boolean
) {
    FAST_PHOTO(
        modelName = AppConstants.MODEL_FAST_PHOTO,
        displayName = "Fast Photo",
        subtitle = "Quick everyday photo upscaler (~2–5s)",
        description = "Speed-tuned AI model running on NPU/GPU to clean compression noise and sharpen soft details.",
        bestFor = "When to use: Everyday camera shots, social downloads, portraits, and fast exports.",
        nativeScale = 4,
        supportsNative2x = false,
        allowFp16Arithmetic = true,
        onnxAssetPath = "models/onnx/realesr-general-x4v3.onnx",
        isOptionalDownload = false
    ),
    ANIME_ART(
        modelName = AppConstants.MODEL_ANIME_ART,
        displayName = "Anime & Text",
        subtitle = "Crisp lines, flat colors & clean text",
        description = "Edge-preserving model tuned for sharp outlines, readable text, and transparent PNGs without grainy texture.",
        bestFor = "When to use: Anime, illustrations, screenshots, documents/text, logos, and stickers.",
        nativeScale = 4,
        supportsNative2x = true,
        allowFp16Arithmetic = false,
        onnxAssetPath = null,
        isOptionalDownload = false
    ),
    ULTRA_PHOTO(
        modelName = AppConstants.MODEL_ULTRA_PHOTO,
        displayName = "Ultra Photo",
        subtitle = "Maximum photo texture & detail (~32 MB download)",
        description = "Deeper restoration model that rebuilds fine natural textures like skin, hair, fabric, and foliage. Slower than Fast Photo.",
        bestFor = "When to use: Old, blurry, heavily compressed, or cropped photos and high-detail prints.",
        nativeScale = 4,
        supportsNative2x = false,
        allowFp16Arithmetic = false,
        onnxAssetPath = null,
        isOptionalDownload = true
    );

    /**
     * Resolve the NCNN asset/file base name for a given scale factor.
     */
    fun ncnnModelBaseName(scale: Int): String = when (this) {
        ANIME_ART -> if (scale == 2) "realesr-animevideov3-x2" else "realesr-animevideov3-x4"
        FAST_PHOTO -> AppConstants.MODEL_FAST_PHOTO
        ULTRA_PHOTO -> AppConstants.MODEL_ULTRA_PHOTO
    }

    companion object {
        fun fromModelName(name: String?): ModelType {
            return entries.find { it.modelName == name } ?: FAST_PHOTO
        }
    }
}
