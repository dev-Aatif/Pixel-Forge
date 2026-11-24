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

/**
 * Plain-English guide describing each scale factor: what it does, when and why to use it.
 */
enum class ScaleOption(
    val factor: Int,
    val title: String,
    val pixelSummary: String,
    val description: String,
    val bestFor: String
) {
    X2(
        factor = 2,
        title = "2× Enlarge",
        pixelSummary = "2× width & height (4× pixels) • Faster",
        description = "Doubles image dimensions for subtle cleanup and sharper detail with faster exports and smaller files.",
        bestFor = "When to use: Already decent photos, minor sharpening, or saving time and storage."
    ),
    X4(
        factor = 4,
        title = "4× Enlarge",
        pixelSummary = "4× width & height (16× pixels) • Max detail",
        description = "Quadruples dimensions to rebuild missing detail in tiny or low-res images. Takes more time and storage.",
        bestFor = "When to use: Small crops, thumbnails, old low-res photos, anime/text, or large prints."
    );

    companion object {
        fun fromFactor(factor: Int): ScaleOption = if (factor == 2) X2 else X4
    }
}
