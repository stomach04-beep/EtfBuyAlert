package com.example.etfbuyalert.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.etfbuyalert.MainViewModel
import com.example.etfbuyalert.ui.screen.detail.EtfDetailScreen
import com.example.etfbuyalert.ui.screen.history.HistoryScreen
import com.example.etfbuyalert.ui.screen.settings.SettingsScreen
import com.example.etfbuyalert.ui.screen.watchlist.WatchListScreen

// ボトムナビの項目
sealed class BottomNavItem(val route: String, val label: String, val icon: ImageVector) {
    data object Watch : BottomNavItem("watch", "監視", Icons.Default.ShowChart)
    data object History : BottomNavItem("history", "履歴", Icons.Default.History)
    data object Settings : BottomNavItem("settings", "設定", Icons.Default.Settings)
}

@Composable
fun AppNavigation(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val items = listOf(BottomNavItem.Watch, BottomNavItem.History, BottomNavItem.Settings)
    val startRoute = BottomNavItem.Watch.route

    // 監視タブ用の状態
    val etfStates by viewModel.etfStates.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val lastSyncOk by viewModel.lastSyncOk.collectAsStateWithLifecycle()
    val lastSyncError by viewModel.lastSyncError.collectAsStateWithLifecycle()
    val lastSyncAt by viewModel.lastSyncAt.collectAsStateWithLifecycle()
    val groupMode by viewModel.groupMode.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val chart by viewModel.chart.collectAsStateWithLifecycle()
    val chartLoading by viewModel.chartLoading.collectAsStateWithLifecycle()
    // 詳細画面の選択期間（既定6ヶ月）
    var detailRange by remember { mutableStateOf("6mo") }

    // 設定タブ用の状態
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

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                items.forEach { item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        selected = currentRoute == item.route ||
                            (currentRoute?.startsWith("detail") == true && item.route == BottomNavItem.Watch.route),
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        // システム戻る：監視タブ以外にいるときは監視タブへ戻す（アプリを閉じない）
        val backEntry by navController.currentBackStackEntryAsState()
        val current = backEntry?.destination?.route
        BackHandler(enabled = current != null && current != startRoute) {
            navController.navigate(startRoute) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }

        NavHost(navController, startDestination = startRoute, modifier = Modifier.padding(innerPadding)) {
            composable(BottomNavItem.Watch.route) {
                WatchListScreen(
                    etfStates = etfStates,
                    isLoading = isLoading,
                    lastSyncOk = lastSyncOk,
                    lastSyncError = lastSyncError,
                    lastSyncAt = lastSyncAt,
                    groupMode = groupMode,
                    onGroupModeChange = { viewModel.setGroupMode(it) },
                    onRefresh = { viewModel.refreshData() },
                    onEtfClick = { ticker -> navController.navigate("detail/$ticker") }
                )
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
            composable(BottomNavItem.History.route) {
                HistoryScreen(
                    notifications = notifications,
                    onReload = { viewModel.reloadNotificationHistory() },
                    onClear = { viewModel.clearNotificationHistory() }
                )
            }
            composable(BottomNavItem.Settings.route) {
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
                    onClearData = { viewModel.clearAllData() }
                )
            }
        }
    }
}
