package com.photoprism.uploader.ui.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.photoprism.uploader.data.mediastore.MediaStoreAlbumRepository
import com.photoprism.uploader.domain.model.Album
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Albums screen.
 */
class AlbumsViewModel(
    private val albumRepository: MediaStoreAlbumRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumsUiState())
    val uiState: StateFlow<AlbumsUiState> = _uiState.asStateFlow()

    fun loadAlbums() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val albums = albumRepository.loadAlbums()
                _uiState.value = _uiState.value.copy(
                    albums = albums,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load albums"
                )
            }
        }
    }

    class Factory(
        private val albumRepository: MediaStoreAlbumRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AlbumsViewModel(albumRepository) as T
        }
    }
}

data class AlbumsUiState(
    val albums: List<Album> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)
