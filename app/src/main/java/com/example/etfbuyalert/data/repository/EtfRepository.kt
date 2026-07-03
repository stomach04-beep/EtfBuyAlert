package com.example.etfbuyalert.data.repository

import android.content.Context
import android.util.Log
import com.example.etfbuyalert.NotificationHelper
import com.example.etfbuyalert.data.model.AppData
import com.example.etfbuyalert.data.model.EtfState
import com.example.etfbuyalert.data.model.UpdateLog
import com.example.etfbuyalert.data.network.NotionClient
import com.example.etfbuyalert.data.network.YahooFinanceClient
import com.example.etfbuyalert.domain.AlertEngine
import com.example.etfbuyalert.domain.EtfCategory
import com.example.etfbuyalert.domain.Money
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ETF監視の中核。Notion同期 → Yahooで価格取得 → 買い時判定 → 通知 → 保存 を束ねる。
// Worker（バックグラウンド）とViewModel（手動更新）の両方から呼ばれる。
class EtfRepository(private val context: Context) {

    private val storage = JsonStorage(context)
    private val TAG = "EtfRepository"

    fun load(): AppData = storage.load()

    // 価格チェック1回ぶんを実行（type=MORNING_SUMMARYなら朝サマリも送る）。
    // 同期失敗時も前回値（キャッシュ）で価格判定を続ける。
    fun update(type: UpdateType): Boolean {
        val data = storage.load()

        // 1) Notion同期（best-effort：失敗してもキャッシュのstatesで続行）
        syncConfigs(data)

        // 2) 各銘柄の価格を取得して買い時判定
        val toggles = AlertEngine.Toggles(
            dip = Settings.notifyDip(context),
            stop = Settings.notifyStop(context),
            breakout = Settings.notifyBreakout(context),
            zoneChange = Settings.notifyZoneChange(context)
        )
        val now = System.currentTimeMillis()
        val updated = ArrayList<EtfState>(data.etfStates.size)
        for (st in data.etfStates) {
            val quote = YahooFinanceClient.fetchQuote(st.ticker)
            var s = if (quote != null) {
                st.copy(
                    price = quote.price,
                    previousClose = quote.previousClose ?: st.previousClose,
                    asOf = now,
                    isLive = quote.isLive
                )
            } else st  // 取得失敗は前回値を維持（サイレント失敗を作らない）

            val (newState, alerts) = AlertEngine.evaluate(s, toggles)
            s = newState
            for (a in alerts) {
                NotificationHelper.sendAlert(context, a.category, a.title, a.message)
            }
            updated.add(s)
        }
        data.etfStates.clear()
        data.etfStates.addAll(updated)

        // 3) 毎朝サマリ
        if (type == UpdateType.MORNING_SUMMARY && Settings.notifyMorning(context)) {
            sendMorningSummary(data)
        }

        // 4) ログ記録＋保存
        appendLog(data, type, success = true)
        storage.save(data)
        return true
    }

    // Notionの設定をstatesへマージ。成功時のみstatesを置き換え（price/armedは引き継ぐ）。
    private fun syncConfigs(data: AppData) {
        val token = Settings.notionToken(context)
        val db = Settings.notionDbId(context)
        val res = NotionClient.fetchWatchedEtfs(token, db)
        if (!res.ok) {
            data.lastSyncOk = false
            data.lastSyncError = res.error
            Log.w(TAG, "Notion同期失敗（キャッシュ継続）: ${res.error}")
            return
        }
        val merged = res.items.map { n ->
            // 既存状態を pageId（無ければticker）で引き継ぐ
            val prev = data.etfStates.find { it.pageId == n.pageId }
                ?: data.etfStates.find { it.ticker == n.ticker }
            EtfState(
                pageId = n.pageId,
                ticker = n.ticker,
                name = n.name,
                market = n.market,
                // カテゴリ：Notionの「カテゴリ」列を最優先、無ければアプリ内対応表で補完
                category = n.category?.takeIf { it.isNotBlank() } ?: EtfCategory.of(n.ticker),
                dipPrice = n.dipPrice,
                deepDipPrice = n.deepDipPrice,
                breakoutPrice = n.breakoutPrice,
                stopLossPrice = n.stopLossPrice,
                purchased = n.purchased,
                price = prev?.price,
                previousClose = prev?.previousClose,
                asOf = prev?.asOf ?: 0L,
                isLive = prev?.isLive ?: false,
                dipArmed = prev?.dipArmed ?: false,
                deepArmed = prev?.deepArmed ?: false,
                breakoutArmed = prev?.breakoutArmed ?: false,
                stopArmed = prev?.stopArmed ?: false,
                lastZone = prev?.lastZone ?: ""
            )
        }
        data.etfStates.clear()
        data.etfStates.addAll(merged)
        data.lastSyncOk = true
        data.lastSyncError = null
        data.lastSyncAt = System.currentTimeMillis()
    }

    // 毎朝サマリ通知を組み立てて送信
    private fun sendMorningSummary(data: AppData) {
        if (data.etfStates.isEmpty()) return
        val sb = StringBuilder()
        for (st in data.etfStates.sortedBy { it.ticker }) {
            val zone = AlertEngine.currentZone(st)
            val price = st.price
            sb.append("• ${st.ticker}  ${Money.format(st.ticker, price)}  ［${zone.label}］\n")
            val parts = mutableListOf<String>()
            if (st.dipPrice != null) parts.add("押し目${Money.format(st.ticker, st.dipPrice)}${gap(price, st.dipPrice)}")
            if (st.stopLossPrice != null) parts.add("損切り${Money.format(st.ticker, st.stopLossPrice)}")
            if (st.breakoutPrice != null) parts.add("順張り${Money.format(st.ticker, st.breakoutPrice)}")
            if (parts.isNotEmpty()) sb.append("   ").append(parts.joinToString(" / ")).append("\n")
        }
        val title = "☀ ETF朝サマリ（${data.etfStates.size}銘柄）"
        NotificationHelper.sendMorningSummary(context, title, sb.toString().trimEnd())
    }

    // 現在値からラインまでの乖離率（マイナス＝そこまで下げ余地）
    private fun gap(price: Double?, line: Double?): String {
        if (price == null || line == null || price == 0.0) return ""
        val pct = (line - price) / price * 100.0
        return String.format("(%+.1f%%)", pct)
    }

    // 更新ログを追記（直近50件だけ残す）
    private fun appendLog(data: AppData, type: UpdateType, success: Boolean, message: String = "") {
        val now = Date()
        data.updateLogs.add(0, UpdateLog(
            date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now),
            time = SimpleDateFormat("HH:mm", Locale.JAPAN).format(now),
            updateType = type.name,
            success = success,
            message = message
        ))
        while (data.updateLogs.size > 50) data.updateLogs.removeAt(data.updateLogs.size - 1)
    }
}
