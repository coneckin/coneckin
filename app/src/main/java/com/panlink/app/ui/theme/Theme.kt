package com.panlink.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2B59E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6ECFD),
    onPrimaryContainer = Color(0xFF14264F),
    background = Color(0xFFFAFAFB),
    onBackground = Color(0xFF1B1F2A),
    surface = Color(0xFFFAFAFB),
    onSurface = Color(0xFF1B1F2A),
    surfaceVariant = Color(0xFFF0F2F6),
    onSurfaceVariant = Color(0xFF5E6675),
    outline = Color(0xFF9AA2B1),
    outlineVariant = Color(0xFFE1E5EC),
    error = Color(0xFFC62828)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AA9FF),
    onPrimary = Color(0xFF0B1330),
    primaryContainer = Color(0xFF1E2A4D),
    onPrimaryContainer = Color(0xFFDCE4FF),
    background = Color(0xFF101216),
    onBackground = Color(0xFFE6E8EE),
    surface = Color(0xFF101216),
    onSurface = Color(0xFFE6E8EE),
    surfaceVariant = Color(0xFF1A1D24),
    onSurfaceVariant = Color(0xFFA0A7B5),
    outline = Color(0xFF6B7385),
    outlineVariant = Color(0xFF2A2E38),
    error = Color(0xFFFF8A80)
)

@Composable
fun PanLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}