package com.example.etfbuyalert

import com.example.etfbuyalert.data.model.EtfState
import com.example.etfbuyalert.domain.AssetKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 種別（日本株 / 米国株 / ETF）判定のテスト。
 *
 * きっかけ: 「ETFタブに個別株が混ざっている」（2026-08-11 ユーザー報告）。
 * 原因は「ライン計算方式=MA200 ならETF」という判定で、200日線方式で監視し始めた
 * 個別株（ニトリ・関西電力・ニッスイ）がETF扱いになっていた。
 * 方式に引きずられないことを、ここで固定する。
 */
class AssetKindTest {

    // テスト用のダミー行を作る（判定に使うのはティッカーと銘柄名だけ）
    private fun state(ticker: String, name: String, method: String?) = EtfState(
        pageId = "dummy-$ticker",
        ticker = ticker,
        name = name,
        lineMethod = method,
    )

    @Test
    fun `方式がMA200でも個別株は日本株のまま`() {
        assertEquals(AssetKind.Kind.JP_STOCK, AssetKind.of(state("9843.T", "ニトリHD (9843)", "MA200")))
        assertEquals(AssetKind.Kind.JP_STOCK, AssetKind.of(state("9503.T", "関西電力 (9503)", "MA200")))
        assertEquals(AssetKind.Kind.JP_STOCK, AssetKind.of(state("1332.T", "ニッスイ (1332)", "MA200")))
    }

    @Test
    fun `方式がMA200のETFは従来どおりETF`() {
        assertEquals(AssetKind.Kind.ETF, AssetKind.of(state("VOO", "VOO バンガード S&P500(コア)", "MA200")))
        assertEquals(AssetKind.Kind.ETF, AssetKind.of(state("QQQ", "QQQ インベスコ Nasdaq100", "MA200")))
    }

    @Test
    fun `対応表に追加した円建てETFはETF`() {
        assertEquals(AssetKind.Kind.ETF, AssetKind.of(state("2521.T", "上場S&P500 為替ヘッジあり (2521)", "MA200")))
        assertEquals(AssetKind.Kind.ETF, AssetKind.of(state("1306.T", "NEXT FUNDS TOPIX連動型上場投信", "手動")))
        assertEquals(AssetKind.Kind.ETF, AssetKind.of(state("2621.T", "iシェアーズ 米国債20年超 為替ヘッジあり (2621)", "手動")))
        assertEquals(AssetKind.Kind.ETF, AssetKind.of(state("1540.T", "純金上場信託 1540（円建て金）", "手動")))
    }

    @Test
    fun `対応表に無くても銘柄名がETF名ならETF`() {
        // 将来Notionに増えたETF（アプリ未登録）を拾う保険
        assertEquals(AssetKind.Kind.ETF, AssetKind.of(state("1478.T", "iシェアーズ MSCIジャパン高配当利回り ETF", "手動")))
        assertEquals(AssetKind.Kind.ETF, AssetKind.of(state("2038.T", "NEXT NOTES 日経・TOCOM原油 ETN", "手動")))
    }

    @Test
    fun `個別株は方式によらず日本株・米国株に入る`() {
        assertEquals(AssetKind.Kind.US_STOCK, AssetKind.of(state("ADP", "オートマティック・データ・プロセッシング", "ADP型")))
        assertEquals(AssetKind.Kind.JP_STOCK, AssetKind.of(state("4206.T", "アイカ工業 (4206)", "手動")))
        assertEquals(AssetKind.Kind.US_STOCK, AssetKind.of(state("RTX", "RTX (旧レイセオン)", "RTX自動")))
    }

    @Test
    fun `REIT・投資法人はETFに混ぜない`() {
        // 「ファンド」はキーワードに入れていない（投資法人が誤ってETF扱いになるため）
        assertEquals(AssetKind.Kind.JP_STOCK, AssetKind.of(state("8951.T", "日本ビルファンド投資法人 (8951)", "手動")))
    }
}
