package com.sbai.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sbai.data.ThemeMode

// Kototoro 风格：深色优先 + 冷色主调 + surfaceContainer 层级
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF0B3057),
    primaryContainer = Color(0xFF274777),
    onPrimaryContainer = Color(0xFFD7E2FF),
    secondary = Color(0xFF7FDBCB),
    onSecondary = Color(0xFF003730),
    secondaryContainer = Color(0xFF005047),
    onSecondaryContainer = Color(0xFF9FF7E8),
    tertiary = Color(0xFFDAB9FF),
    onTertiary = Color(0xFF3F1D71),
    background = Color(0xFF0F141B),
    onBackground = Color(0xFFDFE2EB),
    surface = Color(0xFF0F141B),
    onSurface = Color(0xFFDFE2EB),
    surfaceVariant = Color(0xFF424750),
    onSurfaceVariant = Color(0xFFC2C6D2),
    surfaceContainerLowest = Color(0xFF0A0F15),
    surfaceContainerLow = Color(0xFF171C23),
    surfaceContainer = Color(0xFF1B2027),
    surfaceContainerHigh = Color(0xFF252A32),
    surfaceContainerHighest = Color(0xFF30353D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8C919C),
    outlineVariant = Color(0xFF424750),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3D5F90),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E2FF),
    onPrimaryContainer = Color(0xFF0B3057),
    secondary = Color(0xFF006A60),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF9FF7E8),
    onSecondaryContainer = Color(0xFF003730),
    tertiary = Color(0xFF6B4EA0),
    onTertiary = Color.White,
    background = Color(0xFFF8F9FE),
    onBackground = Color(0xFF191C21),
    surface = Color(0xFFF8F9FE),
    onSurface = Color(0xFF191C21),
    surfaceVariant = Color(0xFFDFE2ED),
    onSurfaceVariant = Color(0xFF424750),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3F9),
    surfaceContainer = Color(0xFFECEEF4),
    surfaceContainerHigh = Color(0xFFE6E8EE),
    surfaceContainerHighest = Color(0xFFE1E2E9),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF727781),
    outlineVariant = Color(0xFFC2C6D2),
)

// Kototoro typography：明确的字号/字重
private val SbTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = 0.sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 0.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
)

@Composable
fun SbAiTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    // Material You 动态取色（Android 12+），失败时回退内置配色
    val colorScheme = remember(darkTheme, dynamicColor) {
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }.getOrNull()
        } else {
            null
        }
    } ?: if (darkTheme) DarkColors else LightColors

    val tokens = SbStyleTokens()
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(tokens.settingsGroupInnerCornerRadius),
        small = RoundedCornerShape(tokens.sectionCornerRadius),
        medium = RoundedCornerShape(tokens.controlCornerRadius),
        large = RoundedCornerShape(tokens.settingsGroupOuterCornerRadius),
        extraLarge = RoundedCornerShape(tokens.groupCornerRadius),
    )
    CompositionLocalProvider(LocalSbStyleTokens provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SbTypography,
            shapes = shapes,
            content = content,
        )
    }
}
