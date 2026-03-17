package com.spectalk.app.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
        }
    }
}
