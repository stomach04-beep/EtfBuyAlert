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
    const val TAB_RTX = "rtx"             // 急落優良（イベント急落した優良大型株＝RTX自動行）
    const val TAB_PICKAXE = "pickaxe"     // つるはし（テーマ過熱×出遅れの自動候補＝つるはし行）
    const val TAB_US = "us"
    const val TAB_JP = "jp"
    const val TAB_ETF = "etf"
    val WATCH_TABS = listOf(TAB_FIRED, TAB_BOOKMARK, TAB_RTX, TAB_PICKAXE, TAB_JP, TAB_US, TAB_ETF)
    // 既定タブ＝発火中（今すぐ見るべき銘柄が最初に出る）
    const val DEFAULT_WATCH_TAB = TAB_FIRED

    fun watchTab(ctx: Context): String {
        val v = prefs(ctx).getString(KEY_WATCH_TAB, DEFAULT_WATCH_TAB).orEmpty()
        return if (v in WATCH_TABS) v else DEFAULT_WATCH_TAB  // 未知の値（旧設定・廃止"all"含む）は既定へ
    }

    // --- 発火中タブの中の「ライン方式」絞り込み ---
    // 同じ「発火」でも方式によって意味がまったく違うので、混ぜて見ると判断を誤る。
    //   ADP型   … 優良株プールが割安水準に到達＝事前登録した条件の成立（意味がある）
    //   RTX自動 … 機械が急落を拾っただけの候補。10年バックテストで優位性なしと確認済み
    //   手動    … 自分で方針を決めた狙い値
    //   MA200   … 200日線を割っただけ（値ごろ感の根拠は無い）
    // タブを増やすと横スクロールが長くなるため、発火中タブの中の絞り込みとして持たせる。
    const val KEY_FIRED_METHOD = "fired_method_filter"
    const val FIRED_METHOD_ALL = "all"
    val FIRED_METHODS = listOf(FIRED_METHOD_ALL, "adp", "manual", "ma200", "rtx", "pickaxe")
    const val DEFAULT_FIRED_METHOD = FIRED_METHOD_ALL

    fun firedMethod(ctx: Context): String {
        val v = prefs(ctx).getString(KEY_FIRED_METHOD, DEFAULT_FIRED_METHOD).orEmpty()
        return if (v in FIRED_METHODS) v else DEFAULT_FIRED_METHOD
    }

    // --- チェック間隔（分）---
    const val KEY_INTERVAL = "check_interval_min"
    const val DEFAULT_INTERVAL = 60
    fun intervalMin(ctx: Context): Int = prefs(ctx).getInt(KEY_INTERVAL, DEFAULT_INTERVAL)

    // WorkManagerへ実際に登録した間隔（分）。ユーザー設定(KEY_INTERVAL)とは別物で、
    // 「定期ジョブを登録し直す必要があるか」の判定にだけ使う内部値。
    // 0＝まだ一度も登録していない（＝初回は必ず登録する）。
    // これが無いと毎プロセス起動ごとにUPDATEで登録し直し、実行中のジョブを
    // 中断→再実行させてしまい同じ通知が何回も飛ぶ（v1.20で修正した不具合）。
    const val KEY_SCHEDULED_INTERVAL = "scheduled_interval_min"
    fun scheduledIntervalMin(ctx: Context): Int = prefs(ctx).getInt(KEY_SCHEDULED_INTERVAL, 0)
    fun setScheduledIntervalMin(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt(KEY_SCHEDULED_INTERVAL, v).apply()
    }

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
