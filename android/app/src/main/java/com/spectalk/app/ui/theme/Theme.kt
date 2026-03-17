package com.spectalk.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ── Dark scheme — elegant, premium, warm dark ─────────────────────────────────
private val DarkColorScheme = darkColorScheme(
    primary              = GervisRed80,          // Soft metallic red on dark
    onPrimary            = GervisRed20,
    primaryContainer     = GervisRed30,          // Deep red container
    onPrimaryContainer   = GervisRed90,

    secondary            = GervisGold80,         // Bright gold on dark
    onSecondary          = GervisGold20,
    secondaryContainer   = GervisGold30,
    onSecondaryContainer = GervisGold90,

    tertiary             = GervisRed80,
    onTertiary           = GervisRed20,

    background           = Obsidian,             // Near-black warm bg
    onBackground         = Cream,

    surface              = Carbon,               // Warm dark card surface
    onSurface            = Cream,
    surfaceVariant       = Charcoal,
    onSurfaceVariant     = GervisGold90,

    outline              = Ash,
    outlineVariant       = Charcoal,

    error                = GervisRed80,
    onError              = GervisRed20,
    errorContainer       = GervisRed30,
    onErrorContainer     = GervisRed90,
)

// ── Light scheme — warm cream, rich red, gold accents ────────────────────────
private val LightColorScheme = lightColorScheme(
    primary              = GervisRed40,          // Core metallic red
    onPrimary            = Cream,
    primaryContainer     = GervisRed95,
    onPrimaryContainer   = GervisRed10,

    secondary            = GervisGold40,         // Deep gold
    onSecondary          = Cream,
    secondaryContainer   = GervisGold95,
    onSecondaryContainer = GervisGold10,

    tertiary             = GervisRed50,
    onTertiary           = Cream,

    background           = Cream,
    onBackground         = GervisRed10,

    surface              = Linen,
    onSurface            = GervisRed10,
    surfaceVariant       = Silk,
    onSurfaceVariant     = GervisGold30,

    outline              = Stone,
    outlineVariant       = Silk,

    error                = GervisRed40,
    onError              = Cream,
    errorContainer       = GervisRed95,
    onErrorContainer     = GervisRed10,
)

@Composable
fun SpecTalkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Transparent status bar — let content extend edge-to-edge
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content,
    )
}
