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
