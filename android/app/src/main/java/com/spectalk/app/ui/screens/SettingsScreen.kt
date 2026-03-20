package com.spectalk.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spectalk.app.SpecTalkApplication
import com.spectalk.app.device.ConnectedDeviceMonitor
import com.spectalk.app.integrations.IntegrationItem
import com.spectalk.app.integrations.IntegrationsRepository
import com.spectalk.app.integrations.SaveResult
import com.spectalk.app.location.UserLocationRepository
import com.spectalk.app.settings.AppPreferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locationRepository = remember { UserLocationRepository(context) }
    val deviceState by ConnectedDeviceMonitor.state.collectAsStateWithLifecycle()
    val tokenRepository = remember { (context.applicationContext as SpecTalkApplication).tokenRepository }
    val integrationsRepository = remember { IntegrationsRepository() }
    val snackbarHostState = remember { SnackbarHostState() }

    // --- Integrations state ---
    var integrations by remember { mutableStateOf<List<IntegrationItem>>(emptyList()) }
    var integrationsLoading by remember { mutableStateOf(false) }
    var showConnectForm by remember { mutableStateOf(false) }
    var connectUrl by remember { mutableStateOf("") }
    var connectToken by remember { mutableStateOf("") }
    var connectBusy by remember { mutableStateOf(false) }

    suspend fun reloadIntegrations() {
        integrationsLoading = true
        integrations = integrationsRepository.getIntegrations(tokenRepository.getProductJwt())
        integrationsLoading = false
    }

    LaunchedEffect(Unit) {
        reloadIntegrations()
    }

    var wakeWord by remember { mutableStateOf(AppPreferences.getWakeWord(context)) }
    var locationSharingEnabled by remember {
        mutableStateOf(AppPreferences.isLocationSharingEnabled(context))
    }
    var autoOpenOnNotification by remember {
        mutableStateOf(AppPreferences.isAutoOpenOnNotification(context))
    }
    var locationSummary by remember { mutableStateOf<String?>(null) }
    var locationBusy by remember { mutableStateOf(false) }

    val hasLocationPermission = rememberLocationPermission(context)

    fun refreshLocation() {
        if (!hasLocationPermission || !locationSharingEnabled) {
            locationSummary = null
            return
        }
        scope.launch {
            locationBusy = true
            locationSummary = runCatching { locationRepository.getLocationContext() }
                .getOrNull()
                ?.locationLabel
                ?: "Location available but no label yet"
            locationBusy = false
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            locationSharingEnabled = true
            AppPreferences.setLocationSharingEnabled(context, true)
            refreshLocation()
        } else {
            locationSharingEnabled = false
            AppPreferences.setLocationSharingEnabled(context, false)
        }
    }

    LaunchedEffect(hasLocationPermission, locationSharingEnabled) {
        if (hasLocationPermission && locationSharingEnabled) {
            refreshLocation()
        } else {
            locationSummary = null
        }
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
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
                    Text("Default: ${AppPreferences.DEFAULT_WAKE_WORD} — applies immediately")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = {
                    val word = wakeWord.trim().ifBlank { AppPreferences.DEFAULT_WAKE_WORD }
                    wakeWord = word
                    AppPreferences.setWakeWord(context, word)
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

            ConnectedDevicesSection(deviceState = deviceState)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Location",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            LocationSettingsSection(
                context = context,
                hasLocationPermission = hasLocationPermission,
                sharingEnabled = locationSharingEnabled,
                locationSummary = locationSummary,
                loading = locationBusy,
                onSharingChanged = { enabled ->
                    if (enabled) {
                        if (hasLocationPermission) {
                            locationSharingEnabled = true
                            AppPreferences.setLocationSharingEnabled(context, true)
                            refreshLocation()
                        } else {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                )
                            )
                        }
                    } else {
                        locationSharingEnabled = false
                        locationSummary = null
                        AppPreferences.setLocationSharingEnabled(context, false)
                    }
                },
                onRefreshLocation = ::refreshLocation,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Notifications",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            SettingsToggleRow(
                title = "Auto-open on job complete",
                subtitle = "When a background job finishes, automatically open the conversation " +
                    "and have Gervis speak the result — no tap needed.",
                checked = autoOpenOnNotification,
                onCheckedChange = { enabled ->
                    autoOpenOnNotification = enabled
                    AppPreferences.setAutoOpenOnNotification(context, enabled)
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Integrations",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (integrationsLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                }
            }

            val openClaw = integrations.find { it.service == "openclaw" }

            IntegrationRow(
                item = openClaw,
                showConnectForm = showConnectForm,
                connectUrl = connectUrl,
                connectToken = connectToken,
                connectBusy = connectBusy,
                onConnectUrl = { connectUrl = it },
                onConnectToken = { connectToken = it },
                onShowForm = { showConnectForm = true },
                onDismissForm = {
                    showConnectForm = false
                    connectUrl = ""
                    connectToken = ""
                },
                onSave = {
                    scope.launch {
                        connectBusy = true
                        val result = integrationsRepository.saveIntegration(
                            jwt = tokenRepository.getProductJwt(),
                            service = "openclaw",
                            url = connectUrl.trim(),
                            token = connectToken.trim(),
                        )
                        connectBusy = false
                        when (result) {
                            is SaveResult.Success -> {
                                showConnectForm = false
                                connectUrl = ""
                                connectToken = ""
                                reloadIntegrations()
                                snackbarHostState.showSnackbar(result.message)
                            }
                            is SaveResult.Error -> {
                                snackbarHostState.showSnackbar(result.message)
                            }
                        }
                    }
                },
                onDisconnect = {
                    scope.launch {
                        integrationsRepository.deleteIntegration(
                            jwt = tokenRepository.getProductJwt(),
                            service = "openclaw",
                        )
                        reloadIntegrations()
                    }
                },
            )
        }
    }
}

