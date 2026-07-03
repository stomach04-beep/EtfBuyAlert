package com.example.etfbuyalert.data.repository

import android.content.Context
import android.content.SharedPreferences

// アプリ設定の単一の真実の源（DRY）。キー名・既定値をここだけに定義し、
// ViewModel・Worker・画面はすべてこの object 経由で読み書きする。
object Settings {
    private const val FILE = "etf_settings"
    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- Notion同期 ---
    const val KEY_NOTION_TOKEN = "notion_token"
    const val KEY_NOTION_DB = "notion_db_id"
    // 既定のDB ID（投資ウォッチリスト）。ユーザーがトークンを入れればすぐ動く。
    const val DEFAULT_NOTION_DB = "8b243e59af5f453b87db5454dc1528ee"

    fun notionToken(ctx: Context): String = prefs(ctx).getString(KEY_NOTION_TOKEN, "") ?: ""
    fun notionDbId(ctx: Context): String =
        prefs(ctx).getString(KEY_NOTION_DB, DEFAULT_NOTION_DB)?.ifBlank { DEFAULT_NOTION_DB } ?: DEFAULT_NOTION_DB

    // --- 監視タブのグループ表示モード（"theme"=テーマ別 / "status"=状況別）---
    const val KEY_GROUP_MODE = "watch_group_mode"
    const val GROUP_THEME = "theme"
    const val GROUP_STATUS = "status"
    fun groupMode(ctx: Context): String =
        prefs(ctx).getString(KEY_GROUP_MODE, GROUP_THEME)?.ifBlank { GROUP_THEME } ?: GROUP_THEME

    // --- チェック間隔（分）---
    const val KEY_INTERVAL = "check_interval_min"
    const val DEFAULT_INTERVAL = 60
    fun intervalMin(ctx: Context): Int = prefs(ctx).getInt(KEY_INTERVAL, DEFAULT_INTERVAL)

    // --- 毎朝サマリの時刻 ---
    const val KEY_MORNING_HOUR = "morning_hour"
    const val KEY_MORNING_MIN = "morning_min"
    const val DEFAULT_MORNING_HOUR = 7
    const val DEFAULT_MORNING_MIN = 0
    fun morningHour(ctx: Context): Int = prefs(ctx).getInt(KEY_MORNING_HOUR, DEFAULT_MORNING_HOUR)
    fun morningMin(ctx: Context): Int = prefs(ctx).getInt(KEY_MORNING_MIN, DEFAULT_MORNING_MIN)

    // --- 通知ON/OFF（4種）---
    const val KEY_NOTIFY_DIP = "notify_dip"        // 押し目・深押し到達
    const val KEY_NOTIFY_STOP = "notify_stop"      // 損切り割れ
    const val KEY_NOTIFY_BREAKOUT = "notify_breakout" // 順張り突破
    const val KEY_NOTIFY_MORNING = "notify_morning"   // 毎朝サマリ
    const val KEY_NOTIFY_ZONE = "notify_zone"         // ステージ(ゾーン)変化
    fun notifyDip(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_NOTIFY_DIP, true)
    fun notifyStop(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_NOTIFY_STOP, true)
    fun notifyBreakout(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_NOTIFY_BREAKOUT, true)
    fun notifyMorning(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_NOTIFY_MORNING, true)
    fun notifyZoneChange(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_NOTIFY_ZONE, true)
}
