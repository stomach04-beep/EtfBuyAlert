package com.example.etfbuyalert.domain

/**
 * 「保有中か」「通知してよい行か」の判定の単一の真実の源（v1.34・2026-11-12 総点検）。
 *
 * なぜ要るか:
 *   v1.33 までは「購入日が入っている＝保有中」だけで判定していた。ところが Notion の
 *   「選定ステータス」が保有中なのに購入日が空の行が19行あり（NVDA・AAPL・RTX・VLO など）、
 *   この行は損切り通知・売り時サイン・健全性チェックが3つとも黙っていた。
 *   実例: VLO は 2026-11-11 終値 363.71 で損切り 370 を割っていたのに通知が出なかった。
 *   また、選定ステータスを読んでいなかったので「見送り」「資料」の行でも、
 *   アプリ監視が ON なら押し目到達が鳴っていた（買わないと決めた行の通知＝雑音）。
 *
 * 選定ステータスの値は PC 側 watchlist-triage/notion_common.py の STATUS_OPTIONS と同じ。
 * 値を変えるときは両方を同時に直す（片方だけ変えると黙ってすべて「保有でない」になる）。
 */
object Holding {
    /** Notion「選定ステータス」の列名 */
    const val PROP_STATUS = "選定ステータス"

    const val STATUS_HELD = "保有中"
    /** 通知も一覧表示もしない選定ステータス（買わないと決めた行・研究メモ） */
    val SILENT_STATUSES = setOf("見送り", "資料")

    /**
     * 保有中か。購入日あり、または選定ステータスが「保有中」なら保有とみなす。
     * どちらか片方だけ入っている行（入力漏れ）でも損切り・売り時を鳴らすため「または」にする。
     */
    fun isHeld(hasPurchaseDate: Boolean, status: String?): Boolean =
        hasPurchaseDate || status?.trim() == STATUS_HELD

    /** 監視から外す行か（見送り・資料）。空欄・未知の値は外さない＝安全側（鳴る側） */
    fun isSilenced(status: String?): Boolean =
        status?.trim() in SILENT_STATUSES
}
