package com.example.etfbuyalert.data.model

// ===== ETF買い時アラート アプリのデータ定義 =====
// VBAで言えば「シートの1行＝1銘柄」のような構造体。Gsonでそのまま
// JSONファイルに保存・読込する（DBは使わずファイル1枚で完結）。

// Notionから同期した1銘柄の「監視状態＋直近価格＋通知済みフラグ」。
// これがアプリの中心データで、JSONに保存される。
data class EtfState(
    val pageId: String,          // NotionページID（同期時の突合キー）
    val ticker: String,          // 例: SMH, URA, NLR
    val name: String,            // 銘柄名（表示用）
    val market: String? = null,  // NASDAQ / NYSE 等
    val category: String? = null, // テーマ/セクター分類（例: 半導体・米国指数）。グループ表示に使う

    // --- Notionから同期した買い時ライン（USD）---
    val dipPrice: Double? = null,        // 買い時価格(押し目)  … 下抜けで通知
    val deepDipPrice: Double? = null,    // 買い増し価格(深押し) … 下抜けで通知
    val breakoutPrice: Double? = null,   // 順張り価格(上抜け)   … 上抜けで通知
    val stopLossPrice: Double? = null,   // 損切り価格           … 終値割れで警告
    val purchased: Boolean = false,      // 購入日あり＝保有中（損切り警告は保有中のみ）

    // --- 直近の価格（Yahoo Financeから取得）---
    val price: Double? = null,           // 現在値（市場が閉じていれば直近終値）
    val previousClose: Double? = null,   // 前日終値
    val asOf: Long = 0L,                 // 価格の取得時刻（epochミリ秒）
    val isLive: Boolean = false,         // 市場が開いている時間か

    // --- アラート重複防止フラグ（ラインを跨いだ瞬間だけ通知するため）---
    // true = 既にそのラインの通知を出した状態。ラインから外れたらfalseへ戻す。
    val dipArmed: Boolean = false,
    val deepArmed: Boolean = false,
    val breakoutArmed: Boolean = false,
    val stopArmed: Boolean = false,

    // 前回のゾーン（ステージ変化通知の判定用。Zone.name を保存）
    val lastZone: String = ""
)

// チャート1点（t=epoch秒、close=終値）
data class ChartPoint(val t: Long, val close: Double)

// チャート系列（1銘柄・1期間ぶんの履歴。キャッシュ単位）
data class ChartSeries(
    val ticker: String,
    val range: String,           // "3mo" / "6mo" / "1y"
    val asOf: Long,              // 取得時刻(epochミリ秒)
    val points: List<ChartPoint>
)

// 更新ログ（同期・価格チェックが走った記録。取りこぼしリカバリの判定に使う）
data class UpdateLog(
    val date: String,        // "yyyy-MM-dd"
    val time: String,        // "HH:mm"
    val updateType: String,  // PRICE_CHECK / MORNING_SUMMARY
    val success: Boolean,
    val message: String = ""
)

// 通知履歴（履歴タブで表示。NotificationHelperが別JSONで管理）
data class NotificationLog(
    val date: String,
    val time: String,
    val category: String,    // 押し目 / 深押し / 順張り / 損切り / 朝サマリ など
    val title: String,
    val message: String
)

// アプリ全体の永続データ（JSONファイル1枚に保存）
data class AppData(
    val etfStates: MutableList<EtfState> = mutableListOf(),
    val updateLogs: MutableList<UpdateLog> = mutableListOf(),
    var lastSyncAt: Long = 0L,           // 最後にNotion同期に成功した時刻
    var lastSyncOk: Boolean = false,     // 直近の同期が成功したか
    var lastSyncError: String? = null    // 同期失敗時のメッセージ（バナー表示用）
)
