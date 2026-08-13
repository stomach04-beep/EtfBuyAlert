package com.example.etfbuyalert.ui.screen.history

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.etfbuyalert.data.model.NotificationLog

// ===== 通知の種類ごとの仕分け定義 =====
// 履歴に入る category 文字列（NotificationHelper / AlertEngine / EtfRepository が書く値）を
// 「見たい単位」にまとめる対応表。ここが仕分けの単一の真実の源。
// 過熱利確①と②は同じ利確ルールの段違いなので1つのグループにまとめる。
private data class HistoryFilter(
    val key: String,             // 選択状態の保存キー（rememberSaveable用）
    val label: String,           // チップ・バッジの表示名
    val color: Color,            // バッジの色（通知の深刻さ・性格で色分け）
    val categories: Set<String>  // このグループに属する category 値。空=どのグループにも入らない残り物
)

private const val FILTER_ALL = "all"
private const val FILTER_OTHER = "other"

private val HISTORY_FILTERS: List<HistoryFilter> = listOf(
    // 並び順＝まとめ通知の深刻順（損切り→ニュース警告→深押し→押し目→…）に合わせる
    HistoryFilter("stop", "損切り", Color(0xFFEF5350), setOf("損切り")),
    HistoryFilter("sell", "売り時", Color(0xFFFF9800), setOf("売り時")),
    HistoryFilter("news", "ニュース警告", Color(0xFFFF8A65), setOf("ニュース警告")),
    HistoryFilter("deep", "深押し", Color(0xFF2E7D32), setOf("深押し")),
    HistoryFilter("dip", "押し目", Color(0xFF66BB6A), setOf("押し目")),
    HistoryFilter("rsi", "過熱利確", Color(0xFFFFB74D), setOf("過熱利確①", "過熱利確②")),
    HistoryFilter("breakout", "順張り", Color(0xFF42A5F5), setOf("順張り")),
    HistoryFilter("rtx", "急落優良", Color(0xFFBA68C8), setOf("急落優良")),
    HistoryFilter("stage", "ステージ変化", Color(0xFF90A4AE), setOf("ステージ変化")),
    HistoryFilter("summary", "朝サマリ", Color(0xFF4DB6AC), setOf("朝サマリ")),
    // テスト・沈黙監視・将来増える未知の category はここに落ちる
    HistoryFilter(FILTER_OTHER, "その他", Color(0xFF78909C), emptySet()),
)

// どの明示グループにも属さない category 名の集合（「その他」判定に使う）
private val KNOWN_CATEGORIES: Set<String> =
    HISTORY_FILTERS.flatMap { it.categories }.toSet()

// このログがフィルタに該当するか
private fun matches(filter: HistoryFilter, log: NotificationLog): Boolean =
    if (filter.key == FILTER_OTHER) log.category !in KNOWN_CATEGORIES
    else log.category in filter.categories

// ログ1件が属するグループ（カードのバッジ表示用）
private fun filterOf(log: NotificationLog): HistoryFilter =
    HISTORY_FILTERS.firstOrNull { log.category in it.categories }
        ?: HISTORY_FILTERS.last()  // 未知の category は「その他」

// 履歴タブ — 過去に出した通知の一覧（種類ごとに絞り込める）
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    notifications: List<NotificationLog>,
    onReload: () -> Unit,
    onClear: () -> Unit
) {
    // 画面表示のたびに最新の履歴を読み直す（StateFlow外で書かれるため）
    LaunchedEffect(Unit) { onReload() }

    // 選択中の絞り込み。画面回転や一時的なプロセス終了でも保持する
    var selectedKey by rememberSaveable { mutableStateOf(FILTER_ALL) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通知履歴", fontWeight = FontWeight.Bold) },
                actions = {
                    if (notifications.isNotEmpty()) {
                        TextButton(onClick = onClear) { Text("クリア") }
                    }
                }
            )
        }
    ) { pad ->
        if (notifications.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("通知履歴はまだありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            // 件数が1件以上ある種類だけチップを出す（発火中タブの方式チップと同じ流儀）
            val shownFilters = HISTORY_FILTERS.filter { f -> notifications.any { matches(f, it) } }
            // 保存されていた選択が今日は0件（クリア後など）なら「すべて」へ戻す
            val selected = shownFilters.firstOrNull { it.key == selectedKey }
            val filtered =
                if (selected == null) notifications
                else notifications.filter { matches(selected, it) }

            Column(Modifier.fillMaxSize().padding(pad)) {
                // 種類が1つしかない日は絞り込む意味が無いので行ごと隠す
                if (shownFilters.size >= 2) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 「すべて」は常に出す（絞り込み解除の導線が消えると戻れなくなる）
                        FilterChip(
                            selected = selected == null,
                            onClick = { selectedKey = FILTER_ALL },
                            label = {
                                Text("すべて ${notifications.size}",
                                    fontSize = 12.sp, maxLines = 1, softWrap = false)
                            }
                        )
                        shownFilters.forEach { f ->
                            val n = notifications.count { matches(f, it) }
                            FilterChip(
                                selected = f.key == selectedKey,
                                onClick = { selectedKey = f.key },
                                label = {
                                    Text("${f.label} $n",
                                        fontSize = 12.sp, maxLines = 1, softWrap = false)
                                }
                            )
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    // 「履歴自体が無い」のと区別する文言（絞り込みで0件になっただけ）
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("この種類の通知はありません",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item { Spacer(Modifier.height(4.dp)) }
                        items(filtered) { log -> NotificationCard(log) }
                        item { Spacer(Modifier.height(12.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(log: NotificationLog) {
    val group = filterOf(log)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 種類バッジ（色分け）。①②の別を残すため文言は元の category をそのまま出す
                CategoryBadge(text = log.category, color = group.color)
                Spacer(Modifier.width(8.dp))
                Text(log.title, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.weight(1f))
                Text("${log.date} ${log.time}", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(log.message, fontSize = 13.sp)
        }
    }
}

// 通知の種類を示す小さな色付きラベル
@Composable
private fun CategoryBadge(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.22f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = 10.sp, color = color, fontWeight = FontWeight.Bold,
            maxLines = 1, softWrap = false)
    }
}
