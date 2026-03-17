package com.spectalk.app.auth

import android.app.Application
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.GoogleAuthProvider
import com.spectalk.app.R
import com.spectalk.app.SpecTalkApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val auth = FirebaseAuth.getInstance()
    private val tokenRepository = (application as SpecTalkApplication).tokenRepository

    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        checkCurrentUser()
    }

    private fun checkCurrentUser() {
        val user = auth.currentUser
        if (user == null || !user.isEmailVerified) {
            _state.value = AuthUiState.Unauthenticated
            return
        }
        // Fast path: stored JWT exists — no network call needed.
        val storedJwt = tokenRepository.getProductJwt()
        if (storedJwt.isNotBlank()) {
            _state.value = AuthUiState.Authenticated(user.email ?: "")
            return
        }
        // Slow path: JWT is missing (e.g. app reinstalled) — exchange silently.
        viewModelScope.launch {
            runCatching {
                val idToken = user.getIdToken(false).await().token
                    ?: throw Exception("Could not get Firebase ID token")
                tokenRepository.exchangeFirebaseToken(idToken)
            }.onSuccess {
                _state.value = AuthUiState.Authenticated(user.email ?: "")
            }.onFailure {
                auth.signOut()
                _state.value = AuthUiState.Unauthenticated
            }
        }
    }

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            try {
                auth.signInWithEmailAndPassword(email, password).await()
                val user = auth.currentUser ?: run {
                    _state.value = AuthUiState.Error("Sign in failed")
                    return@launch
                }
                if (!user.isEmailVerified) {
                    auth.signOut()
                    _state.value = AuthUiState.Error("Please verify your email before signing in.")
                    return@launch
                }
                exchangeAndAuthenticate(user.email ?: "")
            } catch (e: FirebaseAuthInvalidCredentialsException) {
                _state.value = AuthUiState.Error("Wrong email or password.")
            } catch (e: Exception) {
                _state.value = AuthUiState.Error(e.message ?: "Sign in failed")
            }
        }
    }

    fun signInWithGoogle(context: Context) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            try {
                val webClientId = context.getString(R.string.google_web_client_id)
                val credentialManager = CredentialManager.create(context)

                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(webClientId)
                    .setAutoSelectEnabled(false)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = credentialManager.getCredential(context = context, request = request)
                val credential = result.credential

                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val firebaseCredential = GoogleAuthProvider.getCredential(
                        googleIdTokenCredential.idToken, null
                    )
                    auth.signInWithCredential(firebaseCredential).await()
                    val user = auth.currentUser ?: run {
                        _state.value = AuthUiState.Error("Google sign-in failed")
                        return@launch
                    }
                    exchangeAndAuthenticate(user.email ?: "")
                } else {
                    _state.value = AuthUiState.Error("Unexpected credential type")
                }
            } catch (e: GetCredentialCancellationException) {
                _state.value = AuthUiState.Unauthenticated
            } catch (e: Exception) {
                _state.value = AuthUiState.Error(e.message ?: "Google sign-in failed")
            }
        }
    }

    fun register(email: String, password: String) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            try {
                val result = auth.createUserWithEmailAndPassword(email, password).await()
                result.user?.sendEmailVerification()?.await()
                auth.signOut()
                _state.value = AuthUiState.VerificationEmailSent
            } catch (e: FirebaseAuthWeakPasswordException) {
                _state.value = AuthUiState.Error("Password is too weak. Use at least 6 characters.")
            } catch (e: FirebaseAuthUserCollisionException) {
                _state.value = AuthUiState.Error("An account with this email already exists.")
            } catch (e: FirebaseAuthInvalidCredentialsException) {
                _state.value = AuthUiState.Error("Invalid email address.")
            } catch (e: Exception) {
                _state.value = AuthUiState.Error(e.message ?: "Registration failed")
            }
        }
    }

    fun sendPasswordReset(email: String) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            try {
                auth.sendPasswordResetEmail(email).await()
                _state.value = AuthUiState.PasswordResetSent
            } catch (e: Exception) {
                _state.value = AuthUiState.Error(e.message ?: "Failed to send reset email")
            }
        }
    }

    fun signOut() {
        auth.signOut()
        tokenRepository.clearTokens()
        _state.value = AuthUiState.Unauthenticated
    }

    fun resetState() {
        checkCurrentUser()
    }

    private suspend fun exchangeAndAuthenticate(email: String) {
        try {
            val user = auth.currentUser!!
            val idToken = user.getIdToken(false).await().token
                ?: throw Exception("Could not get Firebase ID token")
            tokenRepository.exchangeFirebaseToken(idToken)
            _state.value = AuthUiState.Authenticated(email)
        } catch (e: Exception) {
            auth.signOut()
            _state.value = AuthUiState.Error("Backend unavailable: ${e.message}")
        }
    }
}
