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

    // ブックマーク列の名前は読み取り・書き込みの両方で使うので1か所に定義する。
    // ここがNotion側の列名とズレると「黙って書き込まれない／常にfalse」になる（DRY）。
    const val PROP_BOOKMARK = "ブックマーク"

    // ニュース警告列（PC側の fired_news_check.py が書き込み、アプリは読むだけ）。
    // Python側の PROP_WARN と同じ列名。ズレると黙って常に空になるので定数化。
    const val PROP_NEWS_WARN = "ニュース警告"

    // 生存監視（ハートビート）用。アプリが同期のたびに「アプリ最終同期」へ現在時刻を書く。
    // 外部の見張りジョブ（PC/VPS）はこの時刻だけを見て、アプリが止まっていないかを判断する。
    // ・ティッカー _APP_HEARTBEAT の行は銘柄ではないので価格取得も通知も行わない
    // ・普通の行の「最終更新」ではダメ：アプリは価格チェックのたびにNotionへ書かないうえ、
    //   他ジョブ（ADP型ラインの日次更新など）の書き込みでも新しくなり、別ジョブの生存を
    //   見ていることになる
    const val HEARTBEAT_TICKER = "_APP_HEARTBEAT"
    const val PROP_HEARTBEAT = "アプリ最終同期"

    // つるはし出遅れ候補列（PC側 pickaxe-radar が毎日更新、アプリは読むだけ）。
    // Python側の "出遅れ候補" と同じ列名。ズレると黙って常にfalseになるので定数化。
    const val PROP_PICKAXE_LAGGING = "出遅れ候補"

    // 売りルール除外列（売り時点灯の対象から外す逃し弁）。列が未新設でもfalse扱いで安全。
    const val PROP_SELL_EXCLUDE = "売りルール除外"

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
        val category: String?,  // 任意の「カテゴリ」セレクト列（無ければnull→業種→アプリ内対応表の順で補完）
        val sector: String?,    // 「業種」セレクト列（化学/医薬品/機械/サービス 等）。個別株はこちらが入る
        val lineMethod: String?, // 「ライン計算方式」セレクト列（ADP型 / MA200 / 手動）＝ラインの持ち主
        val rsiWatch: Boolean,  // 「RSI利確監視」checkbox列。列が未新設のDBではfalse扱い
        val bookmarked: Boolean, // 「ブックマーク」checkbox列。方針を定めた銘柄の印（★タブ）
        val newsWarning: String?, // 「ニュース警告」rich_text列。PC側ジョブが書く下落理由の警告文
        val sellExcluded: Boolean, // 「売りルール除外」checkbox列。ONなら売り時点灯の対象外
        val pickaxeLagging: Boolean // 「出遅れ候補」checkbox列。pickaxe-radarが毎日更新（候補提示）
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

    /**
     * 端末内で計算した価格ラインをNotionのページへ書き戻す。
     *
     * 【なぜ必要か】
     * ラインの計算をアプリ側へ移した結果、Notionの数値はPCの月次ジョブが最後に
     * 書いた値のまま凍結する。放置すると「アプリは新しい値・Notionは古い値」で
     * 同じ押し目ラインが2つ存在することになり、Notionを見て判断すると通知と食い違う。
     * 書き戻して単一の値に保つ。
     *
     * 失敗しても呼び出し側は止めない（通知はローカル計算値で動くため）。true=成功。
     */
    fun updateLines(
        token: String,
        pageId: String,
        dip: Double,
        deepDip: Double,
        breakout: Double,
        stopLoss: Double,
    ): Boolean {
        if (token.isBlank() || pageId.isBlank()) return false
        val url = "https://api.notion.com/v1/pages/$pageId"
        // 読み取り側(parse)と同じプロパティ名を使う。ここがズレると黙って書き込まれない
        val body = """
            {"properties":{
              "買い時価格(押し目)":{"number":$dip},
              "買い増し価格(深押し)":{"number":$deepDip},
              "順張り価格(上抜け)":{"number":$breakout},
              "損切り価格":{"number":$stopLoss}
            }}
        """.trimIndent()

        for (attempt in 0 until 3) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Notion-Version", NOTION_VERSION)
                    .addHeader("Content-Type", "application/json")
                    .patch(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return true
                    val text = resp.body?.string() ?: ""
                    Log.w(TAG, "ライン書き戻し失敗 HTTP ${resp.code}: $text")
                    // 権限・プロパティ名の誤りはリトライしても直らないので即あきらめる
                    if (resp.code == 400 || resp.code == 401 || resp.code == 403 || resp.code == 404) return false
                }
            } catch (e: Exception) {
                Log.w(TAG, "ライン書き戻し通信失敗(${attempt + 1}/3): ${e.message}")
            }
            // 429/5xx はしばらく待って再試行（Notionのレート制限は約3req/秒）
            try { Thread.sleep(1000L * (attempt + 1)) } catch (_: InterruptedException) {}
        }
        return false
    }

    /**
     * ブックマーク（★）のON/OFFをNotionへ書き戻す。
     *
     * 読み取りと同じ「ブックマーク」列を使う（プロパティ名は PROP_BOOKMARK に単一定義）。
     * ここを書かないと、アプリで付けた★が次の同期でNotionのfalseに上書きされて消える。
     * true=書き込み成功。呼び出し側は失敗時にUIを元へ戻してユーザーへ知らせる。
     */
    fun updateBookmark(token: String, pageId: String, value: Boolean): Boolean {
        if (token.isBlank() || pageId.isBlank()) return false
        val url = "https://api.notion.com/v1/pages/$pageId"
        val body = """{"properties":{"$PROP_BOOKMARK":{"checkbox":$value}}}"""

        for (attempt in 0 until 3) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Notion-Version", NOTION_VERSION)
                    .addHeader("Content-Type", "application/json")
                    .patch(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return true
                    val text = resp.body?.string() ?: ""
                    Log.w(TAG, "ブックマーク書き戻し失敗 HTTP ${resp.code}: $text")
                    // 権限・プロパティ名の誤りはリトライしても直らない（列の新設漏れもここ）
                    if (resp.code == 400 || resp.code == 401 || resp.code == 403 || resp.code == 404) return false
                }
            } catch (e: Exception) {
                Log.w(TAG, "ブックマーク書き戻し通信失敗(${attempt + 1}/3): ${e.message}")
            }
            try { Thread.sleep(1000L * (attempt + 1)) } catch (_: InterruptedException) {}
        }
        return false
    }

    /**
     * 生存の証（ハートビート）をNotionへ書く。同期が成功したときだけ呼ぶ。
     *
     * 書くのは日時1つだけ。失敗しても通知動作には影響しないので呼び出し側は止めない。
     * タイムゾーン付きISO8601（例 2026-07-28T12:34:56+09:00）で書く。日付だけだと
     * 「何時に同期したか」が消え、6時間の鮮度判定ができなくなる。
     */
    fun updateHeartbeat(token: String, pageId: String): Boolean {
        if (token.isBlank() || pageId.isBlank()) return false
        val url = "https://api.notion.com/v1/pages/$pageId"
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
            .format(java.util.Date())
        val body = """{"properties":{"$PROP_HEARTBEAT":{"date":{"start":"$now"}}}}"""

        for (attempt in 0 until 3) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Notion-Version", NOTION_VERSION)
                    .addHeader("Content-Type", "application/json")
                    .patch(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return true
                    val text = resp.body?.string() ?: ""
                    Log.w(TAG, "ハートビート書き込み失敗 HTTP ${resp.code}: $text")
                    // 列の新設漏れ（400）や権限（401/403/404）はリトライしても直らない
                    if (resp.code == 400 || resp.code == 401 || resp.code == 403 || resp.code == 404) return false
                }
            } catch (e: Exception) {
                Log.w(TAG, "ハートビート通信失敗(${attempt + 1}/3): ${e.message}")
            }
            try { Thread.sleep(1000L * (attempt + 1)) } catch (_: InterruptedException) {}
        }
        return false
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
                        // 「カテゴリ」列は現状このDBに存在しない（＝常にnull）。将来ユーザーが
                        // 手動分類用に足したときに効くよう残してある。実際の分類は業種→対応表で補完する。
                        category = selectName(props, "カテゴリ"),
                        sector = selectName(props, "業種"),
                        lineMethod = selectName(props, "ライン計算方式"),
                        // 週足RSI過熱利確のオプトイン。列がまだ無いDBでもfalseになるだけ（クラッシュしない）
                        rsiWatch = checkbox(props, "RSI利確監視"),
                        // ブックマーク（★）。PC側で方針を決めた銘柄にチェックを入れるとアプリの★タブに出る
                        bookmarked = checkbox(props, PROP_BOOKMARK),
                        // つるはし出遅れ候補。列が未新設でもfalse＝候補なし扱い（安全側）
                        pickaxeLagging = checkbox(props, PROP_PICKAXE_LAGGING),
                        // ニュース警告。列が未新設・空のDBでも空文字→nullになるだけ（クラッシュしない）
                        newsWarning = richText(props, PROP_NEWS_WARN).takeIf { it.isNotBlank() },
                        // 売りルール除外。列が未新設でもfalse＝監視対象のまま（安全側）
                        sellExcluded = checkbox(props, PROP_SELL_EXCLUDE)
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
        // 長文（ニュース警告など）はNotion側で複数セグメントに分かれることがあるため全部つなぐ
        return arr.joinToString("") { el ->
            el.asJsonObject.get("plain_text")?.takeIf { !it.isJsonNull }?.asString ?: ""
        }
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

    // checkbox列の値。プロパティ自体が無い（DB側で未新設）場合もfalseを返す＝安全側
    private fun checkbox(props: JsonObject, name: String): Boolean {
        val p = prop(props, name) ?: return false
        val c = p.get("checkbox") ?: return false
        return if (c.isJsonNull) false else c.asBoolean
    }

    private fun dateNotNull(props: JsonObject, name: String): Boolean {
        val p = prop(props, name) ?: return false
        val d = p.get("date") ?: return false
        if (d.isJsonNull) return false
        val start = d.asJsonObject.get("start") ?: return false
        return !start.isJsonNull
    }
}
