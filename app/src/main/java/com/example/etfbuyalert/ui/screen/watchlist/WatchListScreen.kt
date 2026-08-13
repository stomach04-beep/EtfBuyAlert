package com.example.etfbuyalert.ui.screen.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.etfbuyalert.data.model.EtfState
import com.example.etfbuyalert.data.repository.Settings
import com.example.etfbuyalert.domain.AlertEngine
import com.example.etfbuyalert.domain.AssetKind
import com.example.etfbuyalert.domain.Freshness
import com.example.etfbuyalert.domain.Money
import com.example.etfbuyalert.domain.NewsWarning
import com.example.etfbuyalert.domain.Symbol
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ブックマーク（★）の色。タブのラベルとカードのアイコンで同じ色を使う（対応が分かるように）
private val BOOKMARK_COLOR = Color(0xFFFFD54F)

// 監視タブ — 監視中銘柄（ETF＋ADP型の個別株）の現在値と各ラインまでの距離を表示。
// 90行規模になったため、種別タブ（発火中/日本株/米国株/ETF）で絞り込み、
// 「押し目まであと何%」の近い順に並べる（発火中が自動的に先頭へ来るので埋もれない）。
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
    firedMethod: String,
    onFiredMethodChange: (String) -> Unit,
    onToggleBookmark: (String) -> Unit,
    bookmarkError: String?,
    onBookmarkErrorShown: () -> Unit,
    onRefresh: () -> Unit,
    onEtfClick: (String) -> Unit
) {
    // ブックマークをNotionへ保存できなかったときだけ下部に出す（黙って食い違わせない）
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(bookmarkError) {
        val msg = bookmarkError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        onBookmarkErrorShown()
    }

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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
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
                // 種別タブ（発火中 / ★ / 日本株 / 米国株 / ETF）。件数付きの本物のタブ表示。
                WatchTabSelector(etfStates, watchTab, onWatchTabChange)
                Spacer(Modifier.height(8.dp))

                // 発火中タブのときだけ、ライン方式でさらに絞り込めるようにする。
                // 「ADP型の発火」と「RTX自動の発火」は意味がまるで違うので分けて見られる必要がある。
                if (watchTab == Settings.TAB_FIRED) {
                    val firedStates = remember(etfStates) { etfStates.filter { AlertEngine.isFired(it) } }
                    FiredMethodFilterRow(firedStates, firedMethod, onFiredMethodChange)
                    Spacer(Modifier.height(8.dp))
                }

                // 絞り込み＋「押し目まであと何%」の近い順に並べる（発火中が先頭に来る）
                val shown = remember(etfStates, watchTab, firedMethod) {
                    filterAndSort(etfStates, watchTab, firedMethod)
                }
                // 種別チップは種別が混ざるタブ（発火中・★・急落優良）でのみ表示。
                // 市場タブ（日本株/米国株/ETF）は絞り込み済みなので冗長になり消す。
                val showKind = watchTab == Settings.TAB_FIRED || watchTab == Settings.TAB_BOOKMARK ||
                        watchTab == Settings.TAB_RTX

                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                // 方式で絞った結果0件なのか、そもそも発火が無いのかを区別して伝える
                                watchTab == Settings.TAB_FIRED && firedMethod != Settings.FIRED_METHOD_ALL ->
                                    "この方式で発火中の銘柄はありません\n（「すべて」に戻すと他の方式の発火が見られます）"
                                watchTab == Settings.TAB_FIRED -> "いま発火中（ライン到達）の銘柄はありません"
                                watchTab == Settings.TAB_BOOKMARK -> "ブックマークした銘柄はありません\n（カード右上の★、またはNotionの「ブックマーク」列で登録できます）"
                                watchTab == Settings.TAB_RTX -> "急落優良（イベント急落した優良大型株）の検知はいまありません\n（PC側の急落検知ジョブが自動で登録します。候補であり推奨ではありません）"
                                else -> "この種別に該当する銘柄はありません"
                            },
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shown, key = { it.pageId.ifBlank { it.ticker } }) { st ->
                            EtfCard(
                                st,
                                showKind = showKind,
                                bookmarked = st.bookmarked,
                                onToggleBookmark = { onToggleBookmark(st.ticker) },
                                onClick = { onEtfClick(st.ticker) }
                            )
                        }
                        item { Spacer(Modifier.height(12.dp)) }
                    }
                }
            }
        }
    }
}

// タブの定義（キー・表示名・その銘柄が該当するかの判定）を1か所にまとめる。
// タブを増やすときはここに1行足すだけで、選択肢・件数・絞り込みが全部ついてくる。
// ブックマークの印は EtfState.bookmarked（Notion同期）を見るだけでよい。
private data class WatchTab(
    val key: String,
    val label: String,
    val match: (EtfState) -> Boolean
)

