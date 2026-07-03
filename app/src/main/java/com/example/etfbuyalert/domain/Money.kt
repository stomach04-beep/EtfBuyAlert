package com.example.etfbuyalert.domain

// 金額表示の単一の真実の源（DRY）。ティッカーの末尾 ".T"（東証）なら円、それ以外はドル。
// 例: 1540.T → ¥21,100 ／ VOO → $632.90
object Money {
    fun format(ticker: String, v: Double?): String = when {
        v == null -> "—"
        ticker.uppercase().endsWith(".T") -> "¥" + String.format("%,.0f", v)
        else -> "$" + String.format("%,.2f", v)
    }
}
