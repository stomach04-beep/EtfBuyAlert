package com.example.etfbuyalert.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.etfbuyalert.data.repository.EtfRepository
import com.example.etfbuyalert.data.repository.UpdateType

// バックグラウンドのデータ更新ワーカー。
// PRICE_CHECK（定期の価格チェック＋ライン到達アラート）と
// MORNING_SUMMARY（毎朝の値サマリ）の両方をこのワーカーで処理する。
class DataUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_UPDATE_TYPE = "update_type"
    }

    override suspend fun doWork(): Result {
        // 無限リトライでバッテリーを浪費しないよう上限を設ける
        if (runAttemptCount >= 3) return Result.failure()

        val typeStr = inputData.getString(KEY_UPDATE_TYPE) ?: UpdateType.PRICE_CHECK.name
        val updateType = try {
            UpdateType.valueOf(typeStr)
        } catch (e: Exception) {
            UpdateType.PRICE_CHECK
        }

        return try {
            // waitIfBusy は付けない＝別の同期が走っている最中なら何もせず戻る。
            // WorkManagerはジョブの再スケジュール時に走行中のWorkerを中断→再実行するが、
            // update() の中身は中断に応じず最後まで走るため、待って実行すると
            // 同じ同期が二重に走り同じ通知が連発する（v1.20で修正）。
            EtfRepository(applicationContext).update(updateType)
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
