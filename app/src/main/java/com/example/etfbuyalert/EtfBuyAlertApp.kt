package com.example.etfbuyalert

import android.app.Application
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.etfbuyalert.alarm.AlarmScheduler
import com.example.etfbuyalert.alarm.CatchUpHelper
import com.example.etfbuyalert.worker.AlarmHealthWorker
import java.util.concurrent.TimeUnit

// アプリケーションクラス — 通知チャンネル作成と正確な時刻指定アラーム設定
class EtfBuyAlertApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannels(this)
        // AlarmManager.setAlarmClock で正確な時刻に更新を実行
        // scheduleAllはSecurityException等を内部で捕捉するが念のため外側もガード
        try {
            AlarmScheduler.scheduleAll(this)
        } catch (e: Exception) {
            Log.e("EtfBuyAlertApp", "scheduleAll失敗: ${e.message}")
        }

        // 端末OFF・機内モード等で指定時刻のアラームが不発だった場合のリカバリ
        // 「今日の予定時刻を過ぎているのに成功ログがない」スロットを即時再実行。
        // JSONのload（ファイル読み＋Gsonパース）を含むため別スレッドで実行する
        //（旧実装はメインスレッドで毎プロセス起動時に実行しており起動を遅くしていた）
        Thread {
            try {
                CatchUpHelper.runCatchUp(this)
            } catch (e: Exception) {
                Log.e("EtfBuyAlertApp", "catchUp失敗: ${e.message}")
            }
        }.start()

        // BOOT_COMPLETEDが届かない端末向けのバックアップとして
        // 6時間毎にアラーム再登録＋取りこぼしリカバリを実行するPeriodicWorkを登録
        // （従来24時間→6時間に短縮。当日中に最低でも2〜3回リカバリの機会がある）
        val healthCheck = PeriodicWorkRequestBuilder<AlarmHealthWorker>(
            6, TimeUnit.HOURS
        ).build()
        // UPDATE: 既存の24h周期を6hに差し替えるため（KEEPだと周期変更が反映されない）
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "bargain_alarm_health_check",
            ExistingPeriodicWorkPolicy.UPDATE,
            healthCheck
        )
    }
}
