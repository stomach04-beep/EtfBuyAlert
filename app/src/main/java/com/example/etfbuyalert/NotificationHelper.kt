package com.example.etfbuyalert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.etfbuyalert.data.model.NotificationLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

// 通知ヘルパー — ETFの買い時/損切り/順張りアラートと毎朝サマリを送信。
object NotificationHelper {

    private const val CHANNEL_ALERT = "etf_alert"     // 押し目・深押し・損切り・順張り（重要）
    private const val CHANNEL_SUMMARY = "etf_summary"  // 毎朝サマリ（通常）

    private const val HISTORY_FILE = "notification_history.json"
    private const val MAX_HISTORY = 100
    private val historyLock = Any()

    // 通知チャンネルを作成
    fun createNotificationChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationChannel(
            CHANNEL_ALERT, "買い時・損切りアラート", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "押し目・深押し・順張り・損切りラインに到達したとき通知"
            enableVibration(true)
        }.also { manager.createNotificationChannel(it) }

        NotificationChannel(
            CHANNEL_SUMMARY, "毎朝サマリ", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "米国市場が閉じたあと、監視ETFの終値と各ラインまでの距離を通知"
        }.also { manager.createNotificationChannel(it) }
    }

    // 買い時/損切り/順張りアラートを1件送信
    fun sendAlert(context: Context, category: String, title: String, message: String) {
        // ticker+category で安定した通知ID（銘柄ごと・種別ごとに別通知）
        val notifyId = 10000 + abs((title + category).hashCode() % 80000)
        notifyNow(context, CHANNEL_ALERT, title, message, notifyId, category,
            NotificationCompat.PRIORITY_HIGH)
    }

    // 毎朝サマリを送信
    fun sendMorningSummary(context: Context, title: String, message: String) {
        notifyNow(context, CHANNEL_SUMMARY, title, message, 7001, "朝サマリ",
            NotificationCompat.PRIORITY_DEFAULT)
    }

    // テスト通知（設定画面から）
    fun sendTestNotification(context: Context) {
        notifyNow(context, CHANNEL_ALERT, "テスト通知",
            "ETF買い時アラートの通知は正常に動作しています", 9999, "テスト",
            NotificationCompat.PRIORITY_HIGH)
    }

    // 通知発行の共通処理（履歴追記 + notify）
    private fun notifyNow(
        context: Context, channel: String, title: String, message: String,
        notifyId: Int, category: String, priority: Int
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notifyId, intent, PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(priority)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        appendHistory(context, category, title, message)
        context.getSystemService(NotificationManager::class.java).notify(notifyId, notification)
    }

    // 通知を履歴ファイルへ追記（先頭=最新、上限100件）
    private fun appendHistory(context: Context, category: String, title: String, message: String) {
        synchronized(historyLock) {
            try {
                val file = File(context.filesDir, HISTORY_FILE)
                val gson = Gson()
                val type = object : TypeToken<MutableList<NotificationLog>>() {}.type
                val list: MutableList<NotificationLog> =
                    if (file.exists()) gson.fromJson(file.readText(), type) ?: mutableListOf()
                    else mutableListOf()
                val now = Date()
                list.add(0, NotificationLog(
                    date = SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN).format(now),
                    time = SimpleDateFormat("HH:mm", Locale.JAPAN).format(now),
                    category = category, title = title, message = message
                ))
                while (list.size > MAX_HISTORY) list.removeAt(list.size - 1)
                file.writeText(gson.toJson(list))
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // 履歴を読み込む（履歴タブ用）
    fun loadHistory(context: Context): List<NotificationLog> {
        synchronized(historyLock) {
            return try {
                val file = File(context.filesDir, HISTORY_FILE)
                if (!file.exists()) emptyList()
                else {
                    val type = object : TypeToken<MutableList<NotificationLog>>() {}.type
                    Gson().fromJson(file.readText(), type) ?: emptyList()
                }
            } catch (e: Exception) { emptyList() }
        }
    }

    // 履歴をクリア
    fun clearHistory(context: Context) {
        synchronized(historyLock) {
            try { File(context.filesDir, HISTORY_FILE).delete() } catch (_: Exception) {}
        }
    }
}
