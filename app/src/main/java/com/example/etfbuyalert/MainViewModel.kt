package com.example.etfbuyalert

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.etfbuyalert.alarm.AlarmScheduler
import com.example.etfbuyalert.data.model.ChartSeries
import com.example.etfbuyalert.data.model.EtfState
import com.example.etfbuyalert.data.model.NotificationLog
import com.example.etfbuyalert.data.repository.ChartRepository
import com.example.etfbuyalert.data.repository.EtfRepository
import com.example.etfbuyalert.data.repository.Settings
import com.example.etfbuyalert.data.repository.UpdateType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

// 画面に渡す状態をまとめて保持するViewModel。
// バックグラウンドWorkerが書いたJSONを reloadFromDisk() で読み直して反映する。
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = EtfRepository(app)
    private val chartRepo = ChartRepository(app)

    // --- 監視中ETF一覧 ---
    private val _etfStates = MutableStateFlow<List<EtfState>>(emptyList())
    val etfStates: StateFlow<List<EtfState>> = _etfStates.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // --- 監視タブの絞り込み（発火中 / 日本株 / 米国株 / ETF）---
    private val _watchTab = MutableStateFlow(Settings.watchTab(app))
    val watchTab: StateFlow<String> = _watchTab.asStateFlow()
    fun setWatchTab(tab: String) {
        Settings.prefs(getApplication()).edit().putString(Settings.KEY_WATCH_TAB, tab).apply()
        _watchTab.value = tab
    }

    // --- 発火中タブの中の「ライン方式」絞り込み（すべて / ADP型 / 手動 / 200日線 / RTX）---
    // 同じ発火でも方式で意味が違うため、混ぜたまま見ないで済むようにする。
    private val _firedMethod = MutableStateFlow(Settings.firedMethod(app))
    val firedMethod: StateFlow<String> = _firedMethod.asStateFlow()
    fun setFiredMethod(key: String) {
        Settings.prefs(getApplication()).edit().putString(Settings.KEY_FIRED_METHOD, key).apply()
        _firedMethod.value = key
    }

    // --- ブックマーク（★）の操作結果メッセージ（失敗時だけ出す）---
    // 印そのものは EtfState.bookmarked が持つ（Notionが単一の真実の源）ので、
    // ここでは「Notionへ書けなかった」ことだけを画面へ伝える。
    private val _bookmarkError = MutableStateFlow<String?>(null)
    val bookmarkError: StateFlow<String?> = _bookmarkError.asStateFlow()
    fun clearBookmarkError() { _bookmarkError.value = null }

    /**
     * カードの★から呼ぶ。Notionへ書き戻し、成功したら一覧を読み直して反映する。
     * 失敗時は印を変えずメッセージを出す（黙って食い違わせない）。
     */
    fun toggleBookmark(ticker: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = repo.toggleBookmark(ticker)
            if (result == null) {
                _bookmarkError.value = "ブックマークをNotionに保存できませんでした（通信・トークンを確認してください）"
            } else {
                val data = repo.load()
                _etfStates.value = data.etfStates.sortedBy { it.ticker }
            }
        }
    }

    // --- 同期状態（バナー表示用）---
    private val _lastSyncOk = MutableStateFlow(false)
    val lastSyncOk: StateFlow<Boolean> = _lastSyncOk.asStateFlow()
    private val _lastSyncError = MutableStateFlow<String?>(null)
    val lastSyncError: StateFlow<String?> = _lastSyncError.asStateFlow()
    private val _lastSyncAt = MutableStateFlow(0L)
    val lastSyncAt: StateFlow<Long> = _lastSyncAt.asStateFlow()

    // --- 通知履歴 ---
    private val _notifications = MutableStateFlow<List<NotificationLog>>(emptyList())
    val notifications: StateFlow<List<NotificationLog>> = _notifications.asStateFlow()

    // --- チャート（詳細画面）---
    private val _chart = MutableStateFlow<ChartSeries?>(null)
    val chart: StateFlow<ChartSeries?> = _chart.asStateFlow()
    private val _chartLoading = MutableStateFlow(false)
    val chartLoading: StateFlow<Boolean> = _chartLoading.asStateFlow()

    // --- 設定値（画面表示用。永続値はSettings＝SharedPreferences）---
    private val _notionToken = MutableStateFlow(Settings.notionToken(app))
    val notionToken: StateFlow<String> = _notionToken.asStateFlow()
    private val _notionDb = MutableStateFlow(Settings.notionDbId(app))
    val notionDb: StateFlow<String> = _notionDb.asStateFlow()
    private val _intervalMin = MutableStateFlow(Settings.intervalMin(app))
    val intervalMin: StateFlow<Int> = _intervalMin.asStateFlow()
    private val _morningHour = MutableStateFlow(Settings.morningHour(app))
    val morningHour: StateFlow<Int> = _morningHour.asStateFlow()
    private val _morningMin = MutableStateFlow(Settings.morningMin(app))
    val morningMin: StateFlow<Int> = _morningMin.asStateFlow()
    private val _notifyDip = MutableStateFlow(Settings.notifyDip(app))
    val notifyDip: StateFlow<Boolean> = _notifyDip.asStateFlow()
    private val _notifyStop = MutableStateFlow(Settings.notifyStop(app))
    val notifyStop: StateFlow<Boolean> = _notifyStop.asStateFlow()
    private val _notifyBreakout = MutableStateFlow(Settings.notifyBreakout(app))
    val notifyBreakout: StateFlow<Boolean> = _notifyBreakout.asStateFlow()
    private val _notifyMorning = MutableStateFlow(Settings.notifyMorning(app))
    val notifyMorning: StateFlow<Boolean> = _notifyMorning.asStateFlow()
    private val _notifyZoneChange = MutableStateFlow(Settings.notifyZoneChange(app))
    val notifyZoneChange: StateFlow<Boolean> = _notifyZoneChange.asStateFlow()

    init {
        reloadFromDisk()
        reloadNotificationHistory()
    }

    // JSONからディスク上の最新状態を読み直す
    fun reloadFromDisk() {
        viewModelScope.launch(Dispatchers.IO) {
            val data = repo.load()
            _etfStates.value = data.etfStates.sortedBy { it.ticker }
            _lastSyncOk.value = data.lastSyncOk
            _lastSyncError.value = data.lastSyncError
            _lastSyncAt.value = data.lastSyncAt
        }
    }

    // 手動更新（同期＋価格チェック）。完了後に画面へ反映。
    fun refreshData() {
        if (_isLoading.value) return
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // waitIfBusy=true：裏で定期チェックが走っている最中に押されても、
                // それが終わるのを待ってから自分の分を実行する（無反応に見せない）
                repo.update(UpdateType.PRICE_CHECK, waitIfBusy = true)
            } finally {
                val data = repo.load()
                _etfStates.value = data.etfStates.sortedBy { it.ticker }
                _lastSyncOk.value = data.lastSyncOk
                _lastSyncError.value = data.lastSyncError
                _lastSyncAt.value = data.lastSyncAt
                reloadNotificationHistory()
                _isLoading.value = false
            }
        }
    }

    // チャートを読み込む（先にキャッシュを即表示→ネット取得で更新）
    fun loadChart(ticker: String, range: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _chartLoading.value = true
            val cached = chartRepo.getCached(ticker, range)
            if (cached != null) _chart.value = cached
            val series = chartRepo.get(ticker, range)
            if (series != null) _chart.value = series
            _chartLoading.value = false
        }
    }

    // 詳細画面を離れるときにチャートをクリア（前銘柄の残像を防ぐ）
    fun clearChart() { _chart.value = null }

    fun reloadNotificationHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            _notifications.value = NotificationHelper.loadHistory(getApplication())
        }
    }

    fun clearNotificationHistory() {
        NotificationHelper.clearHistory(getApplication())
        _notifications.value = emptyList()
    }

    // 監視データを全消去（通知履歴も含む）
    fun clearAllData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                File(getApplication<Application>().filesDir, "etf_data.json").delete()
            } catch (_: Exception) {}
            NotificationHelper.clearHistory(getApplication())
            _etfStates.value = emptyList()
            _notifications.value = emptyList()
            _lastSyncOk.value = false
            _lastSyncError.value = null
            _lastSyncAt.value = 0L
        }
    }

    // ===== 設定の変更 =====
    fun setNotionToken(v: String) {
        Settings.prefs(getApplication()).edit().putString(Settings.KEY_NOTION_TOKEN, v.trim()).apply()
        _notionToken.value = v.trim()
    }

    fun setNotionDb(v: String) {
        Settings.prefs(getApplication()).edit().putString(Settings.KEY_NOTION_DB, v.trim()).apply()
        _notionDb.value = v.trim()
    }

    fun setIntervalMin(v: Int) {
        Settings.prefs(getApplication()).edit().putInt(Settings.KEY_INTERVAL, v).apply()
        _intervalMin.value = v
        AlarmScheduler.schedulePriceCheck(getApplication())  // 間隔変更を即反映
    }

    fun setMorningTime(hour: Int, minute: Int) {
        Settings.prefs(getApplication()).edit()
            .putInt(Settings.KEY_MORNING_HOUR, hour)
            .putInt(Settings.KEY_MORNING_MIN, minute).apply()
        _morningHour.value = hour
        _morningMin.value = minute
        AlarmScheduler.scheduleNext(getApplication(), AlarmScheduler.morningSchedule(getApplication()))
    }

    fun setNotifyDip(v: Boolean) = boolPref(Settings.KEY_NOTIFY_DIP, v) { _notifyDip.value = v }
    fun setNotifyStop(v: Boolean) = boolPref(Settings.KEY_NOTIFY_STOP, v) { _notifyStop.value = v }
    fun setNotifyBreakout(v: Boolean) = boolPref(Settings.KEY_NOTIFY_BREAKOUT, v) { _notifyBreakout.value = v }
    fun setNotifyMorning(v: Boolean) = boolPref(Settings.KEY_NOTIFY_MORNING, v) { _notifyMorning.value = v }
    fun setNotifyZoneChange(v: Boolean) = boolPref(Settings.KEY_NOTIFY_ZONE, v) { _notifyZoneChange.value = v }

    private inline fun boolPref(key: String, v: Boolean, after: () -> Unit) {
        Settings.prefs(getApplication()).edit().putBoolean(key, v).apply()
        after()
    }

    fun sendTestNotification() = NotificationHelper.sendTestNotification(getApplication())
}
