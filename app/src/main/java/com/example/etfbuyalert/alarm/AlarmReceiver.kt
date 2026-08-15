package com.example.etfbuyalert.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import com.example.etfbuyalert.data.repository.UpdateType
import com.example.etfbuyalert.worker.DataUpdateWorker

/**
 * AlarmManager から発火されるレシーバー。
 * OneTimeWorkRequest で DataUpdateWorker を即時実行し、
 * 次回のアラームを再スケジュールする。
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_UPDATE_TYPE = "update_type"
        const val EXTRA_REQUEST_CODE = "request_code"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val updateTypeStr = intent.getStringExtra(EXTRA_UPDATE_TYPE) ?: return
        val requestCode = intent.getIntExtra(EXTRA_REQUEST_CODE, -1)

        // OneTimeWork でデータ取得を実行。
        //
        // 【ネットワーク制約を付けない】2026-08-16に実機で判明：Doze中のアプリでは
        // 「ネットワーク接続」制約が満たされないと判定され、アラームが正確に鳴っても
        // 仕事が数時間待たされる（08-15は07:00のアラームに対し実行が13:38＝6時間半遅れ）。
        // このアラームは setExactAndAllowWhileIdle / setAlarmClock なので鳴った直後は
        // 一時的に通信できる。通信できない回は前回値を維持して続行する設計なので、
        // 「制約で待たせる」より「走って必要なら失敗を記録する」ほうが実害が小さい。
        val inputData = workDataOf(DataUpdateWorker.KEY_UPDATE_TYPE to updateTypeStr)

        val workRequest = OneTimeWorkRequestBuilder<DataUpdateWorker>()
            .setInputData(inputData)
            .build()

        // 更新タイプごとに固定名で重複防止（BatteryAlertで発見済みのバグ修正）
        // タイムスタンプを名前に入れるとenqueueUniqueWorkの重複防止が無効化される
        // CatchUpHelper側と同名("update_<TYPE>")に統一し、定刻アラームと取りこぼし
        // リカバリの並走を KEEP で確実に止める
        WorkManager.getInstance(context).enqueueUniqueWork(
            "update_$updateTypeStr",
            ExistingWorkPolicy.KEEP,
            workRequest
        )

        // 次回のアラームを再スケジュール（自己連鎖。これを忘れると1回で止まる）
        //  - 朝サマリ: 翌日の同時刻
        //  - 価格チェック: 設定間隔の次の区切り（毎時0分など）
        if (updateTypeStr == UpdateType.PRICE_CHECK.name) {
            AlarmScheduler.schedulePriceAlarm(context)
        } else {
            val schedule = AlarmScheduler.findSchedule(context, requestCode)
            if (schedule != null) {
                AlarmScheduler.scheduleNext(context, schedule)
            }
        }
    }
}
