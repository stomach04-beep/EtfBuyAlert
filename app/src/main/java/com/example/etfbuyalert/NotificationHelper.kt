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
    private const val BUNDLE_ID = 8001                 // まとめ通知の固定ID（次のまとめで置き換え）

    // まとめ通知用の1件ぶんのアラート（domainに依存しないよう単純な入れ物）
    data class AlertItem(val category: String, val title: String, val message: String)

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

    // 複数アラートをまとめて送信。
    // 1つの価格チェックで複数銘柄が同時にライン到達すると通知が何件も飛んでくるため、
    // 2件以上なら「まとめ通知1件」に集約する（1件だけなら従来どおり個別通知）。
    // 履歴タブでは銘柄別に見たいので、履歴には各件を個別に残す。
    fun sendAlerts(context: Context, items: List<AlertItem>) {
        if (items.isEmpty()) return

        // 履歴は1件ずつ追記（通知はまとめても履歴は銘柄別に残す）
        for (it in items) appendHistory(context, it.category, it.title, it.message)

        if (items.size == 1) {
            // 1件だけなら従来どおり個別通知（ticker+categoryごとの安定ID）
            val it = items[0]
            val notifyId = 10000 + abs((it.title + it.category).hashCode() % 80000)
            postNotification(context, CHANNEL_ALERT, it.title, it.message, notifyId,
                NotificationCompat.PRIORITY_HIGH)
            return
        }

        // 2件以上は1件のまとめ通知に集約
        // （深刻な順＝損切り→ニュース警告→深押し→押し目→過熱利確②→過熱利確①→順張り→他 で並べる。
        //  ニュース警告は「発火していても買ってはいけないかもしれない」印なので押し目より上に置く）
        val order = listOf("損切り", "ニュース警告", "深押し", "押し目", "過熱利確②", "過熱利確①", "順張り", "ステージ変化")
        val sorted = items.sortedBy { order.indexOf(it.category).let { i -> if (i < 0) order.size else i } }
        val title = "買い時アラート ${items.size}件"
        val body = sorted.joinToString("\n") { "・${it.title}" }
        postNotification(context, CHANNEL_ALERT, title, body, BUNDLE_ID,
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
        appendHistory(context, category, title, message)
        postNotification(context, channel, title, message, notifyId, priority)
    }

    // 沈黙監視の警告を送る。
    // 別ID・記録なしで出す：警告自身が「最後に鳴った日」を更新すると
    // 沈黙時計がリセットされ、経過日数の表示が狂うため。
    fun sendSilenceWarning(context: Context, title: String, message: String) {
        appendHistory(context, "沈黙監視", title, message)
        postNotification(context, CHANNEL_ALERT, title, message,
            SilenceWatch.WARN_NOTIFICATION_ID, NotificationCompat.PRIORITY_DEFAULT,
            record = false)
    }

    // 通知の発行だけを行う（履歴追記はしない）。まとめ通知は履歴を別途1件ずつ残すため分離。
    //
    // record: 「最後に鳴った日」（沈黙監視の起点）を更新するか。
    // このアプリは毎朝サマリ（設定ON時）もここを通るので、記録は実質「生存信号」になる。
    // 　→ 朝サマリが出続ける限り警告なし＝アプリ健全
    // 　→ サマリごと止まったら14日後に AlarmHealthWorker（別経路のWorkManager）が警告
    // 買い時ライン到達は14日以上無いのが普通なので、アラートだけを記録対象にすると誤報になる。
    private fun postNotification(
        context: Context, channel: String, title: String, message: String,
        notifyId: Int, priority: Int, record: Boolean = true
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notifyId, intent, PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_buy_ladder)
            .setColor(android.graphics.Color.parseColor("#2979FF"))
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(priority)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java).notify(notifyId, notification)
        // 「最後に鳴った日」を記録する（沈黙監視の起点。上のコメント参照）
        if (record) SilenceWatch.recordNotified(context)
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
