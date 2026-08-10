package com.example.etfbuyalert.data.repository

import android.content.Context
import android.util.Log
import com.example.etfbuyalert.NotificationHelper
import com.example.etfbuyalert.data.model.AppData
import com.example.etfbuyalert.data.model.EtfState
import com.example.etfbuyalert.data.model.UpdateLog
import com.example.etfbuyalert.data.network.NotionClient
import com.example.etfbuyalert.data.network.YahooFinanceClient
import com.example.etfbuyalert.domain.AlertEngine
import com.example.etfbuyalert.domain.AssetKind
import com.example.etfbuyalert.domain.EtfCategory
import com.example.etfbuyalert.domain.Freshness
import com.example.etfbuyalert.domain.Money
import com.example.etfbuyalert.domain.Symbol
import com.example.etfbuyalert.domain.Ma200Lines
import com.example.etfbuyalert.domain.NewsWarning
import com.example.etfbuyalert.domain.WeeklyRsi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ETF監視の中核。Notion同期 → Yahooで価格取得 → 買い時判定 → 通知 → 保存 を束ねる。
// Worker（バックグラウンド）とViewModel（手動更新）の両方から呼ばれる。
class EtfRepository(private val context: Context) {

    private val storage = JsonStorage(context)
    private val TAG = "EtfRepository"

    // MA200ラインの再計算間隔。PC版は4週ごとの月次ジョブで、200日線は1日ではほぼ動かない。
    // 毎回1年ぶんの日足を全銘柄ぶん取りに行くと通信も電池も無駄なので7日に1回までとする。
    private val MA_LINES_REFRESH_MS = 7L * 24 * 60 * 60 * 1000

    fun load(): AppData = storage.load()

    /**
     * ブックマーク（★）を切り替える。Notionへ書き戻してからローカルJSONも更新する。
     *
     * Notionが単一の真実の源なので、書き戻しに失敗したらローカルも変えない
     * （変えてしまうと次の同期でNotionの値に戻され、「押したのに消えた」ように見える。
     *  サイレントに食い違うより、その場で失敗を伝えたほうがよい）。
     *
     * @return 成功したら切り替え後の値、失敗したら null（呼び出し側でメッセージを出す）
     */
    fun toggleBookmark(ticker: String): Boolean? {
        val data = storage.load()
        val idx = data.etfStates.indexOfFirst { it.ticker == ticker }
        if (idx < 0) return null
        val st = data.etfStates[idx]
        val next = !st.bookmarked

        val token = Settings.notionToken(context)
        val ok = NotionClient.updateBookmark(token, st.pageId, next)
        if (!ok) {
            Log.w(TAG, "${st.ticker}: ブックマークのNotion書き戻しに失敗（ローカルも変更しない）")
            return null
        }

        data.etfStates[idx] = st.copy(bookmarked = next)
        storage.save(data)
        return next
    }

