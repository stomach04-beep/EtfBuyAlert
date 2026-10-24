package com.example.etfbuyalert.ui.screen.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.etfbuyalert.data.model.ChartSeries
import com.example.etfbuyalert.data.model.EtfState
import com.example.etfbuyalert.domain.AlertEngine
import com.example.etfbuyalert.domain.Freshness
import com.example.etfbuyalert.domain.Money
import com.example.etfbuyalert.ui.changeColor
import com.example.etfbuyalert.ui.kit.Fmt
import com.example.etfbuyalert.ui.kit.HeaderDivider
import com.example.etfbuyalert.ui.kit.Panel
import com.example.etfbuyalert.ui.kit.Sem
import com.example.etfbuyalert.ui.kit.Tone
import com.example.etfbuyalert.ui.kit.num
import java.time.Instant
import java.time.ZoneId

// ラインの色（チャートと凡例で共通＝DRY）。
// 2026-10-23 表示の決まり UiKit：直書きの緑・赤・青をやめ、意味の色に寄せる。
//  ・押し目／深押し＝買いの合図なので primary 1色（深押しは少し薄くして区別。線の横の「押」「深」でも分かる）
//  ・損切り＝危険（赤はここだけ）／順張り＝情報（青）／価格の線＝本文色
// 明暗の切替に追従させるため、Composable の中で引いて描画側へ渡す
private data class LineColors(
    val dip: Color,
    val deep: Color,
    val breakout: Color,
    val stop: Color,
    val price: Color,
    val axisLabel: Color,
)

