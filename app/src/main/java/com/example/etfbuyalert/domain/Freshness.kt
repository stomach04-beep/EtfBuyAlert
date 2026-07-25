package com.example.etfbuyalert.domain

// ============================================================
// 価格の鮮度ガード（BargainCheckerの鮮度ガードと同じ流儀）。
// 取得経路が死ぬと「前回値の持ち越し」が無期限に続き、古い価格が
// 「現在値」として通知・表示され続ける（2026-07-17監査で指摘）。
// 最終取得成功時刻（EtfState.asOf＝取得成功時のみ更新される）からの
// 経過で3段階に扱いを変える：
//   〜3日   … 通常表示（週末・連休の市場休場はこの範囲に収まる）
//   3日超   … 価格に「（◯日前の値）」を添えて最新値と誤解させない
//   7日超   … 価格を無効扱い。表示・通知に出さず「取得失敗」を明示し、
//             ライン到達判定（AlertEngine）からも除外する
// 判定・閾値・文言ともここが単一の真実の源（DRY）。2か所に書かない。
// ============================================================
object Freshness {

    const val WARN_DAYS = 3      // 「（◯日前の値）」注記を付け始める日数
    const val INVALID_DAYS = 7   // 価格を無効扱いにする日数

    // 7日超のときに価格の代わりに出す文言（監視一覧・詳細・朝サマリで共通）
    const val INVALID_TEXT = "⚠️価格取得が7日以上失敗"

    private const val DAY_MS = 24L * 60 * 60 * 1000

    // 最終取得成功からの経過日数（切り捨て）。
    // 未取得(asOf<=0)は0を返す（そのとき価格自体がnullなので表示側は「—」になる）
    fun ageDays(asOf: Long, now: Long = System.currentTimeMillis()): Int =
        if (asOf <= 0L) 0 else ((now - asOf) / DAY_MS).toInt()

    // 7日超＝価格を無効扱い（表示・通知・ライン到達判定に使わない）
    fun isInvalid(asOf: Long, now: Long = System.currentTimeMillis()): Boolean =
        asOf > 0L && now - asOf > INVALID_DAYS * DAY_MS

    // 3日超〜7日以内＝価格は出すが「（◯日前の値）」を添える
    fun isWarn(asOf: Long, now: Long = System.currentTimeMillis()): Boolean =
        asOf > 0L && now - asOf > WARN_DAYS * DAY_MS && !isInvalid(asOf, now)

    // 価格に添える注記（新鮮=""／3日超="（◯日前の値）"）。7日超は価格自体を出さない前提。
    fun staleSuffix(asOf: Long, now: Long = System.currentTimeMillis()): String =
        if (isWarn(asOf, now)) "（${ageDays(asOf, now)}日前の値）" else ""
}
