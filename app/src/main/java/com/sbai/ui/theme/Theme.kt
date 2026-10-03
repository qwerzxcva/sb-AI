package com.sbai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// AsteriskBOX 风格：深色优先 + 冷色主调
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B3057),
    primaryContainer = Color(0xFF1D4266),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFF7FC9BB),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF1C4F47),
    onSecondaryContainer = Color(0xFFA5F0E1),
    tertiary = Color(0xFFD7B9FF),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE0E2E8),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE0E2E8),
    surfaceVariant = Color(0xFF1B2127),
    onSurfaceVariant = Color(0xFFBFC6D0),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1D5FA6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF0B3057),
    secondary = Color(0xFF006B5E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFA5F0E1),
    onSecondaryContainer = Color(0xFF00382F),
    tertiary = Color(0xFF6B4EA0),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF7F9FC),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE1E6EE),
    onSurfaceVariant = Color(0xFF434A55),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
)

@Composable
fun SbAiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
