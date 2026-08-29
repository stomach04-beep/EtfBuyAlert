package com.example.etfbuyalert

import com.example.etfbuyalert.data.model.EtfState
import com.example.etfbuyalert.domain.HealthCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 監視設定の健全性チェック（F1）の検証。
 * 設定画面のバナーと週1回の通知が同じ結果を見るため、判定はここ1か所しかない。
 */
class HealthCheckTest {

    private fun state(
        ticker: String,
        purchased: Boolean = false,
        dip: Double? = 100.0,
        deep: Double? = null,
        breakout: Double? = null,
        stop: Double? = null,
    ) = EtfState(
        pageId = "p_$ticker",
        ticker = ticker,
        name = "銘柄$ticker",
        dipPrice = dip,
        deepDipPrice = deep,
        breakoutPrice = breakout,
        stopLossPrice = stop,
        purchased = purchased,
    )

    @Test
    fun 保有中で損切りラインが無い銘柄を拾う() {
        val states = listOf(
            state("AAA", purchased = true, stop = null),
            state("BBB", purchased = true, stop = 80.0),   // 設定済み＝問題なし
            state("CCC", purchased = false, stop = null),  // 未保有なら損切りは要らない
        )
        val r = HealthCheck.inspect(states)
        assertEquals(listOf("AAA"), r.missingStopLoss)
        assertTrue(r.hasIssue)
    }

    @Test
    fun ラインが1本も無い銘柄を拾う() {
        val states = listOf(
            state("EMPTY", dip = null, deep = null, breakout = null, stop = null),
            state("OK", dip = 100.0),
        )
        val r = HealthCheck.inspect(states)
        assertEquals(listOf("EMPTY"), r.noLines)
    }

    @Test
    fun 日本株は画面表記の4桁コードで報告する() {
        val states = listOf(state("1925.T", purchased = true, stop = null))
        val r = HealthCheck.inspect(states)
        assertEquals(listOf("1925"), r.missingStopLoss)
    }

    @Test
    fun 問題が無ければ通知文を作らない() {
        val states = listOf(state("OK", purchased = true, stop = 80.0))
        val r = HealthCheck.inspect(states)
        assertFalse(r.hasIssue)
        assertNull(HealthCheck.message(r))
    }

    @Test
    fun 件数が多いときは列挙を丸める() {
        val states = (1..20).map { state("T$it", purchased = true, stop = null) }
        val r = HealthCheck.inspect(states)
        assertEquals(20, r.total)
        val msg = HealthCheck.message(r)!!
        assertTrue(msg.contains("ほか${20 - HealthCheck.MAX_SAMPLES}件"))
    }

    @Test
    fun 両方の問題を同時に数える() {
        val states = listOf(
            state("A", purchased = true, stop = null),
            state("B", dip = null, deep = null, breakout = null, stop = null),
        )
        val r = HealthCheck.inspect(states)
        // Bは「保有していない」ので損切り側には出ず、ライン無し側にだけ出る
        assertEquals(1, r.missingStopLoss.size)
        assertEquals(1, r.noLines.size)
        assertEquals(2, r.total)
    }
}
