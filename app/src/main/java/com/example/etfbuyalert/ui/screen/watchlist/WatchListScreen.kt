package com.example.etfbuyalert.ui.screen.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.etfbuyalert.ui.changeColor
import com.example.etfbuyalert.ui.kit.Fmt
import com.example.etfbuyalert.ui.kit.InfoToggle
import com.example.etfbuyalert.ui.kit.Panel
import com.example.etfbuyalert.ui.kit.ScreenHeader
import com.example.etfbuyalert.ui.kit.Sem
import com.example.etfbuyalert.ui.kit.Tone
import com.example.etfbuyalert.ui.kit.ToneChip
import com.example.etfbuyalert.ui.kit.num
import com.example.etfbuyalert.data.model.EtfState
import com.example.etfbuyalert.data.repository.Settings
import com.example.etfbuyalert.domain.AlertEngine
import com.example.etfbuyalert.domain.AssetKind
import com.example.etfbuyalert.domain.Freshness
import com.example.etfbuyalert.domain.Money
import com.example.etfbuyalert.domain.NewsWarning
import com.example.etfbuyalert.domain.Symbol
import com.example.etfbuyalert.domain.WatchRanking

// ブックマーク（★）の色。タブのラベルとカードのアイコンで同じ色を使う（対応が分かるように）。
// 2026-10-23 表示の決まり UiKit：直書きの金色をやめ本文色に（secondary はミッドナイト配色で primary とほぼ同じ水色になり「買いの合図」と紛れた）
@Composable
private fun bookmarkColor(): Color = MaterialTheme.colorScheme.onSurface

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

    // 最終同期の時刻（今日 07:12／今年 10/22(水) 07:12。書き方は UiKit の Fmt に統一）
    val syncText = if (lastSyncAt > 0) "最終同期 ${Fmt.dateTime(lastSyncAt)}" + (if (lastSyncOk) "" else "（失敗）")
                   else "未同期"

    Scaffold(
        topBar = {
            // 2026-10-23 表示の決まり UiKit：TopAppBar をやめ、背景と同じ色の見出し1段に。
            // 外側の Scaffold は上の余白を入れない作りなので、ここでステータスバー分を空ける
            ScreenHeader(
                title = "買い時アラート",
                sub = syncText,
                modifier = Modifier.statusBarsPadding()
            ) {
                IconButton(onClick = onRefresh, enabled = !isLoading) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "更新",
                        tint = if (isLoading) MaterialTheme.colorScheme.onSurfaceVariant
                               else MaterialTheme.colorScheme.primary
                    )
                }
            }
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

            // 最終同期時刻は見出し（ScreenHeader の sub）へ移した
            Spacer(Modifier.height(4.dp))

            if (etfStates.isEmpty()) {
                EmptyState()
            } else {
                // ティッカー／銘柄名のインクリメンタル検索（約190銘柄あり、
                // 目的の銘柄がどのタブにいるかを覚えていられないため）。
                // 入力中はタブの絞り込みを無視して全銘柄から探す＝探し物が必ず見つかる。
                var query by rememberSaveable { mutableStateOf("") }
                val searching = query.isNotBlank()
                SearchField(query, onChange = { query = it })
                Spacer(Modifier.height(8.dp))

                // 種別タブ（発火中 / ★ / 日本株 / 米国株 / ETF）。件数付きの本物のタブ表示。
                // 検索中はタブで切らないので、迷わせないようタブ自体を隠す。
                if (!searching) {
                    WatchTabSelector(etfStates, watchTab, onWatchTabChange)
                    Spacer(Modifier.height(8.dp))

                    // 発火中タブのときだけ、ライン方式でさらに絞り込めるようにする。
                    // 「ADP型の発火」と「RTX自動の発火」は意味がまるで違うので分けて見られる必要がある。
                    if (watchTab == Settings.TAB_FIRED) {
                        val firedStates = remember(etfStates) { etfStates.filter { AlertEngine.isFired(it) } }
                        FiredMethodFilterRow(firedStates, firedMethod, onFiredMethodChange)
                        Spacer(Modifier.height(8.dp))
                    }
                }

                // 絞り込み＋「押し目まであと何%」の近い順に並べる（発火中が先頭に来る）。
                // 検索中は全銘柄が対象（絞り込み・並べ替えとも WatchRanking に集約）
                val shown = remember(etfStates, watchTab, firedMethod, query) {
                    if (searching) WatchRanking.search(etfStates, query)
                    else filterAndSort(etfStates, watchTab, firedMethod)
                }
                if (searching) {
                    Text(
                        "「$query」に一致 ${shown.size}件（タブを無視して全銘柄から検索中）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                // 種別チップは種別が混ざるタブ（発火中・★・急落優良）でのみ表示。
                // 市場タブ（日本株/米国株/ETF）は絞り込み済みなので冗長になり消す。
                // 検索中は日本株・米国株・ETFが混ざるので必ず出す。
                val showKind = searching || watchTab == Settings.TAB_FIRED ||
                        watchTab == Settings.TAB_BOOKMARK ||
                        watchTab == Settings.TAB_RTX || watchTab == Settings.TAB_PICKAXE

                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                // 検索して0件のときは「タブのせいではない」ことを明示する
                                searching -> "「$query」に一致する銘柄はありません\n（ティッカー・銘柄名の一部で探せます）"
                                // 方式で絞った結果0件なのか、そもそも発火が無いのかを区別して伝える
                                watchTab == Settings.TAB_FIRED && firedMethod != Settings.FIRED_METHOD_ALL ->
                                    "この方式で発火中の銘柄はありません\n（「すべて」に戻すと他の方式の発火が見られます）"
                                watchTab == Settings.TAB_FIRED -> "いま発火中（ライン到達）の銘柄はありません"
                                watchTab == Settings.TAB_BOOKMARK -> "ブックマークした銘柄はありません\n（カード右上の★、またはNotionの「ブックマーク」列で登録できます）"
                                watchTab == Settings.TAB_RTX -> "急落優良（イベント急落した優良大型株）の検知はいまありません\n（PC側の急落検知ジョブが自動で登録します。候補であり推奨ではありません）"
                                watchTab == Settings.TAB_PICKAXE -> "つるはし候補（テーマ過熱×出遅れ）の検知はいまありません\n（PC側のつるはしレーダーが「本命が急騰したのに道具側が出遅れている」銘柄を自動で登録します。候補であり推奨ではありません）"
                                else -> "この種別に該当する銘柄はありません"
                            },
                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        // カードの見方（ふだんは畳む。2026-10-23 表示の決まり UiKit）
                        item {
                            InfoToggle(title = "カードの見方") {
                                Text(
                                    "・「押し目まで ◯%」＝押し目ラインまでの距離（並び順の基準）。下回ると色が付きます\n" +
                                    "・各ラインの右の％＝現在値がそのラインより何％上（+）か下（−）か\n" +
                                    "・前日比は ▲上げ ▼下げ の記号だけで示します（色は付けません）\n" +
                                    "・赤い札は損切りライン割れ（保有中）だけに使います",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        item { Spacer(Modifier.height(12.dp)) }
                    }
                }
            }
        }
    }
}

