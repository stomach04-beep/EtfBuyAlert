package com.example.etfbuyalert

import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * 沈黙監視 — 「このアプリ、しばらく何も通知していないぞ」を自分から知らせる。
 *
 * === なぜ必要か（実際に起きた事故）===
 * 2026年7月、雨アラートが 7/15〜7/28 の2週間まったく通知を出さなくなっていた。
 * 原因は判定条件の副作用で、アプリ自体は毎日正常に動き、ログもすべて成功。
 * 「来ないな」とユーザーが気づくまで13日かかった。
 * 通知アプリの最悪の壊れ方は「エラーを出して止まる」ではなく「黙る」こと。
 * 黙ったことは、黙っているアプリ自身にしか分からない。
 *
 * === 使い方（1アプリあたり2か所だけ）===
 *  1) 本物の通知を出すところ（NotificationHelper の各 send... の中）で
 *         SilenceWatch.recordNotified(context)
 *  2) 定期実行の Worker の最後で
 *         SilenceWatch.buildWarningIfSilent(context)?.let { msg ->
 *             NotificationHelper.send...(context, "しばらく通知していません", msg)
 *         }
 *
 * === この仕組みの限界（正直に）===
 * 警告を出すのは定期Worker自身なので、**Workerごと死んでいる場合は警告も出ない**。
 * 拾えるのは「動いてはいるが判定が何も引っかからない」型の沈黙（＝雨アラートで実際に起きた型）。
 * Workerの生死そのものを見張りたい場合は、PC側やサーバー側からの外部監視が別途必要。
 *
 * === 単一の真実の源 ===
 * このファイルの原本は `AndroidStudioProjects\_shared\SilenceWatch.kt.template`。
 * 直すときは原本を直して `distribute_shared.ps1` で配り直すこと（各アプリで直接編集しない）。
 */
object SilenceWatch {

    /** 何日ぶん黙ったら知らせるか。アプリごとに変えるのはこの1行だけ */
    const val SILENT_DAYS = 14

    /** 他の通知IDとぶつからないよう大きめの番号を使う */
    const val WARN_NOTIFICATION_ID = 99001

    private const val PREFS = "silence_watch"
    private const val KEY_LAST_NOTIFIED = "last_notified_at"
    private const val KEY_LAST_WARNED = "last_warned_at"
    private const val KEY_FIRST_SEEN = "first_seen_at"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun silentMillis() = TimeUnit.DAYS.toMillis(SILENT_DAYS.toLong())

    /** 本物の通知を出したときに呼ぶ。ここが最後に鳴った時刻の記録になる */
    fun recordNotified(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_NOTIFIED, System.currentTimeMillis())
            .apply()
    }

    /**
     * 黙りすぎていれば警告文を返す（黙っていなければ null）。
     *
     * 返したときは「知らせた」印も同時に付けるので、次に出るのは再び SILENT_DAYS 日後。
     * 入れたばかりのアプリがいきなり鳴らないよう、初回起動時刻を起点に数える。
     */
    fun buildWarningIfSilent(context: Context): String? {
        val p = prefs(context)
        val now = System.currentTimeMillis()

        // 初回は起点を置くだけ（インストール直後に「黙っています」と言わない）
        val firstSeen = p.getLong(KEY_FIRST_SEEN, 0L)
        if (firstSeen == 0L) {
            p.edit().putLong(KEY_FIRST_SEEN, now).apply()
            return null
        }

        val lastNotified = p.getLong(KEY_LAST_NOTIFIED, 0L)
        val since = maxOf(lastNotified, firstSeen)
        if (now - since < silentMillis()) return null

        // 警告そのものが毎日鳴ると無視される習慣がつくので、こちらも同じ間隔を空ける
        val lastWarned = p.getLong(KEY_LAST_WARNED, 0L)
        if (now - lastWarned < silentMillis()) return null

        p.edit().putLong(KEY_LAST_WARNED, now).apply()

        val days = (now - since) / TimeUnit.DAYS.toMillis(1)
        return if (lastNotified == 0L) {
            "このアプリは導入から${days}日間、一度も通知を出していません。" +
                "条件が厳しすぎないか、データが取れているかを確認してください。"
        } else {
            "このアプリは${days}日間、通知を出していません。" +
                "本当に何も起きていないのか、判定が壊れて黙っているのかを確認してください。"
        }
    }

    /** 設定画面などから状態を見せたいとき用（最後に通知した時刻・ミリ秒。0なら一度も無し） */
    fun lastNotifiedAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_NOTIFIED, 0L)
}
