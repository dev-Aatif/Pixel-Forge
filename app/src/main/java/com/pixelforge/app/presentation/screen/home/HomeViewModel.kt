package com.pixelforge.app.presentation.screen.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixelforge.app.domain.model.ProcessedImage
import com.pixelforge.app.domain.usecase.GetHistoryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val recentImages: List<ProcessedImage> = emptyList(),
    val isLoading: Boolean = true,
    val selectedImageUri: Uri? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getHistoryUseCase: GetHistoryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadRecentImages()
    }

    private fun loadRecentImages() {
        viewModelScope.launch {
            getHistoryUseCase().collect { images ->
                _uiState.value = _uiState.value.copy(
                    recentImages = images.take(10), // Show latest 10
                    isLoading = false
                )
            }
        }
    }

    fun onImageSelected(uri: Uri) {
        _uiState.value = _uiState.value.copy(selectedImageUri = uri)
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedImageUri = null)
    }
}
