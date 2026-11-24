package com.pixelforge.app.engine

import android.graphics.Bitmap
import com.pixelforge.app.data.model.ModelManager
import com.pixelforge.app.domain.model.ModelType
import com.pixelforge.app.domain.model.UpscaleConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EngineSelectorTest {

    private val ortEngine: OrtNpuUpscaleEngine = mockk(relaxed = true)
    private val ncnnEngine: NcnnUpscaleEngine = mockk(relaxed = true)
    private val facePipeline: FaceRestorationPipeline = mockk(relaxed = true)
    private val postProcessor: PostProcessor = mockk()
    private val modelManager: ModelManager = mockk(relaxed = true)

    private fun createSelector() = EngineSelector(
        ortEngine = ortEngine,
        ncnnEngine = ncnnEngine,
        facePipeline = facePipeline,
        postProcessor = postProcessor,
        modelManager = modelManager
    )

    @Test
    fun `cancelling during ORT run does not fall back to NCNN`() = runTest {
        val input: Bitmap = mockk(relaxed = true)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        every { ortEngine.activeBackend } returns ComputeBackend.NPU_NNAPI
        coEvery { ortEngine.upscale(any(), any(), any()) } coAnswers {
            started.complete(Unit)
            gate.await()
            null
        }

        val selector = createSelector()
        var result: UpscaleResult? = UpscaleResult(input, ComputeBackend.CPU)
        val job = launch {
            result = selector.runPipeline(
                inputBitmap = input,
                config = UpscaleConfig(modelType = ModelType.FAST_PHOTO),
                onStageProgress = { _, _ -> }
            )
        }

        started.await()
        selector.cancelCurrent()
        gate.complete(Unit)
        job.join()

        assertNull(result)
        verify(exactly = 1) { ortEngine.cancel() }
        coVerify(exactly = 0) { ncnnEngine.upscale(any(), any(), any()) }
    }

    @Test
    fun `ORT failure without ULTRA_PHOTO does not silently switch photo to ANIME_ART`() = runTest {
        val input: Bitmap = mockk(relaxed = true)
        every { modelManager.isModelAvailable(ModelType.ULTRA_PHOTO, 4) } returns false
        coEvery { ortEngine.upscale(any(), any(), any()) } returns null

        val selector = createSelector()
        val result = selector.runPipeline(
            inputBitmap = input,
            config = UpscaleConfig(modelType = ModelType.FAST_PHOTO),
            onStageProgress = { _, _ -> }
        )

        assertNull(result)
        coVerify(exactly = 0) { ncnnEngine.upscale(any(), any(), any()) }
    }

    @Test
    fun `ORT failure falls back to NCNN ULTRA_PHOTO when installed`() = runTest {
        val input: Bitmap = mockk(relaxed = true)
        val upscaled: Bitmap = mockk(relaxed = true)
        val polished: Bitmap = mockk(relaxed = true)

        every { modelManager.isModelAvailable(ModelType.ULTRA_PHOTO, 4) } returns true
        every { ncnnEngine.activeBackend } returns ComputeBackend.GPU_VULKAN
        coEvery { ortEngine.upscale(any(), any(), any()) } returns null
        coEvery { ncnnEngine.upscale(any(), match { it.modelType == ModelType.ULTRA_PHOTO }, any()) } returns upscaled
        every { facePipeline.enhanceFaces(upscaled, input) } returns (upscaled to 1)
        every { postProcessor.polish(input, upscaled, any()) } returns polished

        val selector = createSelector()
        val result = selector.runPipeline(
            inputBitmap = input,
            config = UpscaleConfig(modelType = ModelType.FAST_PHOTO, faceEnhance = true),
            onStageProgress = { _, _ -> }
        )

        assertEquals(ComputeBackend.GPU_VULKAN, result?.backendUsed)
        assertEquals(1, result?.facesEnhanced)
        assertEquals(polished, result?.bitmap)
    }

    @Test
    fun `releaseIfIdle defers release while run is active and releases upon completion`() = runTest {
        val input: Bitmap = mockk(relaxed = true)
        val upscaled: Bitmap = mockk(relaxed = true)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        every { ortEngine.activeBackend } returns ComputeBackend.CPU
        coEvery { ortEngine.upscale(any(), any(), any()) } coAnswers {
            started.complete(Unit)
            gate.await()
            upscaled
        }
        every { postProcessor.polish(input, upscaled, any()) } returns upscaled

        val selector = createSelector()
        val job = launch {
            selector.runPipeline(
                inputBitmap = input,
                config = UpscaleConfig(modelType = ModelType.FAST_PHOTO, faceEnhance = false),
                onStageProgress = { _, _ -> }
            )
        }

        started.await()
        selector.releaseIfIdle()

        // Must not release while inference is active
        verify(exactly = 0) { ortEngine.release() }
        verify(exactly = 0) { ncnnEngine.release() }
        verify(exactly = 0) { facePipeline.release() }

        gate.complete(Unit)
        advanceUntilIdle()
        job.join()

        // Must release cleanly once the active run finishes
        verify(exactly = 1) { ortEngine.release() }
        verify(exactly = 1) { ncnnEngine.release() }
        verify(exactly = 1) { facePipeline.release() }
    }
}