@Composable
private fun ConnectedDevicesSection(
    deviceState: com.spectalk.app.device.ConnectedDeviceState,
) {
    val dotColor = if (deviceState.isWakeWordReady) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    }

    val title = deviceState.primaryDeviceLabel ?: "No device connected"
    val subtitle = if (deviceState.isWakeWordReady) {
        "Connected — wake word active only while this device state is available"
    } else {
        "Connect Meta glasses or a Bluetooth audio device to enable wake word"
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

@Composable
private fun LocationSettingsSection(
    context: Context,
    hasLocationPermission: Boolean,
    sharingEnabled: Boolean,
    locationSummary: String?,
    loading: Boolean,
    onSharingChanged: (Boolean) -> Unit,
    onRefreshLocation: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Share location with Gervis",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (hasLocationPermission) {
                        "Used for “near me” and Google Maps grounded requests."
                    } else {
                        "Grant permission to let the backend resolve “near me” requests."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            Switch(
                checked = sharingEnabled && hasLocationPermission,
                onCheckedChange = onSharingChanged,
            )
        }

        when {
            loading -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Refreshing location...",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            sharingEnabled && hasLocationPermission && !locationSummary.isNullOrBlank() -> {
                Text(
                    text = "Current location: $locationSummary",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (hasLocationPermission) {
                OutlinedButton(onClick = onRefreshLocation) {
                    Text("Refresh location")
                }
            } else {
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                    )
                }) {
                    Text("Open app settings")
                }
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun IntegrationRow(
    item: IntegrationItem?,
    showConnectForm: Boolean,
    connectUrl: String,
    connectToken: String,
    connectBusy: Boolean,
    onConnectUrl: (String) -> Unit,
    onConnectToken: (String) -> Unit,
    onShowForm: () -> Unit,
    onDismissForm: () -> Unit,
    onSave: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "OpenClaw",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "OpenClaw — AI coding agent",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                if (item != null && item.connected) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = "Connected · ${item.urlPreview}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(2.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = "Not connected",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            if (item != null && item.connected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Disconnect")
                }
            } else if (!showConnectForm) {
                Button(onClick = onShowForm) {
                    Text("Connect")
                }
            }
        }

        AnimatedVisibility(visible = showConnectForm) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Connect OpenClaw",
                    style = MaterialTheme.typography.titleSmall,
                )

                OutlinedTextField(
                    value = connectUrl,
                    onValueChange = onConnectUrl,
                    label = { Text("Base URL") },
                    placeholder = { Text("https://your-machine.tail-xxxx.ts.net") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = connectToken,
                    onValueChange = onConnectToken,
                    label = { Text("Hook Token") },
                    placeholder = { Text("your-hook-token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onSave,
                        enabled = connectUrl.isNotBlank() && connectToken.isNotBlank() && !connectBusy,
                    ) {
                        if (connectBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Save")
                        }
                    }
                    OutlinedButton(onClick = onDismissForm, enabled = !connectBusy) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
}
