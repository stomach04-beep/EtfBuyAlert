package com.example.etfbuyalert.ui.screen.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.etfbuyalert.data.model.EtfState
import com.example.etfbuyalert.data.repository.Settings
import com.example.etfbuyalert.domain.AlertEngine
import com.example.etfbuyalert.domain.EtfCategory
import com.example.etfbuyalert.domain.Money
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 監視タブ — 監視中ETFの現在値と各ラインまでの距離を表示。
// 銘柄が増えても見やすいよう「テーマ別／状況別」でグループ分け（折りたたみ式）。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchListScreen(
    etfStates: List<EtfState>,
    isLoading: Boolean,
    lastSyncOk: Boolean,
    lastSyncError: String?,
    lastSyncAt: Long,
    groupMode: String,
    onGroupModeChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onEtfClick: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ETF買い時アラート", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = "更新")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 12.dp)
        ) {
            if (isLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
            }

            // 同期エラーのバナー（前回値で動作中であることを伝える）
            if (lastSyncError != null) {
                ErrorBanner(lastSyncError)
            }

            // 最終同期時刻
            Text(
                text = if (lastSyncAt > 0) "最終同期: ${fmtTime(lastSyncAt)}" + (if (lastSyncOk) "" else "（失敗）")
                       else "未同期",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            if (etfStates.isEmpty()) {
                EmptyState()
            } else {
                // テーマ別／状況別の切替
                GroupModeSelector(groupMode, onGroupModeChange)
                Spacer(Modifier.height(8.dp))

                // 選択モードでグループ分け
                val groups = remember(etfStates, groupMode) { buildGroups(etfStates, groupMode) }
                // 折りたたみ状態（見出し名→開いているか。未登録は「開いている」扱い）
                val collapsed = remember { mutableStateMapOf<String, Boolean>() }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    groups.forEach { group ->
                        val isOpen = collapsed[group.title] ?: true
                        item(key = "header_${group.title}") {
                            SectionHeader(
                                title = group.title,
                                count = group.items.size,
                                expanded = isOpen,
                                onToggle = { collapsed[group.title] = !isOpen }
                            )
                        }
                        if (isOpen) {
                            items(group.items, key = { it.pageId.ifBlank { it.ticker } }) { st ->
                                EtfCard(st, onClick = { onEtfClick(st.ticker) })
                            }
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

// 1グループ（見出し＋その銘柄リスト）
private data class EtfGroup(val title: String, val items: List<EtfState>)

// 監視リストを選択モードに従ってグループへ振り分ける。
private fun buildGroups(states: List<EtfState>, mode: String): List<EtfGroup> {
    return if (mode == Settings.GROUP_STATUS) {
        // 状況別：損切り警戒→深押し→押し目→順張り→通常→データなし の順
        val byZone = states.groupBy { AlertEngine.currentZone(it) }
        AlertEngine.Zone.entries
            .filter { byZone.containsKey(it) }
            .map { zone ->
                val items = byZone.getValue(zone).sortedBy { it.ticker }
                EtfGroup(statusHeader(zone), items)
            }
    } else {
        // テーマ別：カテゴリ（Notion優先→アプリ内対応表で補完）でまとめる。
        // 保存値が未設定/「未分類」なら対応表で引き直す（対応表の更新を即反映）。
        val byCat = states.groupBy { st ->
            val c = st.category
            if (c.isNullOrBlank() || c == EtfCategory.UNCATEGORIZED) EtfCategory.of(st.ticker) else c
        }
        byCat.entries
            .sortedWith(compareBy({ EtfCategory.orderIndex(it.key) }, { it.key }))
            .map { (cat, list) -> EtfGroup(cat, list.sortedBy { it.ticker }) }
    }
}

// 状況別グループの見出し文言（バッジより少し説明的に）
private fun statusHeader(zone: AlertEngine.Zone): String = when (zone) {
    AlertEngine.Zone.STOP -> "🔴 損切り警戒"
    AlertEngine.Zone.DEEP -> "🟢 深押し到達"
    AlertEngine.Zone.DIP -> "🟢 押し目到達"
    AlertEngine.Zone.BREAKOUT -> "🔵 順張り突破"
    AlertEngine.Zone.NORMAL -> "通常"
    AlertEngine.Zone.NODATA -> "データなし"
}

// テーマ別／状況別の切替ボタン
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupModeSelector(mode: String, onChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = mode != Settings.GROUP_STATUS,
            onClick = { onChange(Settings.GROUP_THEME) },
            label = { Text("テーマ別") }
        )
        FilterChip(
            selected = mode == Settings.GROUP_STATUS,
            onClick = { onChange(Settings.GROUP_STATUS) },
            label = { Text("状況別") }
        )
    }
}

// グループの見出し（タップで開閉）。銘柄数バッジ付き。
@Composable
private fun SectionHeader(title: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().clickable { onToggle() }
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            // 銘柄数バッジ
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 9.dp, vertical = 2.dp)
            ) {
                Text("$count", color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "閉じる" else "開く"
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Text(
            "⚠ $message\n（前回取得した価格で監視を続けています）",
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontSize = 13.sp,
            modifier = Modifier.padding(10.dp)
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("監視中のETFがありません", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "「設定」タブでNotionトークンを入力し、\n更新ボタンを押すと、Notionの\n「アプリ監視」ON銘柄が表示されます。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EtfCard(st: EtfState, onClick: () -> Unit) {
    val zone = AlertEngine.currentZone(st)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(st.ticker, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(
                        st.name,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                ZoneBadge(zone)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Money.format(st.ticker, st.price), fontWeight = FontWeight.Bold, fontSize = 26.sp)
                Spacer(Modifier.width(8.dp))
                if (st.previousClose != null && st.price != null) {
                    val diff = (st.price - st.previousClose) / st.previousClose * 100.0
                    Text(
                        String.format("前日比 %+.2f%%", diff),
                        fontSize = 13.sp,
                        color = if (diff >= 0) Color(0xFF4CAF50) else Color(0xFFE57373),
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
                if (st.isLive) {
                    Spacer(Modifier.width(6.dp))
                    Text("● LIVE", fontSize = 11.sp, color = Color(0xFF4CAF50),
                        modifier = Modifier.padding(bottom = 4.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            // 各ライン（通貨判定のためティッカーを引き回す）
            LineRow(st.ticker, "押し目", st.dipPrice, st.price, dipStyle = true)
            LineRow(st.ticker, "深押し", st.deepDipPrice, st.price, dipStyle = true)
            LineRow(st.ticker, "順張り", st.breakoutPrice, st.price, dipStyle = false)
            LineRow(st.ticker, "損切り", st.stopLossPrice, st.price, dipStyle = true,
                suffix = if (st.purchased) "（保有中）" else "（未保有・参考）")
        }
    }
}

@Composable
private fun ZoneBadge(zone: AlertEngine.Zone) {
    val (bg, fg) = when (zone) {
        AlertEngine.Zone.STOP -> Color(0xFFC62828) to Color.White
        AlertEngine.Zone.DEEP -> Color(0xFF2E7D32) to Color.White
        AlertEngine.Zone.DIP -> Color(0xFF4CAF50) to Color.White
        AlertEngine.Zone.BREAKOUT -> Color(0xFF1565C0) to Color.White
        AlertEngine.Zone.NORMAL -> Color(0xFF455A64) to Color.White
        AlertEngine.Zone.NODATA -> Color(0xFF616161) to Color.White
    }
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(zone.label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

// 1ライン分の行（ライン値と現在値からの乖離率）。ticker は通貨（円/ドル）判定用。
@Composable
private fun LineRow(ticker: String, label: String, line: Double?, price: Double?, dipStyle: Boolean, suffix: String = "") {
    if (line == null) return
    val gapText = if (price != null && price != 0.0) {
        val pct = (line - price) / price * 100.0
        String.format("（現在値 %+.1f%%）", -pct)  // +なら現在値はラインより上
    } else ""
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("$label$suffix", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${Money.format(ticker, line)} $gapText", fontSize = 13.sp)
    }
}

private fun fmtTime(epoch: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date(epoch))
