package com.pixelforge.app.presentation.screen.settings

import com.pixelforge.app.data.cache.ImageCacheManager
import com.pixelforge.app.data.model.ModelManager
import com.pixelforge.app.domain.model.ModelType
import com.pixelforge.app.domain.repository.SettingsRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val cacheManager: ImageCacheManager = mockk(relaxed = true)
    private val modelManager: ModelManager = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { settingsRepository.getDefaultModelType() } returns flowOf(ModelType.FAST_PHOTO)
        every { settingsRepository.getDefaultScaleFactor() } returns flowOf(4)
        every { settingsRepository.getUseGpu() } returns flowOf(true)
        every { settingsRepository.getUseNpu() } returns flowOf(true)
        every { settingsRepository.getFaceEnhanceDefault() } returns flowOf(true)
        every { settingsRepository.getDefaultExportFormat() } returns flowOf("PNG")
        every { settingsRepository.getJpegQuality() } returns flowOf(95)
        every { settingsRepository.getMaxResolution() } returns flowOf(1600)
        every { cacheManager.getCacheSize() } returns 2048L
        every { modelManager.getDownloadedModelsSizeBytes() } returns 0L
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `updates settings and clears cache cleanly`() = runTest {
        val vm = SettingsViewModel(settingsRepository, cacheManager, modelManager).apply {
            ioDispatcher = testDispatcher
        }
        vm.refreshStorageStats()
        advanceUntilIdle()

        assertEquals("2.0 KB", vm.uiState.value.cacheSizeStr)

        vm.setUseNpu(false)
        vm.setExportFormat("WEBP")
        vm.setJpegQuality(90)
        vm.setMaxResolution(2048)
        vm.clearCache()
        advanceUntilIdle()

        coVerify { settingsRepository.setUseNpu(false) }
        coVerify { settingsRepository.setDefaultExportFormat("WEBP") }
        coVerify { settingsRepository.setJpegQuality(90) }
        coVerify { settingsRepository.setMaxResolution(2048) }
        verify { cacheManager.clearAll() }
    }
}
