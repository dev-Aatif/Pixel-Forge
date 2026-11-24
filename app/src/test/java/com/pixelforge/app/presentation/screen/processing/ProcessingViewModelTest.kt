package com.pixelforge.app.presentation.screen.processing

import android.app.Application
import com.pixelforge.app.data.local.ProcessedImageDao
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessingViewModelTest {

    @Test
    fun `observeWork emits error on malformed job UUID instead of crashing`() {
        val app: Application = mockk(relaxed = true)
        val dao: ProcessedImageDao = mockk(relaxed = true)
        val vm = ProcessingViewModel(app, dao)

        vm.observeWork("not-a-valid-uuid")

        assertEquals("Invalid processing job ID", vm.uiState.value.error)
        assertEquals("Failed", vm.uiState.value.status)
    }
}
