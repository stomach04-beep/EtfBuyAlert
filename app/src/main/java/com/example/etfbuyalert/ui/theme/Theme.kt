package com.example.etfbuyalert.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = AccentGreen,
    onPrimary = Color.White,
    primaryContainer = AccentGreenContainer,
    onPrimaryContainer = TextPrimary,
    secondary = AccentGreenDark,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary
)

private val LightColors = lightColorScheme(
    primary = AccentGreenDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9),
    onPrimaryContainer = Color(0xFF1B3A1D),
    secondary = AccentGreenDark
)

@Composable
fun EtfBuyAlertTheme(
    themeMode: String = ThemePrefs.DEFAULT,
    paletteId: String = ThemePrefs.DEFAULT_PALETTE,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemePrefs.MODE_LIGHT -> false
        ThemePrefs.MODE_DARK -> true
        else -> isSystemInDarkTheme()
    }
    // 選択中の配色（カラーパレット）を取得し、明暗に応じて配色を適用する
    val palette = AppPalettes.byId(paletteId)
    MaterialTheme(
        colorScheme = if (useDark) palette.darkScheme else palette.lightScheme,
        typography = Typography,
        content = content
    )
}