private val TABS: List<WatchTab> = listOf(
    WatchTab(Settings.TAB_FIRED, "発火中") { AlertEngine.isFired(it) },
    WatchTab(Settings.TAB_BOOKMARK, "★") { it.bookmarked },
    // 急落優良＝イベント急落した優良大型株（reversal-screener のRTX型検知が自動登録した行）。
    // ライン計算方式=RTX自動 が印。日本株・米国株が混ざるので種別チップも表示する。
    WatchTab(Settings.TAB_RTX, "急落優良") { it.lineMethod == AssetKind.METHOD_RTX },
    WatchTab(Settings.TAB_JP, "日本株") { AssetKind.of(it) == AssetKind.Kind.JP_STOCK },
    WatchTab(Settings.TAB_US, "米国株") { AssetKind.of(it) == AssetKind.Kind.US_STOCK },
    WatchTab(Settings.TAB_ETF, "ETF") { AssetKind.of(it) == AssetKind.Kind.ETF },
)

// 発火中タブの中の「ライン方式」絞り込み。
// 同じ発火でも方式によって意味がまったく違う（ADP型＝事前登録した割安条件の成立、
// RTX自動＝機械が急落を拾った候補で10年BTでは優位性なし、手動＝自分で決めた狙い値、
// MA200＝200日線を割っただけ）ので、混ぜて見ると判断を誤る。
// タブを増やすと横スクロールが伸びるため、発火中タブの中の絞り込みとして持たせる。
private data class MethodFilter(
    val key: String,
    val label: String,
    val match: (EtfState) -> Boolean
)

// 表示名は AssetKind.methodLabel に単一定義（「ADP型」「RTX」のままでは意味が取れないため
// 「配当割安」「急落優良」へ写して表示する。内部キー・Notion値は従来どおり）
private val METHOD_FILTERS: List<MethodFilter> = listOf(
    MethodFilter(Settings.FIRED_METHOD_ALL, "すべて") { true },
    MethodFilter("adp", AssetKind.methodLabel(AssetKind.METHOD_ADP)) { it.lineMethod == AssetKind.METHOD_ADP },
    MethodFilter("manual", AssetKind.methodLabel(AssetKind.METHOD_MANUAL)) { it.lineMethod == AssetKind.METHOD_MANUAL },
    MethodFilter("ma200", AssetKind.methodLabel(AssetKind.METHOD_MA200)) { it.lineMethod == AssetKind.METHOD_MA200 },
    MethodFilter("rtx", AssetKind.methodLabel(AssetKind.METHOD_RTX)) { it.lineMethod == AssetKind.METHOD_RTX },
)

/**
 * 選択タブで絞り込み、「押し目まであと何%」の近い順に並べる。
 * 発火中（gapが負）が自動的に先頭へ来るので、90行あっても今見るべき銘柄が埋もれない。
 * 距離を出せない銘柄（価格未取得・ライン未設定）は末尾にまとめる。
 * methodKey は発火中タブのときだけ効く（他のタブでは方式で切らない）。
 */
private fun filterAndSort(
    states: List<EtfState>,
    tabKey: String,
    methodKey: String = Settings.FIRED_METHOD_ALL
): List<EtfState> {
    val tab = TABS.firstOrNull { it.key == tabKey } ?: TABS.first()
    val method = if (tabKey == Settings.TAB_FIRED) {
        METHOD_FILTERS.firstOrNull { it.key == methodKey } ?: METHOD_FILTERS.first()
    } else {
        METHOD_FILTERS.first()
    }
    return states.filter { tab.match(it) && method.match(it) }
        .sortedWith(
            compareBy<EtfState> { AlertEngine.dipGapPercent(it) ?: Double.MAX_VALUE }
                .thenBy { it.ticker }
        )
}

/**
 * 発火中タブの中で「ライン方式」を絞り込むチップ行。件数が0の方式は出さない
 * （その日いない方式のチップが並んでも押す意味がなく、横幅を食うだけ）。
 * 「すべて」だけは常に出す（絞り込みを解除する導線が消えると戻れなくなる）。
 */
