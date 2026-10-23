package com.example.etfbuyalert.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
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
    val scheme = if (useDark) palette.darkScheme else palette.lightScheme
    // 明るい表示ではステータスバーの時計・電池のアイコンを濃い色に（白い地に白い文字が重なるのを防ぐ。2026-10-23）
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val ctl = WindowCompat.getInsetsController(window, view)
            ctl.isAppearanceLightStatusBars = !useDark
            ctl.isAppearanceLightNavigationBars = !useDark
            // 暗い表示で画面下のナビバーが明るい灰色の帯になっていた（窓の地が XML テーマの明るい色のまま、
            // その上に OS が下敷きを敷く）。窓の地をテーマの背景色にし、OS の下敷きは切る
            window.setBackgroundDrawable(ColorDrawable(scheme.background.toArgb()))
            window.isNavigationBarContrastEnforced = false
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
