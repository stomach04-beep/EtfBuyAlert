package com.example.etfbuyalert.domain

import com.example.etfbuyalert.data.model.ChartPoint
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// ============================================================
// 週足RSI(14)の計算（レバレッジETFの利確規律用）。
// 「週足RSI(14) > 75 で1/3利確 → > 80 でさらに1/3」のシグナル判定に使う
// （バックテスト検証済みルール）。計算ロジックはこのファイルが
// 単一の真実の源（DRY）。表示側・判定側で再定義しない。
//
// 【未確定バーの除外】ルールは「週足終値」で判定するため、進行中の
// 今週バー（終値がまだ確定していない）は計算から落とす。Yahooの週足は
// 最終バーが当週の途中値になるので、これを混ぜるとザラ場の上下で
// シグナルがブレる。
// ============================================================
object WeeklyRsi {

    private const val PERIOD = 14   // RSIの期間（週）

    // 計算結果：RSI値と、その値の基準週（最終確定バーの週初日 "yyyy-MM-dd"）
    data class Value(val rsi: Double, val weekOf: String)

    /**
     * 週足バー列（古い順・ChartPoint.t=epoch秒）から週足RSI(14)を計算する。
     * 最終バーが「今週」に属する場合は未確定として除外してから計算する。
     * データ不足（確定バーが15本未満）などで計算できないときは null。
     */
    fun calculate(points: List<ChartPoint>, now: Long = System.currentTimeMillis()): Value? {
        if (points.isEmpty()) return null
        // 念のため時刻順に整える（Yahooは昇順で返すが、順序前提が崩れるとRSIが壊れるため）
        val sorted = points.sortedBy { it.t }

        // --- 未確定の当週バーを除外 ---
        // 最終バーのタイムスタンプが今週（月曜始まり）に属するなら落とす
        val thisWeekStart = weekStart(now)
        val confirmed = if (weekStart(sorted.last().t * 1000L) == thisWeekStart) {
            sorted.dropLast(1)
        } else sorted

        val rsi = compute(confirmed.map { it.close }) ?: return null
        // 基準週＝最終確定バーの週初日（表示・デバッグ用に保持する）
        val weekOf = weekStart(confirmed.last().t * 1000L).toString()
        return Value(rsi, weekOf)
    }

    /**
     * Wilder法のRSI(14)。
     * 前週比の上げ幅(gain)/下げ幅(loss)を alpha=1/14 のEMAで平滑化する
     * （初期値は最初の14本の単純平均＝Wilderの標準的な計算方法）。
     * 終値が PERIOD+1 本（15本）未満なら計算不可で null。
     */
    fun compute(closes: List<Double>, period: Int = PERIOD): Double? {
        if (period <= 0 || closes.size < period + 1) return null

        // 前週比の変化量（+なら上げ、−なら下げ）
        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1..period) {
            val diff = closes[i] - closes[i - 1]
            if (diff > 0) avgGain += diff else avgLoss += -diff
        }
        avgGain /= period
        avgLoss /= period

        // 15本目以降はWilderの平滑化（= EMA alpha=1/period）で更新
        for (i in period + 1 until closes.size) {
            val diff = closes[i] - closes[i - 1]
            val gain = if (diff > 0) diff else 0.0
            val loss = if (diff < 0) -diff else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
        }

        // 下げが一度も無い（avgLoss=0）ときはRSI=100
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - 100.0 / (1.0 + rs)
    }

    // epochミリ秒 → その時刻が属する週の月曜日（端末タイムゾーン基準）。
    // 「同じ週か」の比較キーに使う。
    private fun weekStart(epochMs: Long): LocalDate {
        val date = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
        return date.with(DayOfWeek.MONDAY)
    }
}
