package com.example.etfbuyalert

import com.example.etfbuyalert.domain.NewsWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// NewsWarning（ニュース警告文の解釈）のテスト。
// 文面はPC側 fired_news_check.py が実際に書く形式に合わせている。
// 形式を変えるときは Python・このテスト・NewsWarning の3つを同時に直すこと。
class NewsWarningTest {

    @Test
    fun 要注意と参考の両方があるとき_要注意部分だけを取り出す() {
        val w = "[2026-08-10] 【要注意】08/07 1日で-16.6%の急落｜同日前後の開示: 決算短信 ｜ （参考）08/06 決算短信"
        assertEquals("08/07 1日で-16.6%の急落｜同日前後の開示: 決算短信", NewsWarning.criticalPart(w))
    }

    @Test
    fun 要注意のみのとき_全文から日付を除いた本文になる() {
        val w = "[2026-08-10] 【要注意】通期EPSガイダンスを2割下方"
        assertEquals("通期EPSガイダンスを2割下方", NewsWarning.criticalPart(w))
    }

    @Test
    fun 日付だけ変わって内容が同じなら_シグネチャは一致する() {
        val w1 = "[2026-08-10] 【要注意】大型買収でオーガニック成長マイナス"
        val w2 = "[2026-08-11] 【要注意】大型買収でオーガニック成長マイナス"
        assertEquals(NewsWarning.criticalPart(w1), NewsWarning.criticalPart(w2))
    }

    @Test
    fun 該当なしのとき_空になり通知されない() {
        assertEquals("", NewsWarning.criticalPart("[2026-08-10] 直近14日に該当なし（見出し12件を確認）"))
        assertNull(NewsWarning.displayText("[2026-08-10] 直近14日に該当なし（見出し12件を確認）"))
    }

    @Test
    fun 参考のみのとき_空になり通知されない() {
        assertEquals("", NewsWarning.criticalPart("[2026-08-10] （参考）08/06 決算短信"))
    }

    @Test
    fun 空やnullでも落ちない() {
        assertEquals("", NewsWarning.criticalPart(null))
        assertEquals("", NewsWarning.criticalPart(""))
        assertNull(NewsWarning.displayText(null))
    }
}
