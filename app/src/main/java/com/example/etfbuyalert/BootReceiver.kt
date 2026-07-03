package com.example.etfbuyalert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.etfbuyalert.alarm.AlarmScheduler
import com.example.etfbuyalert.alarm.CatchUpHelper

// 端末再起動時・アプリ更新時にアラームを再登録するレシーバー
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // LOCKED_BOOT_COMPLETED は Direct Boot対応が必要なため受けていない
        // （CE ストレージ未解錠状態では scheduleAll が正しく動かないため）
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AlarmScheduler.scheduleAll(context)
                // 再起動で取りこぼしたスロットを即座にリカバリする
                CatchUpHelper.runCatchUp(context)
            }
        }
    }
}
