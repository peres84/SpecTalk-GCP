package com.spectalk.app.device

import android.app.Activity
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MetaWearablesAccessState(
    val registrationState: RegistrationState = RegistrationState.Unavailable(),
    val hasCameraPermission: Boolean = false,
    val isPermissionRequestInFlight: Boolean = false,
    val recentError: String? = null,
) {
    val isRegistered: Boolean
        get() = registrationState is RegistrationState.Registered

    val registrationLabel: String
        get() = when (registrationState) {
            is RegistrationState.Registered -> "Connected to Meta AI"
            is RegistrationState.Registering -> "Connecting to Meta AI..."
            is RegistrationState.Unregistering -> "Disconnecting Meta AI..."
            is RegistrationState.Unavailable -> "Meta setup unavailable"
            else -> "Meta app not connected"
        }

    val canUseCamera: Boolean
        get() = isRegistered && hasCameraPermission
}

object MetaWearablesAccessManager {
    private const val TAG = "MetaWearablesAccess"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(MetaWearablesAccessState())
    val state: StateFlow<MetaWearablesAccessState> = _state.asStateFlow()

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true

        scope.launch {
            runCatching {
                Wearables.registrationState.collect { registrationState ->
                    _state.update {
                        it.copy(
                            registrationState = registrationState,
                            hasCameraPermission = if (registrationState is RegistrationState.Registered) {
                                it.hasCameraPermission
                            } else {
                                false
                            },
                        )
                    }

                    if (registrationState is RegistrationState.Registered) {
                        refreshCameraPermissionStatus()
                    } else if (registrationState !is RegistrationState.Registering) {
                        clearRecentError()
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "Could not observe registration state: ${error.message}", error)
                _state.update { it.copy(recentError = error.message ?: "Meta registration unavailable") }
            }
        }
    }

    fun startRegistration(activity: Activity) {
        runCatching { Wearables.startRegistration(activity) }
            .onFailure { error ->
                Log.w(TAG, "Could not start Meta registration: ${error.message}", error)
                _state.update {
                    it.copy(recentError = error.message ?: "Could not open Meta registration")
                }
            }
    }

    fun startUnregistration(activity: Activity) {
        runCatching { Wearables.startUnregistration(activity) }
            .onFailure { error ->
                Log.w(TAG, "Could not start Meta unregistration: ${error.message}", error)
                _state.update {
                    it.copy(recentError = error.message ?: "Could not disconnect Meta app")
                }
            }
    }

    fun refreshCameraPermissionStatus() {
        scope.launch {
            val hasPermission = fetchCameraPermissionStatus()
            _state.update {
                it.copy(
                    hasCameraPermission = hasPermission,
                    recentError = if (hasPermission) null else it.recentError,
                )
            }
        }
    }

    suspend fun requestCameraPermission(): Boolean {
        _state.update { it.copy(isPermissionRequestInFlight = true, recentError = null) }
        val status = MetaWearablesPermissionBridge.requestPermission(Permission.CAMERA)
        val granted = status == PermissionStatus.Granted
        _state.update {
            it.copy(
                isPermissionRequestInFlight = false,
                hasCameraPermission = granted,
                recentError = if (granted) null else "Meta camera permission not granted",
            )
        }
        if (granted) {
            refreshCameraPermissionStatus()
        }
        return granted
    }

    fun clearRecentError() {
        _state.update { it.copy(recentError = null) }
    }

    private suspend fun fetchCameraPermissionStatus(): Boolean {
        val result = Wearables.checkPermissionStatus(Permission.CAMERA)
        return result.fold(
            onSuccess = { status -> status == PermissionStatus.Granted },
            onFailure = { error, throwable ->
                Log.w(
                    TAG,
                    "Meta camera permission check failed: ${error.description}",
                    throwable,
                )
                _state.update {
                    it.copy(recentError = throwable?.message ?: error.description)
                }
                false
            },
        )
    }
}
