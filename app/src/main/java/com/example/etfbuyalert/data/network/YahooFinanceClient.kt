package com.example.etfbuyalert.data.network

import android.util.Log
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// Yahoo FinanceのチャートAPIからETFの現在値・前日終値を取得する。
// APIキー不要・無料。米国ETF（NASDAQ/NYSE）はティッカーをそのまま使える。
object YahooFinanceClient {

    private const val TAG = "YahooFinanceClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // 取得結果。priceは現在値（市場が閉じていれば直近終値）。
    data class Quote(
        val ticker: String,
        val price: Double,
        val previousClose: Double?,
        val isLive: Boolean   // marketState == REGULAR
    )

    // 1銘柄の価格を取得。失敗時はnull（呼び出し側が前回値を維持）。
    // 接続エラー・一時的な失敗に備え最大3回リトライ（HTTP取得の共通教訓）。
    fun fetchQuote(ticker: String): Quote? {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/" +
            ticker + "?interval=1d&range=5d"
        var lastError: String? = null
        repeat(3) { attempt ->
            try {
                val req = Request.Builder()
                    .url(url)
                    // User-Agentを付けないとYahoo側にブロックされやすい
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastError = "HTTP ${resp.code}"
                        return@use
                    }
                    val body = resp.body?.string() ?: run { lastError = "空レスポンス"; return@use }
                    return parse(ticker, body) ?: run { lastError = "パース失敗"; null }
                }
            } catch (e: Exception) {
                lastError = e.message
                Log.w(TAG, "$ticker 取得失敗(${attempt + 1}/3): ${e.message}")
            }
            // 軽いバックオフ
            try { Thread.sleep(800L * (attempt + 1)) } catch (_: InterruptedException) {}
        }
        Log.e(TAG, "$ticker 取得を諦め: $lastError")
        return null
    }

    // 期間に応じた足の細かさ。長期（5年・上場来）は点が増えすぎないよう週足にする。
    private fun intervalFor(range: String): String = when (range) {
        "5y", "max" -> "1wk"   // 週足
        "2y" -> "1d"
        else -> "1d"           // 1d / 3mo / 6mo / 1y は日足
    }

    // 日足/週足の履歴（チャート用）を取得。range例: "3mo" / "6mo" / "1y" / "5y" / "max"。失敗時null。
    fun fetchHistory(ticker: String, range: String): List<com.example.etfbuyalert.data.model.ChartPoint>? {
        val interval = intervalFor(range)
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/" +
            ticker + "?interval=" + interval + "&range=" + range
        repeat(3) { attempt ->
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    // 足種で異常値判定の閾値が変わる（日足は狭め・週足は広め＝BadTicks参照）
                    return parseHistory(body, isWeekly = interval == "1wk")
                }
            } catch (e: Exception) {
                Log.w(TAG, "$ticker 履歴取得失敗(${attempt + 1}/3): ${e.message}")
            }
            try { Thread.sleep(800L * (attempt + 1)) } catch (_: InterruptedException) {}
        }
        return null
    }

    // 週足の終値系列を取得（週足RSI計算用）。range=3y・interval=1wk 固定。
    // 最終バーは進行中の当週（未確定）を含むことがある＝確定週だけ使う処理は
    // 呼び出し側（WeeklyRsi）が行う。失敗時null（呼び出し側が前回値キャッシュを維持）。
    fun fetchWeeklyCloses(ticker: String): List<com.example.etfbuyalert.data.model.ChartPoint>? {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/" +
            ticker + "?interval=1wk&range=3y"
        repeat(3) { attempt ->
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    // この経路は interval=1wk 固定なので必ず週足の閾値を使う
                    return parseHistory(body, isWeekly = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "$ticker 週足取得失敗(${attempt + 1}/3): ${e.message}")
            }
            try { Thread.sleep(800L * (attempt + 1)) } catch (_: InterruptedException) {}
        }
        return null
    }

    /**
     * chart APIの履歴から (タイムスタンプ, 終値) を取り出す。
     * 【重要】分配金・配当の権利落ちは生の終値だと「下落」として52週高値との差や
     * トレール判定に混入する（Be Greedy側の実測では1306の年1回2%の分配落ちで
     * -15%ラインが約2%早く発火した）。そこで分配金調整済みの adjclose を優先し、
     * 無い場合だけ生の close にフォールバックする。
     * この履歴は売り時判定とMA200線（Notionへ書き戻す）の両方の土台なので、
     * ここが汚れるとPC側のジョブまで汚染が伝播する。
     *
     * @param isWeekly 週足なら true（異常値判定の閾値が足種で変わる＝BadTicks参照）
     */
    private fun parseHistory(
        json: String,
        isWeekly: Boolean,
    ): List<com.example.etfbuyalert.data.model.ChartPoint>? {
        try {
            val root = JsonParser.parseString(json).asJsonObject
            val result = root.getAsJsonObject("chart")?.getAsJsonArray("result") ?: return null
            if (result.size() == 0) return null
            val obj = result[0].asJsonObject
            val ts = obj.getAsJsonArray("timestamp") ?: return null
            val indicators = obj.getAsJsonObject("indicators") ?: return null
            // 分配金調整済みを優先。無ければ生の終値
            val closes = indicators.getAsJsonArray("adjclose")
                ?.takeIf { it.size() > 0 }?.get(0)?.asJsonObject
                ?.getAsJsonArray("adjclose")
                ?: indicators.getAsJsonArray("quote")?.get(0)?.asJsonObject
                    ?.getAsJsonArray("close")
                ?: return null
            val points = ArrayList<com.example.etfbuyalert.data.model.ChartPoint>(ts.size())
            val n = minOf(ts.size(), closes.size())
            for (i in 0 until n) {
                val c = closes[i]
                if (c.isJsonNull) continue  // 欠損日はスキップ
                val v = c.asDouble
                if (v.isNaN() || v <= 0.0) continue  // 0や負は値ではなく欠損
                points.add(com.example.etfbuyalert.data.model.ChartPoint(ts[i].asLong, v))
            }
            // 末尾の足が null で返ってきたときは meta の値段で補完する（LESSON-114・v1.26）。
            // 捨てるだけだと最新の足が1本古いまま MA200ライン・売り時判定に使われる。
            // 異常値除去（BadTicks）より前に足して、meta の値段も同じ異常値判定にかける。
            // 最新の足は調整済み終値と生の終値が一致するので、adjclose の列に足しても問題ない
            val meta = obj.getAsJsonObject("meta")
            val filled = if (meta != null && ts.size() > 0) {
                com.example.etfbuyalert.domain.LastBarFill.fill(
                    points = points,
                    lastTs = ts[ts.size() - 1].asLong,
                    metaTime = meta.get("regularMarketTime")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                    metaPrice = meta.get("regularMarketPrice")?.takeIf { !it.isJsonNull }?.asDouble,
                    intervalSec = if (isWeekly) com.example.etfbuyalert.domain.LastBarFill.WEEKLY_SEC
                    else com.example.etfbuyalert.domain.LastBarFill.DAILY_SEC,
                )
            } else points
            if (filled.size > points.size) Log.i(TAG, "末尾の空の足を meta の値段で補完した（${if (isWeekly) "週足" else "日足"}）")
            // 異常値の除去は純関数 BadTicks に集約（テスト対象。ここでは呼ぶだけ）。
            // 旧実装は「捨てた点で比較基準を更新しない」ため、本物の水準シフト以降の
            // 全バーが連鎖的に捨てられ履歴が黙って打ち切られていた（v1.23で修正）。
            val cleaned = com.example.etfbuyalert.domain.BadTicks.clean(
                filled, com.example.etfbuyalert.domain.BadTicks.thresholdFor(isWeekly)
            )
            if (cleaned.dropped > 0) {
                Log.w(TAG, "履歴の異常値を${cleaned.dropped}件除外した（${if (isWeekly) "週足" else "日足"}）")
            }
            return if (cleaned.points.isEmpty()) null else cleaned.points
        } catch (e: Exception) {
            Log.e(TAG, "履歴パース例外: ${e.message}")
            return null
        }
    }

    // YahooのJSONから必要な値だけ取り出す
    private fun parse(ticker: String, json: String): Quote? {
        try {
            val root = JsonParser.parseString(json).asJsonObject
            val result = root.getAsJsonObject("chart")
                ?.getAsJsonArray("result") ?: return null
            if (result.size() == 0) return null
            val meta = result[0].asJsonObject.getAsJsonObject("meta") ?: return null

            // 現在値：regularMarketPrice（場中）。無ければ直近終値で代用。
            val price = meta.get("regularMarketPrice")?.takeIf { !it.isJsonNull }?.asDouble
                ?: lastClose(result[0].asJsonObject)
                ?: return null

            // 前日終値：日足の終値配列から「最新取引日より前の最後の終値」を取る。
            // chartPreviousClose は「取った期間（range=5d）の最初の足の1本前」＝約5営業日前の終値で、
            // 前日比が1週間分の値動きになっていた（2026-10-22 実データで確認: VOO 707.43=10/14終値、前日10/20は706.80）。
            // previousClose は日足では返ってこない。どちらも前日終値として使わない。
            val obj = result[0].asJsonObject
            val gmtOffset = meta.get("gmtoffset")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            val marketTime = meta.get("regularMarketTime")?.takeIf { !it.isJsonNull }?.asLong
            val prevClose = marketTime?.let {
                prevCloseBefore(timestamps(obj), closes(obj), it, gmtOffset)
            }

            // 場中か：marketState は chart API では返ってこないため、
            // currentTradingPeriod.regular の開始〜終了と今の時刻で判定する
            val nowSec = System.currentTimeMillis() / 1000
            val regular = meta.getAsJsonObject("currentTradingPeriod")?.getAsJsonObject("regular")
            val start = regular?.get("start")?.takeIf { !it.isJsonNull }?.asLong
            val end = regular?.get("end")?.takeIf { !it.isJsonNull }?.asLong
            val live = start != null && end != null && nowSec in start until end
            return Quote(ticker, price, prevClose, isLive = live)
        } catch (e: Exception) {
            Log.e(TAG, "$ticker パース例外: ${e.message}")
            return null
        }
    }

    // 足の時刻（秒）の配列。無ければ空。
    private fun timestamps(resultObj: com.google.gson.JsonObject): List<Long> = try {
        resultObj.getAsJsonArray("timestamp")?.map { it.asLong } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    // 終値の配列（nullを含む）。無ければ空。
    private fun closes(resultObj: com.google.gson.JsonObject): List<Double?> = try {
        resultObj.getAsJsonObject("indicators")?.getAsJsonArray("quote")?.get(0)?.asJsonObject
            ?.getAsJsonArray("close")?.map { if (it.isJsonNull) null else it.asDouble } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    // 最新取引日（regularMarketTime の取引所の日付）より前の、最後の非null終値を返す。
    // 日付は取引所の時差（gmtoffset 秒）を足してから日単位に切る。見つからなければ null（前日比は出さない）。
    internal fun prevCloseBefore(
        timestamps: List<Long>, closes: List<Double?>, marketTimeSec: Long, gmtOffsetSec: Long
    ): Double? {
        val marketDay = Math.floorDiv(marketTimeSec + gmtOffsetSec, 86400L)
        val n = minOf(timestamps.size, closes.size)
        for (i in n - 1 downTo 0) {
            val day = Math.floorDiv(timestamps[i] + gmtOffsetSec, 86400L)
            val c = closes[i]
            if (day < marketDay && c != null) return c
        }
        return null
    }

    // 日足の終値配列から最後の非null値を返す（確定終値の代用）
    private fun lastClose(resultObj: com.google.gson.JsonObject): Double? {
        return try {
            val closes = resultObj.getAsJsonObject("indicators")
                ?.getAsJsonArray("quote")?.get(0)?.asJsonObject
                ?.getAsJsonArray("close") ?: return null
            for (i in closes.size() - 1 downTo 0) {
                val v = closes[i]
                if (!v.isJsonNull) return v.asDouble
            }
            null
        } catch (e: Exception) { null }
    }
}
