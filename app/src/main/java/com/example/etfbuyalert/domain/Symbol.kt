package com.example.etfbuyalert.domain

// 銘柄記号の「単一の真実の源」（DRY）。
// Notion側の表記ゆれをここ1か所で正規形に直し、以降のコード（Yahoo取得・通貨表示・種別判定）は
// すべて正規形だけを見る。判定をあちこちに書くと必ずズレる。
//
// 【なぜ必要か（2026-07-14の実測）】
// Notionの「ティッカー」列は表記が揃っていない：
//   ETF   : "1540.T"（Yahoo記号そのまま・.T付き）
//   日本株: "1925" "9433"（4桁コードのみ・.T無し）… ADP型プールの登録がこの形式
// アプリはティッカーをそのままYahooのURLに入れるため、"1925" は HTTP 404 で価格が取れず、
// 日本株51銘柄が丸ごと沈黙していた（"1925.T" なら200が返ることを実測で確認）。
// さらに Money は末尾".T"で円/ドルを判定するため、"1925" は ¥1,925 でなく $1,925.00 と誤表示される。
// → Notion同期の入口で正規形（4桁コードには.Tを付ける）へ直せば、両方まとめて直る。
object Symbol {

    // 東証の市場名（Notionの「市場」列の選択肢）。ここに前方一致すれば日本株とみなす。
    private const val JP_MARKET_PREFIX = "東証"

    /**
     * Notionのティッカー＋市場 → アプリ内で使う正規形。
     * 日本株の4桁コードには .T を付ける。既に .T 付き／米国株はそのまま。
     * 正規形は「Yahooに投げる記号」であり「通貨判定のキー」でもある。
     */
    fun normalize(ticker: String?, market: String?): String {
        val t = ticker?.trim().orEmpty()
        if (t.isEmpty()) return t
        if (t.uppercase().endsWith(".T")) return t.uppercase()   // 既に正規形（例: 1540.T）
        val isJpMarket = market?.trim()?.startsWith(JP_MARKET_PREFIX) == true
        // 市場が東証、または4桁の数字コード＝日本株とみなして .T を付ける
        if (isJpMarket || Regex("""^\d{4}$""").matches(t)) return "$t.T"
        // 米国のクラス株はYahooだとハイフン表記。Notionは "BF.B" だがYahooは "BF-B" でないと
        // 価格が取れない（実測: BF.B は価格が空欄のままだった）。
        if (Regex("""^[A-Za-z]+\.[A-Za-z]$""").matches(t)) return t.uppercase().replace('.', '-')
        return t.uppercase()
    }

    /** 正規形が日本株か（円建てか）。normalize済みの文字列を渡すこと。 */
    fun isJp(ticker: String?): Boolean = ticker?.uppercase()?.endsWith(".T") == true

    /** 表示用の短い記号（日本株は .T を落として4桁コードだけ見せる）。 */
    fun display(ticker: String?): String {
        val t = ticker?.trim().orEmpty()
        return if (isJp(t)) t.dropLast(2) else t
    }

    /**
     * 通知タイトル・本文で使う「銘柄名（ティッカー）」の表示文字列を作る。
     *
     * 【なぜ必要か】Notionの「銘柄名」列は入力者によって表記が揃っておらず、
     * 既にティッカーを含む行がある（例: 銘柄名 "アップル（AAPL）"）。
     * それに対して機械的に "（ティッカー）" を足すと
     * 「アップル（AAPL）（AAPL）に警告」と二重表示になる。
     * 組み立て方を通知ごとに書くとまたズレるので、ここ1か所に集約する（DRY）。
     *
     * 名前に既にティッカーが出ていればそのまま、出ていなければ "（記号）" を足す。
     */
    fun label(name: String?, ticker: String?): String {
        val n = name?.trim().orEmpty()
        val t = display(ticker)
        if (t.isEmpty()) return n
        if (n.isEmpty()) return t
        return if (containsSymbol(n, t)) n else "$n（$t）"
    }

    /**
     * 銘柄名の中にティッカーが（表記ゆれ込みで）現れているか。
     * 日本株は名前側が "1540" とも "1540.T" とも書かれうる。
     * 米国のクラス株は Notion "BF.B" ／ 正規形 "BF-B" と区切り文字が揺れる。
     */
    private fun containsSymbol(name: String, symbol: String): Boolean {
        val upper = name.uppercase()
        val variants = linkedSetOf(
            symbol.uppercase(),
            symbol.uppercase().replace('-', '.'),
            symbol.uppercase().replace('.', '-')
        )
        return variants.any { containsAsWord(upper, it) }
    }

    /**
     * 「独立した記号として」含まれているかを見る。
     * 単純な contains だと "T"（AT&T）が名前の中のどのTにも当たってしまうため、
     * 前後の文字が英数字でないことを条件にする。
     */
    private fun containsAsWord(haystack: String, needle: String): Boolean {
        if (needle.isEmpty()) return false
        var from = 0
        while (true) {
            val i = haystack.indexOf(needle, from)
            if (i < 0) return false
            val before = i - 1
            val after = i + needle.length
            val okBefore = before < 0 || !haystack[before].isLetterOrDigit()
            val okAfter = after >= haystack.length || !haystack[after].isLetterOrDigit()
            if (okBefore && okAfter) return true
            from = i + 1
        }
    }
}
