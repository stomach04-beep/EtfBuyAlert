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

    // --- 監視タブの絞り込み（種別タブ）---
    // 監視対象がETF11本＋個別株79銘柄＝90行規模になり、テーマ別/状況別の見出し分けでは
    // 「今見るべき銘柄」が埋もれるため、種別で絞って「発火の近さ順」に並べる方式へ変更した。
    // 保存キーは旧「グループ表示モード」のものを流用する（旧値"theme"/"status"や
    // 廃止した"all"は下の候補に無いので DEFAULT_WATCH_TAB に倒れる＝移行処理は不要）。
    // ※「すべて」タブは2026-07-26に廃止（90行を全部並べても見きれないため）。
    const val KEY_WATCH_TAB = "watch_group_mode"
    const val TAB_FIRED = "fired"
    const val TAB_BOOKMARK = "bookmark"   // ブックマークした銘柄だけ（印は Bookmarks が持つ）
    const val TAB_US = "us"
    const val TAB_JP = "jp"
    const val TAB_ETF = "etf"
    val WATCH_TABS = listOf(TAB_FIRED, TAB_BOOKMARK, TAB_JP, TAB_US, TAB_ETF)
    // 既定タブ＝発火中（今すぐ見るべき銘柄が最初に出る）
    const val DEFAULT_WATCH_TAB = TAB_FIRED

    fun watchTab(ctx: Context): String {
        val v = prefs(ctx).getString(KEY_WATCH_TAB, DEFAULT_WATCH_TAB).orEmpty()
        return if (v in WATCH_TABS) v else DEFAULT_WATCH_TAB  // 未知の値（旧設定・廃止"all"含む）は既定へ
    }

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