    // 価格チェック1回ぶんを実行（type=MORNING_SUMMARYなら朝サマリも送る）。
    // 同期失敗時も前回値（キャッシュ）で価格判定を続ける。
    fun update(type: UpdateType): Boolean {
        val data = storage.load()

        // このチェックで出たアラートを一旦ためて、最後にまとめて1件の通知にする
        // （複数銘柄が同時に到達したとき、通知が何件も個別に飛んでこないように）。
        // ニュース警告（同期時に検知）もここに積み、価格アラートと同じまとめ通知に入れる。
        val pending = ArrayList<NotificationHelper.AlertItem>()

        // 1) Notion同期（best-effort：失敗してもキャッシュのstatesで続行）
        syncConfigs(data, pending)

        // 2) 各銘柄の価格を取得して買い時判定
        val toggles = AlertEngine.Toggles(
            dip = Settings.notifyDip(context),
            stop = Settings.notifyStop(context),
            breakout = Settings.notifyBreakout(context),
            zoneChange = Settings.notifyZoneChange(context)
        )
        val now = System.currentTimeMillis()
        val updated = ArrayList<EtfState>(data.etfStates.size)
        var fetchOkCount = 0  // 価格取得に成功した銘柄数（0件なら失敗としてログに残す）
        for (st in data.etfStates) {
            val quote = YahooFinanceClient.fetchQuote(st.ticker)
            var s = if (quote != null) {
                fetchOkCount++
                st.copy(
                    price = quote.price,
                    previousClose = quote.previousClose ?: st.previousClose,
                    // asOf＝最終取得成功時刻。失敗時は更新しない＝鮮度ガード(Freshness)の判定キー
                    asOf = now,
                    isLive = quote.isLive
                )
            } else st  // 取得失敗は前回値を維持（ただし鮮度ガードで無期限の持ち越しは防ぐ）

            // 週足RSI（「RSI利確監視」=ONの銘柄のみ計算。OFFの銘柄は一切触らない）。
            // 取得失敗時は前回値キャッシュで継続（価格取得と同じ流儀。weeklyRsiAsOfも更新しない）。
            if (s.rsiWatch) {
                val weekly = YahooFinanceClient.fetchWeeklyCloses(st.ticker)
                val rsiVal = weekly?.let { WeeklyRsi.calculate(it, now) }
                if (rsiVal != null) {
                    s = s.copy(weeklyRsi = rsiVal.rsi, weeklyRsiWeek = rsiVal.weekOf, weeklyRsiAsOf = now)
                }
            }

            // MA200ラインを端末内で再計算する（「ライン計算方式」=MA200 の行だけ）。
            // 以前はPCの月次ジョブが計算してNotionへ書き戻し、アプリはその固定値を読むだけ
            // だったため、PCを起動しない月はラインが古いまま止まっていた。
            // 取得失敗時は前回のライン（＝Notion値か前回計算値）を維持する＝週足RSIと同じ流儀。
            s = refreshMa200Lines(s, now)

            // 7日超の古い価格ではライン到達判定をしない（evaluateの入口で鮮度ガード）
            val (newState, alerts) = AlertEngine.evaluate(s, toggles, now)
            s = newState
            for (a in alerts) {
                pending.add(NotificationHelper.AlertItem(a.category, a.title, a.message))
            }
            updated.add(s)
        }
        data.etfStates.clear()
        data.etfStates.addAll(updated)

        // ためたアラートをまとめて送信（2件以上は1件のまとめ通知に集約）
        NotificationHelper.sendAlerts(context, pending)

        // 3) 毎朝サマリ
        if (type == UpdateType.MORNING_SUMMARY && Settings.notifyMorning(context)) {
            sendMorningSummary(data)
        }

        // 4) ログ記録＋保存
        // 監視銘柄があるのに価格が1件も取れなかったら「失敗」として記録する
        // （成功と偽ると、更新ログから取得経路の故障に気づけないため）
        val fetchOk = data.etfStates.isEmpty() || fetchOkCount > 0
        val logMessage = buildList {
            if (!fetchOk) add("価格取得0件（全${data.etfStates.size}銘柄失敗）")
            if (!data.lastSyncOk && data.lastSyncError != null) add("Notion同期失敗")
        }.joinToString(" / ")
        appendLog(data, type, success = fetchOk, message = logMessage)
        storage.save(data)
        return fetchOk
    }

    // Notionの設定をstatesへマージ。成功時のみstatesを置き換え（price/armedは引き継ぐ）。
    /**
     * MA200方式かつ端末内で計算済みなら、その値を返す（Notion値で巻き戻さないため）。
     * それ以外は null を返し、呼び出し側で Notion の値にフォールバックさせる。
     */
    private inline fun keepLocal(
        lineMethod: String?,
        prev: EtfState?,
        pick: () -> Double?,
    ): Double? =
        if (lineMethod == Ma200Lines.METHOD && (prev?.maLinesAsOf ?: 0L) > 0L) pick() else null

