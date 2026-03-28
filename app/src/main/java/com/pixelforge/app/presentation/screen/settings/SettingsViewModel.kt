package com.pixelforge.app.presentation.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixelforge.app.data.cache.ImageCacheManager
import com.pixelforge.app.domain.model.ModelType
import com.pixelforge.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val defaultModelType: ModelType = ModelType.PHOTO,
    val defaultScaleFactor: Int = 4,
    val useGpu: Boolean = true,
    val cacheSizeStr: String = "0 B"
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val cacheManager: ImageCacheManager
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
        updateCacheSize()
    }

    private fun updateCacheSize() {
        val bytes = cacheManager.getCacheSize()
        val formatted = formatBytes(bytes)
        _uiState.value = _uiState.value.copy(cacheSizeStr = formatted)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format("%.1f MB", mb)
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

    fun clearCache() {
        cacheManager.clearAll()
        updateCacheSize()
    }
}
