package com.example.etfbuyalert.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.etfbuyalert.NotificationHelper
import com.example.etfbuyalert.SilenceWatch
import com.example.etfbuyalert.alarm.AlarmScheduler
import com.example.etfbuyalert.alarm.CatchUpHelper

/**
 * アラーム健全性チェックワーカー。
 * 24時間毎に起動され、AlarmManagerのアラームを再登録する。
 * 端末再起動後にBOOT_COMPLETEDが届かないケース（Doze/OEMキル/Direct Boot未対応）
 * でもアラームが途切れないようにするバックアップ経路。
 */
class AlarmHealthWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            AlarmScheduler.scheduleAll(applicationContext)
            // 取りこぼし自動リカバリ：予定時刻を過ぎたが未成功のスロットを即時再実行
            CatchUpHelper.runCatchUp(applicationContext)
            // 沈黙監視：通知（毎朝サマリ含む＝生存信号）が14日途絶えたら知らせる。
            // このWorkerはAlarmManagerと別経路（WorkManager）なので、
            // アラーム側が死んでサマリが止まった場合でも警告を出せる（SilenceWatch.kt 参照）
            SilenceWatch.buildWarningIfSilent(applicationContext)?.let { msg ->
                NotificationHelper.sendSilenceWarning(
                    applicationContext, "しばらく通知が出ていません", msg
                )
            }
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            // 健全性チェックは失敗しても次回(6時間後)に再試行される
            Result.success()
        }
    }
}