@Composable
private fun lineColors(): LineColors {
    val primary = MaterialTheme.colorScheme.primary
    return LineColors(
        dip = primary,
        deep = primary.copy(alpha = 0.6f),
        breakout = Sem.fg(Tone.INFO),
        stop = Sem.fg(Tone.CRIT),
        price = MaterialTheme.colorScheme.onSurface,
        axisLabel = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EtfDetailScreen(
    state: EtfState?,
    chart: ChartSeries?,
    chartLoading: Boolean,
    range: String,
    onRangeChange: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    if (state == null) {
        // 想定外（一覧から消えた等）。戻る。
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val colors = lineColors()

    Scaffold(
        topBar = {
            // 2026-10-23 表示の決まり UiKit：見出しは背景と同じ色の1段（色の帯にしない）。
            // タイトルは1行に収める（長いと2行に折り返して時計の直下へ押し上げられる）
            // 見出しの下に境目の線を1本引く（2026-10-24 ユーザー指示）
            Column {
                TopAppBar(
                    title = {
                        Text(
                            "${state.ticker} チャート",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
                HeaderDivider()
            }
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 銘柄名・現在値
            Text(state.name, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 鮮度ガード：7日超の古い価格は「現在値」として出さない（判定・文言はdomain.Freshnessが単一の真実の源）
            if (Freshness.isInvalid(state.asOf)) {
                // 赤は損切り専用にするため警戒の色（2026-10-23 表示の決まり UiKit）
                Text(Freshness.INVALID_TEXT, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = Sem.fg(Tone.WARN))
            } else Row(verticalAlignment = Alignment.Bottom) {
                // この画面の主役の数字（等幅・桁区切りは Money が単一定義）
                Text(Money.format(state.ticker, state.price), fontWeight = FontWeight.Bold, fontSize = 24.sp,
                    style = TextStyle.Default.num())
                // 3日超は「（◯日前の値）」を添えて、最新値と誤解させない
                val staleNote = Freshness.staleSuffix(state.asOf)
                if (staleNote.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(staleNote, fontSize = 12.sp, color = Sem.fg(Tone.WARN),
                        modifier = Modifier.padding(bottom = 3.dp))
                }
                Spacer(Modifier.width(8.dp))
                if (state.previousClose != null && state.price != null && state.previousClose != 0.0) {
                    val diff = (state.price - state.previousClose) / state.previousClose * 100.0
                    // 前日比は ▲▼ の記号と色（プラス＝緑・マイナス＝赤。色は changeColor の1か所で決める）。
                    // Fmt.change は 0 のとき「変わらず」なので % を付けない
                    val delta = Fmt.change(diff, 2)
                    Text("前日比 " + if (diff == 0.0) delta else "$delta%", fontSize = 12.sp,
                        style = TextStyle.Default.num(),
                        color = changeColor(diff),
                        modifier = Modifier.padding(bottom = 3.dp))
                }
            }

            // 期間切替（上場来＝Yahooのmax。長期は週足で表示）
            val ranges = listOf("3mo" to "3ヶ月", "6mo" to "6ヶ月", "1y" to "1年", "5y" to "5年", "max" to "上場来")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ranges.forEach { (value, label) ->
                    FilterChip(selected = range == value, onClick = { onRangeChange(value) },
                        label = { Text(label) })
                }
            }

            // チャート本体（2026-10-23 表示の決まり UiKit：Card の1段上の地をやめ、影・枠なしの面 Panel に）
            Panel(modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(280.dp).padding(8.dp), contentAlignment = Alignment.Center) {
                    when {
                        chart != null && chart.points.size >= 2 ->
                            PriceChart(chart, state, colors)
                        chartLoading ->
                            CircularProgressIndicator()
                        else ->
                            Text("チャートを取得できませんでした", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 凡例
            Legend(state, colors)

            // ラインの値一覧（面 Panel で区切る）
            Panel(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 通貨判定のためティッカーを引き回す
                    LineValue(state.ticker, "買い時(押し目)", state.dipPrice, colors.dip)
                    LineValue(state.ticker, "買い増し(深押し)", state.deepDipPrice, colors.deep)
                    LineValue(state.ticker, "順張り(上抜け)", state.breakoutPrice, colors.breakout)
                    LineValue(state.ticker, "損切り", state.stopLossPrice, colors.stop)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// 価格チャート（折れ線＋各ラインの水平線）
@Composable
private fun PriceChart(chart: ChartSeries, state: EtfState, colors: LineColors) {
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    // 軸ラベルの色（直書きの LTGRAY だとライトテーマで読めないため補足色に）
    val axisArgb = colors.axisLabel.toArgb()

    Canvas(Modifier.fillMaxSize()) {
        val pts = chart.points
        val closes = pts.map { it.close }
        // Y範囲：短期は売買ラインも含めて全部収める。長期(5年・上場来)はライン値を外し、
        // 価格カーブが潰れないようにする（短期ラインは長期では意味が薄いため）。
        val longRange = chart.range == "5y" || chart.range == "max"
        val candidates = ArrayList<Double>(closes)
        if (!longRange) {
            state.dipPrice?.let { candidates.add(it) }
            state.deepDipPrice?.let { candidates.add(it) }
            state.breakoutPrice?.let { candidates.add(it) }
            state.stopLossPrice?.let { candidates.add(it) }
        }
        var minV = candidates.minOrNull() ?: 0.0
        var maxV = candidates.maxOrNull() ?: 1.0
        if (maxV == minV) { maxV += 1; minV -= 1 }
        val padV = (maxV - minV) * 0.06
        minV -= padV; maxV += padV

        val leftPad = 0f
        val rightPad = 96f   // 右側にライン値ラベルの余白
        val topPad = 8f
        val bottomPad = 22f  // 下に日付ラベル
        val w = size.width - leftPad - rightPad
        val h = size.height - topPad - bottomPad

        fun x(i: Int) = leftPad + w * i / (pts.size - 1).coerceAtLeast(1)
        fun y(v: Double) = topPad + (h * (maxV - v) / (maxV - minV)).toFloat()

        // 水平の補助線（最大・最小）
        drawLine(gridColor, Offset(leftPad, topPad), Offset(leftPad + w, topPad), 1f)
        drawLine(gridColor, Offset(leftPad, topPad + h), Offset(leftPad + w, topPad + h), 1f)

        // 各ラインの水平線（破線）
        val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
        fun hline(v: Double?, c: Color, label: String) {
            if (v == null || v < minV || v > maxV) return
            val yy = y(v)
            drawLine(c, Offset(leftPad, yy), Offset(leftPad + w, yy), 2f, pathEffect = dash)
            drawContext.canvas.nativeCanvas.apply {
                val p = android.graphics.Paint().apply {
                    color = c.toArgb()
                    textSize = 26f; isAntiAlias = true
                }
                drawText(label, leftPad + w + 6f, yy + 9f, p)
            }
        }
        hline(state.dipPrice, colors.dip, "押" )
        hline(state.deepDipPrice, colors.deep, "深")
        hline(state.breakoutPrice, colors.breakout, "順")
        hline(state.stopLossPrice, colors.stop, "損")

        // 価格の折れ線
        val path = Path()
        pts.forEachIndexed { i, pt ->
            val px = x(i); val py = y(pt.close)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, colors.price, style = Stroke(width = 3f))

        // 直近の点に丸
        val lastX = x(pts.size - 1); val lastY = y(pts.last().close)
        drawCircle(colors.price, 6f, Offset(lastX, lastY))

        // 軸ラベル（最大・最小・日付）
        drawContext.canvas.nativeCanvas.apply {
            val tp = android.graphics.Paint().apply {
                color = axisArgb; textSize = 24f; isAntiAlias = true
            }
            // Y軸ラベルも通貨対応（円建ては桁が大きいがそのまま表示）
            drawText(Money.format(state.ticker, maxV), leftPad + 4f, topPad + 22f, tp)
            drawText(Money.format(state.ticker, minV), leftPad + 4f, topPad + h - 6f, tp)
            drawText(fmtDate(pts.first().t), leftPad + 4f, size.height - 4f, tp)
            val endLabel = fmtDate(pts.last().t)
            drawText(endLabel, leftPad + w - tp.measureText(endLabel), size.height - 4f, tp)
        }
    }
}

@Composable
private fun Legend(state: EtfState, colors: LineColors) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendDot("価格", colors.price)
        if (state.dipPrice != null) LegendDot("押し目", colors.dip)
        if (state.deepDipPrice != null) LegendDot("深押し", colors.deep)
        if (state.breakoutPrice != null) LegendDot("順張り", colors.breakout)
        if (state.stopLossPrice != null) LegendDot("損切り", colors.stop)
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).padding(0.dp)) {
            Canvas(Modifier.fillMaxSize()) { drawCircle(color) }
        }
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp)
    }
}

// ticker は通貨（円/ドル）判定用
@Composable
private fun LineValue(ticker: String, label: String, v: Double?, color: Color) {
    // 左のラベル側に weight を付け、右の値（右寄せ・等幅数字）が押し出されないようにする
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp)) { Canvas(Modifier.fillMaxSize()) { drawCircle(color) } }
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(Money.format(ticker, v), fontSize = 14.sp, fontWeight = FontWeight.Bold,
            style = TextStyle.Default.num())
    }
}

// チャートの日付ラベル。2026-10-23 表示の決まり UiKit：書き方を Fmt.date に統一（今年 10/22(水)／以前 2025/3/4）
private fun fmtDate(epochSec: Long): String =
    Fmt.date(Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).toLocalDate())
