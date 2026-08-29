package com.example.etfbuyalert

import com.example.etfbuyalert.domain.MarketHours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/**
 * 閉場中スキップ（F3）の検証。すべて JST 固定（DSTは考慮しない設計＝MarketHours参照）。
 *
 * 肝は2つ：
 *   ・閉じている時間に190銘柄ぶんの無駄うちをしないこと
 *   ・それでも「その日の終値」を必ず1回は拾えること（アラームを逃した日も）
 */
class MarketHoursTest {

    /** JSTの日時を作る（2026-08-31 は月曜） */
    private fun jst(y: Int, m: Int, d: Int, h: Int, min: Int = 0): ZonedDateTime =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, MarketHours.JST)

    // ===== 日本株 =====
    @Test
    fun 日本株は平日の立会時間なら取得する() {
        // 2026-09-01(火) 10:00
        val d = MarketHours.decide(true, jst(2026, 9, 1, 10), lastClosingDate = null)
        assertTrue(d.fetch)
        assertFalse(d.isClosingRun)
        assertEquals("2026-09-01", d.sessionKey)
    }

    @Test
    fun 日本株の場前と大引け後は立会時間ではない() {
        assertFalse(MarketHours.inRegularWindow(true, jst(2026, 9, 1, 8, 30)))
        assertTrue(MarketHours.inRegularWindow(true, jst(2026, 9, 1, 8, 55)))
        assertTrue(MarketHours.inRegularWindow(true, jst(2026, 9, 1, 15, 35)))
        assertFalse(MarketHours.inRegularWindow(true, jst(2026, 9, 1, 15, 40)))
    }

    @Test
    fun 日本株は16時台にクロージング取得する() {
        val d = MarketHours.decide(true, jst(2026, 9, 1, 16, 5), lastClosingDate = null)
        assertTrue(d.fetch)
        assertTrue(d.isClosingRun)
        assertEquals("2026-09-01", d.sessionKey)
    }

    @Test
    fun 日本株はクロージング取得済みなら夜間はスキップする() {
        val d = MarketHours.decide(true, jst(2026, 9, 1, 21), lastClosingDate = "2026-09-01")
        assertFalse(d.fetch)
    }

    @Test
    fun 日本株はクロージング未実施なら閉場中でも1回だけ取得する() {
        // 16時台のアラームをDozeで逃した想定。21時でも当日ぶんを拾いに行く
        val d = MarketHours.decide(true, jst(2026, 9, 1, 21), lastClosingDate = "2026-08-31")
        assertTrue(d.fetch)
        assertTrue(d.isClosingRun)
        assertEquals("2026-09-01", d.sessionKey)
    }

    @Test
    fun 日本株は土日はスキップする() {
        // 2026-09-05(土)。直近の立会日は金曜で、その終値は取得済み
        val sat = MarketHours.decide(true, jst(2026, 9, 5, 12), lastClosingDate = "2026-09-04")
        assertFalse(sat.fetch)
        assertEquals("2026-09-04", sat.sessionKey)
        // 2026-09-06(日) も同じ立会日を指す
        val sun = MarketHours.decide(true, jst(2026, 9, 6, 12), lastClosingDate = "2026-09-04")
        assertFalse(sun.fetch)
    }

    @Test
    fun 日本株の月曜早朝は前の金曜が直近の立会日になる() {
        // 2026-09-07(月) 06:00＝まだ場が始まっていない
        val d = MarketHours.decide(true, jst(2026, 9, 7, 6), lastClosingDate = "2026-09-04")
        assertFalse(d.fetch)
        assertEquals("2026-09-04", d.sessionKey)
    }

    // ===== 米国株 =====
    @Test
    fun 米国株は夜間の立会時間なら取得する() {
        // 2026-09-01(火) 23:00 JST ＝ 米国の火曜の寄り。引けるのは JST 水曜 07:00
        val d = MarketHours.decide(false, jst(2026, 9, 1, 23), lastClosingDate = null)
        assertTrue(d.fetch)
        assertFalse(d.isClosingRun)
        assertEquals("2026-09-02", d.sessionKey)
    }

    @Test
    fun 米国株は未明も立会時間として取得する() {
        // 2026-09-02(水) 03:00 JST ＝ 米国の火曜の後場
        assertTrue(MarketHours.inRegularWindow(false, jst(2026, 9, 2, 3)))
        assertFalse(MarketHours.inRegularWindow(false, jst(2026, 9, 2, 8)))
        assertFalse(MarketHours.inRegularWindow(false, jst(2026, 9, 2, 15)))
    }

    @Test
    fun 米国株は7時台にクロージング取得する() {
        val d = MarketHours.decide(false, jst(2026, 9, 2, 7, 10), lastClosingDate = null)
        assertTrue(d.fetch)
        assertTrue(d.isClosingRun)
        assertEquals("2026-09-02", d.sessionKey)
    }

    @Test
    fun 米国株はクロージング取得済みなら日中はスキップする() {
        val d = MarketHours.decide(false, jst(2026, 9, 2, 13), lastClosingDate = "2026-09-02")
        assertFalse(d.fetch)
    }

    @Test
    fun 米国株はクロージング未実施なら日中でも1回だけ取得する() {
        val d = MarketHours.decide(false, jst(2026, 9, 2, 13), lastClosingDate = "2026-09-01")
        assertTrue(d.fetch)
        assertTrue(d.isClosingRun)
    }

    @Test
    fun 米国株の日曜と月曜日中は直近の引けが土曜になる() {
        // JST 日曜は米国に立会が無い。直近の引けは土曜07:00（＝金曜の取引）
        val sun = MarketHours.decide(false, jst(2026, 9, 6, 12), lastClosingDate = "2026-09-05")
        assertFalse(sun.fetch)
        assertEquals("2026-09-05", sun.sessionKey)
        // 月曜の日中もまだ次の立会は始まっていない（始まるのは月曜22:00 JST）
        val mon = MarketHours.decide(false, jst(2026, 9, 7, 12), lastClosingDate = "2026-09-05")
        assertFalse(mon.fetch)
        assertEquals("2026-09-05", mon.sessionKey)
    }

    @Test
    fun 米国株は月曜22時から次の立会が始まる() {
        val d = MarketHours.decide(false, jst(2026, 9, 7, 22), lastClosingDate = "2026-09-05")
        assertTrue(d.fetch)
        assertFalse(d.isClosingRun)
        assertEquals("2026-09-08", d.sessionKey)   // 引けは火曜07:00 JST
    }

    @Test
    fun 米国株は土曜夜には立会が無い() {
        assertFalse(MarketHours.inRegularWindow(false, jst(2026, 9, 5, 23)))
        assertFalse(MarketHours.inRegularWindow(false, jst(2026, 9, 6, 3)))   // 日曜未明
    }
}
