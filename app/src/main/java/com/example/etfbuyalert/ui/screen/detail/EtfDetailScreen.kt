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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.etfbuyalert.data.model.ChartSeries
import com.example.etfbuyalert.data.model.EtfState
import com.example.etfbuyalert.domain.AlertEngine
import com.example.etfbuyalert.domain.Money
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ラインの色（チャートと凡例で共通＝DRY）
private val COLOR_DIP = Color(0xFF4CAF50)
private val COLOR_DEEP = Color(0xFF2E7D32)
private val COLOR_BREAKOUT = Color(0xFF1565C0)
private val COLOR_STOP = Color(0xFFE53935)
private val COLOR_PRICE = Color(0xFF90CAF9)

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${state.ticker} チャート", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 銘柄名・現在値
            Text(state.name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Money.format(state.ticker, state.price), fontWeight = FontWeight.Bold, fontSize = 24.sp)
                Spacer(Modifier.width(8.dp))
                if (state.previousClose != null && state.price != null && state.previousClose != 0.0) {
                    val diff = (state.price - state.previousClose) / state.previousClose * 100.0
                    Text(String.format("前日比 %+.2f%%", diff), fontSize = 13.sp,
                        color = if (diff >= 0) COLOR_DIP else COLOR_STOP,
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

            // チャート本体
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth().height(280.dp).padding(8.dp), contentAlignment = Alignment.Center) {
                    when {
                        chart != null && chart.points.size >= 2 ->
                            PriceChart(chart, state)
                        chartLoading ->
                            CircularProgressIndicator()
                        else ->
                            Text("チャートを取得できませんでした", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 凡例
            Legend(state)

            // ラインの値一覧
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 通貨判定のためティッカーを引き回す
                    LineValue(state.ticker, "買い時(押し目)", state.dipPrice, COLOR_DIP)
                    LineValue(state.ticker, "買い増し(深押し)", state.deepDipPrice, COLOR_DEEP)
                    LineValue(state.ticker, "順張り(上抜け)", state.breakoutPrice, COLOR_BREAKOUT)
                    LineValue(state.ticker, "損切り", state.stopLossPrice, COLOR_STOP)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// 価格チャート（折れ線＋各ラインの水平線）
@Composable
private fun PriceChart(chart: ChartSeries, state: EtfState) {
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)

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
        hline(state.dipPrice, COLOR_DIP, "押" )
        hline(state.deepDipPrice, COLOR_DEEP, "深")
        hline(state.breakoutPrice, COLOR_BREAKOUT, "順")
        hline(state.stopLossPrice, COLOR_STOP, "損")

        // 価格の折れ線
        val path = Path()
        pts.forEachIndexed { i, pt ->
            val px = x(i); val py = y(pt.close)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, COLOR_PRICE, style = Stroke(width = 3f))

        // 直近の点に丸
        val lastX = x(pts.size - 1); val lastY = y(pts.last().close)
        drawCircle(COLOR_PRICE, 6f, Offset(lastX, lastY))

        // 軸ラベル（最大・最小・日付）
        drawContext.canvas.nativeCanvas.apply {
            val tp = android.graphics.Paint().apply {
                color = android.graphics.Color.LTGRAY; textSize = 24f; isAntiAlias = true
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
private fun Legend(state: EtfState) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendDot("価格", COLOR_PRICE)
        if (state.dipPrice != null) LegendDot("押し目", COLOR_DIP)
        if (state.deepDipPrice != null) LegendDot("深押し", COLOR_DEEP)
        if (state.breakoutPrice != null) LegendDot("順張り", COLOR_BREAKOUT)
        if (state.stopLossPrice != null) LegendDot("損切り", COLOR_STOP)
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp)) { Canvas(Modifier.fillMaxSize()) { drawCircle(color) } }
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 13.sp)
        }
        Text(Money.format(ticker, v), fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

private fun fmtDate(epochSec: Long): String =
    SimpleDateFormat("yy/M/d", Locale.JAPAN).format(Date(epochSec * 1000))
