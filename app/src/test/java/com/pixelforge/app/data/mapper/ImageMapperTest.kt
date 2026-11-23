package com.pixelforge.app.data.mapper

import com.pixelforge.app.data.local.ProcessedImageEntity
import com.pixelforge.app.data.mapper.ImageMapper.toDomain
import com.pixelforge.app.data.mapper.ImageMapper.toEntity
import com.pixelforge.app.domain.model.ModelType
import com.pixelforge.app.domain.model.ProcessedImage
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageMapperTest {

    @Test
    fun `toDomain maps legacy realesrgan-x4plus to ULTRA_PHOTO`() {
        val entity = ProcessedImageEntity(
            id = 42L,
            inputUri = "file:///input.jpg",
            outputUri = "content://media/external/images/media/1",
            modelName = "realesrgan-x4plus",
            scaleFactor = 4,
            inputWidth = 800,
            inputHeight = 600,
            outputWidth = 3200,
            outputHeight = 2400,
            fileSizeBytes = 1024_000L,
            processingTimeMs = 4500L,
            status = "completed",
            backendUsed = "GPU (Vulkan)",
            createdAt = 1700000000000L
        )

        val domain = entity.toDomain()

        assertEquals(42L, domain.id)
        assertEquals(ModelType.ULTRA_PHOTO, domain.modelType)
        assertEquals("GPU (Vulkan)", domain.backendUsed)
        assertEquals(3200, domain.outputWidth)
    }

    @Test
    fun `toDomain falls back to FAST_PHOTO on unknown model name`() {
        val entity = ProcessedImageEntity(
            id = 1L,
            inputUri = "file:///a.jpg",
            outputUri = null,
            modelName = "unknown-model-v99",
            scaleFactor = 2,
            inputWidth = 100,
            inputHeight = 100,
            outputWidth = 200,
            outputHeight = 200,
            fileSizeBytes = 0L,
            processingTimeMs = 0L,
            status = "failed",
            createdAt = 1L
        )

        assertEquals(ModelType.FAST_PHOTO, entity.toDomain().modelType)
    }

    @Test
    fun `toEntity round-trips domain model accurately`() {
        val domain = ProcessedImage(
            id = 7L,
            inputUri = "file:///in.png",
            outputUri = "content://out.png",
            modelType = ModelType.ANIME_ART,
            scaleFactor = 2,
            inputWidth = 500,
            inputHeight = 500,
            outputWidth = 1000,
            outputHeight = 1000,
            fileSizeBytes = 5000L,
            processingTimeMs = 1200L,
            status = "completed",
            backendUsed = "NPU (QNN)",
            createdAt = 999L
        )

        val entity = domain.toEntity()

        assertEquals("realesr-animevideov3", entity.modelName)
        assertEquals("NPU (QNN)", entity.backendUsed)
        assertEquals(domain, entity.toDomain())
    }
}
