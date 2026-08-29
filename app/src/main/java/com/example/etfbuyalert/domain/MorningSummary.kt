package com.example.etfbuyalert.domain

import com.example.etfbuyalert.data.model.EtfState

/**
 * 毎朝サマリの本文を組み立てる純関数（画面もWorkerも通さないのでテストできる）。
 *
 * 【なぜ要約するのか（v1.23）】
 * 旧実装は監視中の全銘柄をティッカー順に流し込んでいた。監視が約190銘柄まで増えた結果、
 * 1銘柄2行×190で軽く1万字を超え、AndroidのNotificationは5,120字で本文を切るため、
 * ティッカーが若い先頭の数十銘柄しか読めなかった（しかも切れたことは画面に出ない）。
 * 朝に知りたいのは「いま発火しているか」「次に届きそうなのはどれか」の2点なので、
 *   ① 発火中（押し目・深押しに到達済み）
 *   ② 押し目までの距離が近い順 Top10
 *   ③ 残りは件数だけ
 * に要約する。並べ替えの基準は監視一覧と同じ AlertEngine.dipGapPercent（単一の真実の源）。
 */
object MorningSummary {

    /** 発火中の列挙上限。これを超えたら件数だけ添える（通知は読める長さに収める） */
    const val MAX_FIRED_LINES = 10

    /** 「押し目まで近い順」の列挙件数 */
    const val TOP_N = 10

    /** 組み立て結果（タイトルと本文） */
    data class Summary(val title: String, val body: String)

    /**
     * サマリを組み立てる。監視0件なら null（通知しない）。
     * @param now 判定時刻。鮮度ガード（7日超の古い価格を除外）に使う
     */
    fun build(states: List<EtfState>, now: Long = System.currentTimeMillis()): Summary? {
        if (states.isEmpty()) return null

        // 価格が古すぎて判定に使えない銘柄は本体から外し、件数だけ最後に添える
        val stale = states.filter { Freshness.isInvalid(it.asOf, now) }
        val usable = states.filter { !Freshness.isInvalid(it.asOf, now) }

        // 発火中＝押し目/深押しゾーンに到達済み（判定は AlertEngine が単一の真実の源）
        val fired = usable.filter { AlertEngine.isFired(it) }
            .sortedBy { AlertEngine.dipGapPercent(it, now) ?: Double.MAX_VALUE }
        val firedIds = fired.map { it.pageId.ifBlank { it.ticker } }.toSet()

        // 発火はしていないが押し目が近い順（距離を出せない銘柄は対象外＝末尾の件数に入る）
        val approaching = usable
            .filter { it.pageId.ifBlank { it.ticker } !in firedIds }
            .mapNotNull { st -> AlertEngine.dipGapPercent(st, now)?.let { st to it } }
            .sortedBy { it.second }
            .take(TOP_N)

        val sb = StringBuilder()
        if (fired.isEmpty()) {
            sb.append("発火中の銘柄はありません\n")
        } else {
            sb.append("🟢 発火中 ${fired.size}件\n")
            for (st in fired.take(MAX_FIRED_LINES)) {
                sb.append("• ").append(firedLine(st, now)).append("\n")
            }
            if (fired.size > MAX_FIRED_LINES) {
                sb.append("  ほか${fired.size - MAX_FIRED_LINES}件（発火中タブで確認）\n")
            }
        }

        if (approaching.isNotEmpty()) {
            sb.append("\n📉 押し目が近い順 Top${approaching.size}\n")
            for ((st, gap) in approaching) {
                sb.append("• ").append(nearLine(st, gap)).append("\n")
            }
        }

        // 本文で触れていない銘柄は件数だけ（「全部見えていない」ことを黙らせない）。
        // 列挙を打ち切った発火中の分は「ほか○件」で既に触れているので二重に数えない。
        val rest = states.size - fired.size - approaching.size - stale.size
        if (rest > 0) sb.append("\n他 ${rest}銘柄は監視中\n")
        if (stale.isNotEmpty()) sb.append("⚠ 価格取得に失敗中 ${stale.size}銘柄\n")

        val title = "☀ 朝サマリ（発火中${fired.size}件 / 全${states.size}銘柄）"
        return Summary(title, sb.toString().trimEnd())
    }

    // 発火中の1行：「1925 ¥4,594 押し目を2.3%下回る」
    private fun firedLine(st: EtfState, now: Long): String {
        val gap = AlertEngine.dipGapPercent(st, now)
        val zone = AlertEngine.currentZone(st, now)
        val depth = if (gap != null && gap <= 0) String.format(" 押し目を%.1f%%下回る", -gap) else ""
        return "${Symbol.display(st.ticker)} ${Money.format(st.ticker, st.price)}" +
                "  ［${zone.label}］$depth"
    }

    // 近い順の1行：「SMH $250.10 あと1.2%（押し目 $247.10）」
    private fun nearLine(st: EtfState, gap: Double): String =
        "${Symbol.display(st.ticker)} ${Money.format(st.ticker, st.price)}" +
                String.format("  あと%.1f%%", gap) +
                "（押し目 ${Money.format(st.ticker, st.dipPrice)}）"
}
