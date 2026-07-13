package com.example.etfbuyalert.domain

import com.example.etfbuyalert.data.model.EtfState

// 買い時アラートの判定エンジン（純粋ロジック）。
// 「現在価格」と「Notion同期の各ライン」を比べ、ラインを跨いだ瞬間にだけ
// 通知を出す（armedフラグで重複防止）。UI用のゾーン判定もここに集約（DRY）。
object AlertEngine {

    // 再arm・ゾーン退出のヒステリシス幅（ラインの1%）。
    // ラインちょうど近辺で価格が小刻みに上下すると、跨ぐたびに
    // 「到達→解除→再到達」で通知が連発してしまう。到達判定はライン即時のまま、
    // 解除（再arm）だけは「ラインから1%離れて戻る」ことを条件にして連発を防ぐ。
    private const val REARM = 0.01

    // 発火する1件のアラート
    data class Alert(
        val category: String,  // 押し目 / 深押し / 順張り / 損切り
        val title: String,
        val message: String
    )

    // 通知ON/OFF（設定から渡す）
    data class Toggles(
        val dip: Boolean,
        val stop: Boolean,
        val breakout: Boolean,
        val zoneChange: Boolean
    )

    // 価格チェック1回ぶんの判定。
    // 戻り値: 更新後の状態（armedフラグ更新済み）と、出すべきアラート一覧。
    fun evaluate(state: EtfState, t: Toggles): Pair<EtfState, List<Alert>> {
        val price = state.price ?: return state to emptyList() // 価格未取得なら判定不可
        val alerts = mutableListOf<Alert>()
        var s = state

        // --- 押し目（下抜けで通知）---
        s = s.copy(dipArmed = evalLine(
            fire = state.dipPrice != null && price <= state.dipPrice,
            release = state.dipPrice == null || price > state.dipPrice * (1 + REARM),
            armed = state.dipArmed, enabled = t.dip
        ) {
            alerts += Alert("押し目", "🟢 ${state.ticker} 押し目ライン到達",
                "${state.name}\n現在値 ${Money.format(state.ticker, price)}（押し目 ${Money.format(state.ticker, state.dipPrice)} 以下）。買い時の押し目ゾーンです。")
        })

        // --- 深押し（さらに下・買い増し）---
        s = s.copy(deepArmed = evalLine(
            fire = state.deepDipPrice != null && price <= state.deepDipPrice,
            release = state.deepDipPrice == null || price > state.deepDipPrice * (1 + REARM),
            armed = state.deepArmed, enabled = t.dip
        ) {
            alerts += Alert("深押し", "🟢 ${state.ticker} 深押しライン到達",
                "${state.name}\n現在値 ${Money.format(state.ticker, price)}（深押し ${Money.format(state.ticker, state.deepDipPrice)} 以下）。買い増し検討ゾーンです。")
        })

        // --- 順張り（上抜けで通知）---
        s = s.copy(breakoutArmed = evalLine(
            fire = state.breakoutPrice != null && price >= state.breakoutPrice,
            release = state.breakoutPrice == null || price < state.breakoutPrice * (1 - REARM),
            armed = state.breakoutArmed, enabled = t.breakout
        ) {
            alerts += Alert("順張り", "🔵 ${state.ticker} 順張りライン突破",
                "${state.name}\n現在値 ${Money.format(state.ticker, price)}（順張り ${Money.format(state.ticker, state.breakoutPrice)} 以上）。上抜け・追加検討ゾーンです。")
        })

        // --- 損切り（保有中のみ・価格が損切りライン割れ）---
        s = s.copy(stopArmed = evalLine(
            fire = state.purchased && state.stopLossPrice != null && price < state.stopLossPrice,
            release = !state.purchased || state.stopLossPrice == null || price >= state.stopLossPrice * (1 + REARM),
            armed = state.stopArmed, enabled = t.stop
        ) {
            alerts += Alert("損切り", "🔴 ${state.ticker} 損切りライン割れ",
                "${state.name}\n現在値 ${Money.format(state.ticker, price)}（損切り ${Money.format(state.ticker, state.stopLossPrice)} 割れ）。撤退・損切りの検討を。")
        })

        // --- 同時発火の整理 ---
        // 一気に深押しラインまで下げた場合、「押し目」と「深押し」が同時に発火して
        // 内容が重複する。より深い「深押し」だけ残す。
        if (alerts.any { it.category == "深押し" }) {
            alerts.removeAll { it.category == "押し目" }
        }

        // --- ステージ(ゾーン)変化 ---
        // 現在のゾーンが前回と変わったら、その遷移を1回通知する。
        // 初回(lastZone未設定)やデータ無しでは通知せず記録だけ更新する。
        // 退出側に1%のヒステリシスをかけ、ライン境界での行き来による連発を防ぐ。
        // ライン到達/割れの通知が同時に出ているときは内容が重複するので出さない（記録だけ更新）。
        val prevZone = zoneOf(state.lastZone)
        val newZone = zoneForNotify(s, prevZone)
        if (newZone != Zone.NODATA) {
            if (prevZone != null && prevZone != newZone && t.zoneChange && alerts.isEmpty()) {
                alerts += Alert(
                    "ステージ変化",
                    "🔄 ${state.ticker} ステージ変化: ${prevZone.label} → ${newZone.label}",
                    "${state.name}\n現在値 ${Money.format(state.ticker, price)}\n${prevZone.label} → ${newZone.label} にステージが変わりました。"
                )
            }
            s = s.copy(lastZone = newZone.name)
        }

        return s to alerts
    }

