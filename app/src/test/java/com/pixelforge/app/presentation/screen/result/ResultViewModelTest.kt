package com.pixelforge.app.presentation.screen.result

import android.content.Context
import com.pixelforge.app.domain.repository.ImageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class ResultViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val context: Context = mockk(relaxed = true)
    private val imageRepository: ImageRepository = mockk()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadResult emits error when record is not found`() = runTest {
        coEvery { imageRepository.getById(99L) } returns null

        val vm = ResultViewModel(context, imageRepository)
        vm.loadResult(99L)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals("Result not found", vm.uiState.value.error)
        coVerify(exactly = 1) { imageRepository.getById(99L) }
    }
}
