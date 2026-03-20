package com.spectalk.app.conversations

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spectalk.app.SpecTalkApplication
import kotlinx.coroutines.delay
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
        private const val DELETE_RETRY_DELAY_MS = 450L
    }

    private val tokenRepository = (application as SpecTalkApplication).tokenRepository
    private val conversationRepository = ConversationRepository()

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private val pendingDeletionIds = mutableSetOf<String>()

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
                    _uiState.update {
                        it.copy(
                            conversations = list.filterNot { item -> item.id in pendingDeletionIds },
                            isLoading = false,
                        )
                    }
                }
                .onFailure { e ->
                    Log.w(TAG, "Failed to load conversations: ${e.message}")
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    fun deleteConversation(conversationId: String) {
        val jwt = tokenRepository.getProductJwt()
        if (jwt.isBlank()) return
        if (!pendingDeletionIds.add(conversationId)) return

        _uiState.update {
            it.copy(
                conversations = it.conversations.filterNot { conversation ->
                    conversation.id == conversationId
                }
            )
        }

        viewModelScope.launch {
            val firstAttempt = runCatching {
                conversationRepository.deleteConversation(jwt, conversationId)
            }.getOrDefault(false)

            val deleted = if (firstAttempt) {
                true
            } else {
                delay(DELETE_RETRY_DELAY_MS)
                runCatching {
                    conversationRepository.deleteConversation(jwt, conversationId)
                }.getOrDefault(false)
            }

            if (!deleted) {
                pendingDeletionIds.remove(conversationId)
                Log.w(TAG, "Delete failed for $conversationId after retry - reloading list")
                loadConversations()
                return@launch
            }

            runCatching { conversationRepository.listConversations(jwt) }
                .onSuccess { list ->
                    val visibleList = list.filterNot { item -> item.id in pendingDeletionIds }
                    _uiState.update { it.copy(conversations = visibleList, isLoading = false) }
                }
                .onFailure { e ->
                    Log.w(TAG, "Delete succeeded but refresh failed: ${e.message}")
                }

            pendingDeletionIds.remove(conversationId)
            _uiState.update {
                it.copy(
                    conversations = it.conversations.filterNot { conversation ->
                        conversation.id == conversationId
                    }
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