    // 保存済みのゾーン名(Zone.name)を Zone へ。未設定・未知の値は null。
    private fun zoneOf(name: String): Zone? =
        if (name.isEmpty()) null else try { Zone.valueOf(name) } catch (e: Exception) { null }

    // 1ラインぶんの判定。
    // fire=true（ライン到達）かつ未通知 かつ 通知ON → onFire()して armed=true を返す。
    // release=true（ラインから1%以上離れて戻った）→ armed=false（次回また通知できる）。
    // どちらでもない（ラインとバッファの間）→ 現状維持（境界の小刻みな上下で連発させない）。
    private inline fun evalLine(fire: Boolean, release: Boolean, armed: Boolean, enabled: Boolean, onFire: () -> Unit): Boolean {
        return when {
            fire -> if (!armed && enabled) { onFire(); true } else armed
            release -> false
            else -> armed
        }
    }

    // UI用：現在のゾーン（バッジ表示）。深刻な順に判定。
    enum class Zone(val label: String) {
        STOP("損切り"), DEEP("深押し"), DIP("押し目"), BREAKOUT("順張り"), NORMAL("通常"), NODATA("—")
    }

    fun currentZone(state: EtfState): Zone {
        val price = state.price ?: return Zone.NODATA
        if (state.purchased && state.stopLossPrice != null && price < state.stopLossPrice) return Zone.STOP
        if (state.deepDipPrice != null && price <= state.deepDipPrice) return Zone.DEEP
        if (state.dipPrice != null && price <= state.dipPrice) return Zone.DIP
        if (state.breakoutPrice != null && price >= state.breakoutPrice) return Zone.BREAKOUT
        return Zone.NORMAL
    }

    // 通知・記録用のゾーン判定（退出側に1%ヒステリシス）。
    // 悪化方向（より深刻なゾーンへ落ちる）は即時反映。
    // 改善方向は、前ゾーンの境界ラインから1%離れるまで前ゾーンを維持して、
    // 境界ぴったりの価格で「押し目⇄通常」の変化通知が連発するのを防ぐ。
    // ※UIバッジの currentZone はヒステリシス無しの生判定のまま（表示は常に最新の実態）。
    private fun zoneForNotify(state: EtfState, prev: Zone?): Zone {
        val raw = currentZone(state)
        if (prev == null || prev == raw || raw == Zone.NODATA) return raw
        if (raw.ordinal < prev.ordinal) return raw  // 悪化（STOPが最も深刻）は遅らせない
        val price = state.price ?: return raw
        val stay = when (prev) {
            Zone.STOP -> state.purchased && state.stopLossPrice != null && price < state.stopLossPrice * (1 + REARM)
            Zone.DEEP -> state.deepDipPrice != null && price <= state.deepDipPrice * (1 + REARM)
            Zone.DIP -> state.dipPrice != null && price <= state.dipPrice * (1 + REARM)
            Zone.BREAKOUT -> state.breakoutPrice != null && price >= state.breakoutPrice * (1 - REARM)
            else -> false
        }
        return if (stay) prev else raw
    }
}
