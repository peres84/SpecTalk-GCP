package com.spectalk.app.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectalk.app.R
import com.spectalk.app.auth.AuthUiState
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    authState: AuthUiState,
    onNavigateToHome: () -> Unit,
    onNavigateToLogin: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "splash_alpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.88f,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "splash_scale",
    )

    LaunchedEffect(Unit) { visible = true }

    LaunchedEffect(authState) {
        if (authState !is AuthUiState.Loading) {
            delay(1600)
            if (authState is AuthUiState.Authenticated) onNavigateToHome() else onNavigateToLogin()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .alpha(alpha)
                .scale(scale)
                .padding(horizontal = 40.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.gervis_icon),
                contentDescription = "Gervis",
                modifier = Modifier.size(140.dp),
            )

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "SpecTalk",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 48.sp,
                    letterSpacing = (-0.5).sp,
                ),
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "POWERED BY GERVIS",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.sp),
                color = MaterialTheme.colorScheme.secondary,
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Turn your thoughts into shipped projects",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
            )
        }
    }
}
