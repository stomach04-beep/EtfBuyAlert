package com.example.etfbuyalert.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.etfbuyalert.MainActivity
import com.example.etfbuyalert.data.repository.Settings
import com.example.etfbuyalert.data.repository.UpdateType
import com.example.etfbuyalert.worker.DataUpdateWorker
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 2系統のスケジュールを管理する。
 *  - 毎朝サマリ: AlarmManager.setAlarmClock で正確な時刻に1日1回（Doze耐性が要るため）
 *  - 定期の価格チェック: WorkManagerのPeriodicWorkで「○分ごと」（厳密な時刻は不要）
 */
object AlarmScheduler {

    private const val RC_MORNING = 2001
    const val PRICE_WORK_NAME = "etf_price_check"
    const val MORNING_WORK_NAME = "update_MORNING_SUMMARY"

    data class Schedule(val requestCode: Int, val hour: Int, val minute: Int, val updateType: UpdateType)

    // 現在の設定時刻で朝サマリのスケジュールを作る
    fun morningSchedule(context: Context): Schedule =
        Schedule(RC_MORNING, Settings.morningHour(context), Settings.morningMin(context), UpdateType.MORNING_SUMMARY)

    // 朝サマリ＋定期価格チェックの両方を登録
    fun scheduleAll(context: Context) {
        scheduleNext(context, morningSchedule(context))
        schedulePriceCheck(context)
    }

    // 定期の価格チェック（PeriodicWork）。間隔は設定値（WorkManager下限15分）。
    //
    // 【重要】登録は「間隔が変わったときだけ UPDATE」にする。
    // 以前は毎回 UPDATE で登録し直していたが、UPDATEは実行中のジョブを
    // いったん中断（onStopJob）して再スケジュールするため、次の事故が起きた：
    //   定期ジョブ発火 → アプリのプロセスが起動 → Application.onCreate と
    //   CatchUpHelper が schedulePriceCheck を呼ぶ → 自分を起こしたジョブが中断・再実行
    //   → update() は中断に応じない（通常メソッドなので処理は最後まで走り切る）ため
    //     同じ同期が二重三重に走り、同じ通知が数秒差で何度も飛ぶ
    // 実機ログでは workSpec の generation が 2818 まで進み、2026-08-15 20:56〜20:57 に
    // つるはしの「出遅れ候補が点灯」通知が3回発行されていた。
    // 間隔が同じなら KEEP＝既存の登録に一切触らない（実行中のジョブも中断しない）。
    fun schedulePriceCheck(context: Context) {
        val interval = Settings.intervalMin(context).toLong().coerceAtLeast(15L)
        val alreadyScheduled = Settings.scheduledIntervalMin(context).toLong()
        val changed = alreadyScheduled != interval   // 0（未登録）なら必ず登録される
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val req = PeriodicWorkRequestBuilder<DataUpdateWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(workDataOf(DataUpdateWorker.KEY_UPDATE_TYPE to UpdateType.PRICE_CHECK.name))
            .build()
        // 間隔変更時だけUPDATE（旧間隔のまま残るのを防ぐ）。
        // 変わっていないときはKEEP＝登録済みなら何もしない・消えていれば作り直す。
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PRICE_WORK_NAME,
            if (changed) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            req
        )
        if (changed) Settings.setScheduledIntervalMin(context, interval.toInt())
    }

    // 朝サマリの次回アラームを登録（過ぎていたら翌日）
    fun scheduleNext(context: Context, schedule: Schedule) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = nextTriggerTime(schedule.hour, schedule.minute)

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.example.etfbuyalert.ALARM_UPDATE"
            putExtra(AlarmReceiver.EXTRA_UPDATE_TYPE, schedule.updateType.name)
            putExtra(AlarmReceiver.EXTRA_REQUEST_CODE, schedule.requestCode)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, schedule.requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val showIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmInfo = AlarmManager.AlarmClockInfo(triggerAt, showIntent)
        // 権限取消(SecurityException)・端末独自制限で起動ループに陥らないよう必ず捕捉
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                Log.w("AlarmScheduler", "SCHEDULE_EXACT_ALARM権限なし→不正確アラームで代替")
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                return
            }
            alarmManager.setAlarmClock(alarmInfo, pendingIntent)
        } catch (e: SecurityException) {
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } catch (ignored: Exception) {
                Log.e("AlarmScheduler", "フォールバックも失敗: ${ignored.message}")
            }
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "setAlarmClock失敗: ${e.message}")
        }
    }

    // リクエストコードからScheduleを逆引き（現在の設定時刻で再構築）
    fun findSchedule(context: Context, requestCode: Int): Schedule? =
        if (requestCode == RC_MORNING) morningSchedule(context) else null

    private fun nextTriggerTime(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(now)) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis
    }

    fun cancelAll(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.example.etfbuyalert.ALARM_UPDATE"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, RC_MORNING, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}
