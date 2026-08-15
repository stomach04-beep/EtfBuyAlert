package com.example.etfbuyalert.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
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
    private const val RC_PRICE = 2002          // 価格チェックの自己連鎖アラーム（v1.21で追加）
    const val PRICE_WORK_NAME = "etf_price_check"
    const val MORNING_WORK_NAME = "update_MORNING_SUMMARY"

    // PeriodicWork（保険）の周期。本命はアラーム自己連鎖なので、こちらは
    // 「アラームが端末に消された場合の受け皿」として長めに1本だけ持つ。
    // 設定のチェック間隔と同じ周期で回すと、正常時に同じ同期が2経路で二重に走り
    // 通信と電池を無駄に食う（通知はロックで重複しないが処理は無駄）。
    private const val BACKSTOP_PERIOD_MIN = 360L   // 6時間（AlarmHealthWorkerと同じ間隔）

    data class Schedule(val requestCode: Int, val hour: Int, val minute: Int, val updateType: UpdateType)

    // 現在の設定時刻で朝サマリのスケジュールを作る
    fun morningSchedule(context: Context): Schedule =
        Schedule(RC_MORNING, Settings.morningHour(context), Settings.morningMin(context), UpdateType.MORNING_SUMMARY)

    // 朝サマリ・価格チェックのアラーム＋保険のPeriodicWorkをまとめて登録
    fun scheduleAll(context: Context) {
        scheduleNext(context, morningSchedule(context))
        schedulePriceAlarm(context)
        schedulePriceCheck(context)
    }

    /**
     * 価格チェックの次回アラームを1回だけ仕掛ける（発火のたびに次を仕掛ける自己連鎖）。
     *
     * 【なぜPeriodicWorkをやめたか】2026-08-16に実機ログで判明した遅延の実害：
     * WorkManagerのジョブはDoze（画面消灯＋据え置き）中はメンテナンス窓まで待たされ、
     * さらに「ネットワーク接続」制約はDoze中のアプリでは満たされないと判定されるため、
     * 実測で最大13時間24分（08-12 09:04→22:28）チェックが飛んでいた。
     * 東京市場の立会時間が丸ごと抜けた日もある（08-14 10:08→18:22）。
     * 朝サマリのアラーム自体は07:00に正確に鳴っていたのに、そこから投入した
     * OneTimeWorkが13:38まで動かなかった＝遅れているのはアラームでなく「仕事」の側。
     *
     * MacroAlert・BatteryAlert 等で実績のある setExactAndAllowWhileIdle の自己連鎖に統一する。
     * この種のアラームはDozeでも鳴り、鳴った直後は一時的にネットワークが使える。
     */
    fun schedulePriceAlarm(context: Context) {
        val intervalMin = Settings.intervalMin(context).coerceAtLeast(15)
        setAlarm(
            context,
            requestCode = RC_PRICE,
            updateType = UpdateType.PRICE_CHECK,
            triggerAt = nextGridTime(intervalMin),
            asAlarmClock = false,   // 時計アイコンを出さない（朝サマリだけAlarmClockでよい）
        )
    }

    /**
     * 「間隔の格子」に揃えた次回時刻を返す（60分なら毎時0分、30分なら0分と30分）。
     * 0時起点で intervalMin ごとの区切りを作り、その次の区切りを返す。
     * 端末が寝ていて1回飛んでも、次からは毎時0分に戻る（時刻が少しずつ後ろへずれない）。
     */
    private fun nextGridTime(intervalMin: Int): Long {
        val now = Calendar.getInstance()
        val midnight = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val minutesSinceMidnight = ((now.timeInMillis - midnight.timeInMillis) / 60000L).toInt()
        val nextSlot = (minutesSinceMidnight / intervalMin + 1) * intervalMin
        return midnight.timeInMillis + nextSlot * 60_000L
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
    // v1.21以降このPeriodicWorkは「本命」ではなく保険（6時間周期）。
    // 本命は schedulePriceAlarm のアラーム自己連鎖で、そちらが端末に消されたときだけ
    // ここが拾う。ネットワーク制約は付けない（Doze中は制約が満たされないと判定され、
    // 待たされる原因そのものだったため。通信できなければ前回値維持で続行する設計）。
    fun schedulePriceCheck(context: Context) {
        val interval = BACKSTOP_PERIOD_MIN
        val alreadyScheduled = Settings.scheduledIntervalMin(context).toLong()
        val changed = alreadyScheduled != interval   // 0（未登録）なら必ず登録される
        val req = PeriodicWorkRequestBuilder<DataUpdateWorker>(interval, TimeUnit.MINUTES)
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
        setAlarm(
            context,
            requestCode = schedule.requestCode,
            updateType = schedule.updateType,
            triggerAt = nextTriggerTime(schedule.hour, schedule.minute),
            asAlarmClock = true,   // 朝サマリは時刻厳守なのでAlarmClock（Doze耐性が最も強い）
        )
    }

    /**
     * アラームを1本仕掛ける共通処理（朝サマリ・価格チェックの両方が使う）。
     *
     * @param asAlarmClock true=setAlarmClock（最優先だがステータスバーに時計アイコンが出る）
     *                     false=setExactAndAllowWhileIdle（Dozeでも鳴る・アイコンは出ない）
     * 権限取消(SecurityException)・端末独自制限で落ちないよう必ず捕捉し、
     * 最後は setAndAllowWhileIdle（不正確だがDozeでも鳴る）へ落とす。
     */
    private fun setAlarm(
        context: Context,
        requestCode: Int,
        updateType: UpdateType,
        triggerAt: Long,
        asAlarmClock: Boolean,
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.example.etfbuyalert.ALARM_UPDATE"
            putExtra(AlarmReceiver.EXTRA_UPDATE_TYPE, updateType.name)
            putExtra(AlarmReceiver.EXTRA_REQUEST_CODE, requestCode)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    alarmManager.canScheduleExactAlarms()
            if (!canExact) {
                Log.w("AlarmScheduler", "SCHEDULE_EXACT_ALARM権限なし→不正確アラームで代替")
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                return
            }
            if (asAlarmClock) {
                val showIntent = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAt, showIntent), pendingIntent
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
                )
            }
        } catch (e: SecurityException) {
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } catch (ignored: Exception) {
                Log.e("AlarmScheduler", "フォールバックも失敗: ${ignored.message}")
            }
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "アラーム設置失敗: ${e.message}")
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
