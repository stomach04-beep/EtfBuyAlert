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
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/" +
            ticker + "?interval=" + intervalFor(range) + "&range=" + range
        repeat(3) { attempt ->
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    return parseHistory(body)
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
                    return parseHistory(body)
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
     */
    private fun parseHistory(json: String): List<com.example.etfbuyalert.data.model.ChartPoint>? {
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
            val cleaned = dropBadTicks(points)
            return if (cleaned.isEmpty()) null else cleaned
        } catch (e: Exception) {
            Log.e(TAG, "履歴パース例外: ${e.message}")
            return null
        }
    }

    /**
     * バッドティック（偽の値飛び）を除去する。
     * Yahooの日本株データには日付を誤った分割レコード由来の異常値が実在する
     * （1306の2026-03-30/31だけ価格が1/10スケール＝偽の-90%と+948%を実測）。
     * この履歴は週足なので1週の変動は最大でも±25%程度。それを超えて飛んだ
     * 週は捨てる。放置すると偽の高値・安値が売り時判定とMA200線を狂わせる。
     * 【注意】本物の株式分割も同じ形で飛ぶが、その場合は調整済みの値が
     * 使われるので飛ばない（飛ぶのは調整漏れ＝誤プリントのほう）
     */
    private fun dropBadTicks(
        rows: List<com.example.etfbuyalert.data.model.ChartPoint>
    ): List<com.example.etfbuyalert.data.model.ChartPoint> {
        val out = ArrayList<com.example.etfbuyalert.data.model.ChartPoint>(rows.size)
        var last = Double.NaN
        var dropped = 0
        for (r in rows) {
            if (!last.isNaN() && kotlin.math.abs(r.close / last - 1.0) > 0.35) {
                dropped++
                continue
            }
            out.add(r)
            last = r.close
        }
        if (dropped > 0) Log.w(TAG, "履歴の異常値を${dropped}件除外した")
        return out
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

            // 前日終値：chartPreviousClose（場中も前々日にならず安定）
            val prevClose = meta.get("chartPreviousClose")?.takeIf { !it.isJsonNull }?.asDouble
                ?: meta.get("previousClose")?.takeIf { !it.isJsonNull }?.asDouble

            val state = meta.get("marketState")?.takeIf { !it.isJsonNull }?.asString ?: ""
            return Quote(ticker, price, prevClose, isLive = state == "REGULAR")
        } catch (e: Exception) {
            Log.e(TAG, "$ticker パース例外: ${e.message}")
            return null
        }
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
