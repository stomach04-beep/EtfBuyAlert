package com.example.etfbuyalert.domain

import com.example.etfbuyalert.data.model.ChartPoint
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// ============================================================
// 売り時点灯ルール（つるはし銘柄の売り時検証 2026-08-13 の実装）。
// 計算ロジックはこのファイルが単一の真実の源（DRY）。
//
// 【検証の根拠】J-Quants 全銘柄1,837事象（2017〜2024年・廃止銘柄込み）で、
// 「大きく上がった日本株」を持ち続けると中央値0.80倍・64.8%が元本割れ。
// 機械的な売りルールを掛けると 1.05〜1.07倍・勝率70%前後まで戻り、
// 2017〜2024年の8年すべてでB&Hに勝った。採用した2本:
//   ① トレール-15% … アーム後の最高値から-15%で点灯（中央値1.06倍・勝率70.4%）
//   ② 50日線割れ   … 終値が50日移動平均を下回ったら点灯（1.05倍・勝率71.5%）
// 決算悪化・下方修正を待つルールは全滅（株価が1年以上先行）なので入れない。
//
// 【アーム（監視開始）条件】検証と同じ「ブーム状態」の定義:
//   12ヶ月リターン +100%以上 かつ 現在値が52週高値の95%以上
// これを満たした銘柄だけが売り監視の対象になる。普通の値動きの銘柄に
// トレールを掛けるのは検証範囲外（別のルール）なのでやらない。
//
// 【未確定バーの除外】検証は日足終値ベース。ザラ場の一時的な上下で
// 判定すると検証していない別ルールになるため、当日のバーは
// 東証の大引け（15:00）+10分を過ぎるまで計算から落とす（週足RSIと同じ流儀）。
// 対象は日本株のみなので市場タイムゾーンは Asia/Tokyo 固定でよい。
// ============================================================
object SellRules {

    // --- アーム条件（検証のイベント定義と同じ値）---
    const val ARM_RET12 = 1.0          // 12ヶ月リターン +100%以上
    const val ARM_NEAR_HIGH = 0.95     // 52週高値の95%以上
    private const val LOOKBACK = 252   // 12ヶ月＝52週（営業日）
    // アームの有効期間（営業日）。検証の計測窓が24ヶ月なので、それを超えて
    // 昔の急騰を根拠に監視し続けない（超えたら条件を満たし直すまで解除）
    private const val ARM_WINDOW = 504

    // --- 点灯条件（検証で上位だった2本）---
    const val TRAIL_PCT = 0.15         // ① アーム後高値から-15%
    const val MA_DAYS = 50             // ② 50日移動平均割れ
    // 再arm（解除）のヒステリシス。点灯ラインぴったりの往復で通知が連発しないよう、
    // トレールは-13%まで戻ったら、50日線は線+1%まで戻ったら解除（AlertEngineのREARMと同じ発想）
    const val TRAIL_REARM_PCT = 0.13
    const val MA_REARM = 0.01

    private val TOKYO: ZoneId = ZoneId.of("Asia/Tokyo")
    // 大引け後、Yahooの日足が確定値になるまでの余裕を見て15:10とする
    private val CLOSE_CONFIRMED: LocalTime = LocalTime.of(15, 10)

    // 判定結果（EtfStateへキャッシュされ、AlertEngineが通知判定に使う）
    data class Signal(
        val armed: Boolean,          // 売り監視中か（ブーム状態になったことがあるか）
        val armDate: String?,        // アーム日（条件を最初に満たした確定バーの日付）
        val peak: Double?,           // アーム後の最高終値（トレールの基準）
        val dropPct: Double?,        // 最高値からの下落率（％。-15以下で①点灯）
        val ma50: Double?,           // 50日移動平均（直近確定バー時点）
        val trailFired: Boolean,     // ① トレール-15% 点灯中
        val ma50Fired: Boolean,      // ② 50日線割れ 点灯中
        val lastDate: String,        // 判定に使った最終確定バーの日付
        val lastClose: Double        // 最終確定バーの終値
    )

    /**
     * 日足バー列（古い順・3年ぶん推奨）から売り時シグナルを判定する。
     * データ不足（確定バーが LOOKBACK+1 本未満）なら null。
     */
    fun calculate(points: List<ChartPoint>, now: Long = System.currentTimeMillis()): Signal? {
        if (points.isEmpty()) return null
        val sorted = points.sortedBy { it.t }

        // --- 未確定の当日バーを除外（東京時間で大引け前なら落とす）---
        val confirmed = dropUnconfirmed(sorted, now)
        if (confirmed.size < LOOKBACK + 1) return null

        val closes = confirmed.map { it.close }
        val n = closes.size

        // --- アーム判定：直近 ARM_WINDOW 営業日の中に「ブーム状態」の日があるか ---
        // その最初の日をアーム日とし、以降の最高終値をトレールの基準にする。
        val scanFrom = maxOf(LOOKBACK, n - ARM_WINDOW)
        var armIdx = -1
        for (i in scanFrom until n) {
            val ret12 = closes[i] / closes[i - LOOKBACK] - 1.0
            if (ret12 < ARM_RET12) continue
            // 52週高値（当日含む直近252本の最高値）
            var hi = 0.0
            for (j in i - LOOKBACK + 1..i) if (closes[j] > hi) hi = closes[j]
            if (closes[i] >= ARM_NEAR_HIGH * hi) { armIdx = i; break }
        }

        val lastClose = closes[n - 1]
        val lastDate = dateOf(confirmed[n - 1].t)
        val ma50 = if (n >= MA_DAYS) closes.subList(n - MA_DAYS, n).average() else null

        if (armIdx < 0) {
            return Signal(false, null, null, null, ma50, false, false, lastDate, lastClose)
        }

        // --- 点灯判定（アーム日以降の最高終値を基準に）---
        var peak = 0.0
        for (j in armIdx until n) if (closes[j] > peak) peak = closes[j]
        val dropPct = (lastClose / peak - 1.0) * 100.0
        val trailFired = lastClose <= peak * (1.0 - TRAIL_PCT)
        val ma50Fired = ma50 != null && lastClose < ma50

        return Signal(
            armed = true,
            armDate = dateOf(confirmed[armIdx].t),
            peak = peak,
            dropPct = dropPct,
            ma50 = ma50,
            trailFired = trailFired,
            ma50Fired = ma50Fired,
            lastDate = lastDate,
            lastClose = lastClose
        )
    }

    // 当日バーが未確定（東京時間で15:10前）なら最後のバーを落とす
    private fun dropUnconfirmed(sorted: List<ChartPoint>, now: Long): List<ChartPoint> {
        val nowJst = Instant.ofEpochMilli(now).atZone(TOKYO)
        val lastBarDate = Instant.ofEpochSecond(sorted.last().t).atZone(TOKYO).toLocalDate()
        val isToday = lastBarDate == nowJst.toLocalDate()
        return if (isToday && nowJst.toLocalTime().isBefore(CLOSE_CONFIRMED)) {
            sorted.dropLast(1)
        } else sorted
    }

    private fun dateOf(epochSec: Long): String =
        Instant.ofEpochSecond(epochSec).atZone(TOKYO).toLocalDate().toString()

    /** 日本株の個別株か（売り監視の対象範囲。検証エビデンスが日本株のみのため）。 */
    fun isEligible(ticker: String?, name: String?): Boolean {
        if (!Symbol.isJp(ticker)) return false
        // ETFは検証対象外（個別株のような崩れ方をしない）。判定はAssetKindに委ねる
        return AssetKind.of(ticker, name) != AssetKind.Kind.ETF
    }
}