    /**
     * 「ライン計算方式」=MA200 の銘柄について、200日線ベースのラインを端末内で再計算する。
     *
     * PC版は4週ごとの月次ジョブだった（200日線は1日ではほとんど動かない）ので、
     * ここも7日に1回までに絞る。毎回1年ぶんの日足を全銘柄取りに行くと通信も電池も無駄。
     * 取得・計算に失敗したら既存のラインをそのまま残す（0やダミーで上書きすると
     * 誤ったラインで通知が飛ぶため）。
     */
    private fun refreshMa200Lines(st: EtfState, now: Long): EtfState {
        if (st.lineMethod != Ma200Lines.METHOD) return st          // 担当外の行は一切触らない
        val elapsed = now - st.maLinesAsOf
        if (st.maLinesAsOf > 0L && elapsed < MA_LINES_REFRESH_MS) return st

        val hist = YahooFinanceClient.fetchHistory(st.ticker, "1y") ?: return st
        val lines = Ma200Lines.compute(hist) ?: run {
            Log.w(TAG, "${st.ticker}: MA200ライン計算に必要なデータが不足（既存ラインを維持）")
            return st
        }
        if (lines.isSubstituteWindow) {
            // 上場が浅い等で200日ぶん無い場合。黙って代用せずログに残す（PC版と同じ方針）
            Log.w(TAG, "${st.ticker}: 終値が${lines.maWindowUsed}日ぶんしかなく${lines.maWindowUsed}日線で代用")
        }

        // Notionへ書き戻す。これをしないとNotion側の数値がPCの最終書き込み時点で凍結し、
        // 「アプリは新しい値・Notionは古い値」で同じラインが2つ存在することになる。
        // 書き戻しに失敗しても通知はローカル計算値で動くので、処理は続行する。
        val token = Settings.notionToken(context)
        val wroteBack = NotionClient.updateLines(
            token = token,
            pageId = st.pageId,
            dip = lines.dip,
            deepDip = lines.deepDip,
            breakout = lines.breakout,
            stopLoss = lines.stopLoss,
        )
        if (!wroteBack) {
            Log.w(TAG, "${st.ticker}: Notionへのライン書き戻しに失敗（アプリ内の値は更新済み）")
        }

        return st.copy(
            dipPrice = lines.dip,
            deepDipPrice = lines.deepDip,
            breakoutPrice = lines.breakout,
            stopLossPrice = lines.stopLoss,
            maLinesAsOf = now,
            maWindowUsed = lines.maWindowUsed,
        )
    }

