package com.example.etfbuyalert.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.etfbuyalert.ui.kit.Sem
import com.example.etfbuyalert.ui.kit.Tone

/**
 * 前日比の文字色（一覧と詳細の両方がここを通る。画面ごとに判定を書かない）。
 * プラス＝緑・マイナス＝赤・変わらず＝補足色。
 * 2026-10-24 ユーザー指示「＋−の色は前の仕様に」で v1.27 までの色分けに戻した。
 * 色は明暗どちらでも読める Sem の緑・赤（v1.27 までの #4CAF50 は白い地で薄かった）。
 */
@Composable
fun changeColor(diffPct: Double): Color = when {
    diffPct > 0 -> Sem.fg(Tone.OK)
    diffPct < 0 -> Sem.fg(Tone.CRIT)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
