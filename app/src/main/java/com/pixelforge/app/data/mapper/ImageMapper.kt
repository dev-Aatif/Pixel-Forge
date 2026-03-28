package com.pixelforge.app.data.mapper

import com.pixelforge.app.data.local.ProcessedImageEntity
import com.pixelforge.app.domain.model.ModelType
import com.pixelforge.app.domain.model.ProcessedImage

/**
 * Mappers between data layer entities and domain models.
 * Keeps data layer implementation details out of the domain.
 */
object ImageMapper {

    fun ProcessedImageEntity.toDomain(): ProcessedImage {
        return ProcessedImage(
            id = id,
            inputUri = inputUri,
            outputUri = outputUri,
            modelType = ModelType.entries.find { it.modelName == modelName } ?: ModelType.PHOTO,
            scaleFactor = scaleFactor,
            inputWidth = inputWidth,
            inputHeight = inputHeight,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            fileSizeBytes = fileSizeBytes,
            processingTimeMs = processingTimeMs,
            status = status,
            createdAt = createdAt
        )
    }

    fun ProcessedImage.toEntity(): ProcessedImageEntity {
        return ProcessedImageEntity(
            id = id,
            inputUri = inputUri,
            outputUri = outputUri,
            modelName = modelType.modelName,
            scaleFactor = scaleFactor,
            inputWidth = inputWidth,
            inputHeight = inputHeight,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            fileSizeBytes = fileSizeBytes,
            processingTimeMs = processingTimeMs,
            status = status,
            createdAt = createdAt
        )
    }
}
