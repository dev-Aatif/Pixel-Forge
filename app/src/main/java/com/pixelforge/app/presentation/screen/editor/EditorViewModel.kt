package com.pixelforge.app.presentation.screen.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.pixelforge.app.core.constants.AppConstants
import com.pixelforge.app.core.extensions.getImageDimensions
import com.pixelforge.app.core.extensions.getFileSize
import com.pixelforge.app.core.extensions.formatFileSize
import com.pixelforge.app.core.extensions.copyUriToInternalStorage
import com.pixelforge.app.domain.model.ModelType
import com.pixelforge.app.domain.model.UpscaleConfig
import com.pixelforge.app.domain.repository.SettingsRepository
import com.pixelforge.app.worker.UpscaleWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class EditorUiState(
    val imageUri: String = "",
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val fileSize: String = "",
    val config: UpscaleConfig = UpscaleConfig(),
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    val isReady: Boolean = false
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    application: Application,
    private val settingsRepository: SettingsRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    fun loadImage(uriString: String) {
        viewModelScope.launch {
            val originalUri = Uri.parse(uriString)
            val context = getApplication<Application>()

            // Move I/O to background thread
            val (finalUriString, dimensions, fileSize) = withContext(Dispatchers.IO) {
                val persistentUri = context.copyUriToInternalStorage(originalUri) ?: originalUri
                val dims = context.getImageDimensions(persistentUri)
                val size = context.getFileSize(persistentUri)
                Triple(persistentUri.toString(), dims, size)
            }

            // Load defaults from settings
            val defaultModel = settingsRepository.getDefaultModelType().first()
            val defaultScale = settingsRepository.getDefaultScaleFactor().first()
            val useGpu = settingsRepository.getUseGpu().first()

            val width = dimensions?.first ?: 0
            val height = dimensions?.second ?: 0
            val config = UpscaleConfig(
                modelType = defaultModel,
                scaleFactor = defaultScale,
                useGpu = useGpu
            )

            _uiState.value = EditorUiState(
                imageUri = finalUriString,
                imageWidth = width,
                imageHeight = height,
                fileSize = fileSize.formatFileSize(),
                config = config,
                outputWidth = width * config.scaleFactor,
                outputHeight = height * config.scaleFactor,
                isReady = width > 0 && height > 0
            )
        }
    }

    fun setModelType(modelType: ModelType) {
        _uiState.value = _uiState.value.copy(
            config = _uiState.value.config.copy(modelType = modelType)
        )
    }

    fun setScaleFactor(scale: Int) {
        val state = _uiState.value
        _uiState.value = state.copy(
            config = state.config.copy(scaleFactor = scale),
            outputWidth = state.imageWidth * scale,
            outputHeight = state.imageHeight * scale
        )
    }

    /**
     * Enqueue the upscale work and return the work request ID.
     */
    fun startProcessing(): String {
        val state = _uiState.value
        val inputData = Data.Builder()
            .putString(AppConstants.KEY_INPUT_URI, state.imageUri)
            .putString(AppConstants.KEY_MODEL_NAME, state.config.modelType.modelName)
            .putInt(AppConstants.KEY_SCALE_FACTOR, state.config.scaleFactor)
            .putInt(AppConstants.KEY_TILE_SIZE, state.config.tileSize)
            .putBoolean(AppConstants.KEY_USE_GPU, state.config.useGpu)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<UpscaleWorker>()
            .setInputData(inputData)
            .addTag(AppConstants.UPSCALE_WORK_TAG)
            .build()

        val workManager = WorkManager.getInstance(getApplication())
        workManager.enqueueUniqueWork(
            AppConstants.UPSCALE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        return workRequest.id.toString()
    }
}
