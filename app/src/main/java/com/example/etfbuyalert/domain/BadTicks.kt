package com.example.etfbuyalert.domain

import com.example.etfbuyalert.data.model.ChartPoint
import kotlin.math.abs

/**
 * バッドティック（偽の値飛び）除去の純関数（単一の真実の源。テスト対象）。
 *
 * 【背景】Yahooの日本株データには日付を誤った分割レコード由来の異常値が実在する
 * （1306の2026-03-30/31の2日間だけ価格が1/10スケール＝偽の-90%と+948%を実測）。
 * 放置すると偽の高値・安値が売り時判定とMA200線（Notionへ書き戻す）を狂わせ、
 * PC側ジョブまで汚染が伝播する。
 *
 * 【v1.22 の旧実装の欠陥（v1.23 で修正）】
 * 旧実装は「跳んだ点を捨てても比較基準(last)を更新しない」ため、本物の水準シフト
 * （ストップ高・調整漏れの分割ギャップ・急騰落）が起きると、以降の全バーが
 * 旧水準との比較で跳び続け、履歴が黙ってそこで打ち切られていた。
 *
 * 【v1.23 の方式＝単発〜短期スパイクのみ除去】
 * 跳んだ点があったら、先の数バー（CONFIRM_BARS）を見て見分ける：
 *   ・先のバーが「元の水準」に戻る       → 跳んだ区間は偽スパイク → その区間だけ捨てる
 *   ・次のバーが「跳び先と同水準」で続く → 本物の水準シフト → 両方とも採用する
 *   ・どちらとも言えない／末尾で確認不能 → その1点だけ捨てる（安全側）
 * 確認窓を2バーでなく CONFIRM_BARS=3 にしているのは、実測した1306の偽値が
 * 「2バー連続」だったため（次の1バーだけ見る方式だと連続スパイクを水準シフトと誤認する）。
 *
 * 【閾値は足種別】旧実装はコメントで「週足は±25%程度」と言いながら日足にも
 * 同じ0.35を使っていた。日足の1本は週足より変動が小さいので狭め、週足は広めにする。
 * 本物の大変動（水準シフト）はこの方式なら閾値を超えても捨てられないため、
 * 閾値は「スパイク検査を始める感度」でしかない＝狭めでも実害が出にくい。
 */
object BadTicks {

    /** 日足の閾値（1日で±25%超は検査対象。本物のストップ高は水準シフトとして残る） */
    const val DAILY_THRESHOLD = 0.25

    /** 週足の閾値（1週で±45%超は検査対象。日足より広め） */
    const val WEEKLY_THRESHOLD = 0.45

    /** 跳びの正体を見分けるために先読みするバー数（1306の2バー連続偽値を拾える最小+1） */
    const val CONFIRM_BARS = 3

    /** 足種別の閾値を返す（呼び出し側で分岐を書かない＝DRY） */
    fun thresholdFor(isWeekly: Boolean): Double =
        if (isWeekly) WEEKLY_THRESHOLD else DAILY_THRESHOLD

    /** 除去結果（points=採用したバー、dropped=捨てたバー数。ログ用） */
    data class Result(val points: List<ChartPoint>, val dropped: Int)

    /**
     * 異常スパイクだけを除去して返す純関数。
     * 本物の水準シフト（ストップ高・分割ギャップ・急騰落）は残す。
     */
    fun clean(rows: List<ChartPoint>, threshold: Double): Result {
        if (rows.size <= 1) return Result(rows, 0)
        val out = ArrayList<ChartPoint>(rows.size)
        var dropped = 0
        var i = 0
        while (i < rows.size) {
            val r = rows[i]
            val last = out.lastOrNull()?.close
            // 最初のバー、または前回採用値から閾値内 → そのまま採用
            if (last == null || abs(r.close / last - 1.0) <= threshold) {
                out.add(r)
                i++
                continue
            }
            // 跳んだ。先のバーを見て「偽スパイク」か「本物の水準シフト」かを見分ける
            var returnIdx = -1   // 元の水準へ戻るバーの位置（見つかれば偽スパイク確定）
            val scanEnd = minOf(i + CONFIRM_BARS, rows.size - 1)
            for (j in (i + 1)..scanEnd) {
                if (abs(rows[j].close / last - 1.0) <= threshold) {
                    returnIdx = j
                    break
                }
            }
            when {
                // 窓内で元の水準へ戻った → 跳んだ区間（i..returnIdx-1）は偽スパイク。まとめて捨てる
                returnIdx >= 0 -> {
                    dropped += returnIdx - i
                    i = returnIdx   // 戻ったバーは次周の通常判定で採用される
                }
                // 次のバーが跳び先と同水準で続く → 本物の水準シフト。捨てずに採用する
                i + 1 < rows.size && abs(rows[i + 1].close / r.close - 1.0) <= threshold -> {
                    out.add(r)
                    i++
                }
                // 末尾で確認できない・どちらの水準でもない → その1点だけ捨てる（安全側）
                else -> {
                    dropped++
                    i++
                }
            }
        }
        return Result(out, dropped)
    }
}