/**
 * ティッカー／銘柄名のインクリメンタル検索欄（1文字打つごとに絞り込む）。
 * 判定そのものは WatchRanking.matchesQuery が持ち主で、ここは入力欄だけを持つ。
 */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text("ティッカー・銘柄名で検索", fontSize = 13.sp) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "検索") },
        trailingIcon = {
            // 入力中だけ「消す」を出す（常時出ていると押せる場所と誤解される）
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "検索条件を消す")
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
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
    // つるはし＝pickaxe-radar（テーマ過熱×出遅れ検知）が自動登録した候補行。
    // ライン計算方式=つるはし が印。日本株・米国株が混ざるので種別チップも表示する。
    WatchTab(Settings.TAB_PICKAXE, "つるはし") { it.lineMethod == AssetKind.METHOD_PICKAXE },
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
    MethodFilter("pickaxe", AssetKind.methodLabel(AssetKind.METHOD_PICKAXE)) { it.lineMethod == AssetKind.METHOD_PICKAXE },
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
            // つるはしタブは「出遅れ候補」を先頭に出す（数十銘柄のカタログの中で
            // いま見るべき候補が埋もれないように）。他タブの並びは従来どおり
            // 「押し目まであと何%」の近い順＝WatchRanking が単一の真実の源
            // （ホーム画面ウィジェットの上位3件も同じ並びを使う。UIと二重定義しない）
            compareByDescending<EtfState> {
                tabKey == Settings.TAB_PICKAXE && it.pickaxeLagging
            }.then(WatchRanking.dipGapComparator())
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
        // 2026-10-23 表示の決まり UiKit：区切り線を引かない（選択中の下線だけ残す）
        divider = {},
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
                            fontSize = if (isBookmarkTab) 17.sp else 14.sp,
                            color = if (isBookmarkTab) bookmarkColor() else Color.Unspecified,
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
                                // 2026-10-23 表示の決まり UiKit：発火（＝買いの合図）は primary 1色。
                                // 選択中の件数は primary と紛れないよう本文色にする
                                color = when {
                                    fired -> MaterialTheme.colorScheme.primary   // 発火中タブは件数自体が発火数
                                    i == selectedIndex -> MaterialTheme.colorScheme.onSurface
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            // タブ内の発火中件数（1件以上のときだけ「発火n」を合図の色で添える）
                            if (firedInTab > 0) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "発火$firedInTab",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                    color = MaterialTheme.colorScheme.primary
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
        shape = MaterialTheme.shapes.medium,   // 角丸はテーマの12dp（2026-10-23 表示の決まり UiKit）
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Text(
            "⚠ $message\n（前回取得した価格で監視を続けています）",
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontSize = 14.sp,
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
        Text("監視中の銘柄がありません", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "「設定」タブでNotionトークンを入力し、\n更新ボタンを押すと、Notionの\n「アプリ監視」ON銘柄が表示されます。",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EtfCard(
    st: EtfState,
    showKind: Boolean = true,
    bookmarked: Boolean = false,
    onToggleBookmark: () -> Unit = {},
    onClick: () -> Unit
) {
    val zone = AlertEngine.currentZone(st)
    // 2026-10-23 表示の決まり UiKit：Card（1段上の地）をやめ、影・枠なしの面 Panel で区切る
    Panel(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 日本株は .T を落として4桁コードで見せる（内部は正規形のまま＝Symbol参照）
                        Text(
                            Symbol.display(st.ticker),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            style = TextStyle.Default.num(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // 種別チップは「発火中」タブでのみ表示（市場タブでは絞り込み済みで冗長）
                        if (showKind) {
                            Spacer(Modifier.width(6.dp))
                            KindChip(AssetKind.of(st))
                        }
                        // つるはし出遅れ候補のバッジ（pickaxe-radarの検知。候補提示であり買い推奨ではない）。
                        // 良し悪しではなく「知らせ」なので情報の札（直書きの橙をやめた）
                        if (st.lineMethod == AssetKind.METHOD_PICKAXE && st.pickaxeLagging) {
                            Spacer(Modifier.width(6.dp))
                            ToneChip("出遅れ", Tone.INFO)
                        }
                    }
                    Text(
                        st.name,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ZoneBadge(zone)
                // ブックマークの切替（押すと即保存され、★タブの対象になる）。押す場所は既定の48dp
                IconButton(onClick = onToggleBookmark) {
                    Icon(
                        imageVector = if (bookmarked) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (bookmarked) "ブックマークを外す" else "ブックマークに追加",
                        tint = if (bookmarked) bookmarkColor() else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // 鮮度ガード：7日超の古い価格は表示しない（判定・文言は domain.Freshness が単一の真実の源）
            val priceInvalid = Freshness.isInvalid(st.asOf)
            // 「押し目まであと何%」＝並べ替えのキーと同じ値を表示する（AlertEngineが単一の真実の源）
            val gap = AlertEngine.dipGapPercent(st)
            // 2026-10-23 表示の決まり UiKit：並び順の基準＝押し目までの距離をカードの主役（左・18sp太字）にし、
            // 価格は右に寄せ、前日比などは小さい補足にする（以前は価格26spに距離13spが埋もれていた）
            // 1段目＝距離（左・weight）と価格（右）。幅が足りないときは距離の文字を省略記号で詰める
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (gap != null) {
                        // 下回った＝買いの合図なので primary 1色（緑は「完了・良好」専用）
                        Text(
                            if (gap <= 0) "押し目を ${Fmt.num(-gap, 1)}% 下回り"
                            else "押し目まで ${Fmt.num(gap, 1)}%",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (gap <= 0) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (priceInvalid) {
                    // 価格を出さずに取得失敗を明示（古い値を「現在値」と誤解させない）。
                    // 赤は損切り割れ専用にするため、警戒の色にする
                    Text(
                        Freshness.INVALID_TEXT,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Sem.fg(Tone.WARN),
                        textAlign = TextAlign.End
                    )
                } else {
                    Text(
                        Money.format(st.ticker, st.price),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        style = TextStyle.Default.num(),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
            // 2段目＝価格の補足（右寄せ・12sp）。1段目と横幅を取り合わないよう別の行にする
            if (!priceInvalid) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    PriceSubLine(st)
                }
            }
            // 週足RSI（「RSI利確監視」=ONの銘柄のみ。閾値はAlertEngineが単一の真実の源）
            // 75以上で警告色（順張りの過熱）、80以上でさらに強調（エラー色）
            val rsi = st.weeklyRsi
            if (st.rsiWatch && rsi != null) {
                Spacer(Modifier.height(4.dp))
                // 2026-10-23 表示の決まり UiKit：過熱は「危険」ではないので赤を使わず、警戒／注意の色に
                val (rsiColor, rsiWeight) = when {
                    rsi >= AlertEngine.RSI_TAKE2 -> Sem.fg(Tone.WARN) to FontWeight.Bold
                    rsi >= AlertEngine.RSI_TAKE1 -> Sem.fg(Tone.CAUTION) to FontWeight.Bold
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
                    color = if (fired) Sem.fg(Tone.WARN) else MaterialTheme.colorScheme.onSurfaceVariant  // 直書きの橙→警戒の色
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
                    color = Sem.fg(Tone.WARN),   // 直書きの橙→警戒の色（2026-10-23 表示の決まり UiKit）
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
// 2026-10-23 表示の決まり UiKit：種別は良し悪しではないので、3色の直書きをやめ通常の札1種類に
// （種別は文字で区別できる）
@Composable
private fun KindChip(kind: AssetKind.Kind) {
    ToneChip(kind.label, Tone.NORMAL)
}

// ゾーンの札。2026-10-23 表示の決まり UiKit：
//  ・損切り割れ＝危険（赤）。赤はここだけ
//  ・押し目・深押し＝買いの合図なので primary 1色（緑は使わない）。深押しは塗りつぶしで1段強く
//  ・順張り＝情報（青）、通常・取得なし＝通常（灰）
@Composable
private fun ZoneBadge(zone: AlertEngine.Zone) {
    when (zone) {
        AlertEngine.Zone.STOP -> ToneChip(zone.label, Tone.CRIT)
        AlertEngine.Zone.DEEP -> SignalChip(zone.label, filled = true)
        AlertEngine.Zone.DIP -> SignalChip(zone.label, filled = false)
        AlertEngine.Zone.BREAKOUT -> ToneChip(zone.label, Tone.INFO)
        AlertEngine.Zone.NORMAL, AlertEngine.Zone.NODATA -> ToneChip(zone.label, Tone.NORMAL)
    }
}

// 買いの合図の札（ToneChip と同じ形で、色だけテーマの primary）。
// filled=true は primary の塗りつぶし、false は primary を薄く敷いた地に primary の文字
@Composable
private fun SignalChip(text: String, filled: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val bg = if (filled) primary else primary.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surface)
    val fg = if (filled) MaterialTheme.colorScheme.onPrimary else primary
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

// 価格の下の補足1行（12sp）：古い値の注記・前日比・場中の印。
// 前日比は ▲▼ の記号と色（プラス＝緑・マイナス＝赤）。2026-10-23 に一度色を外したが、
// 2026-10-24 ユーザー指示で v1.27 までの色分けに戻した（色は changeColor の1か所で決める）
@Composable
private fun PriceSubLine(st: EtfState) {
    // 3日超は「（◯日前の値）」を添えて、最新値と誤解させない（文言は Freshness が単一定義）
    val staleNote = Freshness.staleSuffix(st.asOf)
    val prev = st.previousClose
    val price = st.price
    val diff = if (prev != null && price != null) (price - prev) / prev * 100.0 else null
    val diffText = diff?.let {
        val delta = Fmt.change(it, 2)
        // Fmt.change は 0 のとき「変わらず」を返すので % を付けない
        "前日比 " + if (it == 0.0) delta else "$delta%"
    }
    if (staleNote.isEmpty() && diffText == null && !st.isLive) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (staleNote.isNotEmpty()) {
            Text(staleNote, fontSize = 12.sp, color = Sem.fg(Tone.WARN), maxLines = 1, softWrap = false)
            Spacer(Modifier.width(6.dp))
        }
        if (diffText != null && diff != null) {
            Text(
                diffText,
                fontSize = 12.sp,
                style = TextStyle.Default.num(),
                // プラス＝緑・マイナス＝赤（色は changeColor の1か所で決める）
                color = changeColor(diff),
                maxLines = 1,
                softWrap = false
            )
        }
        // 市場が開いている時間（isLive＝取引時間中）の印。英語の LIVE をやめ日本語に
        if (st.isLive) {
            Spacer(Modifier.width(6.dp))
            Text("● 場中", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, softWrap = false)
        }
    }
}

// 1ライン分の行（ライン値と現在値からの乖離率）。ticker は通貨（円/ドル）判定用。
@Composable
private fun LineRow(ticker: String, label: String, line: Double?, price: Double?, dipStyle: Boolean, suffix: String = "") {
    if (line == null) return
    // +なら現在値はラインより上（意味は画面下の「カードの見方」に畳んで書いた）
    val gapText = if (price != null && price != 0.0) {
        val pct = (line - price) / price * 100.0
        Fmt.pct(-pct, 1)
    } else ""
    // 2026-10-23 表示の決まり UiKit：ライン値と乖離率を別の Text に分け、どちらも右寄せの等幅数字に
    // （1つの文字列にまとめると桁がそろわず縦に読めなかった）
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$label$suffix",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            Money.format(ticker, line),
            fontSize = 14.sp,
            style = TextStyle.Default.num(),
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false
        )
        Text(
            gapText,
            fontSize = 12.sp,
            style = TextStyle.Default.num(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.widthIn(min = 64.dp)
        )
    }
}