@Composable
private fun FiredMethodFilterRow(
    firedStates: List<EtfState>,
    selected: String,
    onChange: (String) -> Unit
) {
    val shown = METHOD_FILTERS.filter { f ->
        f.key == Settings.FIRED_METHOD_ALL || firedStates.any(f.match)
    }
    // 方式が1種類しかない日は絞り込む意味が無いので行ごと隠す
    if (shown.size <= 2) return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        shown.forEach { f ->
            val n = firedStates.count(f.match)
            FilterChip(
                selected = f.key == selected,
                onClick = { onChange(f.key) },
                label = { Text("${f.label} $n", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            )
        }
    }
}

// 種別タブ（件数付き）。FilterChipの行からタブ表示に変更（2026-07-26）。
// 選択中は下線＋強調色でひと目で分かる。
//
// ★タブ追加で5タブになったため、等幅の固定TabRowは使えない（実測: 1タブ216pxのうち
// Material3のTab内部padding 32dpを引くと120pxしか残らず「発火中」が「発火C」に切れた）。
// 横スクロール式にして、ラベルと件数を必ず全文表示する。
@Composable
private fun WatchTabSelector(
    states: List<EtfState>,
    selected: String,
    onChange: (String) -> Unit
) {
    // 保存されているキーがタブ一覧に無い場合（旧設定・廃止した「すべて」など）は先頭タブを選択扱いにする
    val selectedIndex = TABS.indexOfFirst { it.key == selected }.let { if (it >= 0) it else 0 }
    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        edgePadding = 0.dp,
        containerColor = Color.Transparent,
    ) {
        TABS.forEachIndexed { i, tab ->
            val n = states.count(tab.match)
            // このタブに属する発火中（ライン到達）の件数。
            // 「発火中」タブ自体は n がそのまま発火数なので二重には出さない。
            val firedInTab = if (tab.key == Settings.TAB_FIRED) 0
                             else states.count { tab.match(it) && AlertEngine.isFired(it) }
            // 発火中タブは1件以上あるとき件数を警告色にして目立たせる
            val fired = tab.key == Settings.TAB_FIRED && n > 0
            Tab(
                selected = i == selectedIndex,
                onClick = { onChange(tab.key) },
                text = {
                    // 上段=種別名だけ、下段=件数＋そのタブ内の発火中件数（2行構成）。
                    // 等幅タブは1枠が狭いので、数字を上段に混ぜると折り返して縦に割れる。
                    // そのため softWrap=false / maxLines=1 で必ず1行に収める。
                    val isBookmarkTab = tab.key == Settings.TAB_BOOKMARK
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            tab.label,
                            // ★（ブックマーク）タブは記号1文字なので少し大きめ＋金色にして
                            // カード右上のブックマークアイコンと色を揃える
                            fontSize = if (isBookmarkTab) 17.sp else 13.sp,
                            color = if (isBookmarkTab) BOOKMARK_COLOR else Color.Unspecified,
                            fontWeight = if (i == selectedIndex) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "$n",
                                fontSize = 12.sp,
                                fontWeight = if (fired) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                softWrap = false,
                                color = when {
                                    fired -> Color(0xFFFFB74D)          // 発火中タブは件数自体が発火数
                                    i == selectedIndex -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            // タブ内の発火中件数（1件以上のときだけ「発火n」を警告色で添える）
                            if (firedInTab > 0) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "発火$firedInTab",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                    color = Color(0xFFFFB74D)
                                )
                            }
                        }
                    }
                }
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
private fun EtfCard(
    st: EtfState,
    showKind: Boolean = true,
    bookmarked: Boolean = false,
    onToggleBookmark: () -> Unit = {},
    onClick: () -> Unit
) {
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
                        // 種別チップは「発火中」タブでのみ表示（市場タブでは絞り込み済みで冗長）
                        if (showKind) {
                            Spacer(Modifier.width(6.dp))
                            KindChip(AssetKind.of(st))
                        }
                    }
                    Text(
                        st.name,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                ZoneBadge(zone)
                Spacer(Modifier.width(4.dp))
                // ブックマークの切替（押すと即保存され、★タブの対象になる）
                IconButton(onClick = onToggleBookmark, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (bookmarked) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (bookmarked) "ブックマークを外す" else "ブックマークに追加",
                        tint = if (bookmarked) BOOKMARK_COLOR else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
            // 売り時監視（保有中の日本株個別株のみ。ルールと根拠は SellRules 参照）。
            // アーム中＝「急騰した玉の売り時を見張っている」状態を1行で示し、
            // 点灯中は警告色で強調する（通知を見逃してもカードで気づけるように）。
            if (st.sellArmed && !st.sellExcluded && st.purchased) {
                Spacer(Modifier.height(4.dp))
                val fired = st.sellTrailFired || st.sellMa50Fired
                val sellText = when {
                    st.sellTrailFired -> String.format("🟠 売り時点灯：高値から%.1f%%下落（-15%%ルール）", -(st.sellDropPct ?: 0.0))
                    st.sellMa50Fired -> "🟠 売り時点灯：50日線割れ"
                    else -> String.format("売り監視中（高値から%.1f%%）", -(st.sellDropPct ?: 0.0))
                }
                Text(
                    sellText,
                    fontSize = 12.sp,
                    fontWeight = if (fired) FontWeight.Bold else FontWeight.Normal,
                    color = if (fired) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // ニュース警告（PC側の照合ジョブがNotionへ書いた【要注意】材料。表示判定は NewsWarning が単一定義）
            // 発火していても「買ってはいけない発火」かもしれない印なので、警告色で目立たせる
            val newsText = NewsWarning.displayText(st.newsWarning)
            if (newsText != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "⚠ $newsText",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFB74D),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
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
