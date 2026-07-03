package com.example.etfbuyalert.data.repository

import android.content.Context
import com.example.etfbuyalert.data.model.ChartSeries
import com.example.etfbuyalert.data.network.YahooFinanceClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

// チャート履歴の取得＋キャッシュ。詳細画面を開くたびに毎回ネット取得しないよう、
// 取得結果を chart_cache.json に "ティッカー|期間" 単位で保存する。
class ChartRepository(private val context: Context) {

    private val gson = Gson()
    private val file: File get() = File(context.filesDir, "chart_cache.json")

    companion object {
        private val lock = Any()
        // キャッシュ有効時間（これより新しければ再取得しない）
        private const val FRESH_MS = 30 * 60 * 1000L  // 30分
    }

    private fun key(ticker: String, range: String) = "$ticker|$range"

    private fun loadAll(): MutableMap<String, ChartSeries> = synchronized(lock) {
        try {
            if (!file.exists()) return mutableMapOf()
            val type = object : TypeToken<MutableMap<String, ChartSeries>>() {}.type
            gson.fromJson(file.readText(), type) ?: mutableMapOf()
        } catch (e: Exception) { mutableMapOf() }
    }

    private fun saveAll(map: Map<String, ChartSeries>) = synchronized(lock) {
        try {
            val tmp = File(context.filesDir, "chart_cache.json.tmp")
            tmp.writeText(gson.toJson(map))
            if (!tmp.renameTo(file)) { file.writeText(gson.toJson(map)); tmp.delete() }
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun getCached(ticker: String, range: String): ChartSeries? = loadAll()[key(ticker, range)]

    // キャッシュが新しければそれを、古ければネット取得して更新したものを返す。
    // ネット取得に失敗したらキャッシュ（あれば）を返す＝サイレント失敗を作らない。
    fun get(ticker: String, range: String, forceRefresh: Boolean = false): ChartSeries? {
        val all = loadAll()
        val cached = all[key(ticker, range)]
        val fresh = cached != null && (System.currentTimeMillis() - cached.asOf) < FRESH_MS
        if (cached != null && fresh && !forceRefresh) return cached

        val points = YahooFinanceClient.fetchHistory(ticker, range)
        if (points == null) return cached  // 取得失敗→前回キャッシュで代替
        val series = ChartSeries(ticker, range, System.currentTimeMillis(), points)
        all[key(ticker, range)] = series
        saveAll(all)
        return series
    }
}
