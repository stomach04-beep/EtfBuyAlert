package com.example.etfbuyalert

import com.example.etfbuyalert.data.model.ChartPoint
import com.example.etfbuyalert.domain.Ma200Lines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端末内のMA200ライン計算が、PC版 etf-watchlist-ma-updater と同じ数値を出すかの検証。
 *
 * 期待値は PC版 update_etf_watchlist_ma.py の compute_lines と同一の式で別途算出した値。
 * 同じ計算を2か所に持つ以上、片方だけ直すとズレる。ここが「ズレていない」ことの担保。
 * ルールを変える場合はPC版・本テスト・Ma200Lines の3つを必ず同時に直すこと。
 */
class Ma200LinesTest {

    /** 終値の配列をChartPointの列に変換（時刻は連番。計算では使わない） */
    private fun bars(closes: List<Double>): List<ChartPoint> =
        closes.mapIndexed { i, c -> ChartPoint(t = i.toLong(), close = c) }

    @Test
    fun 上昇トレンド_現値が200日線より上ならPC版と一致する() {
        val closes = (0 until 250).map { 100.0 + it * 0.5 }
        val r = Ma200Lines.compute(bars(closes))!!
        assertEquals(174.75, r.dip, 0.005)        // 200日線
        assertEquals(166.01, r.deepDip, 0.005)    // ×0.95
        assertEquals(228.99, r.breakout, 0.005)   // max(20日高値, 現値×1.02)
        assertEquals(148.54, r.stopLoss, 0.005)   // ×0.85
        assertEquals(200, r.maWindowUsed)
        assertTrue(!r.isSubstituteWindow)
    }

    @Test
    fun 下降トレンド_現値が200日線より下なら順張りは200日線が下限になる() {
        val closes = (0 until 250).map { 200.0 - it * 0.4 }
        val r = Ma200Lines.compute(bars(closes))!!
        assertEquals(140.20, r.dip, 0.005)
        assertEquals(133.19, r.deepDip, 0.005)
        // 現値100.40 なので max(20日高値, 現値×1.02) は200日線より下になる。
        // 下限ガードが効いて 200日線(=140.20) になるのが正しい。
        // ここが効いていないと、退避圏なのに「順張り突破」通知が出る。
        assertEquals(140.20, r.breakout, 0.005)
        assertEquals(119.17, r.stopLoss, 0.005)
    }

    @Test
    fun 本数不足なら短い移動平均で代用しその旨を持つ() {
        val closes = (0 until 120).map { 50.0 + (it % 7) }
        val r = Ma200Lines.compute(bars(closes))!!
        assertEquals(52.98, r.dip, 0.005)
        assertEquals(50.33, r.deepDip, 0.005)
        assertEquals(56.00, r.breakout, 0.005)
        assertEquals(45.03, r.stopLoss, 0.005)
        assertEquals(120, r.maWindowUsed)
        assertTrue(r.isSubstituteWindow)   // 200日線ではないことを呼び出し側が知れる
    }

    @Test
    fun データが少なすぎる場合はnullを返す_既存ラインを壊さないため() {
        val closes = (0 until 59).map { 100.0 }
        assertNull(Ma200Lines.compute(bars(closes)))
    }

    @Test
    fun 欠損値は除外して計算する() {
        // 0以下やNaNが混ざってもPC版と同じ結果になること（Yahoo応答の欠損行対策）
        val clean = (0 until 250).map { 100.0 + it * 0.5 }
        val dirty = clean.toMutableList().also {
            it.add(60, 0.0)
            it.add(120, -1.0)
            it.add(180, Double.NaN)
        }
        val a = Ma200Lines.compute(bars(clean))!!
        val b = Ma200Lines.compute(bars(dirty))!!
        assertEquals(a.dip, b.dip, 0.005)
        assertEquals(a.breakout, b.breakout, 0.005)
    }
}
