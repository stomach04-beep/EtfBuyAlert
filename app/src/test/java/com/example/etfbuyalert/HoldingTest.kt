package com.example.etfbuyalert

import com.example.etfbuyalert.domain.Holding
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 保有中・監視除外の判定（v1.34）。19行の購入日空欄で損切りが黙っていた件の再発防止 */
class HoldingTest {
    @Test fun 購入日ありなら保有() = assertTrue(Holding.isHeld(true, "実弾"))
    @Test fun 購入日が空でも選定ステータスが保有中なら保有() = assertTrue(Holding.isHeld(false, "保有中"))
    @Test fun 前後の空白は無視する() = assertTrue(Holding.isHeld(false, " 保有中 "))
    @Test fun 両方無ければ保有でない() {
        assertFalse(Holding.isHeld(false, "実弾"))
        assertFalse(Holding.isHeld(false, null))
    }
    @Test fun 見送りと資料は監視から外す() {
        assertTrue(Holding.isSilenced("見送り"))
        assertTrue(Holding.isSilenced("資料"))
    }
    @Test fun 空欄や未知の値は外さない安全側() {
        assertFalse(Holding.isSilenced(null))
        assertFalse(Holding.isSilenced(""))
        assertFalse(Holding.isSilenced("要仕分け"))
        assertFalse(Holding.isSilenced("保有中"))
    }
}
