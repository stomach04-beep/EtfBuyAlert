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

    // --- 週足RSI過熱利確の閾値（単一の真実の源。表示側＝WatchListScreenもここを参照）---
    // レバレッジETF等の利確規律「週足RSI(14)>75で1/3利確 → >80でさらに1/3」
    // （バックテスト検証済みルール）。対象はNotionの「RSI利確監視」checkbox=ONの銘柄のみ。
    const val RSI_TAKE1 = 75.0   // 過熱利確①：1/3利確を検討
    const val RSI_TAKE2 = 80.0   // 過熱利確②：さらに1/3利確を検討
    // RSIの再arm幅：閾値-2を下回ったら再arm（例: 75の再armは73未満）。
    // 価格ラインのREARM=1%と同じ発想のヒステリシスで、閾値ちょうど近辺の連発を防ぐ。
    const val RSI_REARM = 2.0

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
    fun evaluate(state: EtfState, t: Toggles, now: Long = System.currentTimeMillis()): Pair<EtfState, List<Alert>> {
        // 鮮度ガード：7日超の古い価格では判定しない（古い値でのライン到達誤通知を防ぐ）。
        // armedフラグ・lastZoneは現状維持し、取得が復活したら次回から通常判定に戻る。
        if (Freshness.isInvalid(state.asOf, now)) return state to emptyList()
        val price = state.price ?: return state to emptyList() // 価格未取得なら判定不可
        val alerts = mutableListOf<Alert>()
        var s = state

        // --- 押し目（下抜けで通知）---
        s = s.copy(dipArmed = evalLine(
            fire = state.dipPrice != null && price <= state.dipPrice,
            release = state.dipPrice == null || price > state.dipPrice * (1 + REARM),
            armed = state.dipArmed, enabled = t.dip
        ) {
            alerts += Alert("押し目", "🟢 ${Symbol.display(state.ticker)} 押し目ライン到達",
                "${state.name}\n現在値 ${Money.format(state.ticker, price)}（押し目 ${Money.format(state.ticker, state.dipPrice)} 以下）。" +
                    reason(state, "買い時の押し目ゾーンです。"))
        })

        // --- 深押し（さらに下・買い増し）---
        s = s.copy(deepArmed = evalLine(
            fire = state.deepDipPrice != null && price <= state.deepDipPrice,
            release = state.deepDipPrice == null || price > state.deepDipPrice * (1 + REARM),
            armed = state.deepArmed, enabled = t.dip
        ) {
            alerts += Alert("深押し", "🟢 ${Symbol.display(state.ticker)} 深押しライン到達",
                "${state.name}\n現在値 ${Money.format(state.ticker, price)}（深押し ${Money.format(state.ticker, state.deepDipPrice)} 以下）。" +
                    reason(state, "買い増し検討ゾーンです。"))
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

        // --- 週足RSI過熱利確（「RSI利確監視」=ONの銘柄のみ。利確規律のシグナル）---
        // RSIは週足の確定終値から計算した値（EtfState.weeklyRsi にキャッシュ済み）。
        // 判定・再armとも既存のラインと同じ armed フラグ方式。RSI未計算(null)なら現状維持。
        // 初回同期で既に閾値超えの場合も、既存ライン系と同じくその場で1回通知する
        // （armed=false始まりのfire即通知＝押し目ライン等の初回挙動に準拠）。
        if (state.rsiWatch) {
            val rsi = state.weeklyRsi
            // 過熱利確①：RSI>75 で1/3利確を検討
            s = s.copy(rsiTake1Armed = evalLine(
                fire = rsi != null && rsi > RSI_TAKE1,
                release = rsi != null && rsi < RSI_TAKE1 - RSI_REARM,
                armed = state.rsiTake1Armed, enabled = true
            ) {
                alerts += Alert("過熱利確①", "${Symbol.display(state.ticker)} 週足RSI過熱（利確①）",
                    "${state.name}\n週足RSI ${String.format("%.1f", rsi)} が ${RSI_TAKE1.toInt()} を超過。" +
                        "1/3利確を検討（週足RSI利確規律）。")
            })
            // 過熱利確②：RSI>80 でさらに1/3利確を検討
            s = s.copy(rsiTake2Armed = evalLine(
                fire = rsi != null && rsi > RSI_TAKE2,
                release = rsi != null && rsi < RSI_TAKE2 - RSI_REARM,
                armed = state.rsiTake2Armed, enabled = true
            ) {
                alerts += Alert("過熱利確②", "${Symbol.display(state.ticker)} 週足RSI過熱（利確②）",
                    "${state.name}\n週足RSI ${String.format("%.1f", rsi)} が ${RSI_TAKE2.toInt()} を超過。" +
                        "さらに1/3利確を検討（週足RSI利確規律）。")
            })
        }

        // --- 同時発火の整理 ---
        // 一気に深押しラインまで下げた場合、「押し目」と「深押し」が同時に発火して
        // 内容が重複する。より深い「深押し」だけ残す。
        if (alerts.any { it.category == "深押し" }) {
            alerts.removeAll { it.category == "押し目" }
        }
        // RSIも同様：一気に80超えまで上げた場合は①②が同時発火するので、より強い「②」だけ残す。
        if (alerts.any { it.category == "過熱利確②" }) {
            alerts.removeAll { it.category == "過熱利確①" }
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

    /**
     * 通知文の締めの一言。ラインの根拠が方式ごとに違うので、何を意味する到達なのかを添える。
     *   ADP型 … 配当王・貴族の個別株プール。ラインは「相対利回り＋高値からの下落率」から逆算した価格で、
     *           到達＝質は落ちていないのに叩き売られている状態（＝待ち構えていた場面）。
     *   MA200 … ETFの200日移動平均ベース。従来どおりの押し目。
     */
    private fun reason(state: EtfState, fallback: String): String = when (state.lineMethod) {
        AssetKind.METHOD_ADP ->
            "質ゲート通過の増配株が、平常より高い利回り水準まで売られています（ADP型の待ち構え）。減配・業績悪化が無いかだけ確認を。"
        else -> fallback
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

    fun currentZone(state: EtfState, now: Long = System.currentTimeMillis()): Zone {
        val price = state.price ?: return Zone.NODATA
        // 鮮度ガード：7日超の古い価格ではゾーンを判定しない（バッジは「—」になる）
        if (Freshness.isInvalid(state.asOf, now)) return Zone.NODATA
        if (state.purchased && state.stopLossPrice != null && price < state.stopLossPrice) return Zone.STOP
        if (state.deepDipPrice != null && price <= state.deepDipPrice) return Zone.DEEP
        if (state.dipPrice != null && price <= state.dipPrice) return Zone.DIP
        if (state.breakoutPrice != null && price >= state.breakoutPrice) return Zone.BREAKOUT
        return Zone.NORMAL
    }

    /**
     * 押し目ラインまであと何%か（一覧の並べ替えと表示の単一の真実の源＝DRY）。
     *   0未満 … 既に押し目を下回っている（＝発火中。数値が小さいほど深い）
     *   0以上 … あと何%下がれば押し目に届くか
     *   null  … 価格かラインが無くて判定できない（並べ替えでは最後に置く）
     * 監視対象が90行規模になり「今見るべき銘柄」が埋もれるため、この距離で並べる。
     */
    fun dipGapPercent(state: EtfState, now: Long = System.currentTimeMillis()): Double? {
        val price = state.price ?: return null
        // 鮮度ガード：7日超の古い価格では距離を出さない（並べ替えでは末尾に置かれる）
        if (Freshness.isInvalid(state.asOf, now)) return null
        val dip = state.dipPrice ?: return null
        if (dip <= 0) return null
        return (price - dip) / dip * 100.0
    }

    /** 押し目（または深押し）に到達しているか＝発火中。タブの絞り込みに使う。 */
    fun isFired(state: EtfState): Boolean =
        currentZone(state).let { it == Zone.DIP || it == Zone.DEEP }

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
