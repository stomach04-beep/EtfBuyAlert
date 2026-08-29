package com.example.etfbuyalert

import com.example.etfbuyalert.data.model.EtfState
import com.example.etfbuyalert.domain.WatchRanking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 監視一覧の並べ替え・検索の検証（U2の検索欄と、F4のウィジェット上位3件が
 * 同じロジックを使う＝アプリとウィジェットで順番がズレないことの担保）。
 */
class WatchRankingTest {

    private val now = System.currentTimeMillis()

    private fun state(
        ticker: String,
        name: String = "銘柄$ticker",
        price: Double? = 100.0,
        dip: Double? = 100.0,
        asOf: Long = now,
    ) = EtfState(
        pageId = "p_$ticker",
        ticker = ticker,
        name = name,
        dipPrice = dip,
        price = price,
        asOf = asOf,
    )

    @Test
    fun 押し目が近い順に並び距離を出せない銘柄は末尾へ回る() {
        val states = listOf(
            state("FAR", price = 150.0, dip = 100.0),    // +50%
            state("NONE", price = null, dip = null),      // 判定不能
            state("HIT", price = 90.0, dip = 100.0),      // -10%（発火中）
            state("NEAR", price = 105.0, dip = 100.0),    // +5%
        )
        val sorted = WatchRanking.byDipGap(states, now).map { it.ticker }
        assertEquals(listOf("HIT", "NEAR", "FAR", "NONE"), sorted)
    }

    @Test
    fun ウィジェットの上位3件は判定不能な銘柄を含まない() {
        val states = listOf(
            state("A", price = 90.0, dip = 100.0),
            state("B", price = 101.0, dip = 100.0),
            state("NONE1", price = null, dip = null),
            state("C", price = 102.0, dip = 100.0),
            state("D", price = 130.0, dip = 100.0),
        )
        val top = WatchRanking.topByDipGap(states, 3, now).map { it.ticker }
        assertEquals(listOf("A", "B", "C"), top)
    }

    @Test
    fun 発火中の件数は押し目到達で数える() {
        val states = listOf(
            state("HIT1", price = 90.0, dip = 100.0),
            state("HIT2", price = 100.0, dip = 100.0),   // ちょうど到達も発火
            state("NO", price = 101.0, dip = 100.0),
        )
        assertEquals(2, WatchRanking.firedCount(states))
    }

    @Test
    fun 検索はティッカーと銘柄名の両方に当たる() {
        val st = state("SMH", name = "半導体ETF")
        assertTrue(WatchRanking.matchesQuery(st, "smh"))     // 大文字小文字を区別しない
        assertTrue(WatchRanking.matchesQuery(st, "半導体"))
        assertFalse(WatchRanking.matchesQuery(st, "VOO"))
    }

    @Test
    fun 日本株は画面表記の4桁コードでも探せる() {
        // 内部は正規形 "1925.T" だが、ユーザーが打つのは "1925"
        val st = state("1925.T", name = "大和ハウス工業")
        assertTrue(WatchRanking.matchesQuery(st, "1925"))
        assertTrue(WatchRanking.matchesQuery(st, "1925.T"))
        assertTrue(WatchRanking.matchesQuery(st, "大和"))
    }

    @Test
    fun 空白だけの検索は絞り込まない() {
        val states = listOf(state("A"), state("B"))
        assertEquals(2, WatchRanking.search(states, "   ", now).size)
    }

    @Test
    fun 検索結果も押し目が近い順に並ぶ() {
        val states = listOf(
            state("XA", name = "テスト", price = 150.0, dip = 100.0),
            state("XB", name = "テスト", price = 95.0, dip = 100.0),
        )
        val result = WatchRanking.search(states, "テスト", now).map { it.ticker }
        assertEquals(listOf("XB", "XA"), result)
    }
}
