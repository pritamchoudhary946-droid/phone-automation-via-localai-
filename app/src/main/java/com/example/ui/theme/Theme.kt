package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = CyanPrimaryDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E2FF),
    onPrimaryContainer = Color(0xFF1E1B7A),
    secondary = ElectricBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCEBFF),
    onSecondaryContainer = Color(0xFF0B2E5C),
    tertiary = IndigoAccent,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = TextPrimary,
    surface = LightSurface,
    onSurface = TextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = Color(0xFFF1F3FA),
    outline = LightSurfaceBorder,
    outlineVariant = Color(0xFFEDEFF7),
    error = CrimsonDanger,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFA5A3FF),
    onPrimary = Color(0xFF201C6B),
    primaryContainer = Color(0xFF3B3796),
    onPrimaryContainer = Color(0xFFE3E2FF),
    secondary = ElectricBlue,
    onSecondary = Color(0xFF00344F),
    secondaryContainer = Color(0xFF004B70),
    onSecondaryContainer = Color(0xFFDCEBFF),
    tertiary = IndigoAccent,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    surfaceContainer = Color(0xFF1B2038),
    outline = DarkSurfaceBorder,
    outlineVariant = Color(0xFF2A3050),
    error = CrimsonDanger,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(), // Light by default, follows the device
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
