package com.spectalk.app.auth

sealed interface AuthUiState {
    data object Loading : AuthUiState
    data object Unauthenticated : AuthUiState
    data class Authenticated(val email: String) : AuthUiState
    data class Error(val message: String) : AuthUiState
    data object VerificationEmailSent : AuthUiState
    data object PasswordResetSent : AuthUiState
}
