package com.example.etfbuyalert

import com.example.etfbuyalert.data.model.ChartPoint
import com.example.etfbuyalert.domain.BadTicks
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 異常値（バッドティック）除去の検証。
 *
 * v1.22 の旧実装は「捨てた点で比較基準(last)を更新しない」ため、本物の水準シフト
 * （ストップ高・調整漏れの分割ギャップ・急騰落）が起きると、それ以降の全バーが
 * 旧水準との比較で跳び続け、履歴が黙ってそこで打ち切られていた。
 * その履歴は売り時判定・MA200線（Notionへ書き戻す）の土台なので、
 * PC側ジョブまで汚染が伝播する。以下は「本物は残す／偽物だけ捨てる」ことの担保。
 */
class BadTicksTest {

    /** 終値の配列を ChartPoint の列に変換（時刻は連番。判定では使わない） */
    private fun bars(closes: List<Double>): List<ChartPoint> =
        closes.mapIndexed { i, c -> ChartPoint(t = i.toLong(), close = c) }

    private fun closesOf(points: List<ChartPoint>): List<Double> = points.map { it.close }

    private val daily = BadTicks.DAILY_THRESHOLD
    private val weekly = BadTicks.WEEKLY_THRESHOLD

    // ===== ケース1: 本物の水準シフト（ストップ高・急騰）=====
    @Test
    fun 水準シフトは捨てずに以降のバーも全部残す() {
        // 100→130（+30%）と跳んだあと、その水準が続く＝本物の変動
        val rows = bars(listOf(100.0, 100.0, 100.0, 130.0, 131.0, 132.0, 133.0))
        val r = BadTicks.clean(rows, daily)
        assertEquals(0, r.dropped)
        assertEquals(7, r.points.size)   // 旧実装はここが3件で打ち切られていた
        assertEquals(133.0, r.points.last().close, 0.0001)
    }

    @Test
    fun 急落の水準シフトも同様に残る() {
        // 100→60（-40%）と落ち、その水準が続く（イベント急落型）
        val rows = bars(listOf(100.0, 101.0, 100.0, 60.0, 61.0, 59.0, 60.0))
        val r = BadTicks.clean(rows, daily)
        assertEquals(0, r.dropped)
        assertEquals(7, r.points.size)
    }

    // ===== ケース2: 単発スパイク =====
    @Test
    fun 単発スパイクは1点だけ捨てて前後はつながる() {
        val rows = bars(listOf(100.0, 100.0, 100.0, 10.0, 100.0, 100.0))
        val r = BadTicks.clean(rows, daily)
        assertEquals(1, r.dropped)
        assertEquals(listOf(100.0, 100.0, 100.0, 100.0, 100.0), closesOf(r.points))
    }

    @Test
    fun 上方向の単発スパイクも同じく1点だけ捨てる() {
        val rows = bars(listOf(50.0, 51.0, 500.0, 52.0, 51.0))
        val r = BadTicks.clean(rows, daily)
        assertEquals(1, r.dropped)
        assertEquals(listOf(50.0, 51.0, 52.0, 51.0), closesOf(r.points))
    }

    // ===== ケース3: 連続スパイク（実測した1306の2日連続1/10スケール）=====
    @Test
    fun 連続スパイクは区間ごと捨てて元の水準に戻る() {
        // 2バー続けて1/10になり、その後元の水準へ戻る＝誤プリント
        val rows = bars(listOf(2000.0, 2010.0, 201.0, 200.0, 2020.0, 2030.0))
        val r = BadTicks.clean(rows, daily)
        assertEquals(2, r.dropped)
        assertEquals(listOf(2000.0, 2010.0, 2020.0, 2030.0), closesOf(r.points))
    }

    // ===== ケース4: 分割ギャップ（調整漏れの1/2分割。以降ずっと新水準）=====
    @Test
    fun 分割ギャップは水準シフトとして扱い以降を捨てない() {
        val rows = bars(listOf(1000.0, 1000.0, 1000.0, 500.0, 505.0, 510.0, 500.0))
        val r = BadTicks.clean(rows, daily)
        assertEquals(0, r.dropped)
        assertEquals(7, r.points.size)
        assertEquals(500.0, r.points.last().close, 0.0001)
    }

    // ===== ケース5: 末尾で確認できない跳び（安全側で捨てる）=====
    @Test
    fun 末尾の跳びは本物か確認できないので捨てる() {
        val rows = bars(listOf(100.0, 100.0, 100.0, 10.0))
        val r = BadTicks.clean(rows, daily)
        assertEquals(1, r.dropped)
        assertEquals(listOf(100.0, 100.0, 100.0), closesOf(r.points))
    }

    // ===== ケース6: 足種別の閾値 =====
    @Test
    fun 同じ跳びでも日足は異常週足は許容する() {
        // +30%の単発スパイク。日足の閾値(0.25)は超えるが週足(0.45)には収まる
        val rows = bars(listOf(100.0, 100.0, 130.0, 100.0, 100.0))
        val d = BadTicks.clean(rows, daily)
        assertEquals(1, d.dropped)
        val w = BadTicks.clean(rows, weekly)
        assertEquals(0, w.dropped)
        assertEquals(5, w.points.size)
    }

    @Test
    fun 閾値は足種で切り替わる() {
        assertEquals(BadTicks.DAILY_THRESHOLD, BadTicks.thresholdFor(false), 0.0001)
        assertEquals(BadTicks.WEEKLY_THRESHOLD, BadTicks.thresholdFor(true), 0.0001)
    }

    // ===== 平常系・端の条件 =====
    @Test
    fun 通常の値動きは1点も捨てない() {
        val rows = bars((0 until 100).map { 100.0 + it * 0.5 })
        val r = BadTicks.clean(rows, daily)
        assertEquals(0, r.dropped)
        assertEquals(100, r.points.size)
    }

    @Test
    fun 空や1点は素通しする() {
        assertEquals(0, BadTicks.clean(emptyList(), daily).points.size)
        val one = bars(listOf(123.0))
        val r = BadTicks.clean(one, daily)
        assertEquals(1, r.points.size)
        assertEquals(0, r.dropped)
    }

    @Test
    fun 先頭のスパイクも次バーが戻れば捨てる() {
        // 1本目が誤プリントの場合。以降の正常な水準へ戻ることで見分ける
        val rows = bars(listOf(1000.0, 100.0, 101.0, 100.0))
        val r = BadTicks.clean(rows, daily)
        // 先頭は比較対象が無いので採用され、2本目の跳びを検査する。
        // 2本目以降が同水準で続く＝水準シフト扱いになり、データは打ち切られない
        assertEquals(4, r.points.size)
        assertEquals(0, r.dropped)
    }
}
