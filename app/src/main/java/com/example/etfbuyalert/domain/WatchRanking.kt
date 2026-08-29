package com.example.etfbuyalert.domain

import com.example.etfbuyalert.data.model.EtfState

/**
 * 監視一覧の「並べ替え」と「絞り込み」の単一の真実の源（DRY）。
 *
 * 画面（WatchListScreen）とホーム画面ウィジェット（EtfWidgetProvider）が同じ順番・
 * 同じ発火判定を使う必要があるため、UIの中でなくここに置く。
 * 同じ意味の並べ替えを2か所に書くと必ずズレて、
 * 「ウィジェットの上位3件とアプリの上位3件が違う」という一番たちの悪い壊れ方をする。
 */
object WatchRanking {

    /**
     * 「押し目まであと何%」の近い順（発火中＝マイナスが自動的に先頭に来る）。
     * 距離を出せない銘柄（価格未取得・ライン未設定・鮮度切れ）は末尾へ回す。
     */
    fun dipGapComparator(now: Long = System.currentTimeMillis()): Comparator<EtfState> =
        compareBy<EtfState> { AlertEngine.dipGapPercent(it, now) ?: Double.MAX_VALUE }
            .thenBy { it.ticker }

    /** 上の基準で並べ替えた一覧 */
    fun byDipGap(states: List<EtfState>, now: Long = System.currentTimeMillis()): List<EtfState> =
        states.sortedWith(dipGapComparator(now))

    /** いま発火中（押し目・深押しに到達）の件数。判定は AlertEngine が持ち主。 */
    fun firedCount(states: List<EtfState>): Int = states.count { AlertEngine.isFired(it) }

    /**
     * 押し目が近い上位n件（ウィジェットの表示用）。
     * 距離を出せない銘柄は「近い」とは言えないので最初から除く
     * （末尾要素で埋めると、価格が取れていない銘柄が上位3件に紛れ込む）。
     */
    fun topByDipGap(
        states: List<EtfState>,
        n: Int,
        now: Long = System.currentTimeMillis(),
    ): List<EtfState> =
        states.filter { AlertEngine.dipGapPercent(it, now) != null }
            .sortedWith(dipGapComparator(now))
            .take(n)

    /**
     * 監視タブの検索（ティッカー／銘柄名の部分一致・大文字小文字を区別しない）。
     * 日本株は内部が "1925.T" でも画面表記の "1925" で探せるようにする（Symbol.display）。
     * 空白のみのクエリは「絞り込みなし」＝全件一致とする。
     */
    fun matchesQuery(st: EtfState, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val needle = q.uppercase()
        return st.ticker.uppercase().contains(needle) ||
                Symbol.display(st.ticker).uppercase().contains(needle) ||
                st.name.uppercase().contains(needle)
    }

    /** 検索結果（並びは押し目が近い順のまま＝一覧と同じ見え方にする） */
    fun search(
        states: List<EtfState>,
        query: String,
        now: Long = System.currentTimeMillis(),
    ): List<EtfState> =
        states.filter { matchesQuery(it, query) }.sortedWith(dipGapComparator(now))
}
