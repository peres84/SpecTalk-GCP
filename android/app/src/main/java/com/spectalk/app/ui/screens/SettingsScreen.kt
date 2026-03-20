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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
    onSignOut: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locationRepository = remember { UserLocationRepository(context) }
    val deviceState by ConnectedDeviceMonitor.state.collectAsStateWithLifecycle()
    val tokenRepository = remember { (context.applicationContext as SpecTalkApplication).tokenRepository }
    val integrationsRepository = remember { IntegrationsRepository() }
    val snackbarHostState = remember { SnackbarHostState() }

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

    LaunchedEffect(Unit) { reloadIntegrations() }

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
        if (!hasLocationPermission || !locationSharingEnabled) { locationSummary = null; return }
        scope.launch {
            locationBusy = true
            locationSummary = runCatching { locationRepository.getLocationContext() }
                .getOrNull()?.locationLabel ?: "Location available but no label yet"
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
        if (hasLocationPermission && locationSharingEnabled) refreshLocation()
        else locationSummary = null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {

            // ── Voice ─────────────────────────────────────────────────────────
            SettingsGroup(title = "Voice") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = wakeWord,
                        onValueChange = { wakeWord = it },
                        label = { Text("Wake word") },
                        supportingText = { Text("Default: ${AppPreferences.DEFAULT_WAKE_WORD}") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            val word = wakeWord.trim().ifBlank { AppPreferences.DEFAULT_WAKE_WORD }
                            wakeWord = word
                            AppPreferences.setWakeWord(context, word)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Save", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // ── Devices ───────────────────────────────────────────────────────
            SettingsGroup(title = "Devices") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (deviceState.isWakeWordReady) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            ),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = deviceState.primaryDeviceLabel ?: "No device connected",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (deviceState.isWakeWordReady)
                                "Connected — wake word active"
                            else
                                "Connect Meta glasses or a Bluetooth audio device",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                }
            }

            // ── Location ──────────────────────────────────────────────────────
            SettingsGroup(title = "Location") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                "Share location with Gervis",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = if (hasLocationPermission)
                                    "Used for \"near me\" and Google Maps requests."
                                else
                                    "Grant permission for location-aware responses.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            )
                        }
                        Switch(
                            checked = locationSharingEnabled && hasLocationPermission,
                            onCheckedChange = { enabled ->
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
                        )
                    }

                    when {
                        locationBusy -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("Refreshing…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        locationSharingEnabled && hasLocationPermission && !locationSummary.isNullOrBlank() -> {
                            Text(
                                "Current location: $locationSummary",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            )
                        }
                    }

                    if (hasLocationPermission) {
                        OutlinedButton(
                            onClick = ::refreshLocation,
                            shape = RoundedCornerShape(10.dp),
                        ) { Text("Refresh location") }
                    } else {
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null),
                                    )
                                )
                            },
                            shape = RoundedCornerShape(10.dp),
                        ) { Text("Open app settings") }
                    }
                }
            }

            // ── Notifications ─────────────────────────────────────────────────
            SettingsGroup(title = "Notifications") {
                SettingsToggleRow(
                    title = "Auto-open on job complete",
                    subtitle = "Opens the conversation and has Gervis speak the result automatically.",
                    checked = autoOpenOnNotification,
                    onCheckedChange = { enabled ->
                        autoOpenOnNotification = enabled
                        AppPreferences.setAutoOpenOnNotification(context, enabled)
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }

            // ── Integrations ──────────────────────────────────────────────────
            SettingsGroup(
                title = "Integrations",
                trailingContent = {
                    if (integrationsLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    }
                },
            ) {
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
                                is SaveResult.Error -> snackbarHostState.showSnackbar(result.message)
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

            // ── Sign Out ──────────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onSignOut,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(
                    "Sign Out",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── iOS-style grouped section ─────────────────────────────────────────────────

@Composable
private fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    trailingContent: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        ) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                fontWeight = FontWeight.Medium,
            )
            trailingContent?.invoke()
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
        ) {
            Column(content = content)
        }
    }
}

// ── Toggle row ────────────────────────────────────────────────────────────────

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ── Integration row ───────────────────────────────────────────────────────────

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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "OpenClaw",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "AI coding agent",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
                if (item != null && item.connected) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
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
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = "Not connected",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            if (item != null && item.connected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Disconnect") }
            } else if (!showConnectForm) {
                Button(
                    onClick = onShowForm,
                    shape = RoundedCornerShape(10.dp),
                ) { Text("Connect") }
            }
        }

        AnimatedVisibility(visible = showConnectForm) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                Text(
                    text = "Connect OpenClaw",
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = connectUrl,
                    onValueChange = onConnectUrl,
                    label = { Text("Base URL") },
                    placeholder = { Text("https://your-machine.tail-xxxx.ts.net") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = connectToken,
                    onValueChange = onConnectToken,
                    label = { Text("Hook Token") },
                    placeholder = { Text("your-hook-token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onSave,
                        enabled = connectUrl.isNotBlank() && connectToken.isNotBlank() && !connectBusy,
                        shape = RoundedCornerShape(10.dp),
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
                    OutlinedButton(
                        onClick = onDismissForm,
                        enabled = !connectBusy,
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun rememberLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
}
