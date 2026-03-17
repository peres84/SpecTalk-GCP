package com.spectalk.app.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.Wearables
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectedDeviceState(
    val hasBluetoothAudioDevice: Boolean = false,
    val bluetoothDeviceName: String? = null,
    val hasMetaWearable: Boolean = false,
) {
    val isWakeWordReady: Boolean
        get() = hasBluetoothAudioDevice || hasMetaWearable

    val primaryDeviceLabel: String?
        get() = when {
            hasMetaWearable -> "Meta wearable"
            bluetoothDeviceName.isNullOrBlank().not() -> bluetoothDeviceName
            hasBluetoothAudioDevice -> "Bluetooth audio device"
            else -> null
        }
}

object ConnectedDeviceMonitor {
    private const val TAG = "ConnectedDeviceMonitor"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(ConnectedDeviceState())
    val state: StateFlow<ConnectedDeviceState> = _state.asStateFlow()

    @Volatile
    private var started = false

    private var lastBluetoothDeviceName: String? = null

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (started) return
        started = true

        val appContext = context.applicationContext
        refreshBluetoothState(appContext)
        registerBluetoothReceiver(appContext)
        registerAudioDeviceCallback(appContext)
        observeMetaWearables()
    }

    @SuppressLint("MissingPermission")
    private fun refreshBluetoothState(context: Context) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val hasAudioRoute = runCatching {
            audioManager?.getDevices(AudioManager.GET_DEVICES_ALL)
                ?.any { it.type in bluetoothAudioTypes } == true
        }.getOrDefault(false)

        val hasHeadsetProfile = runCatching {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            btManager?.adapter
                ?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
        }.getOrDefault(false)

        val routeDeviceName = runCatching {
            audioManager?.getDevices(AudioManager.GET_DEVICES_ALL)
                ?.firstOrNull { it.type in bluetoothAudioTypes }
                ?.productName
                ?.toString()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

        val connected = hasAudioRoute || hasHeadsetProfile
        _state.update {
            it.copy(
                hasBluetoothAudioDevice = connected,
                bluetoothDeviceName = if (connected) {
                    routeDeviceName ?: lastBluetoothDeviceName ?: it.bluetoothDeviceName
                } else {
                    null
                },
            )
        }
    }

    private fun registerBluetoothReceiver(context: Context) {
        val receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(ctx: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                val connected = state == BluetoothProfile.STATE_CONNECTED
                if (connected) {
                    val device = intent.getBluetoothDeviceExtra()
                    lastBluetoothDeviceName = device?.name?.takeIf { it.isNotBlank() }
                } else {
                    lastBluetoothDeviceName = null
                }
                refreshBluetoothState(ctx)
            }
        }

        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { e ->
            Log.w(TAG, "Could not register Bluetooth receiver: ${e.message}")
        }
    }

    private fun registerAudioDeviceCallback(context: Context) {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                refreshBluetoothState(context)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                refreshBluetoothState(context)
            }
        }
        runCatching {
            audioManager.registerAudioDeviceCallback(callback, null)
        }.onFailure { e ->
            Log.w(TAG, "Could not register audio device callback: ${e.message}")
        }
    }

    private fun observeMetaWearables() {
        scope.launch {
            runCatching {
                Wearables.devices.collect { devices ->
                    _state.update { current ->
                        current.copy(hasMetaWearable = devices.isNotEmpty())
                    }
                }
            }.onFailure { e ->
                Log.w(TAG, "Meta wearables availability observation failed: ${e.message}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.getBluetoothDeviceExtra(): BluetoothDevice? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private val bluetoothAudioTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
    )
}
