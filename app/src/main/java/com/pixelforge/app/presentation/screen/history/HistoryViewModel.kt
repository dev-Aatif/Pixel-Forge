package com.pixelforge.app.presentation.screen.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixelforge.app.domain.model.ProcessedImage
import com.pixelforge.app.domain.usecase.DeleteImageUseCase
import com.pixelforge.app.domain.usecase.GetHistoryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val images: List<ProcessedImage> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val getHistoryUseCase: GetHistoryUseCase,
    private val deleteImageUseCase: DeleteImageUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            getHistoryUseCase().collect { items ->
                _uiState.value = _uiState.value.copy(
                    images = items,
                    isLoading = false
                )
            }
        }
    }

    fun deleteImage(id: Long) {
        viewModelScope.launch {
            deleteImageUseCase(id)
        }
    }
}
