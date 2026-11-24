package com.pixelforge.app.data.model

import android.content.Context
import android.content.res.AssetManager
import com.pixelforge.app.core.constants.AppConstants
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory

class ModelManagerTest {

    private lateinit var tempFilesDir: File
    private lateinit var context: Context
    private lateinit var assets: AssetManager
    private lateinit var modelManager: ModelManager

    @Before
    fun setUp() {
        tempFilesDir = createTempDirectory("pf_model_test").toFile()
        assets = mockk()
        every { assets.open(any()) } throws FileNotFoundException("no bundled asset")
        context = mockk(relaxed = true)
        every { context.filesDir } returns tempFilesDir
        every { context.assets } returns assets
        modelManager = ModelManager(context)
    }

    @After
    fun tearDown() {
        tempFilesDir.deleteRecursively()
    }

    @Test
    fun `isNcnnModelAvailable rejects corrupt or undersized ultra files`() {
        val modelsDir = File(tempFilesDir, "models").apply { mkdirs() }
        val param = File(modelsDir, "${AppConstants.MODEL_ULTRA_PHOTO}.param")
        val bin = File(modelsDir, "${AppConstants.MODEL_ULTRA_PHOTO}.bin")

        // Wrong magic & tiny bin
        param.writeText("<html>error</html>\n" + "x".repeat(12_000))
        bin.writeBytes(ByteArray(1024))

        assertFalse(modelManager.isNcnnModelAvailable(AppConstants.MODEL_ULTRA_PHOTO))
        assertNull(modelManager.ncnnExternalParamPath(AppConstants.MODEL_ULTRA_PHOTO))
        assertNull(modelManager.ncnnExternalBinPath(AppConstants.MODEL_ULTRA_PHOTO))
    }

    @Test
    fun `isNcnnModelAvailable accepts valid magic and size for ultra pack`() {
        val modelsDir = File(tempFilesDir, "models").apply { mkdirs() }
        val param = File(modelsDir, "${AppConstants.MODEL_ULTRA_PHOTO}.param")
        val bin = File(modelsDir, "${AppConstants.MODEL_ULTRA_PHOTO}.bin")

        param.writeText("7767517\n999 1782\n" + "a".repeat(12_000))
        RandomAccessFile(bin, "rw").use { it.setLength(25_000_000L) }

        assertTrue(modelManager.isNcnnModelAvailable(AppConstants.MODEL_ULTRA_PHOTO))
        assertNotNull(modelManager.ncnnExternalParamPath(AppConstants.MODEL_ULTRA_PHOTO))
        assertNotNull(modelManager.ncnnExternalBinPath(AppConstants.MODEL_ULTRA_PHOTO))
    }

    @Test
    fun `isFaceRestoreAvailable enforces minimum 200MB threshold`() {
        val faceFile = modelManager.faceRestoreFile()
        faceFile.parentFile?.mkdirs()

        RandomAccessFile(faceFile, "rw").use { it.setLength(15_000_000L) }
        assertFalse(modelManager.isFaceRestoreAvailable())

        RandomAccessFile(faceFile, "rw").use { it.setLength(250_000_000L) }
        assertTrue(modelManager.isFaceRestoreAvailable())

        modelManager.clearDownloadedModels()
        assertEquals(0L, modelManager.getDownloadedModelsSizeBytes())
    }
}
