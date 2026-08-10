package com.example.etfbuyalert.domain

// Notionの「ニュース警告」列（PC側の fired_news_check.py が毎日書き込む）を解釈する。
//
// 文面の形式（Python側と揃っている。変えるときは両方直すこと）:
//   [2026-08-10] 【要注意】8/07 1日で-16.6%の急落… ｜ （参考）8/06 決算短信…
//   [2026-08-10] 直近14日に該当なし（見出し12件を確認）
//
// 通知の判定は「【要注意】部分の本文」だけで行う。
// 先頭の日付はジョブが走るたびに変わるので、日付込みの全文で比較すると
// 内容が同じでも毎日「変わった」と誤判定して通知が鳴り続ける。
object NewsWarning {

    // 【要注意】と（参考）の区切り。Python側の " ｜ " と同じ文字を使う
    private const val SEPARATOR = " ｜ "
    private const val CRITICAL_MARK = "【要注意】"

    /**
     * 警告文から【要注意】部分の本文だけを取り出す（通知の比較キー＝シグネチャ）。
     * 【要注意】が無い（該当なし・（参考）のみ・空）の場合は空文字を返す＝通知しない。
     */
    fun criticalPart(warning: String?): String {
        if (warning.isNullOrBlank()) return ""
        val start = warning.indexOf(CRITICAL_MARK)
        if (start < 0) return ""
        val body = warning.substring(start + CRITICAL_MARK.length)
        val end = body.indexOf(SEPARATOR)
        return (if (end >= 0) body.substring(0, end) else body).trim()
    }

    /** カード表示用。【要注意】本文があればそれを返す（無ければnull＝表示しない） */
    fun displayText(warning: String?): String? =
        criticalPart(warning).takeIf { it.isNotEmpty() }
}
