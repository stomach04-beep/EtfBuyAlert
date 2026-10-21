package com.example.etfbuyalert

import com.example.etfbuyalert.data.network.YahooFinanceClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// 前日終値の取り出し（v1.27）。range=5d の chartPreviousClose は約5営業日前の終値なので使わず、
// 終値配列から「最新取引日より前の最後の終値」を取る。値は 2026-10-22 に取った VOO の実データ。
class PrevCloseTest {
    // VOO の日足の時刻（米国東部 9:30 の UTC 秒）と終値。10/15〜10/21 の5本
    private val ts = listOf(1792071000L, 1792157400L, 1792416600L, 1792503000L, 1792589400L)
    private val closes = listOf<Double?>(709.43, 701.61, 704.60, 706.80, 708.98)
    private val nyOffset = -14400L   // 夏時間の東部時間（UTC-4）

    @Test
    fun 引け後は最新の足の1本前が前日終値() {
        // regularMarketTime＝10/21 15:05 ET（取得時点の最新取引日は10/21）
        val rmt = 1792610193L
        assertEquals(706.80, YahooFinanceClient.prevCloseBefore(ts, closes, rmt, nyOffset)!!, 1e-9)
    }

    @Test
    fun 最新の足がまだ無い朝は最後の足が前日終値() {
        // 足が10/20までしか無く、最新取引日も10/20のとき → 10/17 の終値
        val rmt = 1792525000L
        assertEquals(704.60, YahooFinanceClient.prevCloseBefore(ts.take(4), closes.take(4), rmt, nyOffset)!!, 1e-9)
    }

    @Test
    fun 前日の足がnullなら飛ばしてその前を使う() {
        val withNull = listOf<Double?>(709.43, 701.61, 704.60, null, 708.98)
        val rmt = 1792610193L
        assertEquals(704.60, YahooFinanceClient.prevCloseBefore(ts, withNull, rmt, nyOffset)!!, 1e-9)
    }

    @Test
    fun 前の足が無ければnull() {
        val rmt = 1792610193L
        assertNull(YahooFinanceClient.prevCloseBefore(ts.takeLast(1), closes.takeLast(1), rmt, nyOffset))
    }
}
