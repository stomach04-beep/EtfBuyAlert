package com.example.etfbuyalert.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.etfbuyalert.MainViewModel
import com.example.etfbuyalert.R
import com.example.etfbuyalert.ui.kit.GearButton
import com.example.etfbuyalert.ui.kit.ScreenHeader
import com.example.etfbuyalert.ui.kit.ScreenTabs
import com.example.etfbuyalert.ui.screen.detail.EtfDetailScreen
import com.example.etfbuyalert.ui.screen.history.HistoryScreen
import com.example.etfbuyalert.ui.screen.settings.SettingsScreen
import com.example.etfbuyalert.ui.screen.watchlist.WatchListScreen
import kotlinx.coroutines.launch

/**
 * 主画面の横タブの名前（タブ名の一覧はここ1か所）。
 * 2026-10-25 APP_RULES.md A2・A4・A5：下タブ（監視／履歴／設定）をやめ、見出しのすぐ下の横タブ2枚にした。
 * 設定はタブにせず見出し右の歯車から開く1段深い画面（route "settings"）。
 */
private val MAIN_TABS = listOf("監視", "履歴")

/** 画面の行き先（主画面＝横タブ2枚、詳細、設定） */
private const val ROUTE_MAIN = "main"
private const val ROUTE_SETTINGS = "settings"

/**
 * @param openTicker          通知タップで開くよう指定された銘柄（無ければ null）。
 *                            1銘柄ぶんの通知は「その銘柄の話」なので詳細画面へ直行させる（F2）。
 * @param onOpenTickerHandled 遷移し終わったら呼ぶ（同じ指定で二度飛ばないようにする）
 */
