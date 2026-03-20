package com.spectalk.app.gallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GalleryUiState(
    val images: List<GalleryImage> = emptyList(),
    val isLoading: Boolean = true,
)

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = GalleryUiState(
                images = GalleryRepository.listImages(getApplication()),
                isLoading = false,
            )
        }
    }

    fun delete(image: GalleryImage) {
        viewModelScope.launch(Dispatchers.IO) {
            GalleryRepository.deleteImage(image.file)
            load()
        }
    }
}
