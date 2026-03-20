package com.spectalk.app.ui.screens

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spectalk.app.ui.components.PrdConfirmationCard
import com.spectalk.app.voice.ConversationTurn
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

            // ── Main content ─────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Back button row
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
                    Text(
                        text = "Gervis",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(end = 52.dp), // balance the back button
                    )
                }

                // ── Orb hero area ────────────────────────────────────────────
                VoiceOrb(
                    uiState = uiState,
                    modifier = Modifier
                        .padding(top = 24.dp, bottom = 20.dp)
                        .size(200.dp),
                )

                // ── Status text ──────────────────────────────────────────────
                val statusLabel = when {
                    uiState.isMicStreaming -> "Listening…"
                    uiState.isConnecting  -> "Connecting…"
                    uiState.isConnected   -> "Connected"
                    else                  -> uiState.statusMessage.ifBlank { "Offline" }
                }
                val orbColor = orbColor(uiState)
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = orbColor,
                    textAlign = TextAlign.Center,
                )

                // coding_mode or job subtitle
                val subtitle = when {
                    uiState.conversationState == "coding_mode" -> "Gervis is designing your project…"
                    uiState.activeJobDescription.isNotBlank()  -> uiState.activeJobDescription
                    else                                        -> null
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

                // ── Transcript ───────────────────────────────────────────────
                TranscriptArea(
                    uiState = uiState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )

                // ── End Session button ────────────────────────────────────────
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
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                        width = 1.dp,
                    ),
                ) {
                    Text(
                        "End Session",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    )
                }
            }

            // ── PRD confirmation card overlay ─────────────────────────────────
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
                            viewModel.confirmPrd(convId, confirmed = false, changeRequest = changeRequest)
                        },
                    )
                }
            }

            // ── Fallback when PRD state stored but no prd_summary ─────────────
            val showFallback = uiState.conversationState == "awaiting_confirmation"
                && uiState.prdSummary == null
                && !uiState.isConnecting
                && !uiState.isConnected
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

// ── Orb hero component ────────────────────────────────────────────────────────

@Composable
private fun orbColor(uiState: VoiceSessionUiState) = when {
    uiState.isMicStreaming -> MaterialTheme.colorScheme.secondary
    uiState.isConnecting  -> MaterialTheme.colorScheme.tertiary
    uiState.isConnected   -> MaterialTheme.colorScheme.primary
    else                  -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
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
        // Outermost ambient ring
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(pulse)
                .clip(CircleShape)
                .background(color.copy(alpha = if (isActive) 0.04f else 0f)),
        )
        // Middle ring
        Box(
            modifier = Modifier
                .size(140.dp)
                .scale(pulse)
                .clip(CircleShape)
                .background(color.copy(alpha = if (isActive) 0.09f else 0f)),
        )
        // Inner ring
        Box(
            modifier = Modifier
                .size(100.dp)
                .scale(pulse)
                .clip(CircleShape)
                .background(color.copy(alpha = if (isActive) 0.16f else 0.04f)),
        )
        // Core orb with mic icon
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

// ── Transcript area ───────────────────────────────────────────────────────────

@Composable
private fun TranscriptArea(uiState: VoiceSessionUiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.turns.size) {
        if (uiState.turns.isNotEmpty()) listState.animateScrollToItem(uiState.turns.lastIndex)
    }

    if (uiState.turns.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "Say \"Hey Gervis\" or start talking",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(uiState.turns) { turn -> TurnBubble(turn = turn) }
    }
}

// ── Chat bubble ───────────────────────────────────────────────────────────────

@Composable
private fun TurnBubble(turn: ConversationTurn) {
    val isUser = turn.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) {
            Text(
                text = turn.text.stripMarkdown(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .widthIn(max = 285.dp)
                    .clip(RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        } else {
            Text(
                text = turn.text.stripMarkdown(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .widthIn(max = 285.dp)
                    .clip(RoundedCornerShape(5.dp, 20.dp, 20.dp, 20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

private fun String.stripMarkdown(): String = this
    .replace(Regex("""\*\*(.+?)\*\*""")) { it.groupValues[1] }
    .replace(Regex("""\*(.+?)\*""")) { it.groupValues[1] }
    .replace(Regex("""#{1,6} """), "")
    .replace(Regex("""`(.+?)`""")) { it.groupValues[1] }
    .trim()
