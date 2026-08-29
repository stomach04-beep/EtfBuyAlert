package com.example.etfbuyalert.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 「いま価格を取りに行くべきか」を決める純関数（テスト対象）。
 *
 * 【なぜ必要か】約190銘柄を毎時取得しているが、市場が閉じている間の取得は
 * 前回と同じ終値を取り直しているだけで、通信も電池も丸ごと無駄になる。
 * 日本株は夜間・米国株は日中がまるまる無駄うちだった。
 *
 * 【窓（すべて JST 固定）】
 *   日本株 … 平日 08:55〜15:35 を立会中として毎時取得。16時台に1回クロージング取得。
 *   米国株 … 平日夜 22:00〜翌07:00 を立会中として毎時取得。7時台に1回クロージング取得。
 *
 * 【DSTは考慮しない（意図的）】米国の夏時間で実際の取引時間は1時間ずれるが、
 * 22:00〜07:00 という広めの窓がそのズレを丸ごと飲み込むため、切替日の判定ミスを
 * 抱えるより固定窓のほうが安全。日本株には夏時間が無い。
 *
 * 【安全側の設計】「その立会日のクロージング取得がまだ済んでいない」場合は、
 * 閉場中でも1回だけ取得する。端末が寝ていて16時台・7時台のアラームを逃しても
 * その日の終値を必ず1回は拾える（取りこぼしのほうが、無駄うちよりずっと痛い）。
 */
object MarketHours {

    val JST: ZoneId = ZoneId.of("Asia/Tokyo")

    // --- 日本株の窓（分単位。0:00からの経過分）---
    private const val JP_OPEN_MIN = 8 * 60 + 55    // 08:55（寄り付き前の気配を含める）
    private const val JP_CLOSE_MIN = 15 * 60 + 35  // 15:35（大引け後の確定を含める）
    private const val JP_CLOSING_HOUR = 16         // クロージング取得の時間帯（16時台）

    // --- 米国株の窓（JST。日をまたぐ）---
    private const val US_EVENING_START_HOUR = 22   // 22:00〜23:59（前場側）
    private const val US_MORNING_END_HOUR = 7      // 00:00〜06:59（後場側）
    private const val US_CLOSING_HOUR = 7          // クロージング取得の時間帯（7時台）

    /**
     * 判定結果。
     * @param fetch          いま価格を取りに行くべきか
     * @param isClosingRun   その立会日のクロージング取得としてカウントする回か
     * @param sessionKey     対象の立会日（"yyyy-MM-dd"）。クロージング取得済みの記録キー
     * @param reason         ログ・デバッグ用の理由
     */
    data class Decision(
        val fetch: Boolean,
        val isClosingRun: Boolean,
        val sessionKey: String,
        val reason: String,
    )

    /**
     * @param isJp             日本株か（Symbol.isJp の結果を渡す）
     * @param at               判定時刻（JST）
     * @param lastClosingDate  この市場で最後にクロージング取得した立会日（"yyyy-MM-dd"／未実施なら null）
     */
    fun decide(isJp: Boolean, at: ZonedDateTime, lastClosingDate: String?): Decision {
        val session = sessionKey(isJp, at)
        if (inRegularWindow(isJp, at)) {
            return Decision(true, isClosingRun = false, sessionKey = session, reason = "立会時間中")
        }
        val closingDone = lastClosingDate == session
        if (!closingDone) {
            val inClosing = inClosingWindow(isJp, at)
            return Decision(
                fetch = true,
                isClosingRun = true,
                sessionKey = session,
                reason = if (inClosing) "クロージング取得" else "クロージング取得の取りこぼし補填",
            )
        }
        return Decision(false, isClosingRun = false, sessionKey = session, reason = "閉場中（当日分は取得済み）")
    }

    /** 立会時間中か（毎時取得の対象） */
    fun inRegularWindow(isJp: Boolean, at: ZonedDateTime): Boolean {
        val dow = at.dayOfWeek
        return if (isJp) {
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false
            val minutes = at.hour * 60 + at.minute
            minutes in JP_OPEN_MIN..JP_CLOSE_MIN
        } else {
            when {
                // 22:00〜23:59 は月〜金の夜（＝米国の月〜金の寄り）
                at.hour >= US_EVENING_START_HOUR -> dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY
                // 00:00〜06:59 は火〜土の未明（＝米国の月〜金の引け前）
                at.hour < US_MORNING_END_HOUR -> dow != DayOfWeek.SUNDAY && dow != DayOfWeek.MONDAY
                else -> false
            }
        }
    }

    /** クロージング取得の時間帯か（日本株=16時台／米国株=7時台） */
    fun inClosingWindow(isJp: Boolean, at: ZonedDateTime): Boolean =
        if (isJp) at.hour == JP_CLOSING_HOUR else at.hour == US_CLOSING_HOUR

    /**
     * 対象の立会日キー（"yyyy-MM-dd"）。
     * 日本株はその日付そのもの（土日・場前は直前の平日）。
     * 米国株は「JSTで引けを迎える日付」でキーにする
     * （米国の月曜の取引は JST 火曜 07:00 に引けるので、キーは火曜の日付）。
     * ※祝日は考慮しない。祝日は立会が無く値も動かないので、
     *   1日1回の空取得が起きるだけで判定は壊れない。
     */
    fun sessionKey(isJp: Boolean, at: ZonedDateTime): String =
        if (isJp) jpSessionDate(at).toString() else usSessionDate(at).toString()

    private fun jpSessionDate(at: ZonedDateTime): LocalDate {
        var d = at.toLocalDate()
        val beforeOpen = at.hour * 60 + at.minute < JP_OPEN_MIN
        // 場が始まる前は「前の立会日」がまだ最新（その日の終値はまだ無い）
        if (beforeOpen) d = d.minusDays(1)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.minusDays(1)
        return d
    }

    private fun usSessionDate(at: ZonedDateTime): LocalDate {
        // 22時以降は「翌朝に引ける立会」が進行中なので翌日をキーにする
        var d = if (at.hour >= US_EVENING_START_HOUR) at.toLocalDate().plusDays(1) else at.toLocalDate()
        // 米国の立会が引けるのは JST の火〜土。日・月にはならないので遡る
        while (d.dayOfWeek == DayOfWeek.SUNDAY || d.dayOfWeek == DayOfWeek.MONDAY) d = d.minusDays(1)
        return d
    }
}
