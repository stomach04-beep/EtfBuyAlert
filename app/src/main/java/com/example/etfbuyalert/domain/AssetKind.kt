package com.example.etfbuyalert.domain

import com.example.etfbuyalert.data.model.EtfState

// 銘柄の「種別」判定の単一の真実の源（DRY）。一覧のタブ分けはここだけを見る。
//
// 【背景】もともとETF専用アプリだったが、ADP型 質ゲート監視プール（配当王・貴族の個別株79銘柄）が
// 同じNotion DBに加わり、監視対象がETF11本＋個別株79銘柄＝計90行規模になった。
// 「ETFか個別株か」「米国株か日本株か」を画面ごとに判定するとズレるので、ここに集約する。
object AssetKind {

    enum class Kind(val label: String) {
        US_STOCK("米国株"),
        JP_STOCK("日本株"),
        ETF("ETF"),
    }

    // Notionの「ライン計算方式」列の値。ADP型＝個別株プールの日次ジョブが持ち主。
    const val METHOD_ADP = "ADP型"
    const val METHOD_MA200 = "MA200"
    // RTX自動＝reversal-screener のRTX型（イベント急落型）検知ジョブが持ち主。
    // イベントで急落した優良大型株を自動登録した行（「急落優良」タブに出る）。
    const val METHOD_RTX = "RTX自動"
    // 手動＝自分で狙い値を決めた行。どのジョブも上書きしない。
    const val METHOD_MANUAL = "手動"

    /**
     * ライン方式の画面表示名（表示名⇔内部キーの対応はここに単一定義）。
     * 内部値「ADP型」「RTX自動」はNotion列の値としてPC側ジョブと共有しているため変えられないが、
     * 画面にそのまま出すと由来（米国株ADP・RTX社の下落型）を知らないと意味が取れない。
     * ユーザーに伝わる言葉に写して表示する（2026-08-10 ユーザー要望）。
     */
    fun methodLabel(lineMethod: String?): String = when (lineMethod) {
        METHOD_ADP -> "配当割安"      // 優良増配株が平常より割安な利回り水準に到達する型
        METHOD_RTX -> "急落優良"      // イベント急落した優良大型株の機械検知（タブ名と同じ）
        METHOD_MA200 -> "200日線"
        METHOD_MANUAL -> "手動"
        else -> lineMethod ?: "—"
    }

    /**
     * 種別を判定する。
     * ETFかどうかは「銘柄そのものの性質」で決める。ライン計算方式は見ない。
     *   1) EtfCategory の対応表に載っている＝ETF（VOO/QQQ/SMH/URA/1540.T 等）
     *   2) 銘柄名がETF名（「上場投信」「ETF」等）＝ETF（対応表への登録漏れの保険）
     * それ以外は個別株とみなし、円建て（.T）なら日本株、そうでなければ米国株。
     *
     * ⚠️【2026-08-11 修正】以前は「ライン計算方式=MA200 ならETF」という判定を併用していたが、
     * これは誤りだった。MA200は"どの式でラインを計算するか"であって"何の商品か"ではない。
     * 実際に個別株（9843ニトリ・9503関西電力・1332ニッスイ）を200日線方式で監視し始めた結果、
     * それらがETFタブに並んでしまった。方式=ADP型を個別株と決めつけないのと同じ理由で、
     * 方式からは種別を決めない。
     */
    fun of(state: EtfState): Kind = of(state.ticker, state.name)

    fun of(ticker: String?, name: String?): Kind = when {
        EtfCategory.isEtf(ticker) || EtfCategory.looksLikeEtfName(name) -> Kind.ETF
        Symbol.isJp(ticker) -> Kind.JP_STOCK
        else -> Kind.US_STOCK
    }
}
