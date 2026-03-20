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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) startHotwordService(context) else permissionLauncher.launch(needed.toTypedArray())
    }

    LaunchedEffect(Unit) {
        if (HotwordEventBus.consumePendingWakeWord()) {
            onNavigateToVoiceSession(null)
            return@LaunchedEffect
        }
        HotwordEventBus.wakeWordDetected.collect { onNavigateToVoiceSession(null) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "SpecTalk",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        )
                    }
                    IconButton(onClick = {
                        authViewModel.signOut()
                        onSignOut()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Sign out",
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onNavigateToVoiceSession(null) },
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
                icon = {
                    Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
                },
                text = {
                    Text("Start talking", style = MaterialTheme.typography.labelLarge)
                },
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
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
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item { Spacer(Modifier.height(8.dp)) }
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
                                        .padding(horizontal = 16.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f))
                                        .padding(end = 24.dp),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            },
                        ) {
                            ConversationCard(
                                item = conversation,
                                onClick = { onNavigateToVoiceSession(conversation.id) },
                            )
                        }
                    }
                    // Extra bottom space so the FAB doesn't cover the last item
                    item { Spacer(Modifier.height(96.dp)) }
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
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "No conversations yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Tap \"Start talking\" or say \"Hey Gervis\" to create your first project.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
            )
            if (email.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = email,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                )
            }
        }
    }
}

// ── Conversation card ─────────────────────────────────────────────────────────

@Composable
private fun ConversationCard(item: ConversationItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // State indicator dot
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(stateColor(item.state)),
            )

            // Content
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = item.lastTurnSummary ?: "Voice conversation",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StateChip(state = item.state)
                    Text(
                        text = formatRelativeTime(item.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
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
}

@Composable
private fun StateChip(state: String) {
    val color = stateColor(state)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f))
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
    else                    -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
}

private fun stateLabel(state: String) = when (state) {
    "active"                -> "Active"
    "awaiting_resume"       -> "Resume"
    "awaiting_confirmation" -> "Review"
    "running_job"           -> "Working"
    "idle"                  -> "Idle"
    else                    -> state.replaceFirstChar { it.uppercase() }
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
