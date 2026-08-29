package com.example.etfbuyalert.domain

import com.example.etfbuyalert.data.model.EtfState

/**
 * 監視設定の健全性チェック（純関数。設定画面のバナーと週1回の通知が同じ結果を見る）。
 *
 * 【なぜ必要か】このアプリの一番たちの悪い壊れ方は「エラーで止まる」ではなく
 * 「設定が欠けていて、その銘柄だけ静かに監視されない」こと。
 * 実際に起こり得るのは次の2つで、どちらも画面上は普通の行に見える：
 *   ① 保有中(purchased)なのに損切りラインが空 … 撤退の合図が永遠に鳴らない
 *   ② ラインが4つとも空                       … 監視ONなのに判定材料がゼロ＝完全な沈黙
 * Notion側の入力漏れが原因なので、アプリからは直せない。だから「気づかせる」ことに徹する。
 */
object HealthCheck {

    /** 通知・バナーに並べるティッカーの上限（多すぎると読まれない） */
    const val MAX_SAMPLES = 8

    data class Report(
        /** 保有中なのに損切りラインが無い銘柄（正規形ティッカー） */
        val missingStopLoss: List<String>,
        /** 4本のラインがどれも無い銘柄（監視ONなのに判定できない） */
        val noLines: List<String>,
    ) {
        val total: Int get() = missingStopLoss.size + noLines.size
        val hasIssue: Boolean get() = total > 0
    }

    /** 監視中の全銘柄を点検する。states は同期後の最新状態を渡すこと。 */
    fun inspect(states: List<EtfState>): Report {
        val missingStop = ArrayList<String>()
        val noLines = ArrayList<String>()
        for (st in states) {
            if (st.purchased && st.stopLossPrice == null) missingStop.add(Symbol.display(st.ticker))
            val allEmpty = st.dipPrice == null && st.deepDipPrice == null &&
                    st.breakoutPrice == null && st.stopLossPrice == null
            if (allEmpty) noLines.add(Symbol.display(st.ticker))
        }
        return Report(missingStop.sorted(), noLines.sorted())
    }

    /**
     * 通知・バナー用の本文（問題が無ければ null）。
     * 表示と通知で文言を二重に書かないよう、ここが唯一の組み立て場所。
     */
    fun message(report: Report): String? {
        if (!report.hasIssue) return null
        val sb = StringBuilder()
        if (report.missingStopLoss.isNotEmpty()) {
            sb.append("・保有中なのに損切りラインが未設定：${report.missingStopLoss.size}件\n")
            sb.append("  ").append(sample(report.missingStopLoss)).append("\n")
        }
        if (report.noLines.isNotEmpty()) {
            sb.append("・ラインが1本も無い（監視できていない）：${report.noLines.size}件\n")
            sb.append("  ").append(sample(report.noLines)).append("\n")
        }
        sb.append("Notionの該当行に価格を入れると次の同期で監視が始まります。")
        return sb.toString()
    }

    /** ティッカーの列挙（上限を超えたら「ほか○件」に丸める） */
    private fun sample(items: List<String>): String {
        val head = items.take(MAX_SAMPLES).joinToString(", ")
        return if (items.size > MAX_SAMPLES) "$head ほか${items.size - MAX_SAMPLES}件" else head
    }
}
