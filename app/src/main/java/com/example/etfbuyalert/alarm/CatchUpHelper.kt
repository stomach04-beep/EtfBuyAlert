package com.example.etfbuyalert.alarm

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.etfbuyalert.data.repository.JsonStorage
import com.example.etfbuyalert.data.repository.UpdateType
import com.example.etfbuyalert.worker.DataUpdateWorker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 取りこぼし自動リカバリ。
 * 端末OFF・機内モード等で「毎朝サマリ」アラームが不発だった場合、
 * 当日分が永久に欠落する（setAlarmClockは次回1回のみ予約のため）。
 * この helper は「今日の朝サマリ予定時刻を過ぎているのに成功ログがない」ときに
 * MORNING_SUMMARY を即時投入する。あわせて定期価格チェックの登録も保証する。
 *
 * 呼び出し箇所: アプリ起動時 / フォアグラウンド復帰 / 端末再起動後 / 定期ヘルスワーカー
 */
object CatchUpHelper {

    private const val TAG = "CatchUpHelper"

    fun runCatchUp(context: Context) {
        // 定期価格チェックが消えていないよう毎回登録（UPDATEなので重複しない）
        try { AlarmScheduler.schedulePriceCheck(context) } catch (_: Exception) {}

        val now = Calendar.getInstance()
        val morning = AlarmScheduler.morningSchedule(context)

        // 今日の朝サマリ予定時刻
        val triggerTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, morning.hour)
            set(Calendar.MINUTE, morning.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // まだ予定時刻が来ていなければ何もしない
        if (now.before(triggerTime)) return

        // 今日 MORNING_SUMMARY が成功済みかを更新ログで確認
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now.time)
        val done = try {
            JsonStorage(context).load().updateLogs.any {
                it.date == today && it.success && it.updateType == UpdateType.MORNING_SUMMARY.name
            }
        } catch (e: Exception) {
            Log.e(TAG, "ログ読込失敗: ${e.message}"); return
        }
        if (done) return

        // 取りこぼし → 即時実行
        Log.w(TAG, "朝サマリ取りこぼし検出 → 即時実行")
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val req = OneTimeWorkRequestBuilder<DataUpdateWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(DataUpdateWorker.KEY_UPDATE_TYPE to UpdateType.MORNING_SUMMARY.name))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            AlarmScheduler.MORNING_WORK_NAME, ExistingWorkPolicy.KEEP, req
        )
    }
}