    // pending: 同期中に気づいたアラート（ニュース警告の新規・変化）を積む先。
    // 呼び出し元 update() が価格アラートと合わせて最後にまとめて通知する。
    private fun syncConfigs(data: AppData, pending: MutableList<NotificationHelper.AlertItem>) {
        val token = Settings.notionToken(context)
        val db = Settings.notionDbId(context)
        val res = NotionClient.fetchWatchedEtfs(token, db)
        if (!res.ok) {
            data.lastSyncOk = false
            data.lastSyncError = res.error
            Log.w(TAG, "Notion同期失敗（キャッシュ継続）: ${res.error}")
            return
        }
        // 生存監視用の特別行を先に抜き取る（銘柄ではないので価格取得も通知も監視一覧入りもしない）。
        // 同期が成功したこの瞬間だけ「アプリ最終同期」に現在時刻を書き、外部の見張りジョブが
        // アプリの停止（Doze・電池最適化・force-stop・トークン失効）に気づけるようにする。
        val heartbeatRow = res.items.find { it.ticker == NotionClient.HEARTBEAT_TICKER }
        if (heartbeatRow != null) {
            val hbOk = NotionClient.updateHeartbeat(token, heartbeatRow.pageId)
            if (!hbOk) Log.w(TAG, "ハートビート書き込みに失敗（監視動作は継続）")
        }
        val items = res.items.filter { it.ticker != NotionClient.HEARTBEAT_TICKER }

        val merged = items.map { n ->
            // Notionのティッカー表記はゆれている（ETFは"1540.T"、ADP型日本株は"1925"）ので、
            // ここ＝同期の入口で正規形に直す。以降のYahoo取得も通貨表示もこの値だけを見る（Symbol参照）。
            // これをしないと日本株はYahooが404を返し価格が取れず、円建てもドル表示になる。
            val ticker = Symbol.normalize(n.ticker, n.market)
            // 既存状態を pageId（無ければ正規化後ticker）で引き継ぐ
            val prev = data.etfStates.find { it.pageId == n.pageId }
                ?: data.etfStates.find { it.ticker == ticker }
            EtfState(
                pageId = n.pageId,
                ticker = ticker,
                name = n.name,
                market = n.market,
                // 分類：Notionの「カテゴリ」列 → 「業種」列 → アプリ内対応表 の順で補完
                category = n.category?.takeIf { it.isNotBlank() }
                    ?: n.sector?.takeIf { it.isNotBlank() }
                    ?: EtfCategory.of(ticker),
                sector = n.sector,
                lineMethod = n.lineMethod,
                // ライン計算方式=MA200 の行は、端末内で計算した値を優先する。
                // 通常は書き戻し済みでNotionと同値だが、書き戻しが失敗した回や、
                // 移行直後にNotionへまだ反映されていない間は、Notion側が古い。
                // そこで巻き戻さないよう端末計算値を優先する。
                // 未計算(maLinesAsOf=0)のうちはNotionの値を使う＝初回や移行直後も動く。
                dipPrice = keepLocal(n.lineMethod, prev) { prev?.dipPrice } ?: n.dipPrice,
                deepDipPrice = keepLocal(n.lineMethod, prev) { prev?.deepDipPrice } ?: n.deepDipPrice,
                breakoutPrice = keepLocal(n.lineMethod, prev) { prev?.breakoutPrice } ?: n.breakoutPrice,
                stopLossPrice = keepLocal(n.lineMethod, prev) { prev?.stopLossPrice } ?: n.stopLossPrice,
                maLinesAsOf = if (n.lineMethod == Ma200Lines.METHOD) prev?.maLinesAsOf ?: 0L else 0L,
                maWindowUsed = if (n.lineMethod == Ma200Lines.METHOD) prev?.maWindowUsed ?: 0 else 0,
                purchased = n.purchased,
                price = prev?.price,
                previousClose = prev?.previousClose,
                asOf = prev?.asOf ?: 0L,
                isLive = prev?.isLive ?: false,
                // 週足RSI：ON/OFFはNotionが真実の源、計算値・通知済みフラグは前回状態を引き継ぐ
                rsiWatch = n.rsiWatch,
                // ブックマーク（★）もNotionが真実の源。PC側（Claude）で方針を決めて
                // Notionの「ブックマーク」にチェックを入れれば、次の同期で★タブに出る。
                // アプリで押した分はその場でNotionへ書き戻しているので値は一致する。
                bookmarked = n.bookmarked,
                // ニュース警告はNotionが単一の真実の源（PC側ジョブが書き、アプリは読むだけ）
                newsWarning = n.newsWarning,
                weeklyRsi = prev?.weeklyRsi,
                weeklyRsiWeek = prev?.weeklyRsiWeek,
                weeklyRsiAsOf = prev?.weeklyRsiAsOf ?: 0L,
                dipArmed = prev?.dipArmed ?: false,
                deepArmed = prev?.deepArmed ?: false,
                breakoutArmed = prev?.breakoutArmed ?: false,
                stopArmed = prev?.stopArmed ?: false,
                rsiTake1Armed = prev?.rsiTake1Armed ?: false,
                rsiTake2Armed = prev?.rsiTake2Armed ?: false,
                lastZone = prev?.lastZone ?: ""
            )
        }
        // 急落優良（RTX自動）行の新規追加を1回だけ通知する。
        // reversal-screener のRTX型検知ジョブがNotionへ登録した銘柄に「気づく」ための通知で、
        // 価格ライン到達の通知（AlertEngine）とは別物。
        // 初回同期（既存stateが空）は全行が"新規"になり大量通知になるため出さない。
        if (data.etfStates.isNotEmpty()) {
            val prevRtxIds = data.etfStates
                .filter { it.lineMethod == AssetKind.METHOD_RTX }
                .map { it.pageId }.toSet()
            val newRtx = merged.filter {
                it.lineMethod == AssetKind.METHOD_RTX && it.pageId !in prevRtxIds
            }
            if (newRtx.isNotEmpty()) {
                val body = newRtx.joinToString("\n") { "・${it.name}（${it.ticker}）" } +
                        "\nイベント急落型として検知。候補であり推奨ではありません（急落優良タブ参照）"
                NotificationHelper.sendAlert(
                    context, "急落優良",
                    "急落優良に新規 ${newRtx.size}件", body
                )
            }
        }

        // ニュース警告の新規・変化をアプリから通知する（以前はPC側ジョブがLINEで送っていた分）。
        // 比較は【要注意】部分の本文だけで行う（NewsWarning.criticalPart）。
        // 警告文の先頭には照合日が付き、ジョブが走るたび日付だけが変わるため、
        // 全文比較だと内容が同じでも毎日鳴ってしまう。
        // 初回同期（既存stateが空＝インストール直後）は既存の警告が全部「新規」に見えて
        // 大量通知になるので出さない（急落優良の新規通知と同じ流儀）。
        if (data.etfStates.isNotEmpty()) {
            val prevByPage = data.etfStates.associateBy { it.pageId }
            for (m in merged) {
                val sig = NewsWarning.criticalPart(m.newsWarning)
                if (sig.isEmpty()) continue  // 【要注意】無し（該当なし・参考のみ）は通知しない
                val prevSig = NewsWarning.criticalPart(prevByPage[m.pageId]?.newsWarning)
                if (sig == prevSig) continue // 内容が前回と同じなら鳴らさない
                pending.add(NotificationHelper.AlertItem(
                    category = "ニュース警告",
                    title = "⚠ ${m.name}（${Symbol.display(m.ticker)}）に警告",
                    message = "発火中ですが一時的な下落と言い切れない材料があります。\n" +
                            "$sig\nラインに触れただけで買わず、内容を確認してください。"
                ))
            }
        }

        data.etfStates.clear()
        data.etfStates.addAll(merged)
        data.lastSyncOk = true
        data.lastSyncError = null
        data.lastSyncAt = System.currentTimeMillis()
    }

