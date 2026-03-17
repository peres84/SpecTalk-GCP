package com.spectalk.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spectalk.app.auth.AuthUiState
import com.spectalk.app.auth.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    authViewModel: AuthViewModel,
    onNavigateToSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    val email = (authState as? AuthUiState.Authenticated)?.email ?: ""

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
            FloatingActionButton(onClick = { /* TODO Phase 1: start voice session */ }) {
                Icon(Icons.Filled.Add, contentDescription = "New conversation")
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(32.dp),
            ) {
                Text(
                    text = "No conversations yet",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Say \"Hey Gervis\" or tap + to start a new conversation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
                if (email.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Signed in as $email",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}
