package com.example.etfbuyalert.domain

import com.example.etfbuyalert.data.model.EtfState

// 買い時アラートの判定エンジン（純粋ロジック）。
// 「現在価格」と「Notion同期の各ライン」を比べ、ラインを跨いだ瞬間にだけ
// 通知を出す（armedフラグで重複防止）。UI用のゾーン判定もここに集約（DRY）。
object AlertEngine {

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
            cond = state.dipPrice != null && price <= state.dipPrice,
            armed = state.dipArmed, enabled = t.dip
        ) {
            alerts += Alert("押し目", "🟢 ${state.ticker} 押し目ライン到達",
                "${state.name}\n現在値 ${Money.format(state.ticker, price)}（押し目 ${Money.format(state.ticker, state.dipPrice)} 以下）。買い時の押し目ゾーンです。")
        })

        // --- 深押し（さらに下・買い増し）---
        s = s.copy(deepArmed = evalLine(
            cond = state.deepDipPrice != null && price <= state.deepDipPrice,
            armed = state.deepArmed, enabled = t.dip
        ) {
            alerts += Alert("深押し", "🟢 ${state.ticker} 深押しライン到達",
                "${state.name}\n現在値 ${Money.format(state.ticker, price)}（深押し ${Money.format(state.ticker, state.deepDipPrice)} 以下）。買い増し検討ゾーンです。")
        })

        // --- 順張り（上抜けで通知）---
        s = s.copy(breakoutArmed = evalLine(
            cond = state.breakoutPrice != null && price >= state.breakoutPrice,
            armed = state.breakoutArmed, enabled = t.breakout
        ) {
            alerts += Alert("順張り", "🔵 ${state.ticker} 順張りライン突破",
                "${state.name}\n現在値 ${Money.format(state.ticker, price)}（順張り ${Money.format(state.ticker, state.breakoutPrice)} 以上）。上抜け・追加検討ゾーンです。")
        })

        // --- 損切り（保有中のみ・価格が損切りライン割れ）---
        s = s.copy(stopArmed = evalLine(
            cond = state.purchased && state.stopLossPrice != null && price < state.stopLossPrice,
            armed = state.stopArmed, enabled = t.stop
        ) {
            alerts += Alert("損切り", "🔴 ${state.ticker} 損切りライン割れ",
                "${state.name}\n現在値 ${Money.format(state.ticker, price)}（損切り ${Money.format(state.ticker, state.stopLossPrice)} 割れ）。撤退・損切りの検討を。")
        })

        // --- ステージ(ゾーン)変化 ---
        // 現在のゾーンが前回と変わったら、その遷移を1回通知する。
        // 初回(lastZone未設定)やデータ無しでは通知せず記録だけ更新する。
        val newZone = currentZone(s)
        if (newZone != Zone.NODATA) {
            val prev = state.lastZone
            if (prev.isNotEmpty() && prev != newZone.name) {
                if (t.zoneChange) {
                    val oldLabel = zoneLabelOf(prev)
                    alerts += Alert(
                        "ステージ変化",
                        "🔄 ${state.ticker} ステージ変化: $oldLabel → ${newZone.label}",
                        "${state.name}\n現在値 ${Money.format(state.ticker, price)}\n$oldLabel → ${newZone.label} にステージが変わりました。"
                    )
                }
            }
            s = s.copy(lastZone = newZone.name)
        }

        return s to alerts
    }

    // 保存済みのゾーン名(Zone.name)を日本語ラベルへ。未知の値は「—」。
    private fun zoneLabelOf(name: String): String =
        try { Zone.valueOf(name).label } catch (e: Exception) { "—" }

    // 1ラインぶんの判定。
    // cond=true かつ未通知 かつ 通知ON → onFire()して armed=true を返す。
    // cond=false → armed=false（ゾーンを外れたので次回また通知できる）。
    private inline fun evalLine(cond: Boolean, armed: Boolean, enabled: Boolean, onFire: () -> Unit): Boolean {
        return if (cond) {
            if (!armed && enabled) { onFire(); true } else armed
        } else {
            false
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
}
