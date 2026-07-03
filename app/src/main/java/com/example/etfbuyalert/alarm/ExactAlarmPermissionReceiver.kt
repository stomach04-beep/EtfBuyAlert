package com.example.etfbuyalert.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 正確なアラーム権限の状態が変化した時に呼ばれるレシーバー（Android 12+）。
 * ユーザーがOS設定で「アラームとリマインダー」権限を切り替えた直後に
 * アラームを再スケジュールすることで、不正確フォールバックから精密な
 * setAlarmClock 経路へ即座に復帰させる。
 */
class ExactAlarmPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            AlarmScheduler.scheduleAll(context)
        }
    }
}
