package com.spectalk.app.conversations

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spectalk.app.SpecTalkApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val conversations: List<ConversationItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "HomeViewModel"
    }

    private val tokenRepository = (application as SpecTalkApplication).tokenRepository
    private val conversationRepository = ConversationRepository()

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadConversations()
    }

    fun loadConversations() {
        val jwt = tokenRepository.getProductJwt()
        if (jwt.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching { conversationRepository.listConversations(jwt) }
                .onSuccess { list ->
                    _uiState.update { it.copy(conversations = list, isLoading = false) }
                }
                .onFailure { e ->
                    Log.w(TAG, "Failed to load conversations: ${e.message}")
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
