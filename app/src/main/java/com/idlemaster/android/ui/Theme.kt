package com.idlemaster.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Steam client palette (same idea as the desktop app's "dark theme" option). */
object SteamColors {
    val Background = Color(0xFF171D25)
    val Surface = Color(0xFF1B2838)
    val SurfaceHigh = Color(0xFF223246)
    val Outline = Color(0xFF2A475E)
    val Blue = Color(0xFF66C0F4)
    val Green = Color(0xFF7EA64B) // colorSteamGreen from the desktop settings
    val Text = Color(0xFFC6D4DF)
    val TextDim = Color(0xFF8F98A0)
    val Warning = Color(0xFFE3B341)
    val Error = Color(0xFFE5645A)
}

private val scheme = darkColorScheme(
    primary = SteamColors.Blue,
    onPrimary = Color(0xFF0E1A26),
    primaryContainer = SteamColors.Outline,
    onPrimaryContainer = SteamColors.Text,
    secondary = SteamColors.Green,
    onSecondary = Color(0xFF10180A),
    background = SteamColors.Background,
    onBackground = SteamColors.Text,
    surface = SteamColors.Surface,
    onSurface = SteamColors.Text,
    surfaceVariant = SteamColors.SurfaceHigh,
    onSurfaceVariant = SteamColors.TextDim,
    surfaceContainer = SteamColors.Surface,
    surfaceContainerHigh = SteamColors.SurfaceHigh,
    surfaceContainerHighest = SteamColors.SurfaceHigh,
    surfaceContainerLow = SteamColors.Surface,
    outline = SteamColors.Outline,
    outlineVariant = SteamColors.Outline,
    error = SteamColors.Error,
)

@Composable
fun IdleMasterTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
