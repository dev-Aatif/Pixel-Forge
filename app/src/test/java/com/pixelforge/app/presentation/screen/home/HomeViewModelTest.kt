package com.pixelforge.app.presentation.screen.home

import app.cash.turbine.test
import com.pixelforge.app.domain.model.ModelType
import com.pixelforge.app.domain.model.ProcessedImage
import com.pixelforge.app.domain.usecase.GetHistoryUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val getHistoryUseCase: GetHistoryUseCase = mockk()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `shows only completed records in recent plates`() = runTest {
        val completed = ProcessedImage(
            id = 1L,
            inputUri = "file:///a.jpg",
            outputUri = "content://out.png",
            modelType = ModelType.FAST_PHOTO,
            scaleFactor = 4,
            inputWidth = 400,
            inputHeight = 300,
            outputWidth = 1600,
            outputHeight = 1200,
            fileSizeBytes = 1024L,
            processingTimeMs = 1200L,
            status = "completed",
            backendUsed = "CPU (OpenMP/XNNPACK)",
            createdAt = 200L
        )
        val inProgress = completed.copy(id = 2L, outputUri = null, status = "processing")
        every { getHistoryUseCase() } returns flowOf(listOf(inProgress, completed))

        val vm = HomeViewModel(getHistoryUseCase)
        vm.uiState.test {
            skipItems(1) // initial loading state
            val loaded = awaitItem()
            assertFalse(loaded.isLoading)
            assertEquals(listOf(completed), loaded.recentImages)
        }
    }
}
