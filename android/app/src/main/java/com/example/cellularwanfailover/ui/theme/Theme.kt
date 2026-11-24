package com.example.cellularwanfailover.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2B5CA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF0E2F66),
    secondaryContainer = Color(0xFFD6E3FF),
    onSecondaryContainer = Color(0xFF0E2F66),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF7F9FC),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE1E2E8),
    onSurfaceVariant = Color(0xFF44474E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F4F9),
    surfaceContainer = Color(0xFFEEF1F6),
    surfaceContainerHigh = Color(0xFFE8EBF0),
    surfaceContainerHighest = Color(0xFFE2E5EA),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFE1E2E8),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF0E2F66),
    primaryContainer = Color(0xFF1C4180),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondaryContainer = Color(0xFF1C4180),
    onSecondaryContainer = Color(0xFFD6E3FF),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color(0xFF181B20),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF272A2F),
    surfaceContainerHighest = Color(0xFF32353A),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF33363C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

/** Colors of one status: a tinted [container] with its [content] text, and a solid [accent] badge. */
@Immutable
data class StatusPalette(
    val container: Color,
    val content: Color,
    val accent: Color,
    val onAccent: Color
)

/**
 * The app's status colors, beyond Material's roles. They also differ in lightness, so they stay
 * distinguishable without relying on hue alone.
 */
@Immutable
data class StatusColors(
    /** Protected / ready / OK */
    val good: StatusPalette,
    /** Failover carrying traffic, information */
    val active: StatusPalette,
    /** Paused, needs a small action */
    val paused: StatusPalette,
    /** Something failed or blocks the app */
    val attention: StatusPalette,
    /** Not set up yet */
    val neutral: StatusPalette
)

private val LightStatusColors = StatusColors(
    good = StatusPalette(Color(0xFFDDF3E4), Color(0xFF0F3D22), Color(0xFF1E6B3A), Color.White),
    active = StatusPalette(Color(0xFFDCE6FF), Color(0xFF0E2F66), Color(0xFF2B5CA8), Color.White),
    paused = StatusPalette(Color(0xFFFFE3C2), Color(0xFF3F2400), Color(0xFF8A5300), Color.White),
    attention = StatusPalette(Color(0xFFFFDAD6), Color(0xFF410002), Color(0xFFB3261E), Color.White),
    neutral = StatusPalette(Color(0xFFE6E8EE), Color(0xFF191C20), Color(0xFF44474E), Color.White)
)

private val DarkStatusColors = StatusColors(
    good = StatusPalette(Color(0xFF1B3A26), Color(0xFFBDEBC9), Color(0xFF7FD69A), Color(0xFF003919)),
    active = StatusPalette(Color(0xFF1C3460), Color(0xFFD6E3FF), Color(0xFFA9C7FF), Color(0xFF0E2F66)),
    paused = StatusPalette(Color(0xFF4A2C00), Color(0xFFFFDDB5), Color(0xFFFFB869), Color(0xFF4A2800)),
    attention = StatusPalette(Color(0xFF5C1512), Color(0xFFFFDAD6), Color(0xFFFFB4AB), Color(0xFF690005)),
    neutral = StatusPalette(Color(0xFF2A2D33), Color(0xFFE2E2E9), Color(0xFFC4C6D0), Color(0xFF2E3138))
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

@Composable
fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors) {
        MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
    }
}
