package com.example.etfbuyalert.domain

import com.example.etfbuyalert.data.model.ChartPoint

/**
 * 200日移動平均ベースの価格ラインを端末内で計算する。
 *
 * 【なぜアプリ側に持つのか】
 * 以前は PC の月次ジョブ etf-watchlist-ma-updater がこの計算をして Notion に
 * 書き込み、アプリはその固定数値を読むだけだった。つまり PC を起動しない月は
 * ラインが古いまま放置される（PCが計算役・Notionが受け渡し場所）。
 * アプリは同じ日足データを既に取得できるので、ここで直接計算すれば
 * PC も Notion への書き込みも不要になる。
 *
 * 【計算式（PC版 update_etf_watchlist_ma.py と同一。変更時は両方を同時に直すこと）】
 *   買い時(押し目)   = 200日線
 *   買い増し(深押し) = 200日線 × 0.95
 *   損切り(退避)     = 200日線 × 0.85   ← 課税口座向けに粗いトレンド退避
 *   順張り(上抜け)   = max(直近20日高値, 現値 × 1.02)
 *                      ただし現値が200日線未満の間は200日線を下限にする
 *
 * 順張りの下限ガードが無いと、順張りラインが押し目ライン(=200日線)より下に出る
 * 逆転が起き、トレンド退避圏にいるのに「順張り突破」通知が出てしまう。
 */
object Ma200Lines {

    /** この計算方式を担当する「ライン計算方式」列の値。これ以外の行は触らない。 */
    const val METHOD = "MA200"

    /** 計算に最低限必要な終値の本数（PC版と同じ）。これ未満はデータ不足として扱う。 */
    private const val MIN_BARS = 60

    private const val MA_WINDOW = 200
    private const val BREAKOUT_LOOKBACK = 20

    data class Lines(
        val dip: Double,        // 買い時(押し目)
        val deepDip: Double,    // 買い増し(深押し)
        val breakout: Double,   // 順張り(上抜け)
        val stopLoss: Double,   // 損切り(退避)
        val maWindowUsed: Int,  // 実際に使った移動平均の日数（200未満なら代用したという意味）
    ) {
        /** 200日ぶん揃わず短い移動平均で代用したか（UIで注記するため） */
        val isSubstituteWindow: Boolean get() = maWindowUsed < MA_WINDOW
    }

    /**
     * 日足の終値系列からラインを計算する。
     * データ不足・欠損だらけの場合は null を返す（呼び出し側は既存の値を保持すること。
     * ここで 0 やダミー値を返すと、誤ったラインで通知が飛ぶ）。
     */
    fun compute(points: List<ChartPoint>): Lines? {
        // 0以下やNaNは Yahoo 応答の欠損行に混ざることがあるので必ず除外する
        val closes = points.map { it.close }.filter { it > 0.0 && !it.isNaN() }
        if (closes.size < MIN_BARS) return null

        val cur = closes.last()
        val window = minOf(MA_WINDOW, closes.size)
        val ma = closes.takeLast(window).average()
        if (ma <= 0.0) return null

        val hi20 = closes.takeLast(BREAKOUT_LOOKBACK).max()
        var breakout = maxOf(hi20, cur * 1.02)
        // 現値が200日線より下にいる間は「200日線の回復」が復帰シグナルなので下限にする
        if (cur < ma) breakout = maxOf(breakout, ma)

        return Lines(
            dip = round2(ma),
            deepDip = round2(ma * 0.95),
            breakout = round2(breakout),
            stopLoss = round2(ma * 0.85),
            maWindowUsed = window,
        )
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
