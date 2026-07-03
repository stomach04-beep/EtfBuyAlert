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

        // OneTimeWork でデータ取得を実行（ネットワーク必須）
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = workDataOf(DataUpdateWorker.KEY_UPDATE_TYPE to updateTypeStr)

        val workRequest = OneTimeWorkRequestBuilder<DataUpdateWorker>()
            .setConstraints(constraints)
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

        // 次回のアラームを再スケジュール（翌日の同時刻）
        val schedule = AlarmScheduler.findSchedule(context, requestCode)
        if (schedule != null) {
            AlarmScheduler.scheduleNext(context, schedule)
        }
    }
}
