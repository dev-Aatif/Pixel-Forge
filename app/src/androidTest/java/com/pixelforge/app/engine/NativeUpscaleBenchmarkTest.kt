package com.pixelforge.app.engine

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pixelforge.app.bridge.NativeLoader
import com.pixelforge.app.bridge.RealesrganBridge
import com.pixelforge.app.data.model.ModelManager
import com.pixelforge.app.domain.model.ModelType
import com.pixelforge.app.domain.model.UpscaleConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device instrumentation smoke + pixel-accuracy + latency benchmark test.
 * Run with: ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class NativeUpscaleBenchmarkTest {

    @Test
    fun fastPhotoOrtEngine_upscalesTestBitmapWithinTargetBudget() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ortEngine = OrtNpuUpscaleEngine(context)

        val input = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(80, 140, 200))
        }

        val startMs = System.currentTimeMillis()
        val output = ortEngine.upscale(
            bitmap = input,
            config = UpscaleConfig(modelType = ModelType.FAST_PHOTO, scaleFactor = 4, useNpu = true),
            onProgress = {}
        )
        val elapsedMs = System.currentTimeMillis() - startMs

        assertNotNull("Fast Photo ORT output should not be null", output)
        assertEquals(1024, output!!.width)
        assertEquals(1024, output.height)
        val center = output.getPixel(512, 512)
        assertTrue("Output should not be black", Color.red(center) > 40 && Color.blue(center) > 120)
        assertTrue("256x256 tile should finish well under 10s, took ${elapsedMs}ms", elapsedMs < 10_000L)

        output.recycle()
        input.recycle()
        ortEngine.release()
    }

    @Test
    fun animeArtNcnnEngine_native2xPreservesColorAndAlpha() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Native library should load", NativeLoader.load())

        val engine = NcnnUpscaleEngine(
            context = context,
            bridge = RealesrganBridge(),
            modelManager = ModelManager(context)
        )

        val input = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.argb(128, 220, 120, 60))
        }

        val out2x = engine.upscale(
            bitmap = input,
            config = UpscaleConfig(modelType = ModelType.ANIME_ART, scaleFactor = 2, useGpu = true),
            onProgress = {}
        )
        assertNotNull("Anime 2x output should not be null", out2x)
        assertEquals(256, out2x!!.width)
        assertEquals(256, out2x.height)

        val hasVulkanCompute = context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE) ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        if (hasVulkanCompute) {
            assertEquals(
                "NCNN should use Vulkan GPU when Vulkan hardware is available",
                ComputeBackend.GPU_VULKAN,
                engine.activeBackend
            )
        }

        val center = out2x.getPixel(128, 128)
        assertTrue(
            "Anime 2x output must not be black or glitched: r=${Color.red(center)} g=${Color.green(center)} b=${Color.blue(center)}",
            Color.red(center) in 160..255 && Color.green(center) in 70..170 && Color.blue(center) in 20..110
        )
        assertTrue(
            "Alpha should be preserved (~128), got ${Color.alpha(center)}",
            Color.alpha(center) in 115..140
        )

        out2x.recycle()
        input.recycle()
        engine.release()
    }

    @Test
    fun ultraPhotoNcnnEngine_ifAvailable_preservesColorWithoutGlitch() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelManager = ModelManager(context)
        if (!modelManager.isModelAvailable(ModelType.ULTRA_PHOTO, 4)) {
            return@runBlocking
        }
        assertTrue("Native library should load", NativeLoader.load())

        val engine = NcnnUpscaleEngine(
            context = context,
            bridge = RealesrganBridge(),
            modelManager = modelManager
        )

        val input = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(90, 150, 210))
        }

        val out4x = engine.upscale(
            bitmap = input,
            config = UpscaleConfig(modelType = ModelType.ULTRA_PHOTO, scaleFactor = 4, useGpu = true),
            onProgress = {}
        )
        assertNotNull("Ultra 4x output should not be null", out4x)
        assertEquals(384, out4x!!.width)
        assertEquals(384, out4x.height)

        val center = out4x.getPixel(192, 192)
        assertTrue(
            "Ultra 4x output should match input color cleanly: r=${Color.red(center)} g=${Color.green(center)} b=${Color.blue(center)}",
            Color.red(center) in 50..130 && Color.green(center) in 110..190 && Color.blue(center) in 170..250
        )

        out4x.recycle()
        input.recycle()
        engine.release()
    }

    @Test
    fun fastPhotoOrtEngine_multiTileAnd2xDownsample_completesWithoutSeamArtifacts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Native library should load", NativeLoader.load())
        val ortEngine = OrtNpuUpscaleEngine(context)

        // 320x280 forces 2x2 tiles with 256 tileSize, exercising border padding & core cropping
        val input = Bitmap.createBitmap(320, 280, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(100, 150, 200))
        }

        val out2x = ortEngine.upscale(
            bitmap = input,
            config = UpscaleConfig(modelType = ModelType.FAST_PHOTO, scaleFactor = 2, useNpu = true),
            onProgress = {}
        )
        assertNotNull("ORT 2x multi-tile output should not be null", out2x)
        assertEquals(640, out2x!!.width)
        assertEquals(560, out2x.height)

        val seamPixel = out2x.getPixel(512, 512)
        assertTrue(
            "Boundary tile pixel should preserve color cleanly",
            Color.red(seamPixel) in 60..140 && Color.green(seamPixel) in 110..190 && Color.blue(seamPixel) in 160..240
        )

        out2x.recycle()
        input.recycle()
        ortEngine.release()
    }

    @Test
    fun postProcessor_nativeInPlacePolish_preservesLargeOutputWithoutOom() {
        assertTrue("Native library should load", NativeLoader.load())
        val source = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(120, 140, 160))
        }
        val upscaled = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(100, 120, 140))
        }

        val polished = PostProcessor().polish(source, upscaled, sharpenAmount = 0.11f)
        assertEquals(1024, polished.width)
        assertEquals(1024, polished.height)

        val center = polished.getPixel(512, 512)
        assertTrue("Color should shift slightly toward source", Color.red(center) > 100)

        source.recycle()
        polished.recycle()
    }
}
