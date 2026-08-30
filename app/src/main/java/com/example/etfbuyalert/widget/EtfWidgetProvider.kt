package com.example.etfbuyalert.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.example.etfbuyalert.MainActivity
import com.example.etfbuyalert.R
import com.example.etfbuyalert.data.model.EtfState
import com.example.etfbuyalert.data.repository.JsonStorage
import com.example.etfbuyalert.domain.AlertEngine
import com.example.etfbuyalert.domain.Symbol
import com.example.etfbuyalert.domain.WatchRanking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ホーム画面ウィジェット（2x2）。
 *   1行目 … いま発火中（押し目・深押しに到達）の件数
 *   2〜4行目 … 押し目までの距離が近い上位3銘柄（ティッカーと距離%）
 *   最終行 … 最終更新 HH:mm
 *
 * 【並び順はアプリ本体と同じものを使う】発火判定も並べ替えも domain.WatchRanking /
 * AlertEngine に集約してあり、ここでは呼ぶだけ。UI側と別の並べ替えを書くと
 * 「ウィジェットの上位3件とアプリの上位3件が違う」という一番たちの悪いズレになる。
 *
 * 【更新契機】毎時同期の末尾（EtfRepository）と、ウィジェット追加時（onUpdate）。
 * 保険として etf_widget_info.xml の updatePeriodMillis（30分）も置いてある。
 *
 * 【RemoteViewsの制約】使えるクラスは限られる。<View>・カスタムView・RecyclerViewは不可で、
 * 置いてもクラッシュログが出ないまま空白になる。区切り線は TextView + background で作る。
 */
class EtfWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val views = buildViews(context)
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    companion object {

        private const val TAG = "EtfWidget"

        /** ウィジェットに出す銘柄数（2x2に収まる行数） */
        private const val TOP_N = 3

        /**
         * 現在の保存データでウィジェットを描き直す（毎時同期の末尾から呼ぶ）。
         * 置かれていなければ何もしない。ウィジェットの失敗で同期を落とさないよう例外は握る。
         */
        fun refresh(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(
                    ComponentName(context, EtfWidgetProvider::class.java)
                )
                if (ids.isEmpty()) return
                manager.updateAppWidget(ids, buildViews(context))
            } catch (e: Exception) {
                Log.w(TAG, "ウィジェット更新に失敗: ${e.message}")
            }
        }

        /** 保存済みのJSONから表示内容を組み立てる（通信はしない） */
        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_etf)
            val data = try {
                JsonStorage(context).load()
            } catch (e: Exception) {
                Log.w(TAG, "データ読み込みに失敗: ${e.message}")
                null
            }
            val states: List<EtfState> = data?.etfStates ?: emptyList()

            if (states.isEmpty()) {
                views.setTextViewText(R.id.widget_fired, "未同期")
                views.setTextViewText(R.id.widget_row1, "アプリを開いて同期してください")
                views.setTextViewText(R.id.widget_row2, "")
                views.setTextViewText(R.id.widget_row3, "")
                views.setTextViewText(R.id.widget_updated, "")
                views.setOnClickPendingIntent(R.id.widget_fired, openAppIntent(context))
                return views
            }

            val now = System.currentTimeMillis()
            val fired = WatchRanking.firedCount(states)
            // 2列幅（最小サイズ）だと「発火中 23件 / 190銘柄」は末尾が省略されて
            // 肝心の母数が消える。数字だけの短い形にして、狭い幅でも意味が落ちないようにする。
            views.setTextViewText(R.id.widget_fired, "発火中 ${fired} / ${states.size}")

            // 押し目が近い上位3件（距離を出せない銘柄は最初から除外される＝WatchRanking）
            val top = WatchRanking.topByDipGap(states, TOP_N, now)
            val rowIds = listOf(R.id.widget_row1, R.id.widget_row2, R.id.widget_row3)
            for (i in rowIds.indices) {
                val text = top.getOrNull(i)?.let { line(it, now) } ?: ""
                views.setTextViewText(rowIds[i], text)
            }
            if (top.isEmpty()) {
                views.setTextViewText(R.id.widget_row1, "価格を取得できていません")
            }

            // 最終更新＝価格を最後に取れた時刻（同期時刻より実態に近い）
            val lastPrice = states.maxOfOrNull { it.asOf } ?: 0L
            val updatedAt = maxOf(lastPrice, data?.lastSyncAt ?: 0L)
            views.setTextViewText(
                R.id.widget_updated,
                if (updatedAt > 0) "最終更新 ${fmt(updatedAt)}" else "最終更新 —"
            )

            // どこを押してもアプリが開くようにする（2x2は狭いので押し分けはしない）
            views.setOnClickPendingIntent(R.id.widget_fired, openAppIntent(context))
            for (id in rowIds) views.setOnClickPendingIntent(id, openAppIntent(context))
            views.setOnClickPendingIntent(R.id.widget_updated, openAppIntent(context))
            return views
        }

        /** 1銘柄ぶんの行：「SMH  到達2.3%」「1925  あと1.2%」 */
        private fun line(st: EtfState, now: Long): String {
            val gap = AlertEngine.dipGapPercent(st, now) ?: return Symbol.display(st.ticker)
            val label = if (gap <= 0) String.format("到達%.1f%%", -gap)
                        else String.format("あと%.1f%%", gap)
            return "${Symbol.display(st.ticker)}  $label"
        }

        private fun openAppIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            return PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun fmt(epoch: Long): String =
            SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(epoch))
    }
}
