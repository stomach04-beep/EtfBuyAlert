package com.example.etfbuyalert.data.network

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// Notionの「投資ウォッチリスト」DBから、アプリ監視=ON の銘柄と買い時ラインを取得する。
// 数値プロパティ（買い時価格・損切り価格 等）だけを読むので壊れにくい
// （自由文のエントリー設計は解析しない）。
object NotionClient {

    private const val TAG = "NotionClient"
    private const val NOTION_VERSION = "2022-06-28"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // 同期した1銘柄ぶんの設定
    data class NotionEtf(
        val pageId: String,
        val ticker: String,
        val name: String,
        val market: String?,
        val dipPrice: Double?,
        val deepDipPrice: Double?,
        val breakoutPrice: Double?,
        val stopLossPrice: Double?,
        val purchased: Boolean,
        val category: String?   // 任意の「カテゴリ」セレクト列（無ければnull→アプリ内対応表で補完）
    )

    // 同期結果（成功/失敗とメッセージを呼び出し側へ返す）
    data class SyncResult(
        val ok: Boolean,
        val items: List<NotionEtf>,
        val error: String?
    )

    // DBをクエリして アプリ監視=true の行を取得する。
    // 1回のクエリは最大100件なので、has_more / next_cursor で続きを取得し全件を結合する。
    fun fetchWatchedEtfs(token: String, dbId: String): SyncResult {
        if (token.isBlank()) {
            return SyncResult(false, emptyList(), "Notionトークンが未設定です")
        }
        val url = "https://api.notion.com/v1/databases/$dbId/query"

        val all = mutableListOf<NotionEtf>()
        var startCursor: String? = null
        // 暴走防止：ページ取得は最大10ページ（100件×10 = 1000件）で打ち切る
        val maxPages = 10
        for (pageIndex in 0 until maxPages) {
            // アプリ監視=true のみ取得。2ページ目以降は start_cursor で続きから
            val bodyJson = buildString {
                append("""{"filter":{"property":"アプリ監視","checkbox":{"equals":true}},"page_size":100""")
                val cur = startCursor
                if (cur != null) append(""","start_cursor":"$cur"""")
                append("}")
            }

            // 1ページぶんの取得（3回リトライ・バックオフは従来どおり）
            var pageRoot: JsonObject? = null
            var lastError: String? = null
            for (attempt in 0 until 3) {
                try {
                    val req = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("Notion-Version", NOTION_VERSION)
                        .addHeader("Content-Type", "application/json")
                        .post(bodyJson.toRequestBody("application/json".toMediaType()))
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val text = resp.body?.string() ?: ""
                        if (!resp.isSuccessful) {
                            // 401/403/404はトークン・DB共有設定の問題なのでリトライ無意味→即返す
                            val msg = friendlyError(resp.code, text)
                            Log.w(TAG, "Notion失敗 HTTP ${resp.code}: $text")
                            if (resp.code == 401 || resp.code == 403 || resp.code == 404) {
                                return SyncResult(false, emptyList(), msg)
                            }
                            lastError = msg
                        } else {
                            pageRoot = JsonParser.parseString(text).asJsonObject
                        }
                    }
                } catch (e: Exception) {
                    lastError = e.message
                    Log.w(TAG, "Notion通信失敗(${attempt + 1}/3): ${e.message}")
                }
                if (pageRoot != null) break
                try { Thread.sleep(800L * (attempt + 1)) } catch (_: InterruptedException) {}
            }

            // 3回とも失敗 → 部分結果は返さず失敗にする（途中ページ欠落で銘柄が消えるのを防ぐ）
            val root = pageRoot
                ?: return SyncResult(false, emptyList(), "Notionに接続できません: ${lastError ?: "不明なエラー"}")

            all.addAll(parse(root))

            // 次ページが無ければ完了
            val hasMore = root.get("has_more")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            if (!hasMore) return SyncResult(true, all, null)
            startCursor = root.get("next_cursor")?.takeIf { !it.isJsonNull }?.asString
                ?: return SyncResult(true, all, null)  // カーソル欠損なら安全側でここまでを返す
        }
        // 上限10ページ（1000件）到達 → 暴走防止のため打ち切り、取得できたぶんまで返す
        Log.w(TAG, "ページ数上限($maxPages)に到達。取得済み ${all.size} 件で打ち切り")
        return SyncResult(true, all, null)
    }

    private fun friendlyError(code: Int, body: String): String = when (code) {
        401 -> "Notionトークンが無効です（設定を確認してください）"
        403, 404 -> "DBにアクセスできません。Notionでこのインテグレーションを\n「投資ウォッチリスト」に接続してください"
        else -> "Notionエラー HTTP $code"
    }

    // クエリ結果のJSON（1ページぶん）から銘柄リストを取り出す
    private fun parse(root: JsonObject): List<NotionEtf> {
        val list = mutableListOf<NotionEtf>()
        try {
            val results = root.getAsJsonArray("results") ?: return list
            for (el in results) {
                val page = el.asJsonObject
                val pageId = page.get("id")?.asString ?: continue
                val props = page.getAsJsonObject("properties") ?: continue

                val ticker = richText(props, "ティッカー").trim()
                if (ticker.isBlank()) continue  // ティッカー無しは監視できない
                val name = title(props, "銘柄名").ifBlank { ticker }
                val market = selectName(props, "市場")
                list.add(
                    NotionEtf(
                        pageId = pageId,
                        ticker = ticker,
                        name = name,
                        market = market,
                        dipPrice = number(props, "買い時価格(押し目)"),
                        deepDipPrice = number(props, "買い増し価格(深押し)"),
                        breakoutPrice = number(props, "順張り価格(上抜け)"),
                        stopLossPrice = number(props, "損切り価格"),
                        purchased = dateNotNull(props, "購入日"),
                        category = selectName(props, "カテゴリ")  // 列が無ければnullで返る（壊れない）
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "パース例外: ${e.message}")
        }
        return list
    }

    // ===== プロパティ型ごとの値取り出しヘルパー =====
    private fun prop(props: JsonObject, name: String): JsonObject? =
        props.get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun number(props: JsonObject, name: String): Double? {
        val p = prop(props, name) ?: return null
        val n = p.get("number") ?: return null
        return if (n.isJsonNull) null else n.asDouble
    }

    private fun richText(props: JsonObject, name: String): String {
        val p = prop(props, name) ?: return ""
        val arr = p.getAsJsonArray("rich_text") ?: return ""
        if (arr.size() == 0) return ""
        return arr[0].asJsonObject.get("plain_text")?.takeIf { !it.isJsonNull }?.asString ?: ""
    }

    private fun title(props: JsonObject, name: String): String {
        val p = prop(props, name) ?: return ""
        val arr = p.getAsJsonArray("title") ?: return ""
        if (arr.size() == 0) return ""
        return arr[0].asJsonObject.get("plain_text")?.takeIf { !it.isJsonNull }?.asString ?: ""
    }

    private fun selectName(props: JsonObject, name: String): String? {
        val p = prop(props, name) ?: return null
        val sel = p.get("select")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return sel.get("name")?.takeIf { !it.isJsonNull }?.asString
    }

    private fun dateNotNull(props: JsonObject, name: String): Boolean {
        val p = prop(props, name) ?: return false
        val d = p.get("date") ?: return false
        if (d.isJsonNull) return false
        val start = d.asJsonObject.get("start") ?: return false
        return !start.isJsonNull
    }
}
