package com.standby.opticalscanner.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = StandbyBlue,
    secondary = StandbyLightBlue,
    background = DarkBackground,
    surface = DarkBackground,
    onPrimary = White,
    onBackground = White,
    onSurface = White
)

@Composable
fun OpticalScannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
