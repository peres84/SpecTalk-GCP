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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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

@OptIn(ExperimentalMaterial3Api::class)
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
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.disconnect()
                        onNavigateBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                title = { Text("Gervis", fontWeight = FontWeight.SemiBold) },
            )
        },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── Main content column ──────────────────────────────────────────
            Column(modifier = Modifier.fillMaxSize()) {
                // Compact status row: small orb + status label + mode subtitle
                CompactStatusRow(
                    uiState = uiState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )

                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                )

                // Transcript area — scrollable, fills remaining space
                TranscriptArea(
                    uiState = uiState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )

                // End Session button
                Button(
                    onClick = {
                        viewModel.disconnect()
                        onNavigateBack()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .navigationBarsPadding()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("End Session", fontWeight = FontWeight.SemiBold)
                }
            }

            // ── PRD confirmation card overlay ───────────────────────────────
            // Slides up from bottom when awaiting_confirmation state is active.
            // Remains until the state changes (voice or button confirmation).
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

            // ── Fallback: PRD state set but no prd_summary (app was killed) ─
            // The prd_summary is stored in SharedPreferences, so this scenario
            // only occurs if the app was killed before the state_update arrived.
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
                        .padding(16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Your project plan is ready. Tap + and say \"Hey Gervis\" to review it by voice.",
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
private fun CompactStatusRow(uiState: VoiceSessionUiState, modifier: Modifier = Modifier) {
    val orbColor = when {
        uiState.isMicStreaming -> MaterialTheme.colorScheme.secondary
        uiState.isConnecting  -> MaterialTheme.colorScheme.tertiary
        uiState.isConnected   -> MaterialTheme.colorScheme.primary
        else                  -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    }

    val isActive = uiState.isConnected || uiState.isConnecting || uiState.isMicStreaming

    val transition = rememberInfiniteTransition(label = "compactOrb")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.18f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orbScale",
    )

    val statusLabel = when {
        uiState.isMicStreaming -> "Listening…"
        uiState.isConnecting  -> "Connecting…"
        uiState.isConnected   -> "Connected"
        else                  -> "Offline"
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(orbColor.copy(alpha = 0.20f))
                .border(1.5.dp, orbColor.copy(alpha = 0.45f), CircleShape),
        )

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = orbColor,
                fontWeight = FontWeight.SemiBold,
            )
            // coding_mode subtitle: Gervis is actively shaping the PRD via questions
            if (uiState.conversationState == "coding_mode") {
                Text(
                    text = "Gervis is designing your project…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f),
                    fontStyle = FontStyle.Italic,
                )
            }
            val jobDescription = uiState.activeJobDescription
            if (jobDescription.isNotBlank()) {
                Text(
                    text = jobDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun TranscriptArea(uiState: VoiceSessionUiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    // Auto-scroll to the latest turn whenever turns change
    LaunchedEffect(uiState.turns.size) {
        if (uiState.turns.isNotEmpty()) {
            listState.animateScrollToItem(uiState.turns.lastIndex)
        }
    }

    if (uiState.turns.isEmpty()) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Say \"Hey Gervis\" or tap the mic to start",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(top = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = "Conversation",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontWeight = FontWeight.SemiBold,
            )
        }
        items(uiState.turns) { turn -> TurnBubble(turn = turn) }
    }
}

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
                    .widthIn(max = 290.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                    )
                    .padding(12.dp, 10.dp),
            )
        } else {
            Text(
                text = turn.text.stripMarkdown(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .widthIn(max = 290.dp)
                    .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
                    )
                    .padding(12.dp, 10.dp),
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
