package com.example.etfbuyalert.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.etfbuyalert.BOOKMARK_SAVE_FAILED
import com.example.etfbuyalert.ui.kit.AppSnackbarHost
import com.example.etfbuyalert.ui.theme.EtfBuyAlertTheme
import com.example.etfbuyalert.ui.theme.ThemePrefs

/**
 * 2026-11-12 画面下の知らせ（★の保存の失敗）の帯を、前の形と今の形で見比べるための画面（デバッグ版にだけ入る）。
 *
 * 本物の帯は★の保存に失敗したときしか出ないので、通信を切らずに撮れるようにした。
 * Notion・prefs・Worker・通知には触れない（帯に見本の文を出すだけ）。配色は利用者が選んでいる配色を読む。
 *
 * 開き方（mode = light / dark、style = new / old）:
 *   adb shell am start --activity-clear-task -n com.example.etfbuyalert/.debug.DesignPreviewActivity --es mode light --es style new
 */
class DesignPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 明暗（既定は暗い）と、帯の形（既定は今の形 new）
        val themeMode = if (intent.getStringExtra("mode") == "light") ThemePrefs.MODE_LIGHT else ThemePrefs.MODE_DARK
        val oldStyle = intent.getStringExtra("style") == "old"
        // 配色は本物と同じく利用者の選んだものを使う（見た目を本物とそろえるため。読むだけ）
        val palette = ThemePrefs.getPalette(this)
        // 前の形は前の長い文、今の形は縮めた文（v1.35 で両方変えたため）
        val message = if (oldStyle) "ブックマークをNotionに保存できませんでした（通信・トークンを確認してください）"
                      else BOOKMARK_SAVE_FAILED
        setContent {
            EtfBuyAlertTheme(themeMode = themeMode, paletteId = palette) {
                val host = remember { SnackbarHostState() }
                // 帯を出しっぱなしにする（撮影のあいだ消えないように）
                LaunchedEffect(Unit) { host.showSnackbar(message, duration = SnackbarDuration.Indefinite) }
                Scaffold { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                if (oldStyle) "見本：前の帯（素の SnackbarHost）" else "見本：今の帯（AppSnackbarHost）",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 本物の監視タブと同じく、画面の下端に重ねる
                        Box(Modifier.align(Alignment.BottomCenter)) {
                            if (oldStyle) SnackbarHost(host) else AppSnackbarHost(host)
                        }
                    }
                }
            }
        }
    }
}
