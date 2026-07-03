package com.example.etfbuyalert.ui.screen.settings

import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.etfbuyalert.ui.theme.ThemePrefs

// 設定タブ
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    notionToken: String,
    notionDb: String,
    intervalMin: Int,
    morningHour: Int,
    morningMin: Int,
    notifyDip: Boolean,
    notifyStop: Boolean,
    notifyBreakout: Boolean,
    notifyMorning: Boolean,
    notifyZoneChange: Boolean,
    onNotionTokenChange: (String) -> Unit,
    onNotionDbChange: (String) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onMorningTimeChange: (Int, Int) -> Unit,
    onNotifyDipToggle: (Boolean) -> Unit,
    onNotifyStopToggle: (Boolean) -> Unit,
    onNotifyBreakoutToggle: (Boolean) -> Unit,
    onNotifyMorningToggle: (Boolean) -> Unit,
    onNotifyZoneChangeToggle: (Boolean) -> Unit,
    onTestNotification: () -> Unit,
    onClearData: () -> Unit
) {
    val ctx = LocalContext.current
    val scroll = rememberScrollState()

    // 入力中の一時状態（フォーカスが外れたら確定保存）
    var tokenInput by remember(notionToken) { mutableStateOf(notionToken) }
    var dbInput by remember(notionDb) { mutableStateOf(notionDb) }
    var tokenVisible by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("設定", fontWeight = FontWeight.Bold) }) }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ===== Notion連携 =====
            Section("Notion連携") {
                Text(
                    "Notionの「内部インテグレーション」トークンを入力し、" +
                    "「投資ウォッチリスト」DBにそのインテグレーションを接続してください。" +
                    "アプリ監視＝ONの銘柄が同期されます。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text("Notionトークン (secret_...)") },
                    singleLine = true,
                    visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                if (tokenVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "表示切替"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dbInput,
                    onValueChange = { dbInput = it },
                    label = { Text("データベースID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        onNotionTokenChange(tokenInput)
                        onNotionDbChange(dbInput)
                    },
                    modifier = Modifier.align(Alignment.End)
                ) { Text("保存") }
            }

            // ===== チェック間隔 =====
            Section("価格チェック間隔") {
                Text("この間隔ごとに価格を取得し、ライン到達を判定します。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val options = listOf(30 to "30分", 60 to "1時間", 120 to "2時間", 240 to "4時間")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { (min, label) ->
                        FilterChip(
                            selected = intervalMin == min,
                            onClick = { onIntervalChange(min) },
                            label = { Text(label) }
                        )
                    }
                }
            }

            // ===== 毎朝サマリ時刻 =====
            Section("毎朝サマリの時刻") {
                Text("米国市場が閉じたあとの確認に。指定時刻に全監視ETFの終値と各ラインまでの距離を1通で通知します。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier.fillMaxWidth().clickable {
                        TimePickerDialog(ctx, { _, h, m -> onMorningTimeChange(h, m) },
                            morningHour, morningMin, true).show()
                    }.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("通知時刻")
                    Text(String.format("%02d:%02d", morningHour, morningMin), fontWeight = FontWeight.Bold)
                }
            }

            // ===== 通知ON/OFF =====
            Section("通知の種類") {
                ToggleRow("押し目・深押し到達", "買い時ラインまで下がったら通知", notifyDip, onNotifyDipToggle)
                ToggleRow("損切りライン割れ", "保有中の銘柄が損切り価格を割れたら警告", notifyStop, onNotifyStopToggle)
                ToggleRow("順張りライン突破", "上抜けの追加検討ラインを超えたら通知", notifyBreakout, onNotifyBreakoutToggle)
                ToggleRow("ステージ変化", "通常/押し目/深押し/順張り/損切りの段階が切り替わったら通知", notifyZoneChange, onNotifyZoneChangeToggle)
                ToggleRow("毎朝サマリ", "毎朝の値まとめ通知", notifyMorning, onNotifyMorningToggle)
                OutlinedButton(onClick = onTestNotification, modifier = Modifier.padding(top = 4.dp)) {
                    Text("テスト通知を送る")
                }
            }

            // ===== テーマ =====
            Section("テーマ") {
                var mode by remember { mutableStateOf(ThemePrefs.get(ctx)) }
                val modes = listOf(
                    ThemePrefs.MODE_SYSTEM to "システムに従う",
                    ThemePrefs.MODE_LIGHT to "ライト",
                    ThemePrefs.MODE_DARK to "ダーク"
                )
                modes.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            mode = value; ThemePrefs.set(ctx, value)
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = mode == value, onClick = {
                            mode = value; ThemePrefs.set(ctx, value)
                        })
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }

            // ===== データ =====
            Section("データ") {
                OutlinedButton(
                    onClick = { showClearDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("監視データ・通知履歴を全消去") }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("全消去しますか？") },
            text = { Text("監視中ETFのキャッシュと通知履歴を消去します。次回の同期でNotionから再取得されます。") },
            confirmButton = {
                TextButton(onClick = { onClearData(); showClearDialog = false }) {
                    Text("消去", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("キャンセル") } }
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}
