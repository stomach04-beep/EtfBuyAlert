package com.example.etfbuyalert

import com.example.etfbuyalert.data.model.EtfState
import com.example.etfbuyalert.domain.ValueFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 割安の加点の表示（v1.37）。Notion「割安加点」列の文字を札と通知文の1行にする */
class ValueFlagTest {
    // 試験用の最小の行（日本株）
    private fun st(flag: String?, day: String? = "2026-11-01") =
        EtfState(pageId = "p", ticker = "5280.T", name = "ヨシコン", valueFlag = flag, valueFlagDate = day)

    @Test fun 空なら札も行も出さない() {
        assertNull(ValueFlag.chip(st(null)))
        assertNull(ValueFlag.chip(st("  ")))
        assertEquals("", ValueFlag.noteLine(st(null)))
    }

    @Test fun 札は短く縮める() {
        assertEquals("割安 両方", ValueFlag.chip(st("両方")))
        assertEquals("割安 安い2割", ValueFlag.chip(st("安い2割")))
        assertEquals("割安 NN", ValueFlag.chip(st("ネットネット")))
        assertEquals("割安？", ValueFlag.chip(st("要確認(分割)")))
    }

    @Test fun 要確認だけ注意扱い() {
        assertTrue(ValueFlag.isCaution(st("要確認(税引前)")))
        assertFalse(ValueFlag.isCaution(st("両方")))
    }

    @Test fun 通知文の行は全文と一覧の日付() {
        assertEquals("\n💹 割安の加点：要確認(分割)（2026-11-01 の一覧）", ValueFlag.noteLine(st("要確認(分割)")))
        assertEquals("\n💹 割安の加点：両方", ValueFlag.noteLine(st("両方", day = null)))
    }

    @Test fun まとめ通知の印は加点の行があるときだけ() {
        assertEquals(" 💹割安", ValueFlag.bundleMark("押し目に到達" + ValueFlag.noteLine(st("両方"))))
        assertEquals("", ValueFlag.bundleMark("押し目に到達"))
    }
}
