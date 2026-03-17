package com.spectalk.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spectalk.app.voice.VoiceAgentViewModel
import com.spectalk.app.voice.VoiceSessionUiState

@Composable
fun VoiceSessionScreen(
    conversationId: String?,
    onNavigateBack: () -> Unit,
    viewModel: VoiceAgentViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Auto-start session when screen opens (either new or resuming existing conversation)
    LaunchedEffect(conversationId) {
        viewModel.startSession(conversationId)
    }

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
                    shape = RoundedCornerShape(14.dp),
                ) { Text(data.visuals.message) }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surface,
                        )
                    )
                )
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Spacer(Modifier.height(32.dp))

            // Connection status pill
            ConnectionStatusPill(uiState = uiState)

            Spacer(Modifier.height(24.dp))

            // Animated orb — center piece
            GervisOrb(uiState = uiState)

            Spacer(Modifier.height(24.dp))

            // Transcript bubbles
            TranscriptArea(uiState = uiState, modifier = Modifier.weight(1f))

            Spacer(Modifier.height(16.dp))

            // Disconnect button
            Button(
                onClick = {
                    viewModel.disconnect()
                    onNavigateBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("End Session", fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConnectionStatusPill(uiState: VoiceSessionUiState) {
    val (color, label) = when {
        uiState.isMicStreaming  -> MaterialTheme.colorScheme.secondary to "Listening"
        uiState.isConnecting   -> MaterialTheme.colorScheme.tertiary  to "Connecting…"
        uiState.isConnected    -> MaterialTheme.colorScheme.primary   to "Connected"
        else                   -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) to "Offline"
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PulsingDot(color = color, active = uiState.isConnected || uiState.isConnecting)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PulsingDot(color: Color, active: Boolean) {
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (active) 0.3f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotAlpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}

@Composable
private fun GervisOrb(uiState: VoiceSessionUiState) {
    val orbColor by animateColorAsState(
        targetValue = when {
            uiState.isMicStreaming -> MaterialTheme.colorScheme.secondary
            uiState.isConnecting  -> MaterialTheme.colorScheme.tertiary
            uiState.isConnected   -> MaterialTheme.colorScheme.primary
            else                  -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        },
        animationSpec = tween(600),
        label = "orbColor",
    )

    val transition = rememberInfiniteTransition(label = "orb")

    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (uiState.isMicStreaming) 1.28f else if (uiState.isConnected) 1.10f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (uiState.isMicStreaming) 750 else 1800,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (uiState.isMicStreaming) 750 else 1800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ringAlpha",
    )

    val statusText = when {
        uiState.isMicStreaming -> "Listening…"
        uiState.isConnecting  -> "Connecting…"
        uiState.isConnected   -> "Ready"
        else                  -> "Say \"Hey Gervis\" to start"
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Outer glow rings
            if (uiState.isConnected || uiState.isConnecting) {
                Box(
                    modifier = Modifier
                        .size(164.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(orbColor.copy(alpha = ringAlpha * 0.25f)),
                )
                Box(
                    modifier = Modifier
                        .size(132.dp)
                        .scale(pulseScale * 0.88f)
                        .clip(CircleShape)
                        .background(orbColor.copy(alpha = ringAlpha * 0.18f)),
                )
            }

            // Core orb
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(orbColor.copy(alpha = 0.45f), orbColor.copy(alpha = 0.10f)),
                        )
                    )
                    .border(
                        width = 1.5.dp,
                        brush = Brush.linearGradient(
                            listOf(orbColor.copy(alpha = 0.80f), orbColor.copy(alpha = 0.15f)),
                        ),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    uiState.isConnecting -> CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 2.5.dp,
                        color = orbColor,
                        trackColor = orbColor.copy(alpha = 0.15f),
                    )
                    else -> Icon(
                        imageVector = if (uiState.isMicStreaming) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        tint = if (uiState.isConnected) Color.White else orbColor,
                    )
                }
            }
        }

        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = orbColor,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TranscriptArea(uiState: VoiceSessionUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (uiState.latestUserTranscript.isBlank() && uiState.latestAssistantTranscript.isBlank()) return@Column

        Text(
            text = "Conversation",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )

        // User bubble — right-aligned
        if (uiState.latestUserTranscript.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = uiState.latestUserTranscript,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .widthIn(max = 290.dp)
                        .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                            RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                        )
                        .padding(12.dp, 10.dp),
                )
            }
        }

        // Assistant bubble — left-aligned
        if (uiState.latestAssistantTranscript.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
            ) {
                Text(
                    text = uiState.latestAssistantTranscript,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .widthIn(max = 290.dp)
                        .clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f),
                            RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
                        )
                        .padding(12.dp, 10.dp),
                )
            }
        }
    }
}
