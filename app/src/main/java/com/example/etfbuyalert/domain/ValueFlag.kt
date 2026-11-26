package com.example.etfbuyalert.domain

import com.example.etfbuyalert.data.model.EtfState

// 割安の加点（v1.37・2026-11-26）の表示の単一の真実の源。
//
// 【目的】利用者の要望「割安の加点（EV÷営業利益の安い2割・ネットネット）に入った銘柄は買い時アラートにのるように」。
// 判定は PC の value-screen/notify_value.py が月次の一覧から行い、Notion「割安加点」列に書く。
// アプリは列の文字をそのまま出すだけ（値の一覧を持ち直さない＝PC と2か所に持つとズレる）。
// 【決まり】加点は精査で比べるときの優先順位であって買う合図ではない（buy-review §②C）。
// だから割安専用の通知は作らず、ラインで鳴った通知に1行添えるのと、監視カードの印だけにする。
object ValueFlag {

    // 「要確認(分割)」「要確認(税引前)」は当たってはいるが数字がずれているおそれ（色を弱める合図）
    fun isCaution(st: EtfState): Boolean = st.valueFlag.orEmpty().startsWith("要確認")

    // 監視カードに出す短い札（当たらなければ null）。カードの2段目は狭いので短く縮める
    // （両方→「割安 両方」・ネットネット→「割安 NN」・要確認(…)→「割安？」。全文は通知文の1行に出る）
    fun chip(st: EtfState): String? {
        val f = st.valueFlag?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            f.startsWith("要確認") -> "割安？"
            f == "ネットネット" -> "割安 NN"
            else -> "割安 $f"
        }
    }

    private const val NOTE_HEAD = "💹 割安の加点："

    // 通知文の末尾に足す1行（当たらなければ空文字）。例「\n💹 割安の加点：両方（2026-11-01 の一覧）」
    fun noteLine(st: EtfState): String {
        val f = st.valueFlag?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
        val day = st.valueFlagDate?.let { "（$it の一覧）" }.orEmpty()
        return "\n$NOTE_HEAD$f$day"
    }

    // 2件以上のまとめ通知は題名しか並べないので、本文に加点の行がある項目には短い印を添える
    fun bundleMark(message: String): String = if (NOTE_HEAD in message) " 💹割安" else ""
}
