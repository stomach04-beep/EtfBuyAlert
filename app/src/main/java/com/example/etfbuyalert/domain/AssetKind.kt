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
     * ETFの判定はティッカー対応表（EtfCategory）を正とし、方式=MA200も補助的に見る。
     *   - EtfCategory の対応表に載っている＝ETF（VOO/QQQ/SMH/URA/1540.T 等）
     *   - ライン計算方式=MA200 ＝ ETF用の200日線ジョブが回している行＝ETF
     * それ以外は個別株とみなし、円建て（.T）なら日本株、そうでなければ米国株。
     * ※「方式=ADP型なら個別株」とは判定しない。将来ETFをADP型で運用する可能性があり、
     *   方式は"どの式で計算するか"であって"何の商品か"ではないため。
     */
    fun of(state: EtfState): Kind = of(state.ticker, state.lineMethod)

    fun of(ticker: String?, lineMethod: String?): Kind = when {
        EtfCategory.isEtf(ticker) || lineMethod == METHOD_MA200 -> Kind.ETF
        Symbol.isJp(ticker) -> Kind.JP_STOCK
        else -> Kind.US_STOCK
    }
}
