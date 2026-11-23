package com.pixelforge.app.presentation.screen.history

import app.cash.turbine.test
import com.pixelforge.app.domain.model.ModelType
import com.pixelforge.app.domain.model.ProcessedImage
import com.pixelforge.app.domain.usecase.DeleteHistoryUseCase
import com.pixelforge.app.domain.usecase.DeleteImageUseCase
import com.pixelforge.app.domain.usecase.GetHistoryUseCase
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val getHistoryUseCase: GetHistoryUseCase = mockk()
    private val deleteImageUseCase: DeleteImageUseCase = mockk(relaxed = true)
    private val deleteHistoryUseCase: DeleteHistoryUseCase = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads history items and delegates deletes`() = runTest {
        val sample = ProcessedImage(
            id = 10L,
            inputUri = "file:///in.jpg",
            outputUri = "content://out.png",
            modelType = ModelType.FAST_PHOTO,
            scaleFactor = 4,
            inputWidth = 400,
            inputHeight = 300,
            outputWidth = 1600,
            outputHeight = 1200,
            fileSizeBytes = 2048L,
            processingTimeMs = 1500L,
            status = "completed",
            backendUsed = "NPU (QNN)",
            createdAt = 100L
        )
        every { getHistoryUseCase() } returns flowOf(listOf(sample))

        val vm = HistoryViewModel(getHistoryUseCase, deleteImageUseCase, deleteHistoryUseCase)

        vm.uiState.test {
            skipItems(1) // initial loading state
            val loaded = awaitItem()
            assertFalse(loaded.isLoading)
            assertEquals(listOf(sample), loaded.images)
        }

        vm.deleteImage(10L)
        vm.clearHistory()
        advanceUntilIdle()

        coVerify(exactly = 1) { deleteImageUseCase(10L) }
        coVerify(exactly = 1) { deleteHistoryUseCase() }
    }
}
