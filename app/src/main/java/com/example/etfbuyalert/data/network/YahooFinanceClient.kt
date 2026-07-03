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

    private fun parseHistory(json: String): List<com.example.etfbuyalert.data.model.ChartPoint>? {
        try {
            val root = JsonParser.parseString(json).asJsonObject
            val result = root.getAsJsonObject("chart")?.getAsJsonArray("result") ?: return null
            if (result.size() == 0) return null
            val obj = result[0].asJsonObject
            val ts = obj.getAsJsonArray("timestamp") ?: return null
            val closes = obj.getAsJsonObject("indicators")
                ?.getAsJsonArray("quote")?.get(0)?.asJsonObject
                ?.getAsJsonArray("close") ?: return null
            val points = ArrayList<com.example.etfbuyalert.data.model.ChartPoint>(ts.size())
            val n = minOf(ts.size(), closes.size())
            for (i in 0 until n) {
                val c = closes[i]
                if (c.isJsonNull) continue  // 欠損日はスキップ
                points.add(com.example.etfbuyalert.data.model.ChartPoint(ts[i].asLong, c.asDouble))
            }
            return if (points.isEmpty()) null else points
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
