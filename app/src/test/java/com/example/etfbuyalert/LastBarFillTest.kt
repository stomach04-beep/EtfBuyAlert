package com.example.etfbuyalert

import com.example.etfbuyalert.data.model.ChartPoint
import com.example.etfbuyalert.domain.LastBarFill
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 末尾null足の補完（LastBarFill・v1.26）の検証。
 * 日足の時刻は 2026-10-15 に実測した ^N225（range=6mo・interval=1d）の値:
 *   10-13 の足 = 1791849600（09:00 JST）… 終値 67777.52
 *   10-14 の足 = 1791936000（09:00 JST）… close=null で返ってきた
 *   meta.regularMarketTime = 1791960303（10-14 15:45 JST）/ regularMarketPrice = 67744.29
 */
class LastBarFillTest {

    private val d1013 = 1791849600L
    private val d1014 = 1791936000L
    private val meta1014 = 1791960303L
    private val daily = LastBarFill.DAILY_SEC
    private val weekly = LastBarFill.WEEKLY_SEC
    private val before = listOf(ChartPoint(d1013, 67777.52))   // 10-14 は null で捨てられた状態

    @Test
    fun 日足の末尾がnullならmetaの値段で埋まる() {
        val out = LastBarFill.fill(before, d1014, meta1014, 67744.29, daily)
        assertEquals(2, out.size)
        assertEquals(d1014, out.last().t)
        assertEquals(67744.29, out.last().close, 1e-9)
    }

    @Test
    fun 末尾が入っていれば何もしない() {
        val full = before + ChartPoint(d1014, 67744.29)
        assertEquals(full, LastBarFill.fill(full, d1014, meta1014, 999.0, daily))
    }

    @Test
    fun metaが前の足の時刻なら埋めない() {
        // meta が前日（10-13 15:45）のまま＝10-14 の値段は持っていない
        assertEquals(before, LastBarFill.fill(before, d1014, meta1014 - 86_400, 67777.52, daily))
    }

    @Test
    fun metaが足の時間幅より先なら埋めない() {
        // meta が翌日の場中（10-15 10:00）まで進んでいたら、10-14 の足の値段ではない
        assertEquals(before, LastBarFill.fill(before, d1014, d1014 + 86_400 + 3_600, 67000.0, daily))
    }

    @Test
    fun metaが欠けていれば埋めない() {
        assertEquals(before, LastBarFill.fill(before, d1014, meta1014, null, daily))
        assertEquals(before, LastBarFill.fill(before, d1014, meta1014, Double.NaN, daily))
        assertEquals(before, LastBarFill.fill(before, d1014, 0L, 67744.29, daily))
        assertEquals(before, LastBarFill.fill(before, d1014, meta1014, 0.0, daily))
    }

    @Test
    fun 米国の日足も同じ式で埋まる() {
        // 2026-09-18 09:30 ET の足（=13:30 UTC）と、同日 16:00 ET の meta（=20:00 UTC）。
        // 時間帯を使わない時間幅の判定なので、JST で日付をまたいでも埋まる
        val bar = 1789738200L
        val prev = listOf(ChartPoint(bar - 86_400, 6500.0))
        val out = LastBarFill.fill(prev, bar, 1789761600L, 6510.0, daily)
        assertEquals(6510.0, out.last().close, 1e-9)
    }

    @Test
    fun 週足の進行中の週は時刻が一致していても埋まる() {
        // 週足の最新足は timestamp＝regularMarketTime で返る（2026-10-15 実測・1306.T 5y/1wk）
        val weekStart = 1791763200L          // 10-12 の週
        val liveBar = 1791958200L            // 10-14 15:30 JST（＝meta の時刻）
        val prev = listOf(ChartPoint(weekStart - 7 * 86_400, 430.0))
        val out = LastBarFill.fill(prev, liveBar, liveBar, 431.3, weekly)
        assertEquals(liveBar, out.last().t)
        assertEquals(431.3, out.last().close, 1e-9)
    }

    @Test
    fun 週足でも8日以上先のmetaでは埋めない() {
        val prev = listOf(ChartPoint(d1013 - 7 * 86_400, 430.0))
        assertEquals(prev, LastBarFill.fill(prev, d1013, d1013 + 8 * 86_400, 440.0, weekly))
    }
}
