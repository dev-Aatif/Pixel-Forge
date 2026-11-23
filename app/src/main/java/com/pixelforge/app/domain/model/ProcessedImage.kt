package com.pixelforge.app.domain.model

/**
 * Domain model for a processed image record.
 * Pure Kotlin — no Android dependencies.
 */
data class ProcessedImage(
    val id: Long = 0,
    val inputUri: String,
    val outputUri: String?,
    val modelType: ModelType,
    val scaleFactor: Int,
    val inputWidth: Int,
    val inputHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val fileSizeBytes: Long,
    val processingTimeMs: Long,
    val status: String, // "completed", "failed", "processing"
    val backendUsed: String = "NCNN",
    val createdAt: Long // epoch millis
)
