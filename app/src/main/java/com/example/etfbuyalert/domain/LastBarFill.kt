package com.example.etfbuyalert.domain

import com.example.etfbuyalert.data.model.ChartPoint

/**
 * Yahoo chart API の「末尾の足が close=null」を meta の値段で埋める純関数（v1.26・LESSON-114）。
 *
 * Yahoo は日付が変わった直後や週末に、最終営業日（週足なら最新週）の行を close=null で返すことがある。
 * meta の regularMarketPrice / regularMarketTime だけは正しい。空行を捨てるだけだと最新の足が
 * 1本古いまま残り、MA200ライン・売り時判定（高値からの下落率）・チャートが黙って1本ずれる
 * （2026-10-15 実測: ^N225 の 10-14 の日足が close=null、meta は 10-14 15:45 の終値を持っていた）。
 *
 * 判定は MacroAlert の HistoryFetcher.fillLastPointFromMeta と同じ「足の時間幅」方式:
 * 配列末尾のタイムスタンプ〜その足の時間幅の間に regularMarketTime が入っているときだけ埋める。
 * 日足なら「同じ日の値段を meta が持っている」、週足なら「進行中の週の値段を meta が持っている」
 * の意味になり、1つの式で日足・週足の両方を扱える（週足の最新足は timestamp＝regularMarketTime で
 * 返ってくることを 2026-10-15 に実測）。
 * JSON にも Log にも触らない（単体テスト用）。
 */
object LastBarFill {

    /** 日足1本の時間幅（秒） */
    const val DAILY_SEC = 86_400L

    /** 週足1本の時間幅（秒） */
    const val WEEKLY_SEC = 7L * 86_400L

    /**
     * @param points 欠損行を捨てたあとの点列（古い順・t は UNIX秒）
     * @param lastTs 配列末尾のタイムスタンプ（UNIX秒。null 行も含めた本当の末尾）
     * @param metaTime regularMarketTime（UNIX秒。無ければ 0）
     * @param metaPrice regularMarketPrice（無ければ null）
     * @param intervalSec 1本の足の時間幅（秒）
     */
    fun fill(
        points: List<ChartPoint>,
        lastTs: Long,
        metaTime: Long,
        metaPrice: Double?,
        intervalSec: Long,
    ): List<ChartPoint> {
        if (metaPrice == null || metaPrice.isNaN() || metaPrice <= 0.0) return points
        if (lastTs <= 0L || metaTime <= 0L) return points
        // meta の時刻が末尾の足の外（前の足・次の足）なら、その足の値段は分からないので触らない
        if (metaTime < lastTs || metaTime - lastTs > intervalSec) return points
        // 末尾の足がちゃんと入っている（か、それより新しい点がある＝順序が崩れる）なら何もしない
        val lastValid = points.lastOrNull()?.t
        if (lastValid != null && lastValid >= lastTs) return points
        return points + ChartPoint(lastTs, metaPrice)
    }
}
