package com.pixelforge.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTypeTest {

    @Test
    fun `ANIME_ART resolves native 2x and 4x model names`() {
        assertTrue(ModelType.ANIME_ART.supportsNative2x)
        assertEquals("realesr-animevideov3-x2", ModelType.ANIME_ART.ncnnModelBaseName(2))
        assertEquals("realesr-animevideov3-x4", ModelType.ANIME_ART.ncnnModelBaseName(4))
    }

    @Test
    fun `NCNN models disable fp16 arithmetic to avoid mobile Vulkan artifacts`() {
        assertTrue(ModelType.FAST_PHOTO.allowFp16Arithmetic)
        assertFalse(ModelType.ANIME_ART.allowFp16Arithmetic)
        assertFalse(ModelType.ULTRA_PHOTO.allowFp16Arithmetic)
    }

    @Test
    fun `fromModelName returns FAST_PHOTO for null or missing`() {
        assertEquals(ModelType.FAST_PHOTO, ModelType.fromModelName(null))
        assertEquals(ModelType.FAST_PHOTO, ModelType.fromModelName("nonexistent"))
        assertEquals(ModelType.ANIME_ART, ModelType.fromModelName("realesr-animevideov3"))
    }

    @Test
    fun `all models and scale options provide user-facing descriptions and usage guidance`() {
        ModelType.entries.forEach { model ->
            assertTrue("${model.name} subtitle blank", model.subtitle.isNotBlank())
            assertTrue("${model.name} description blank", model.description.isNotBlank())
            assertTrue("${model.name} bestFor blank", model.bestFor.isNotBlank())
        }
        assertEquals(ScaleOption.X2, ScaleOption.fromFactor(2))
        assertEquals(ScaleOption.X4, ScaleOption.fromFactor(4))
        ScaleOption.entries.forEach { scale ->
            assertTrue("${scale.name} pixelSummary blank", scale.pixelSummary.isNotBlank())
            assertTrue("${scale.name} description blank", scale.description.isNotBlank())
            assertTrue("${scale.name} bestFor blank", scale.bestFor.isNotBlank())
        }
    }
}
