package com.securevault.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = OceanBlue,
    secondary = CyanAccent,
    background = Mist,
    surface = Mist,
    onPrimary = Mist,
    onSecondary = Mist,
    onBackground = Ink,
    onSurface = Ink
)

private val DarkColors = darkColorScheme(
    primary = CyanAccent,
    secondary = OceanBlue,
    background = Deep,
    surface = Slate,
    onPrimary = Deep,
    onSecondary = Deep,
    onBackground = Mist,
    onSurface = Mist
)

@Composable
fun SecureVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
