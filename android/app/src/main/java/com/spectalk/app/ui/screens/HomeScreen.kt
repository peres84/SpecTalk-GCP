package com.spectalk.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spectalk.app.auth.AuthUiState
import com.spectalk.app.auth.AuthViewModel
import com.spectalk.app.conversations.ConversationItem
import com.spectalk.app.conversations.HomeViewModel
import com.spectalk.app.hotword.HotwordEventBus
import com.spectalk.app.hotword.HotwordService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    authViewModel: AuthViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToVoiceSession: (conversationId: String?) -> Unit,
    onSignOut: () -> Unit,
    homeViewModel: HomeViewModel = viewModel(),
) {
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Request RECORD_AUDIO (+ POST_NOTIFICATIONS on Android 13+) then start HotwordService.
    // The service runs as a foreground service and keeps the wake word detector alive while
    // the app is in the foreground or background (phone locked). If the app process is killed,
    // the service restarts automatically next time the user opens the app to HomeScreen.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            startHotwordService(context)
        }
    }

    LaunchedEffect(Unit) {
        homeViewModel.loadConversations()
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            // BLUETOOTH_CONNECT is required on API 31+ (minSdk=31) to read the BT headset
            // connection state in HotwordService. Without it, isBtHeadsetConnected is always
            // false and Vosk never starts even when earbuds are connected.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) {
            startHotwordService(context)  // already granted — start immediately
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    // Wake word detected → always start a new conversation
    LaunchedEffect(Unit) {
        if (HotwordEventBus.consumePendingWakeWord()) {
            onNavigateToVoiceSession(null)
            return@LaunchedEffect
        }
        HotwordEventBus.wakeWordDetected.collect {
            onNavigateToVoiceSession(null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SpecTalk") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    TextButton(onClick = {
                        authViewModel.signOut()
                        onSignOut()
                    }) {
                        Text("Sign out")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                onNavigateToVoiceSession(null)
            }) {
                Icon(Icons.Filled.Add, contentDescription = "New conversation")
            }
        },
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = homeState.isLoading,
            onRefresh = homeViewModel::loadConversations,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            if (!homeState.isLoading && homeState.conversations.isEmpty()) {
                EmptyState(
                    email = (authState as? AuthUiState.Authenticated)?.email ?: "",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(homeState.conversations, key = { it.id }) { conversation ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    homeViewModel.deleteConversation(conversation.id)
                                    true
                                } else false
                            }
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = false,
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
                                        .padding(end = 24.dp),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            },
                        ) {
                            ConversationRow(
                                item = conversation,
                                onClick = { onNavigateToVoiceSession(conversation.id) },
                            )
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── Hotword service ───────────────────────────────────────────────────────────

private fun startHotwordService(context: android.content.Context) {
    HotwordEventBus.resume()
    runCatching {
        context.startForegroundService(Intent(context, HotwordService::class.java))
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(email: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            )
            Text(
                text = "No conversations yet",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Say \"Hey Gervis\" or tap + to start.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
            if (email.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = email,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                )
            }
        }
    }
}

// ── Conversation row ──────────────────────────────────────────────────────────

@Composable
private fun ConversationRow(item: ConversationItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Avatar circle with state color
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(stateColor(item.state).copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stateEmoji(item.state),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        // Text content
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatRelativeTime(item.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                StateChip(state = item.state)
            }
            Text(
                text = item.lastTurnSummary ?: "Voice conversation",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Resume badge
        if (item.pendingResumeCount > 0) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.pendingResumeCount.coerceAtMost(9).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun StateChip(state: String) {
    val color = stateColor(state)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = stateLabel(state),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun stateColor(state: String) = when (state) {
    "active"                -> MaterialTheme.colorScheme.secondary
    "awaiting_resume"       -> MaterialTheme.colorScheme.primary
    "awaiting_confirmation" -> MaterialTheme.colorScheme.tertiary
    "running_job"           -> MaterialTheme.colorScheme.tertiary
    else                    -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
}

private fun stateLabel(state: String) = when (state) {
    "active"                -> "Active"
    "awaiting_resume"       -> "Resume"
    "awaiting_confirmation" -> "Review"
    "running_job"           -> "Working"
    "idle"                  -> "Idle"
    else                    -> state.replaceFirstChar { it.uppercase() }
}

private fun stateEmoji(state: String) = when (state) {
    "active"                -> "🎙"
    "awaiting_resume"       -> "💬"
    "awaiting_confirmation" -> "❓"
    "running_job"           -> "⚙️"
    else                    -> "💬"
}

private fun formatRelativeTime(iso: String): String {
    return runCatching {
        val instant = Instant.parse(iso)
        val now = Instant.now()
        val minutes = ChronoUnit.MINUTES.between(instant, now)
        when {
            minutes < 1    -> "Just now"
            minutes < 60   -> "${minutes}m ago"
            minutes < 1440 -> "${minutes / 60}h ago"
            else -> DateTimeFormatter.ofPattern("MMM d")
                .withZone(ZoneId.systemDefault())
                .format(instant)
        }
    }.getOrDefault(iso.take(10))
}
