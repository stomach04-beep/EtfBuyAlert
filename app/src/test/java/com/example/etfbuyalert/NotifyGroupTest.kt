package com.example.etfbuyalert

import com.example.etfbuyalert.data.model.EtfState
import com.example.etfbuyalert.domain.AlertEngine
import com.example.etfbuyalert.domain.AssetKind
import com.example.etfbuyalert.domain.NotifyGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 通知グループ（v1.36）の判定と、オフにしたときの鳴らし方の試験
class NotifyGroupTest {

    // 試験用の1銘柄（必要な列だけ変える）
    private fun st(
        ticker: String = "ABC",
        method: String? = AssetKind.METHOD_MANUAL,
        raw: String? = null,
        tags: List<String>? = null,
        fav: String? = null,
        purpose: String? = null,
        purchased: Boolean = false
    ) = EtfState(
        pageId = "p-$ticker", ticker = ticker, name = "テスト$ticker", lineMethod = method,
        notifyGroupRaw = raw, tags = tags, favoritePage = fav, purpose = purpose, purchased = purchased
    )

    @Test fun 自動登録の行は方式から決まる() {
        assertEquals(NotifyGroup.PICKAXE, NotifyGroup.of(st(method = AssetKind.METHOD_PICKAXE)))
        assertEquals(NotifyGroup.RTX, NotifyGroup.of(st(method = AssetKind.METHOD_RTX)))
        assertEquals(NotifyGroup.GROWTH_DIV, NotifyGroup.of(st(method = AssetKind.METHOD_ADP, tags = listOf("高配当", "長期保有"))))
    }

    @Test fun ETFはETF指数() {
        assertEquals(NotifyGroup.ETF, NotifyGroup.of(st(ticker = "VOO", method = AssetKind.METHOD_MA200)))
    }

    @Test fun Notionの上書きが最優先() {
        assertEquals("自分用", NotifyGroup.of(st(method = AssetKind.METHOD_RTX, raw = "自分用")))
    }

    @Test fun 実在する組み合わせの振り分け() {
        // 成長配当タグ＋高配当タグ → 成長高配当（成長配当を先に見る）
        assertEquals(NotifyGroup.GROWTH_DIV, NotifyGroup.of(st(tags = listOf("高配当", "長期保有", "成長配当"))))
        // お気に入り＝米国グロースで、タグが高配当・割安 → グロース（ページ割当を高配当より先に見る）
        assertEquals(NotifyGroup.GROWTH, NotifyGroup.of(st(tags = listOf("割安", "高配当"), fav = "米国グロース")))
        // グロース＋長期保有 → グロース
        assertEquals(NotifyGroup.GROWTH, NotifyGroup.of(st(tags = listOf("グロース", "長期保有"))))
        // タグなし・宇宙防衛ページ → テーマ
        assertEquals(NotifyGroup.THEME, NotifyGroup.of(st(fav = "宇宙・防衛")))
        // 高配当＋割安だけ → 高配当・割安
        assertEquals(NotifyGroup.HIGH_DIV, NotifyGroup.of(st(tags = listOf("高配当", "割安"))))
        // 投資目的＝配当成長だけ → 成長高配当
        assertEquals(NotifyGroup.GROWTH_DIV, NotifyGroup.of(st(purpose = "配当成長")))
    }

    @Test fun 材料が何も無ければ未分類_旧JSONのnullでも落ちない() {
        assertEquals(NotifyGroup.NONE, NotifyGroup.of(st(tags = null, fav = "載せない")))
    }

    @Test fun オフのグループは押し目を鳴らさないが到達の印は進む() {
        val s = st(method = AssetKind.METHOD_RTX).copy(
            price = 90.0, dipPrice = 100.0, asOf = System.currentTimeMillis()
        )
        val (next, alerts) = AlertEngine.evaluate(s, AlertEngine.Toggles(true, true, true, true))
        assertTrue("押し目は判定される", alerts.any { it.category == "押し目" })
        assertTrue("到達済みの印は立つ（オンに戻しても一斉に鳴らない）", next.dipArmed)
        assertFalse(NotifyGroup.shouldNotify(next, "押し目", setOf(NotifyGroup.RTX)))
        assertTrue(NotifyGroup.shouldNotify(next, "押し目", setOf(NotifyGroup.GROWTH)))
    }

    @Test fun 守りの通知はオフでも鳴る() {
        val held = st(fav = "米国グロース", purchased = true)
        val muted = setOf(NotifyGroup.GROWTH)
        assertTrue(NotifyGroup.shouldNotify(held, "損切り", muted))
        assertTrue(NotifyGroup.shouldNotify(held, "売り時", muted))
        assertTrue(NotifyGroup.shouldNotify(held, "過熱利確②", muted))
        assertTrue("保有中のニュース警告は守り", NotifyGroup.shouldNotify(held, "ニュース警告", muted))
        assertFalse("保有中でも押し目は買い側", NotifyGroup.shouldNotify(held, "押し目", muted))
        val notHeld = st(fav = "米国グロース")
        assertFalse("未保有のニュース警告は止める", NotifyGroup.shouldNotify(notHeld, "ニュース警告", muted))
    }

    @Test fun 知らないグループは既定オン() {
        assertTrue(NotifyGroup.shouldNotify(st(raw = "新しい型"), "押し目", setOf(NotifyGroup.GROWTH)))
        assertTrue(NotifyGroup.shouldNotify(st(raw = "新しい型"), "押し目", emptySet()))
    }

    @Test fun 朝サマリから外すのは未保有だけ() {
        val muted = setOf(NotifyGroup.THEME)
        assertTrue(NotifyGroup.isMuted(st(fav = "半導体"), muted))
        assertFalse(NotifyGroup.isMuted(st(fav = "半導体", purchased = true), muted))
    }

    @Test fun 設定画面の並びは定義順で未分類が最後() {
        val list = listOf(st(fav = "載せない"), st(method = AssetKind.METHOD_PICKAXE), st(purpose = "配当成長"))
        assertEquals(
            listOf(NotifyGroup.GROWTH_DIV, NotifyGroup.PICKAXE, NotifyGroup.NONE),
            NotifyGroup.countsOf(list).map { it.first }
        )
    }
}
