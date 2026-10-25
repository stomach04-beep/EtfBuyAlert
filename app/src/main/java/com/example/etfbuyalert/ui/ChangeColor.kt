package com.example.etfbuyalert.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.etfbuyalert.ui.kit.Sem

/**
 * 前日比の文字色（一覧と詳細の両方がここを通る。画面ごとに判定を書かない）。
 * 2026-10-25 APP_RULES.md B3：＋＝赤・−＝青・変わらず＝補足色（日本の株価の板と同じ向き）。
 * 向きと色は UiKit の Sem.sign が単一の持ち主。前日比は小数2桁で出すので、
 * 表示が「▲0.00%」になる値は灰にする（桁を渡す版）
 */
@Composable
fun changeColor(diffPct: Double): Color = Sem.sign(diffPct, 2)
