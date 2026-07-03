package com.example.etfbuyalert.domain

// ETFのテーマ/セクター分類の「単一の真実の源」（DRY）。
// ティッカー → カテゴリ名 の対応表をここだけに持つ。
// 画面のグループ分けは必ずこの object を経由する。
//
// 分類の決め方：
//   1) Notionの「カテゴリ」列に値があればそれを最優先（ユーザーの上書き）
//   2) 無ければ下の対応表（MAP）で自動判定
//   3) どちらも無ければ「未分類」
object EtfCategory {

    // 「未分類」の表示名（対応表に無い銘柄の受け皿）
    const val UNCATEGORIZED = "未分類"

    // ティッカー（大文字）→ カテゴリ名 の対応表。
    // 新しいETFを足したらここに1行追加するだけでテーマ別表示に反映される。
    private val MAP: Map<String, String> = mapOf(
        // 米国大型指数（S&P500・ナスダック100 など）
        "VOO" to "米国指数",
        "VTI" to "米国指数",
        "SPY" to "米国指数",
        "IVV" to "米国指数",
        "QQQ" to "米国指数",
        "QQQM" to "米国指数",

        // グロース（成長株）系
        "SCHG" to "グロース",
        "VUG" to "グロース",
        "MGK" to "グロース",
        "VGT" to "グロース",
        "XLK" to "グロース",

        // 半導体
        "SMH" to "半導体",
        "SOXX" to "半導体",
        "SOXL" to "半導体",

        // 原子力・ウラン
        "URA" to "原子力・ウラン",
        "URNM" to "原子力・ウラン",
        "NLR" to "原子力・ウラン",
        "NUKZ" to "原子力・ウラン",

        // コモディティ・金（ドル建て）
        "GLDM" to "コモディティ・金",
        "GLD" to "コモディティ・金",
        "IAU" to "コモディティ・金",
        "SGOL" to "コモディティ・金",
        "GDX" to "コモディティ・金",
        "GDXJ" to "コモディティ・金",
        // コモディティ・金（円建て・東京上場。ティッカーはYahoo記号そのまま .T 付き）
        "1540.T" to "コモディティ・金",   // 純金上場信託（現物裏付け）
        "1326.T" to "コモディティ・金",   // SPDRゴールド・シェア 東証
    )

    // 表示順（この順で見出しを並べる。表に無いカテゴリは後ろ、未分類は最後）。
    private val ORDER: List<String> = listOf(
        "米国指数", "グロース", "半導体", "原子力・ウラン", "コモディティ・金",
    )

    // ティッカーからカテゴリを引く（対応表に無ければ「未分類」）。
    fun of(ticker: String?): String {
        val key = ticker?.trim()?.uppercase() ?: return UNCATEGORIZED
        return MAP[key] ?: UNCATEGORIZED
    }

    // カテゴリ名の並び順スコア（小さいほど先頭、未分類は最後）。
    fun orderIndex(category: String): Int {
        val i = ORDER.indexOf(category)
        return when {
            i >= 0 -> i                       // 既知カテゴリ：定義順
            category == UNCATEGORIZED -> 9999 // 未分類は常に最後
            else -> 9000                      // それ以外（Notionで追加された名前）は未分類の手前
        }
    }
}
