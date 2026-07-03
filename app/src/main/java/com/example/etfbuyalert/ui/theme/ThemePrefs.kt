package com.example.etfbuyalert.ui.theme

import android.content.Context
import android.content.SharedPreferences

object ThemePrefs {
    private const val FILE = "theme_prefs"
    const val KEY = "theme_mode"
    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"
    const val DEFAULT = MODE_DARK

    // 配色（カラーパレット）。値は AppPalettes の id
    const val KEY_PALETTE = "theme_palette"
    const val DEFAULT_PALETTE = "midnight"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(ctx: Context): String = prefs(ctx).getString(KEY, DEFAULT) ?: DEFAULT
    fun set(ctx: Context, mode: String) {
        prefs(ctx).edit().putString(KEY, mode).apply()
    }

    // 配色（カラーパレット）の取得／保存
    fun getPalette(ctx: Context): String =
        prefs(ctx).getString(KEY_PALETTE, DEFAULT_PALETTE) ?: DEFAULT_PALETTE
    fun setPalette(ctx: Context, paletteId: String) {
        prefs(ctx).edit().putString(KEY_PALETTE, paletteId).apply()
    }
}