    // 毎朝サマリ通知を組み立てて送信
    private fun sendMorningSummary(data: AppData) {
        if (data.etfStates.isEmpty()) return
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        for (st in data.etfStates.sortedBy { it.ticker }) {
            // 鮮度ガード：7日超の古い価格は「現在値」として出さず、取得失敗を明示する
            if (Freshness.isInvalid(st.asOf, now)) {
                sb.append("• ${st.ticker}  ${Freshness.INVALID_TEXT}\n")
                continue
            }
            val zone = AlertEngine.currentZone(st, now)
            val price = st.price
            // 3日超は「（◯日前の値）」を添えて、最新値と誤解させない
            val staleNote = Freshness.staleSuffix(st.asOf, now)
            sb.append("• ${st.ticker}  ${Money.format(st.ticker, price)}$staleNote  ［${zone.label}］\n")
            val parts = mutableListOf<String>()
            if (st.dipPrice != null) parts.add("押し目${Money.format(st.ticker, st.dipPrice)}${gap(price, st.dipPrice)}")
            if (st.stopLossPrice != null) parts.add("損切り${Money.format(st.ticker, st.stopLossPrice)}")
            if (st.breakoutPrice != null) parts.add("順張り${Money.format(st.ticker, st.breakoutPrice)}")
            if (parts.isNotEmpty()) sb.append("   ").append(parts.joinToString(" / ")).append("\n")
        }
        val title = "☀ ETF朝サマリ（${data.etfStates.size}銘柄）"
        NotificationHelper.sendMorningSummary(context, title, sb.toString().trimEnd())
    }

    // 現在値からラインまでの乖離率（マイナス＝そこまで下げ余地）
    private fun gap(price: Double?, line: Double?): String {
        if (price == null || line == null || price == 0.0) return ""
        val pct = (line - price) / price * 100.0
        return String.format("(%+.1f%%)", pct)
    }

    // 更新ログを追記（直近50件だけ残す）
    private fun appendLog(data: AppData, type: UpdateType, success: Boolean, message: String = "") {
        val now = Date()
        data.updateLogs.add(0, UpdateLog(
            date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now),
            time = SimpleDateFormat("HH:mm", Locale.JAPAN).format(now),
            updateType = type.name,
            success = success,
            message = message
        ))
        while (data.updateLogs.size > 50) data.updateLogs.removeAt(data.updateLogs.size - 1)
    }
}
