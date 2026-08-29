package com.example.etfbuyalert

import com.example.etfbuyalert.data.model.EtfState
import com.example.etfbuyalert.domain.MorningSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 毎朝サマリの要約（v1.23）の検証。
 *
 * 旧実装は全銘柄を流し込んでおり、監視が約190銘柄になった時点で
 * Androidの通知本文の上限（5,120字）に切られ、先頭しか読めなくなっていた。
 * 「上限に収まること」と「発火中が必ず本文に載ること」がこの機能の肝。
 */
class MorningSummaryTest {

    /** Androidの通知本文が切り捨てられる長さ（OS側の上限） */
    private val NOTIFICATION_LIMIT = 5120

    private val now = System.currentTimeMillis()

    private fun state(
        ticker: String,
        price: Double,
        dip: Double,
        asOf: Long = now,
    ) = EtfState(
        pageId = "p_$ticker",
        ticker = ticker,
        name = "テスト銘柄$ticker",
        dipPrice = dip,
        price = price,
        asOf = asOf,
    )

    @Test
    fun 監視0件ならサマリを作らない() {
        assertNull(MorningSummary.build(emptyList(), now))
    }

    @Test
    fun 銘柄190件でも通知の文字数上限に収まる() {
        // 現行の監視規模（約190銘柄）。旧実装はここで1万字を超えて切られていた
        val states = (1..190).map { i ->
            state("TST$i", price = 100.0 + i, dip = 90.0 + i)
        }
        val s = MorningSummary.build(states, now)!!
        assertTrue("本文が通知上限を超えている: ${s.body.length}字", s.body.length < NOTIFICATION_LIMIT)
        assertTrue(s.title.contains("190"))
    }

    @Test
    fun 発火中が先頭に出て深い順に並ぶ() {
        val states = listOf(
            state("AAA", price = 95.0, dip = 100.0),   // 押し目を5%下回る（発火中）
            state("BBB", price = 80.0, dip = 100.0),   // 押し目を20%下回る（より深い）
            state("CCC", price = 120.0, dip = 100.0),  // まだ届いていない
        )
        val body = MorningSummary.build(states, now)!!.body
        assertTrue(body.startsWith("🟢 発火中 2件"))
        // より深いBBBが先に出る（並べ替えの基準は一覧と同じ dipGapPercent）
        assertTrue(body.indexOf("BBB") < body.indexOf("AAA"))
    }

    @Test
    fun 押し目が近い順にTop10だけ並べ残りは件数にする() {
        // 発火はしていないが距離がばらばらな30銘柄
        val states = (1..30).map { i ->
            state("N$i", price = 100.0 + i, dip = 100.0)
        }
        val s = MorningSummary.build(states, now)!!
        assertTrue(s.body.contains("押し目が近い順 Top${MorningSummary.TOP_N}"))
        // 最も近いN1が載り、遠いN30は載らない（代わりに件数だけ）
        assertTrue(s.body.contains("N1 "))
        assertTrue(!s.body.contains("N30 "))
        assertTrue(s.body.contains("他 20銘柄は監視中"))
    }

    @Test
    fun 発火が多すぎるときは列挙を打ち切って件数を添える() {
        val states = (1..15).map { i ->
            state("F$i", price = 50.0 - i, dip = 100.0)   // 全部発火中
        }
        val s = MorningSummary.build(states, now)!!
        assertTrue(s.body.startsWith("🟢 発火中 15件"))
        assertTrue(s.body.contains("ほか${15 - MorningSummary.MAX_FIRED_LINES}件"))
    }

    @Test
    fun 価格が古すぎる銘柄は本体から外して件数だけ添える() {
        val old = now - 30L * 24 * 60 * 60 * 1000   // 30日前＝鮮度切れ（7日超）
        val states = listOf(
            state("OK1", price = 95.0, dip = 100.0),
            state("OLD1", price = 95.0, dip = 100.0, asOf = old),
        )
        val s = MorningSummary.build(states, now)!!
        assertTrue(s.body.contains("⚠ 価格取得に失敗中 1銘柄"))
        assertTrue(!s.body.contains("OLD1"))
        assertEquals(true, s.body.contains("OK1"))
    }

    @Test
    fun 発火ゼロでもその旨を必ず書く() {
        val states = listOf(state("XYZ", price = 200.0, dip = 100.0))
        val body = MorningSummary.build(states, now)!!.body
        assertTrue(body.startsWith("発火中の銘柄はありません"))
    }
}
