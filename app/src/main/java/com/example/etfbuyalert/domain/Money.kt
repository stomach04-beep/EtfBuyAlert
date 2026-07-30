package com.example.etfbuyalert.domain

// 金額表示の単一の真実の源（DRY）。円/ドルの判定は Symbol に委ねる（同じ判定を2か所に書かない）。
// 例: 1540.T → ¥21,100 ／ 1925.T → ¥4,594 ／ VOO → $632.90
// ※ 渡すティッカーは Symbol.normalize 済みの正規形であること（Notion同期の入口で正規化している）。
object Money {
    fun format(ticker: String, v: Double?): String = when {
        v == null -> "—"
        Symbol.isJp(ticker) -> "¥" + String.format("%,.0f", v)
        else -> "$" + String.format("%,.2f", v)
    }
}
