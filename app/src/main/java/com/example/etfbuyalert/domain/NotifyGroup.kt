package com.example.etfbuyalert.domain

import com.example.etfbuyalert.data.model.EtfState

// 通知グループ（銘柄の「型」のタグ）の単一の真実の源（DRY）。v1.36 で新設。
//
// 【目的】利用者の要望「各銘柄にグロースや成長高配当などのタグを付け、タグごとに通知をオンオフしたい」。
// 1銘柄に1つだけグループを決め、設定画面のスイッチでグループごとに通知を止められるようにする。
//
// 【決め方】（上から順に、最初に当てはまったもの）
//   0) Notionの「通知グループ」列（手で付けた上書き）があればそれ
//   1) ライン計算方式＝つるはし → つるはし ／ RTX自動 → 急落優良（PC側ジョブが自動登録する行）
//   2) ETF（AssetKind 判定）→ ETF・指数
//   3) ライン計算方式＝ADP型（優良増配株の割安利回り型）→ 成長高配当
//   4) 既存の「タグ」に成長配当／お気に入りページが日本・米国配当成長／投資目的＝配当成長 → 成長高配当
//   5) 既存の「タグ」にグロース／お気に入りページが米国グロース・日本成長・医薬 → グロース
//   6) お気に入りページがテーマ物（半導体・ロボット・宇宙防衛など）→ テーマ
//   7) 既存の「タグ」に高配当か割安 → 高配当・割安
//   8) どれでもない → 未分類
// Notionへ導いた値を書き戻さないのは、同じ値を2か所に持つとズレるため（導出はこの関数だけ）。
// 自動登録の行（つるはし・急落優良・ADP型）もPC側ジョブを直さずに既定が付く。
object NotifyGroup {

    const val GROWTH_DIV = "成長高配当"
    const val HIGH_DIV = "高配当・割安"
    const val GROWTH = "グロース"
    const val THEME = "テーマ"
    const val ETF = "ETF・指数"
    const val RTX = "急落優良"
    const val PICKAXE = "つるはし"
    const val NONE = "未分類"

    // 設定画面に並べる順（ここに無いグループ＝Notionで手入力した新しい名前は後ろ、未分類は最後）
    val ORDER: List<String> = listOf(GROWTH_DIV, HIGH_DIV, GROWTH, THEME, ETF, RTX, PICKAXE)

    // お気に入りページ（楽天iSPEEDのページ割当）からの読み替え。PC側 ms2-watchlist-editor と同じ列
    private val FAV_GROWTH_DIV = setOf("日本配当成長", "米国配当成長")
    private val FAV_GROWTH = setOf("米国グロース", "日本成長・医薬")
    private val FAV_THEME = setOf("半導体", "ロボット", "ロボット・FA", "宇宙・防衛", "電力・円高ヘッジ", "資本財・機械")

    // 通知グループを決める（上の決め方0〜8）
    fun of(st: EtfState): String {
        // Gsonは既定値を使わないため、旧JSONから読んだ行は List が null になりうる→orEmpty
        val tags = st.tags.orEmpty()
        val fav = st.favoritePage
        st.notifyGroupRaw?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return when {
            st.lineMethod == AssetKind.METHOD_PICKAXE -> PICKAXE
            st.lineMethod == AssetKind.METHOD_RTX -> RTX
            AssetKind.of(st) == AssetKind.Kind.ETF -> ETF
            st.lineMethod == AssetKind.METHOD_ADP -> GROWTH_DIV
            "成長配当" in tags || fav in FAV_GROWTH_DIV || st.purpose == "配当成長" -> GROWTH_DIV
            "グロース" in tags || fav in FAV_GROWTH -> GROWTH
            fav in FAV_THEME -> THEME
            "高配当" in tags || "割安" in tags -> HIGH_DIV
            else -> NONE
        }
    }

    // 設定画面のスイッチの下に出す1行の説明（どの銘柄が入るか）
    fun describe(group: String): String = when (group) {
        GROWTH_DIV -> "増配を続ける配当株（ADP型・配当成長の行）"
        HIGH_DIV -> "利回りや割安さで選んだ銘柄"
        GROWTH -> "成長株（別枠）"
        THEME -> "半導体・ロボット・宇宙防衛などのテーマ株"
        ETF -> "ETF・指数連動"
        RTX -> "イベント急落の自動候補（新規追加の知らせも含む）"
        PICKAXE -> "テーマ過熱×出遅れの自動候補（新規・出遅れ点灯の知らせも含む）"
        NONE -> "上のどれにも当てはまらない銘柄"
        else -> "Notionの「通知グループ」列で付けたグループ"
    }

    // 設定画面の並び順スコア（小さいほど先頭）
    fun orderIndex(group: String): Int {
        val i = ORDER.indexOf(group)
        return when {
            i >= 0 -> i
            group == NONE -> 9999
            else -> 9000
        }
    }

    // 監視中の銘柄に実際に出てくるグループを、並び順どおり件数付きで返す（設定画面のスイッチ一覧）。
    // 語彙をアプリに固定で持たず、同期した実データから作る＝Notionで新しい名前を足しても自動で並ぶ。
    fun countsOf(states: List<EtfState>): List<Pair<String, Int>> =
        states.groupingBy { of(it) }.eachCount().toList()
            .sortedWith(compareBy({ orderIndex(it.first) }, { it.first }))

    // 「守り」の通知＝グループをオフにしても必ず鳴らす種類。
    // 損切り・売り時・過熱利確は保有中の行にしか出ない（AlertEngine側で保有が条件）。
    // ニュース警告は保有中の行だけ守り扱い（未保有の買い候補の警告は買い側の知らせ）。
    private val PROTECTIVE = setOf("損切り", "売り時", "過熱利確①", "過熱利確②")

    fun isProtective(category: String, purchased: Boolean): Boolean =
        category in PROTECTIVE || (category == "ニュース警告" && purchased)

    /**
     * この銘柄のこの種類の通知を鳴らすか（通知の全出口がこれを呼ぶ）。
     * @param muted 設定でオフにしたグループ名の集合（オフを持つ＝新しいグループは既定オン）
     * 到達判定（AlertEngine.evaluate）は止めず、鳴らす手前でだけ使うこと。
     * 判定まで止めると到達済みの印が進まず、オンに戻した瞬間にまとめて鳴る。
     */
    fun shouldNotify(st: EtfState, category: String, muted: Set<String>): Boolean {
        if (muted.isEmpty()) return true
        if (isProtective(category, st.purchased)) return true
        return of(st) !in muted
    }

    // 通知を止めている銘柄か（朝サマリから外す判定。保有中は守りの知らせのため外さない）
    fun isMuted(st: EtfState, muted: Set<String>): Boolean =
        muted.isNotEmpty() && !st.purchased && of(st) in muted
}
