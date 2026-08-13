package com.example.etfbuyalert

import com.example.etfbuyalert.data.model.ChartPoint
import com.example.etfbuyalert.domain.SellRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

// SellRules（売り時点灯）の判定検証。
// バックテスト（pickaxe-backtest/sell_partB_jp.py）と同じ意味論になっているかを
// 合成データで確かめる。アーム条件＝12M+100%かつ52週高値の95%以上、
// 点灯＝トレール-15% / 50日線割れ、当日未確定バーの除外、の4点が肝。
class SellRulesTest {

    private val tokyo = ZoneId.of("Asia/Tokyo")

    // 平日だけの日足を作る（土日はYahooのバーに存在しないので飛ばす）
    private fun makePoints(closes: List<Double>, endDate: LocalDate): List<ChartPoint> {
        val pts = ArrayList<ChartPoint>(closes.size)
        var d = endDate
        val stack = ArrayDeque<LocalDate>()
        while (stack.size < closes.size) {
            if (d.dayOfWeek.value <= 5) stack.addFirst(d)
            d = d.minusDays(1)
        }
        for ((i, date) in stack.withIndex()) {
            // 15:00 JST の大引け時刻をバーの時刻にする
            val t = date.atTime(15, 0).atZone(tokyo).toEpochSecond()
            pts.add(ChartPoint(t, closes[i]))
        }
        return pts
    }

    // now＝最終バーの翌日昼（全バー確定済み）
    private fun nowAfter(endDate: LocalDate): Long =
        endDate.plusDays(1).atTime(12, 0).atZone(tokyo).toEpochSecond() * 1000L

    @Test
    fun `2倍高になった銘柄はアームされる`() {
        // 300日かけて100→220へ上昇（12Mリターン+120%・高値付近）
        val closes = (0 until 300).map { 100.0 + it * 0.4 }
        val end = LocalDate.of(2026, 8, 7)
        val sig = SellRules.calculate(makePoints(closes, end), nowAfter(end))
        assertNotNull(sig)
        assertTrue("+120%で高値付近ならアームされるはず", sig!!.armed)
        assertFalse("高値更新中に点灯してはいけない", sig.trailFired)
        assertFalse(sig.ma50Fired)
    }

    @Test
    fun `横ばいの銘柄はアームされない`() {
        val closes = (0 until 300).map { 100.0 + (it % 7) * 0.1 }
        val end = LocalDate.of(2026, 8, 7)
        val sig = SellRules.calculate(makePoints(closes, end), nowAfter(end))
        assertNotNull(sig)
        assertFalse("値動きの無い銘柄をアームしてはいけない", sig!!.armed)
    }

    @Test
    fun `アーム後に高値から16パーセント下げるとトレール点灯`() {
        // 260日で100→240（アーム成立）→ その後、高値240から16%下の201.6まで下落
        val up = (0 until 260).map { 100.0 + it * 0.54 }   // 最終 239.86
        val peak = up.last()
        val down = (1..30).map { peak * (1.0 - 0.16 * it / 30.0) }  // -16%まで滑らかに下落
        val closes = up + down
        val end = LocalDate.of(2026, 8, 7)
        val sig = SellRules.calculate(makePoints(closes, end), nowAfter(end))
        assertNotNull(sig)
        assertTrue(sig!!.armed)
        assertTrue("高値-16%ならトレール-15%が点灯するはず", sig.trailFired)
        assertEquals(-16.0, sig.dropPct!!, 0.5)
    }

    @Test
    fun `下落が10パーセントならトレールは点灯しない`() {
        val up = (0 until 260).map { 100.0 + it * 0.54 }
        val peak = up.last()
        val down = (1..20).map { peak * (1.0 - 0.10 * it / 20.0) }  // -10%止まり
        val closes = up + down
        val end = LocalDate.of(2026, 8, 7)
        val sig = SellRules.calculate(makePoints(closes, end), nowAfter(end))
        assertNotNull(sig)
        assertTrue(sig!!.armed)
        assertFalse("-10%ではまだ点灯しない（閾値は-15%）", sig.trailFired)
    }

    @Test
    fun `50日線割れの判定`() {
        // 急騰後に横ばい→じり安で50日線を下回る形
        val up = (0 until 260).map { 100.0 + it * 0.54 }
        val peak = up.last()
        // 50日横ばいのあと5%下落（50日平均より下に出る）
        val flat = (1..50).map { peak }
        val slide = (1..10).map { peak * (1.0 - 0.05 * it / 10.0) }
        val closes = up + flat + slide
        val end = LocalDate.of(2026, 8, 7)
        val sig = SellRules.calculate(makePoints(closes, end), nowAfter(end))
        assertNotNull(sig)
        assertTrue(sig!!.armed)
        assertTrue("終値が50日平均を下回れば点灯", sig.ma50Fired)
        assertFalse("-5%ではトレールはまだ", sig.trailFired)
    }

    @Test
    fun `当日の未確定バーは判定から除外される`() {
        // 最終バーが「今日」の日付で、今が大引け前（14:00）なら、そのバーは無視される。
        // 今日の暴落(-20%)を含めればトレール点灯だが、確定前なので点灯してはいけない。
        val up = (0 until 300).map { 100.0 + it * 0.5 }
        val today = LocalDate.of(2026, 8, 7)  // 金曜
        val peak = up.last()
        val closes = up + listOf(peak * 0.80)   // 今日の未確定バー＝-20%
        val pts = makePoints(closes, today)
        val nowBeforeClose = today.atTime(14, 0).atZone(tokyo).toEpochSecond() * 1000L
        val sig = SellRules.calculate(pts, nowBeforeClose)
        assertNotNull(sig)
        assertFalse("大引け前の暴落で点灯してはいけない（終値確定を待つ）", sig!!.trailFired)

        // 大引け後（15:30）なら同じデータで点灯する
        val nowAfterClose = today.atTime(15, 30).atZone(tokyo).toEpochSecond() * 1000L
        val sig2 = SellRules.calculate(pts, nowAfterClose)
        assertTrue("大引け後は当日終値で点灯するはず", sig2!!.trailFired)
    }

    @Test
    fun `データ不足ならnull`() {
        val closes = (0 until 100).map { 100.0 + it }  // 253本未満
        val end = LocalDate.of(2026, 8, 7)
        assertNull(SellRules.calculate(makePoints(closes, end), nowAfter(end)))
    }

    @Test
    fun `対象は日本株の個別株のみ`() {
        assertTrue(SellRules.isEligible("5803.T", "フジクラ"))
        assertFalse("米国株は対象外", SellRules.isEligible("NVDA", "エヌビディア"))
        assertFalse("ETFは対象外", SellRules.isEligible("1306.T", "NEXT FUNDS TOPIX連動型上場投信"))
    }
}
