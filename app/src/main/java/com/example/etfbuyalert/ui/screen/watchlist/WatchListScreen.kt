package com.example.etfbuyalert.ui.screen.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.example.etfbuyalert.domain.AssetKind
import com.example.etfbuyalert.domain.Freshness
import com.example.etfbuyalert.domain.Money
import com.example.etfbuyalert.domain.Symbol
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 監視タブ — 監視中銘柄（ETF＋ADP型の個別株）の現在値と各ラインまでの距離を表示。
// 90行規模になったため、種別タブで絞り込み「押し目まであと何%」の近い順に並べる
// （発火中が自動的に先頭へ来るので、今見るべき銘柄が埋もれない）。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchListScreen(
    etfStates: List<EtfState>,
    isLoading: Boolean,
    lastSyncOk: Boolean,
    lastSyncError: String?,
    lastSyncAt: Long,
    watchTab: String,
    onWatchTabChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onEtfClick: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("買い時アラート", fontWeight = FontWeight.Bold) },
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
                // 種別タブ（すべて / 発火中 / 米国株 / 日本株 / ETF）。件数バッジ付き。
                WatchTabSelector(etfStates, watchTab, onWatchTabChange)
                Spacer(Modifier.height(8.dp))

                // 絞り込み＋「押し目まであと何%」の近い順に並べる（発火中が先頭に来る）
                val shown = remember(etfStates, watchTab) { filterAndSort(etfStates, watchTab) }

                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                        Text("この種別に該当する銘柄はありません",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shown, key = { it.pageId.ifBlank { it.ticker } }) { st ->
                            EtfCard(st, onClick = { onEtfClick(st.ticker) })
                        }
                        item { Spacer(Modifier.height(12.dp)) }
                    }
                }
            }
        }
    }
}

// タブの定義（キー・表示名・その銘柄が該当するかの判定）を1か所にまとめる。
// タブを増やすときはここに1行足すだけで、選択肢・件数バッジ・絞り込みが全部ついてくる。
private data class WatchTab(val key: String, val label: String, val match: (EtfState) -> Boolean)

private val TABS: List<WatchTab> = listOf(
    WatchTab(Settings.TAB_ALL, "すべて") { true },
    WatchTab(Settings.TAB_FIRED, "発火中") { AlertEngine.isFired(it) },
    WatchTab(Settings.TAB_US, "米国株") { AssetKind.of(it) == AssetKind.Kind.US_STOCK },
    WatchTab(Settings.TAB_JP, "日本株") { AssetKind.of(it) == AssetKind.Kind.JP_STOCK },
    WatchTab(Settings.TAB_ETF, "ETF") { AssetKind.of(it) == AssetKind.Kind.ETF },
)

/**
 * 選択タブで絞り込み、「押し目まであと何%」の近い順に並べる。
 * 発火中（gapが負）が自動的に先頭へ来るので、90行あっても今見るべき銘柄が埋もれない。
 * 距離を出せない銘柄（価格未取得・ライン未設定）は末尾にまとめる。
 */
private fun filterAndSort(states: List<EtfState>, tabKey: String): List<EtfState> {
    val tab = TABS.firstOrNull { it.key == tabKey } ?: TABS.first()
    return states.filter(tab.match)
        .sortedWith(
            compareBy<EtfState> { AlertEngine.dipGapPercent(it) ?: Double.MAX_VALUE }
                .thenBy { it.ticker }
        )
}

// 種別タブ（横スクロール可・件数バッジ付き）
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchTabSelector(states: List<EtfState>, selected: String, onChange: (String) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TABS.forEach { tab ->
            val n = states.count(tab.match)
            FilterChip(
                selected = selected == tab.key,
                onClick = { onChange(tab.key) },
                label = { Text("${tab.label} $n") }
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
        Text("監視中の銘柄がありません", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 日本株は .T を落として4桁コードで見せる（内部は正規形のまま＝Symbol参照）
                        Text(Symbol.display(st.ticker), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(Modifier.width(6.dp))
                        KindChip(AssetKind.of(st))
                    }
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
            // 鮮度ガード：7日超の古い価格は表示しない（判定・文言は domain.Freshness が単一の真実の源）
            val priceInvalid = Freshness.isInvalid(st.asOf)
            if (priceInvalid) {
                // 価格を出さずに取得失敗を明示（古い値を「現在値」と誤解させない）
                Text(
                    Freshness.INVALID_TEXT,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.error
                )
            } else Row(verticalAlignment = Alignment.Bottom) {
                Text(Money.format(st.ticker, st.price), fontWeight = FontWeight.Bold, fontSize = 26.sp)
                // 3日超は「（◯日前の値）」を添えて、最新値と誤解させない
                val staleNote = Freshness.staleSuffix(st.asOf)
                if (staleNote.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(staleNote, fontSize = 12.sp, color = Color(0xFFFFB74D),
                        modifier = Modifier.padding(bottom = 4.dp))
                }
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
            // 「押し目まであと何%」＝並べ替えのキーと同じ値を表示する（AlertEngineが単一の真実の源）
            val gap = AlertEngine.dipGapPercent(st)
            if (gap != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (gap <= 0) String.format("押し目を %.1f%% 下回っています", -gap)
                    else String.format("押し目まであと %.1f%%", gap),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (gap <= 0) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 週足RSI（「RSI利確監視」=ONの銘柄のみ。閾値はAlertEngineが単一の真実の源）
            // 75以上で警告色（順張りの過熱）、80以上でさらに強調（エラー色）
            val rsi = st.weeklyRsi
            if (st.rsiWatch && rsi != null) {
                Spacer(Modifier.height(4.dp))
                val (rsiColor, rsiWeight) = when {
                    rsi >= AlertEngine.RSI_TAKE2 -> MaterialTheme.colorScheme.error to FontWeight.Bold
                    rsi >= AlertEngine.RSI_TAKE1 -> Color(0xFFFFB74D) to FontWeight.Bold
                    else -> MaterialTheme.colorScheme.onSurfaceVariant to FontWeight.Normal
                }
                Text(
                    String.format("週足RSI %.1f", rsi),
                    fontSize = 12.sp,
                    fontWeight = rsiWeight,
                    color = rsiColor
                )
            }
            Spacer(Modifier.height(10.dp))
            // 各ライン（通貨判定のためティッカーを引き回す）。値が無い行は表示されない
            // （ADP型の個別株は損切り・順張りを設定しない＝待ち構え用のため）
            // 鮮度切れの価格では乖離率を出さない（ライン値自体はNotion由来なので表示する）
            val gapPrice = if (priceInvalid) null else st.price
            LineRow(st.ticker, "押し目", st.dipPrice, gapPrice, dipStyle = true)
            LineRow(st.ticker, "深押し", st.deepDipPrice, gapPrice, dipStyle = true)
            LineRow(st.ticker, "順張り", st.breakoutPrice, gapPrice, dipStyle = false)
            LineRow(st.ticker, "損切り", st.stopLossPrice, gapPrice, dipStyle = true,
                suffix = if (st.purchased) "（保有中）" else "（未保有・参考）")
        }
    }
}

// 種別チップ（米国株 / 日本株 / ETF）。ティッカーの隣に小さく出す。
@Composable
private fun KindChip(kind: AssetKind.Kind) {
    val bg = when (kind) {
        AssetKind.Kind.US_STOCK -> Color(0xFF37474F)
        AssetKind.Kind.JP_STOCK -> Color(0xFF4E342E)
        AssetKind.Kind.ETF -> Color(0xFF283593)
    }
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(kind.label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
