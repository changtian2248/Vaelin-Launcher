package com.vaelin.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Axolotl v1.10.5 neutral surfaces with its default green identity. */
private val AxolotlGreenLight = Color(0xFF00AF5C)
private val AxolotlGreenDark = Color(0xFF1BD96A)
private val GreenOnPrimary = Color(0xFF00391A)

private val LightColors = lightColorScheme(
    primary = AxolotlGreenLight,
    onPrimary = GreenOnPrimary,
    primaryContainer = Color(0xFFD6F5E3),
    onPrimaryContainer = Color(0xFF002111),
    secondary = Color(0xFF595B61),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFEBEBEB),
    onBackground = Color(0xFF1A202C),
    surface = Color(0xFFF8F8F8),
    onSurface = Color(0xFF1A202C),
    surfaceVariant = Color(0xFFEEF1F5),
    onSurfaceVariant = Color(0xFF484D54),
    outline = Color(0xFF83868D),
    outlineVariant = Color(0xFFCECfd3),
    surfaceContainerLowest = Color(0xFFEBEBEB),
    surfaceContainerLow = Color(0xFFF5F5F5),
    surfaceContainer = Color(0xFFF8F8F8),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = AxolotlGreenDark,
    onPrimary = GreenOnPrimary,
    primaryContainer = Color(0xFF17472F),
    onPrimaryContainer = Color(0xFFC6F7DA),
    secondary = Color(0xFFB4BAC5),
    onSecondary = Color(0xFF252627),
    background = Color(0xFF101215),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1A1E24),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF262B34),
    onSurfaceVariant = Color(0xFFB0BAC5),
    outline = Color(0xFF777B8B),
    outlineVariant = Color(0xFF424854),
    surfaceContainerLowest = Color(0xFF101215),
    surfaceContainerLow = Color(0xFF1A1E24),
    surfaceContainer = Color(0xFF262B34),
    surfaceContainerHigh = Color(0xFF333A46),
    surfaceContainerHighest = Color(0xFF424854),
)

/** OLED uses a true-black page with progressively lifted containers for card separation. */
private val OledColors = darkColorScheme(
    primary = AxolotlGreenDark,
    onPrimary = GreenOnPrimary,
    primaryContainer = Color(0xFF17472F),
    onPrimaryContainer = Color(0xFFC6F7DA),
    secondary = Color(0xFFB4BAC5),
    onSecondary = Color(0xFF252627),
    background = Color.Black,
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF0C0E12),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF171B21),
    onSurfaceVariant = Color(0xFFB0BAC5),
    outline = Color(0xFF616472),
    outlineVariant = Color(0xFF333A45),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0C0E12),
    surfaceContainer = Color(0xFF171B21),
    surfaceContainerHigh = Color(0xFF242A33),
    surfaceContainerHighest = Color(0xFF333A45),
)

@Composable
fun VaelinTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val colorScheme = when (mode) {
        ThemeMode.LIGHT -> LightColors
        ThemeMode.DARK -> DarkColors
        ThemeMode.OLED -> OledColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
