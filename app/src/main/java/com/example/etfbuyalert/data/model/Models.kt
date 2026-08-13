package com.example.etfbuyalert.data.model

// ===== 買い時アラート アプリのデータ定義 =====
// VBAで言えば「シートの1行＝1銘柄」のような構造体。Gsonでそのまま
// JSONファイルに保存・読込する（DBは使わずファイル1枚で完結）。
// ※ 監視対象はETFだけでなく個別株（ADP型 質ゲート監視プール）も含む。
//   クラス名の Etf は旧称の名残で、パッケージ名と同じく互換のため据え置いている。

// Notionから同期した1銘柄の「監視状態＋直近価格＋通知済みフラグ」。
// これがアプリの中心データで、JSONに保存される。
data class EtfState(
    val pageId: String,          // NotionページID（同期時の突合キー）
    // 正規形のティッカー。日本株は .T 付き（例: 1925.T）＝ Symbol.normalize 済みの値だけを入れること。
    // Yahooに投げる記号であり、円/ドル判定のキーでもある（Symbol / Money 参照）。
    val ticker: String,          // 例: SMH, ADP, 1925.T
    val name: String,            // 銘柄名（表示用）
    val market: String? = null,  // NASDAQ / NYSE / 東証プライム 等
    val category: String? = null, // テーマ/セクター分類（例: 半導体・米国指数）。グループ表示に使う
    val sector: String? = null,   // Notionの「業種」列（化学/医薬品/機械/サービス 等）。個別株の補足表示用
    // Notionの「ライン計算方式」列（ADP型 / MA200 / RTX自動 / 手動）＝どのジョブがラインの持ち主か。
    // 発火中タブの方式チップと、詳細画面での説明表示に使う。
    // ※種別判定(AssetKind)には使わない。方式は「どの式で計算するか」であって「何の商品か」ではない
    //   （個別株をMA200方式で監視するとETFタブに出る不具合があった。2026-08-11修正）。
    val lineMethod: String? = null,

    // --- Notionから同期した買い時ライン（通貨は銘柄による。円建てなら円）---
    val dipPrice: Double? = null,        // 買い時価格(押し目)  … 下抜けで通知
    val deepDipPrice: Double? = null,    // 買い増し価格(深押し) … 下抜けで通知
    val breakoutPrice: Double? = null,   // 順張り価格(上抜け)   … 上抜けで通知
    val stopLossPrice: Double? = null,   // 損切り価格           … 終値割れで警告
    val purchased: Boolean = false,      // 購入日あり＝保有中（損切り警告は保有中のみ）

    // --- 端末内で計算したMA200ラインの記録（ライン計算方式=MA200 の行のみ）---
    // 以前はPCの月次ジョブが計算してNotionに書き戻していたため、PCを起動しない月は
    // ラインが古いまま止まっていた。アプリが自分で計算するようになった目印。
    val maLinesAsOf: Long = 0L,          // 端末内でラインを計算した時刻(epochミリ秒)。0＝未計算(=Notionの値)
    val maWindowUsed: Int = 0,           // 実際に使った移動平均日数。200未満なら上場が浅く代用したという意味

    // --- 直近の価格（Yahoo Financeから取得）---
    val price: Double? = null,           // 現在値（市場が閉じていれば直近終値）
    val previousClose: Double? = null,   // 前日終値
    val asOf: Long = 0L,                 // 価格の取得時刻（epochミリ秒）
    val isLive: Boolean = false,         // 市場が開いている時間か

    // --- 週足RSI過熱利確監視（レバレッジETFの利確規律。オプトイン）---
    // Notionの「RSI利確監視」checkboxがONの銘柄だけRSI計算・通知の対象になる
    // （プロパティ未新設のDBでもfalse扱いになるだけでクラッシュしない）。
    val rsiWatch: Boolean = false,       // RSI利確監視のON/OFF（Notion同期）

    // --- ニュース警告（PC側の fired_news_check.py がNotionへ毎日書き込む）---
    // 発火中の銘柄に「なぜ下げたか」の材料（下方修正・増資・急落など）が見つかったときの警告文。
    // Notionが単一の真実の源で、アプリは読むだけ（書かない）。
    // 【要注意】部分が前回と変わったときだけアプリから通知する（判定は domain.NewsWarning）。
    val newsWarning: String? = null,

    // --- ブックマーク（★。打診買い・押し目買いの方針を定めた銘柄の印）---
    // Notionの「ブックマーク」checkbox列と双方向で同期する（Notionが単一の真実の源）。
    // PC側（Claude）で方針を決めたときにNotionのチェックを入れれば、アプリの★タブに出る。
    // アプリで★を押した場合もNotionへ書き戻すので、値が2つに分裂しない。
    val bookmarked: Boolean = false,
    val weeklyRsi: Double? = null,       // 週足RSI(14)。確定週の終値ベース（WeeklyRsiで計算）
    val weeklyRsiWeek: String? = null,   // RSIの基準週（最終確定バーの週初日 "yyyy-MM-dd"）
    val weeklyRsiAsOf: Long = 0L,        // RSIの最終計算成功時刻（失敗時は前回値キャッシュで継続）

    // --- 売り時点灯（つるはし銘柄の売り時検証 2026-08-13。計算は domain.SellRules）---
    // 対象＝保有中(purchased)の日本株個別株のみ。ETF・米国株は検証エビデンスが無いため対象外。
    // 「売りルール除外」checkbox（Notion）がONの銘柄は監視しない（10倍株を狙って
    // 持ち続けると決めた銘柄をルールから外すための逃し弁）。
    val sellExcluded: Boolean = false,   // Notion「売りルール除外」列（trueなら監視しない）
    val sellArmed: Boolean = false,      // 売り監視中（12M+100%かつ52週高値-5%以内になったことがある）
    val sellArmDate: String? = null,     // アーム日（"yyyy-MM-dd"）
    val sellPeak: Double? = null,        // アーム後の最高終値（トレールの基準）
    val sellDropPct: Double? = null,     // 最高値からの下落率（％）
    val sellMa50: Double? = null,        // 50日移動平均（直近確定バー時点）
    val sellTrailFired: Boolean = false, // ① トレール-15% 点灯中（確定終値ベース）
    val sellMa50Fired: Boolean = false,  // ② 50日線割れ 点灯中（確定終値ベース）
    val sellAsOf: Long = 0L,             // 売り判定の最終計算成功時刻（失敗時は前回値で継続）

    // --- アラート重複防止フラグ（ラインを跨いだ瞬間だけ通知するため）---
    // true = 既にそのラインの通知を出した状態。ラインから外れたらfalseへ戻す。
    val dipArmed: Boolean = false,
    val deepArmed: Boolean = false,
    val breakoutArmed: Boolean = false,
    val stopArmed: Boolean = false,
    // 週足RSI過熱利確の通知済みフラグ（再armは「閾値-2を下回ったら」＝AlertEngine参照）
    val rsiTake1Armed: Boolean = false,  // 過熱利確①（RSI>75）
    val rsiTake2Armed: Boolean = false,  // 過熱利確②（RSI>80）
    // 売り時点灯の通知済みフラグ（再armのヒステリシスは SellRules の定数参照）
    val sellTrailArmed: Boolean = false, // 売り時①（トレール-15%）
    val sellMa50Armed: Boolean = false,  // 売り時②（50日線割れ）

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
