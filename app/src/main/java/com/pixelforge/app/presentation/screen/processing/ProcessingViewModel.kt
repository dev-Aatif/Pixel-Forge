package com.pixelforge.app.presentation.screen.processing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pixelforge.app.core.constants.AppConstants
import com.pixelforge.app.data.local.ProcessedImageDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ProcessingUiState(
    val progress: Float = 0f,
    val status: String = "Starting...",
    val isComplete: Boolean = false,
    val recordId: Long? = null,
    val error: String? = null
)

@HiltViewModel
class ProcessingViewModel @Inject constructor(
    application: Application,
    private val dao: ProcessedImageDao
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ProcessingUiState())
    val uiState: StateFlow<ProcessingUiState> = _uiState.asStateFlow()

    fun observeWork(workIdStr: String) {
        val workManager = WorkManager.getInstance(getApplication())
        val workId = UUID.fromString(workIdStr)

        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workId).collect { workInfo ->
                if (workInfo == null) return@collect

                when (workInfo.state) {
                    WorkInfo.State.ENQUEUED -> {
                        _uiState.value = _uiState.value.copy(status = "Waiting in queue...")
                    }
                    WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress.getFloat(AppConstants.KEY_PROGRESS, 0f)
                        _uiState.value = _uiState.value.copy(
                            progress = progress,
                            status = "Enhancing image... ${(progress * 100).toInt()}%"
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        // Wait briefly to ensure DB is written
                        delay(200)
                        val latestRecords = dao.getAllOrdered().first()
                        val recordId = latestRecords.firstOrNull()?.id
                        
                        _uiState.value = _uiState.value.copy(
                            progress = 1.0f,
                            status = "Done!",
                            isComplete = true,
                            recordId = recordId
                        )
                    }
                    WorkInfo.State.FAILED -> {
                        val error = workInfo.outputData.getString(AppConstants.KEY_ERROR_MESSAGE)
                            ?: "Unknown error occurred"
                        _uiState.value = _uiState.value.copy(
                            status = "Failed",
                            error = error
                        )
                    }
                    WorkInfo.State.CANCELLED -> {
                        _uiState.value = _uiState.value.copy(
                            status = "Cancelled",
                            error = "Processing was cancelled"
                        )
                    }
                    else -> {}
                }
            }
        }
    }
    
    fun cancelWork(workIdStr: String) {
        val workManager = WorkManager.getInstance(getApplication())
        workManager.cancelWorkById(UUID.fromString(workIdStr))
    }
}
