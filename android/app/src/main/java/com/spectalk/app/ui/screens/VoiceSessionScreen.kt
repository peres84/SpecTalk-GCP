package com.spectalk.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spectalk.app.ui.components.PrdConfirmationCard
import com.spectalk.app.voice.ConversationTurn
import com.spectalk.app.voice.VoiceAgentViewModel
import com.spectalk.app.voice.VoiceSessionUiState
import kotlinx.coroutines.launch

@Composable
fun VoiceSessionScreen(
    conversationId: String?,
    onNavigateBack: () -> Unit,
    viewModel: VoiceAgentViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingPhoneCameraLaunch by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview(),
    ) { bitmap ->
        bitmap?.let {
            val out = java.io.ByteArrayOutputStream()
            it.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            viewModel.sendCameraImage(out.toByteArray())
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingPhoneCameraLaunch) {
            pendingPhoneCameraLaunch = false
            cameraLauncher.launch(null)
        } else if (!granted) {
            pendingPhoneCameraLaunch = false
            scope.launch {
                snackbarHostState.showSnackbar("Camera permission is needed to take a picture.")
            }
        }
    }

    val launchPhoneCamera = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            cameraLauncher.launch(null)
        } else {
            pendingPhoneCameraLaunch = true
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(conversationId) { viewModel.startSession(conversationId) }

    LaunchedEffect(uiState.recentError) {
        uiState.recentError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding(),
            ) { data ->
                Snackbar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(16.dp),
                ) { Text(data.visuals.message) }
            }
        },
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        viewModel.disconnect()
                        onNavigateBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Gervis",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = sessionModeLabel(uiState),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.42f),
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    when {
                        uiState.isConnected && uiState.isGlassesCameraReady ->
                            IconButton(onClick = { viewModel.sendGlassesFrame() }) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = "Send glasses view to Gervis",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }

                        uiState.isConnected ->
                            IconButton(onClick = launchPhoneCamera) {
                                Icon(
                                    imageVector = Icons.Default.PhotoCamera,
                                    contentDescription = "Take photo for Gervis",
                                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                                )
                            }

                        else -> Spacer(Modifier.size(48.dp))
                    }
                }

                VoiceOrb(
                    uiState = uiState,
                    modifier = Modifier
                        .padding(top = 24.dp, bottom = 20.dp)
                        .size(200.dp),
                )

                val statusLabel = when {
                    uiState.isMicStreaming -> "Listening..."
                    uiState.isConnecting -> "Connecting..."
                    uiState.isConnected -> "Connected"
                    else -> uiState.statusMessage.ifBlank { "Offline" }
                }
                val orbColor = orbColor(uiState)
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = orbColor,
                    textAlign = TextAlign.Center,
                )

                val subtitle = when {
                    uiState.conversationState == "coding_mode" ->
                        "Gervis is designing your project..."

                    uiState.activeJobDescription.isNotBlank() ->
                        uiState.activeJobDescription

                    uiState.isConnected && !uiState.isWakeWordDeviceConnected ->
                        "App-open mode: use the phone mic and speaker while this screen stays visible."

                    else -> null
                }
                if (subtitle != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
                        fontStyle = FontStyle.Italic,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))

                TranscriptArea(
                    uiState = uiState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )

                OutlinedButton(
                    onClick = {
                        viewModel.disconnect()
                        onNavigateBack()
                    },
                    modifier = Modifier
                        .padding(bottom = 28.dp)
                        .navigationBarsPadding()
                        .height(44.dp)
                        .widthIn(min = 160.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(width = 1.dp),
                ) {
                    Text(
                        "End Session",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    )
                }
            }

            AnimatedVisibility(
                visible = uiState.prdSummary != null,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            ) {
                val prd = uiState.prdSummary
                val convId = uiState.conversationId
                if (prd != null && convId != null) {
                    PrdConfirmationCard(
                        prdSummary = prd,
                        onBuildIt = {
                            viewModel.confirmPrd(convId, confirmed = true, changeRequest = null)
                        },
                        onChangeSomething = { changeRequest ->
                            viewModel.confirmPrd(
                                convId,
                                confirmed = false,
                                changeRequest = changeRequest,
                            )
                        },
                    )
                }
            }

            val showFallback = uiState.conversationState == "awaiting_confirmation" &&
                uiState.prdSummary == null &&
                !uiState.isConnecting &&
                !uiState.isConnected
            if (showFallback) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Your project plan is ready. Say \"Hey Gervis\" to review it by voice.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun orbColor(uiState: VoiceSessionUiState) = when {
    uiState.isMicStreaming -> MaterialTheme.colorScheme.secondary
    uiState.isConnecting -> MaterialTheme.colorScheme.tertiary
    uiState.isConnected -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
}

