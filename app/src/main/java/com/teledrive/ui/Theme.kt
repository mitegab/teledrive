package com.teledrive

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF176B87),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9ECFF),
    onPrimaryContainer = Color(0xFF001F29),
    secondary = Color(0xFF7D5700),
    secondaryContainer = Color(0xFFFFDEA6),
    background = Color(0xFFF7FAFC),
    surface = Color(0xFFF7FAFC),
    surfaceVariant = Color(0xFFDCE5E8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF83D4EF),
    onPrimary = Color(0xFF003544),
    primaryContainer = Color(0xFF005166),
    onPrimaryContainer = Color(0xFFB9ECFF),
    secondary = Color(0xFFF3BE5D),
    secondaryContainer = Color(0xFF5C4100),
    background = Color(0xFF0B1418),
    surface = Color(0xFF0B1418),
    surfaceVariant = Color(0xFF3E494D),
)

@Composable
fun TeleDriveTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = TeleDriveTypography,
        content = content,
    )
}