@Composable
fun AppNavigation(
    viewModel: MainViewModel,
    openTicker: String? = null,
    onOpenTickerHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    // 監視タブ用の状態
    val etfStates by viewModel.etfStates.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val lastSyncOk by viewModel.lastSyncOk.collectAsStateWithLifecycle()
    val lastSyncError by viewModel.lastSyncError.collectAsStateWithLifecycle()
    val lastSyncAt by viewModel.lastSyncAt.collectAsStateWithLifecycle()
    val watchTab by viewModel.watchTab.collectAsStateWithLifecycle()
    val firedMethod by viewModel.firedMethod.collectAsStateWithLifecycle()
    val bookmarkError by viewModel.bookmarkError.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val chart by viewModel.chart.collectAsStateWithLifecycle()
    val chartLoading by viewModel.chartLoading.collectAsStateWithLifecycle()
    // 詳細画面の選択期間（既定6ヶ月）
    var detailRange by remember { mutableStateOf("6mo") }

    // 設定画面用の状態
    val notionToken by viewModel.notionToken.collectAsStateWithLifecycle()
    val notionDb by viewModel.notionDb.collectAsStateWithLifecycle()
    val intervalMin by viewModel.intervalMin.collectAsStateWithLifecycle()
    val morningHour by viewModel.morningHour.collectAsStateWithLifecycle()
    val morningMin by viewModel.morningMin.collectAsStateWithLifecycle()
    val notifyDip by viewModel.notifyDip.collectAsStateWithLifecycle()
    val notifyStop by viewModel.notifyStop.collectAsStateWithLifecycle()
    val notifyBreakout by viewModel.notifyBreakout.collectAsStateWithLifecycle()
    val notifyMorning by viewModel.notifyMorning.collectAsStateWithLifecycle()
    val notifyZoneChange by viewModel.notifyZoneChange.collectAsStateWithLifecycle()

    // 横タブのページ位置（2026-10-25 APP_RULES.md A2）。NavHost の外で持つので、
    // 詳細・設定から戻っても直前のタブのまま
    val pager = rememberPagerState(pageCount = { MAIN_TABS.size })

    // 2026-10-25 APP_RULES.md A7：上下のシステムのバーの余白はこの Scaffold で1回だけ入れる。
    // 中の画面は ScreenHeader（自分では余白を足さない）なので、consumeWindowInsets で二重に足さないようにする
    Scaffold { innerPadding ->
        NavHost(
            navController,
            startDestination = ROUTE_MAIN,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            composable(ROUTE_MAIN) {
                // 2026-10-25 APP_RULES.md A5：履歴タブで戻るを押したら監視タブへ（監視タブでは今までどおり終了）
                BackHandler(enabled = pager.currentPage != 0) {
                    scope.launch { pager.animateScrollToPage(0) }
                }
                Column(modifier = Modifier.fillMaxSize()) {
                    // 2026-10-25 APP_RULES.md A2・A5：見出しはアプリ名＋歯車の1つだけ。線はタブの列の下
                    ScreenHeader(title = stringResource(R.string.app_name), divider = false) {
                        GearButton(onClick = {
                            navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true }
                        })
                    }
                    ScreenTabs(MAIN_TABS, pager.currentPage) { i ->
                        scope.launch { pager.animateScrollToPage(i) }
                    }
                    // 監視・履歴の中に横に引く操作は無い（チップの列は内側が先に動く）ので、横スワイプでもタブを移れる
                    HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                        when (page) {
                            0 -> WatchListScreen(
                                etfStates = etfStates,
                                isLoading = isLoading,
                                lastSyncOk = lastSyncOk,
                                lastSyncError = lastSyncError,
                                lastSyncAt = lastSyncAt,
                                watchTab = watchTab,
                                onWatchTabChange = { viewModel.setWatchTab(it) },
                                firedMethod = firedMethod,
                                onFiredMethodChange = { viewModel.setFiredMethod(it) },
                                onToggleBookmark = { viewModel.toggleBookmark(it) },
                                bookmarkError = bookmarkError,
                                onBookmarkErrorShown = { viewModel.clearBookmarkError() },
                                onRefresh = { viewModel.refreshData() },
                                onEtfClick = { ticker -> navController.navigate("detail/$ticker") }
                            )
                            else -> HistoryScreen(
                                notifications = notifications,
                                onReload = { viewModel.reloadNotificationHistory() },
                                onClear = { viewModel.clearNotificationHistory() }
                            )
                        }
                    }
                }
            }
            composable(
                "detail/{ticker}",
                arguments = listOf(navArgument("ticker") { type = NavType.StringType })
            ) { backStackEntry ->
                val ticker = backStackEntry.arguments?.getString("ticker") ?: return@composable
                val st = etfStates.find { it.ticker == ticker }
                // 画面表示・期間変更のたびにチャートを読み込む
                LaunchedEffect(ticker, detailRange) { viewModel.loadChart(ticker, detailRange) }
                EtfDetailScreen(
                    state = st,
                    chart = chart,
                    chartLoading = chartLoading,
                    range = detailRange,
                    onRangeChange = { detailRange = it },
                    onBack = { viewModel.clearChart(); navController.popBackStack() }
                )
            }
            composable(ROUTE_SETTINGS) {
                // 歯車から開く1段深い画面。戻ると主画面の直前のタブへ（ページ位置は NavHost の外で保持）
                SettingsScreen(
                    notionToken = notionToken,
                    notionDb = notionDb,
                    intervalMin = intervalMin,
                    morningHour = morningHour,
                    morningMin = morningMin,
                    notifyDip = notifyDip,
                    notifyStop = notifyStop,
                    notifyBreakout = notifyBreakout,
                    notifyMorning = notifyMorning,
                    notifyZoneChange = notifyZoneChange,
                    // 健全性チェックのバナー（F1）は監視中の実データから毎回求める。
                    // 数値を別に保存すると画面と実データがズレるので、状態そのものを渡す
                    etfStates = etfStates,
                    onNotionTokenChange = { viewModel.setNotionToken(it) },
                    onNotionDbChange = { viewModel.setNotionDb(it) },
                    onIntervalChange = { viewModel.setIntervalMin(it) },
                    onMorningTimeChange = { h, m -> viewModel.setMorningTime(h, m) },
                    onNotifyDipToggle = { viewModel.setNotifyDip(it) },
                    onNotifyStopToggle = { viewModel.setNotifyStop(it) },
                    onNotifyBreakoutToggle = { viewModel.setNotifyBreakout(it) },
                    onNotifyMorningToggle = { viewModel.setNotifyMorning(it) },
                    onNotifyZoneChangeToggle = { viewModel.setNotifyZoneChange(it) },
                    onTestNotification = { viewModel.sendTestNotification() },
                    onClearData = { viewModel.clearAllData() },
                    onBack = { navController.popBackStack() }
                )
            }
        }

        // 通知から渡された銘柄の詳細画面へ飛ぶ（F2）。
        // NavHostより後に置くこと：NavHostが画面定義を登録する前に navigate すると落ちる。
        // 銘柄が一覧に無い（初回同期前・削除済み）ときは飛ばさずトップのままにする
        // （空の詳細画面を出しても何も分からないため）。
        LaunchedEffect(openTicker) {
            val t = openTicker ?: return@LaunchedEffect
            onOpenTickerHandled()
            if (etfStates.any { it.ticker == t }) {
                try {
                    navController.navigate("detail/$t") { launchSingleTop = true }
                } catch (_: Exception) { }
            }
        }
    }
}
