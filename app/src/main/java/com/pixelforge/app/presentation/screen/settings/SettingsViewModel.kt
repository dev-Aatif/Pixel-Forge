package com.pixelforge.app.presentation.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixelforge.app.core.constants.AppConstants
import com.pixelforge.app.core.extensions.formatFileSize
import com.pixelforge.app.data.cache.ImageCacheManager
import com.pixelforge.app.data.model.ModelManager
import com.pixelforge.app.domain.model.ModelType
import com.pixelforge.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val defaultModelType: ModelType = ModelType.FAST_PHOTO,
    val defaultScaleFactor: Int = AppConstants.DEFAULT_SCALE_FACTOR,
    val useGpu: Boolean = AppConstants.DEFAULT_USE_GPU,
    val useNpu: Boolean = AppConstants.DEFAULT_USE_NPU,
    val faceEnhanceDefault: Boolean = AppConstants.DEFAULT_FACE_ENHANCE,
    val exportFormat: String = AppConstants.DEFAULT_EXPORT_FORMAT,
    val jpegQuality: Int = AppConstants.DEFAULT_JPEG_QUALITY,
    val maxResolution: Int = AppConstants.DEFAULT_MAX_RESOLUTION,
    val cacheSizeStr: String = "0 B",
    val isUltraDownloaded: Boolean = false,
    val isFacePackDownloaded: Boolean = false,
    val ultraDownloadProgress: Float? = null,
    val facePackDownloadProgress: Float? = null,
    val downloadedModelsSizeStr: String = "0 B"
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val cacheManager: ImageCacheManager,
    private val modelManager: ModelManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.getDefaultModelType().collect { model ->
                _uiState.value = _uiState.value.copy(defaultModelType = model)
            }
        }
        viewModelScope.launch {
            settingsRepository.getDefaultScaleFactor().collect { scale ->
                _uiState.value = _uiState.value.copy(defaultScaleFactor = scale)
            }
        }
        viewModelScope.launch {
            settingsRepository.getUseGpu().collect { gpu ->
                _uiState.value = _uiState.value.copy(useGpu = gpu)
            }
        }
        viewModelScope.launch {
            settingsRepository.getUseNpu().collect { npu ->
                _uiState.value = _uiState.value.copy(useNpu = npu)
            }
        }
        viewModelScope.launch {
            settingsRepository.getFaceEnhanceDefault().collect { face ->
                _uiState.value = _uiState.value.copy(faceEnhanceDefault = face)
            }
        }
        viewModelScope.launch {
            settingsRepository.getDefaultExportFormat().collect { fmt ->
                _uiState.value = _uiState.value.copy(exportFormat = fmt)
            }
        }
        viewModelScope.launch {
            settingsRepository.getJpegQuality().collect { q ->
                _uiState.value = _uiState.value.copy(jpegQuality = q)
            }
        }
        viewModelScope.launch {
            settingsRepository.getMaxResolution().collect { maxRes ->
                _uiState.value = _uiState.value.copy(maxResolution = maxRes)
            }
        }
        refreshStorageStats()
    }

    fun refreshStorageStats() {
        _uiState.value = _uiState.value.copy(
            cacheSizeStr = cacheManager.getCacheSize().formatFileSize(),
            isUltraDownloaded = modelManager.isModelAvailable(ModelType.ULTRA_PHOTO, 4),
            isFacePackDownloaded = modelManager.isFaceRestoreAvailable(),
            downloadedModelsSizeStr = modelManager.getDownloadedModelsSizeBytes().formatFileSize()
        )
    }

    fun setModelType(modelType: ModelType) {
        viewModelScope.launch { settingsRepository.setDefaultModelType(modelType) }
    }

    fun setScaleFactor(scale: Int) {
        viewModelScope.launch { settingsRepository.setDefaultScaleFactor(scale) }
    }

    fun setUseGpu(useGpu: Boolean) {
        viewModelScope.launch { settingsRepository.setUseGpu(useGpu) }
    }

    fun setUseNpu(useNpu: Boolean) {
        viewModelScope.launch { settingsRepository.setUseNpu(useNpu) }
    }

    fun setFaceEnhanceDefault(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setFaceEnhanceDefault(enabled) }
    }

    fun setExportFormat(format: String) {
        viewModelScope.launch { settingsRepository.setDefaultExportFormat(format) }
    }

    fun setJpegQuality(quality: Int) {
        viewModelScope.launch { settingsRepository.setJpegQuality(quality) }
    }

    fun setMaxResolution(maxRes: Int) {
        viewModelScope.launch { settingsRepository.setMaxResolution(maxRes) }
    }

    fun downloadUltraModel() {
        if (_uiState.value.ultraDownloadProgress != null) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(ultraDownloadProgress = 0f)
            modelManager.downloadUltraPhotoModel { p ->
                _uiState.value = _uiState.value.copy(ultraDownloadProgress = p)
            }
            _uiState.value = _uiState.value.copy(ultraDownloadProgress = null)
            refreshStorageStats()
        }
    }

    fun downloadFaceRestoreModel() {
        if (_uiState.value.facePackDownloadProgress != null) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(facePackDownloadProgress = 0f)
            modelManager.downloadFaceRestoreModel { p ->
                _uiState.value = _uiState.value.copy(facePackDownloadProgress = p)
            }
            _uiState.value = _uiState.value.copy(facePackDownloadProgress = null)
            refreshStorageStats()
        }
    }

    fun clearDownloadedModels() {
        modelManager.clearDownloadedModels()
        refreshStorageStats()
    }

    fun clearCache() {
        cacheManager.clearAll()
        refreshStorageStats()
    }
}
