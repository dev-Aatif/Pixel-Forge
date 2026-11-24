package com.pixelforge.app.presentation.screen.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import com.pixelforge.app.core.extensions.copyUriToInternalStorage
import com.pixelforge.app.core.extensions.decodePreviewBitmap
import com.pixelforge.app.core.extensions.deleteAppOwnedFile
import com.pixelforge.app.core.extensions.getFileSize
import com.pixelforge.app.core.extensions.getImageDimensions
import com.pixelforge.app.core.utils.ImageAnalyzer
import com.pixelforge.app.core.utils.ImageProfile
import com.pixelforge.app.data.model.ModelManager
import com.pixelforge.app.domain.model.ModelType
import com.pixelforge.app.domain.repository.SettingsRepository
import com.pixelforge.app.engine.FaceRestorationPipeline
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var facePipeline: FaceRestorationPipeline
    private lateinit var modelManager: ModelManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        every { settingsRepository.getDefaultModelType() } returns flowOf(ModelType.FAST_PHOTO)
        every { settingsRepository.getDefaultScaleFactor() } returns flowOf(4)
        every { settingsRepository.getUseGpu() } returns flowOf(true)
        every { settingsRepository.getUseNpu() } returns flowOf(true)
        every { settingsRepository.getFaceEnhanceDefault() } returns flowOf(true)
        every { settingsRepository.getDefaultExportFormat() } returns flowOf("PNG")
        every { settingsRepository.getJpegQuality() } returns flowOf(95)
        every { settingsRepository.getMaxResolution() } returns flowOf(1600)

        facePipeline = mockk(relaxed = true)
        every { facePipeline.detectFaceCount(any()) } returns 0

        modelManager = mockk(relaxed = true)
        every { modelManager.isModelAvailable(ModelType.ULTRA_PHOTO, 4) } returns false

        mockkStatic(Uri::class)
        mockkStatic("com.pixelforge.app.core.extensions.ImageExtensionsKt")
        mockkObject(ImageAnalyzer)
    }

    @After
    fun tearDown() {
        unmockkObject(ImageAnalyzer)
        unmockkStatic("com.pixelforge.app.core.extensions.ImageExtensionsKt")
        unmockkStatic(Uri::class)
        Dispatchers.resetMain()
    }

    @Test
    fun `unreadable image sets loadError and keeps isReady false`() = runTest {
        val rawUri = mockk<Uri>()
        every { Uri.parse("content://bad/image") } returns rawUri
        every { app.copyUriToInternalStorage(rawUri, any()) } returns null
        every { app.getImageDimensions(rawUri) } returns null
        every { app.getFileSize(rawUri) } returns 0L
        every { app.decodePreviewBitmap(rawUri, any()) } returns null

        val vm = EditorViewModel(app, settingsRepository, facePipeline, modelManager).apply {
            ioDispatcher = testDispatcher
        }
        vm.loadImage("content://bad/image")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isReady)
        assertNotNull(vm.uiState.value.loadError)
    }

    @Test
    fun `large image flags downscale, updates scale projections, surfaces download errors, and cleans staged file on clear`() = runTest {
        val rawUri = mockk<Uri>()
        val stagedUri = mockk<Uri>()
        val previewBmp = mockk<Bitmap>(relaxed = true)

        every { rawUri.toString() } returns "content://picker/1"
        every { stagedUri.toString() } returns "file:///data/user/0/com.pixelforge.app/files/uploads/IMG_1.jpg"
        every { Uri.parse("content://picker/1") } returns rawUri
        every { app.copyUriToInternalStorage(rawUri, any()) } returns stagedUri
        every { app.getImageDimensions(stagedUri) } returns (2400 to 1200)
        every { app.getFileSize(stagedUri) } returns 2_000_000L
        every { app.decodePreviewBitmap(stagedUri, any()) } returns previewBmp
        every { ImageAnalyzer.analyze(previewBmp) } returns ImageProfile(2400, 1200, false, false)
        every { app.deleteAppOwnedFile(any()) } returns true
        coEvery { modelManager.downloadUltraPhotoModel(any()) } returns Result.failure(IllegalStateException("Network timeout"))

        val vm = EditorViewModel(app, settingsRepository, facePipeline, modelManager).apply {
            ioDispatcher = testDispatcher
        }
        vm.loadImage("content://picker/1")
        advanceUntilIdle()

        val loaded = vm.uiState.value
        assertTrue(loaded.isReady)
        assertNull(loaded.loadError)
        assertTrue(loaded.isDownscaledInput)
        assertEquals(6400, loaded.outputWidth)
        assertEquals(3200, loaded.outputHeight)

        vm.setScaleFactor(2)
        assertEquals(3200, vm.uiState.value.outputWidth)
        assertEquals(1600, vm.uiState.value.outputHeight)

        vm.setModelType(ModelType.ANIME_ART)
        assertFalse(vm.uiState.value.config.faceEnhance)

        vm.downloadUltraModel()
        advanceUntilIdle()
        assertEquals("Network timeout", vm.uiState.value.downloadError)
        vm.clearDownloadError()
        assertNull(vm.uiState.value.downloadError)

        // Navigating back without enqueueing WorkManager must delete staged upload
        vm.javaClass.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(vm)
        verify(exactly = 1) {
            app.deleteAppOwnedFile("file:///data/user/0/com.pixelforge.app/files/uploads/IMG_1.jpg")
        }
    }
}
