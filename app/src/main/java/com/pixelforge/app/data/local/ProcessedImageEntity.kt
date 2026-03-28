package com.pixelforge.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for a processed image record.
 */
@Entity(tableName = "processed_images")
data class ProcessedImageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val inputUri: String,
    val outputUri: String?,
    val modelName: String,
    val scaleFactor: Int,
    val inputWidth: Int,
    val inputHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val fileSizeBytes: Long,
    val processingTimeMs: Long,
    val status: String, // "completed", "failed", "processing"
    val createdAt: Long // epoch millis
)
