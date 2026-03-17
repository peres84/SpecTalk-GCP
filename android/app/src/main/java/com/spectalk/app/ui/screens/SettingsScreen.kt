package com.spectalk.app.ui.screens

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit

private const val PREFS_NAME = "spectalk_prefs"
private const val PREF_WAKE_WORD = "pref_wake_word"
private const val DEFAULT_WAKE_WORD = "Hey Gervis"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var wakeWord by remember {
        mutableStateOf(prefs.getString(PREF_WAKE_WORD, DEFAULT_WAKE_WORD) ?: DEFAULT_WAKE_WORD)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Voice",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            OutlinedTextField(
                value = wakeWord,
                onValueChange = { wakeWord = it },
                label = { Text("Wake word") },
                supportingText = {
                    Text("Default: $DEFAULT_WAKE_WORD — takes effect on next app start")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = {
                    val word = wakeWord.trim().ifBlank { DEFAULT_WAKE_WORD }
                    wakeWord = word
                    prefs.edit { putString(PREF_WAKE_WORD, word) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Devices",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            ConnectedDevicesSection()
        }
    }
}

@Composable
private fun ConnectedDevicesSection() {
    val context = LocalContext.current
    var isConnected by remember { mutableStateOf(false) }
    var deviceName by remember { mutableStateOf<String?>(null) }

    // Check initial BT state
    LaunchedEffect(Unit) {
        runCatching {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val state = btManager?.adapter?.getProfileConnectionState(BluetoothProfile.HEADSET)
            isConnected = state == BluetoothProfile.STATE_CONNECTED
        }
    }

    // Register BroadcastReceiver for BT connection state changes
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                val connected = state == BluetoothProfile.STATE_CONNECTED
                isConnected = connected
                if (connected) {
                    deviceName = runCatching {
                        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        device?.name
                    }.getOrNull()
                } else {
                    deviceName = null
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    val dotColor = if (isConnected) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    }

    val title = if (isConnected) deviceName ?: "Audio Device" else "No device connected"
    val subtitle = if (isConnected) {
        "Connected — wake word active"
    } else {
        "Connect earbuds or Meta glasses to enable wake word"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor),
        )

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}