@Composable
private fun VoiceOrb(uiState: VoiceSessionUiState, modifier: Modifier = Modifier) {
    val color = orbColor(uiState)
    val isActive = uiState.isConnected || uiState.isConnecting || uiState.isMicStreaming
    val pulseSpeed = if (uiState.isMicStreaming) 650 else 1100

    val transition = rememberInfiniteTransition(label = "orbPulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.14f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(pulseSpeed, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(pulse)
                .clip(CircleShape)
                .background(color.copy(alpha = if (isActive) 0.04f else 0f)),
        )
        Box(
            modifier = Modifier
                .size(140.dp)
                .scale(pulse)
                .clip(CircleShape)
                .background(color.copy(alpha = if (isActive) 0.09f else 0f)),
        )
        Box(
            modifier = Modifier
                .size(100.dp)
                .scale(pulse)
                .clip(CircleShape)
                .background(color.copy(alpha = if (isActive) 0.16f else 0.04f)),
        )
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = if (isActive) 0.28f else 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = color.copy(alpha = if (isActive) 1f else 0.4f),
            )
        }
    }
}

@Composable
private fun TranscriptArea(uiState: VoiceSessionUiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.turns.size) {
        if (uiState.turns.isNotEmpty()) listState.animateScrollToItem(uiState.turns.lastIndex)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                    ),
                ),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                shape = RoundedCornerShape(28.dp),
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Conversation",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Live transcript between you and Gervis",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                )
            }
            SessionStatePill(uiState = uiState)
        }

        Spacer(Modifier.height(14.dp))

        if (uiState.turns.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Say \"Hey Gervis\" or start talking",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.34f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
            return
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(uiState.turns) { turn ->
                TurnBubble(turn = turn)
            }
        }
    }
}

@Composable
private fun SessionStatePill(uiState: VoiceSessionUiState) {
    val label = when {
        uiState.isMicStreaming -> "Listening"
        uiState.isConnecting -> "Joining"
        uiState.isConnected -> "Live"
        else -> "Idle"
    }
    val color = orbColor(uiState)

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TurnBubble(turn: ConversationTurn) {
    val isUser = turn.role == "user"
    val bubbleShape = if (isUser) {
        RoundedCornerShape(22.dp, 22.dp, 8.dp, 22.dp)
    } else {
        RoundedCornerShape(8.dp, 22.dp, 22.dp, 22.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 296.dp),
        ) {
            Text(
                text = if (isUser) "You" else "Gervis",
                style = MaterialTheme.typography.labelMedium,
                color = if (isUser) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondary
                },
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .padding(bottom = 6.dp),
            )

            Text(
                text = turn.text.stripMarkdown(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(bubbleShape)
                    .background(
                        if (isUser) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                        }
                    )
                    .border(
                        width = 1.dp,
                        color = if (isUser) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                        },
                        shape = bubbleShape,
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

private fun sessionModeLabel(uiState: VoiceSessionUiState): String = when {
    uiState.isGlassesCameraReady -> "Meta camera ready"
    uiState.isConnected && !uiState.isWakeWordDeviceConnected -> "Phone mic + speaker"
    uiState.isWakeWordDeviceConnected -> "Wearable audio"
    else -> "Voice session"
}

private fun String.stripMarkdown(): String = this
    .replace(Regex("""\*\*(.+?)\*\*""")) { it.groupValues[1] }
    .replace(Regex("""\*(.+?)\*""")) { it.groupValues[1] }
    .replace(Regex("""#{1,6} """), "")
    .replace(Regex("""`(.+?)`""")) { it.groupValues[1] }
    .trim()
