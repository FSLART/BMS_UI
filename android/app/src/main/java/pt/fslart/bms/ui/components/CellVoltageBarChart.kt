package pt.fslart.bms.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import pt.fslart.bms.model.Cell
import pt.fslart.bms.model.Severity
import pt.fslart.bms.ui.theme.*

// Colors matching the desktop application (TOKENS in panels.js)
val ChartMinBlue = Color(0xFF0A84FF)  // Azul para célula mínima
val ChartMaxRed = Color(0xFFFF453A)   // Vermelho para célula máxima
val ChartOkGreen = Color(0xFF32D74B)  // Verde para células normais
val ChartWarnAmber = Color(0xFFFFD60A)// Amarelo para aviso
val ChartOpenWirePurple = Color(0xFFBF5AF2) // Roxo para fio aberto
val ChartGridLine = Color(0x1FFFFFFF)
val ChartAxisLine = Color(0x33FFFFFF)

@Composable
fun CellVoltageBarChart(
    cells: List<Cell>,
    vMinLimit: Float = 2.50f,
    vMaxLimit: Float = 4.30f,
    vWarnLow: Float = 2.80f,
    vWarnHigh: Float = 4.15f,
    onMaximize: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isMaximized by remember { mutableStateOf(false) }
    var selectedCell by remember { mutableStateOf<Cell?>(null) }

    // Find global minimum and maximum healthy cells (ENTIRE PACK)
    val validCells = cells.filter { it.status != Severity.UNKNOWN && !it.openWire && it.voltage > 0f }
    val minCell = validCells.minByOrNull { it.voltage }
    val maxCell = validCells.maxByOrNull { it.voltage }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurface)
            .border(1.dp, DarkBorder, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        // HEADER WITH EXTREMES STATS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "TENSÃO POR GRUPO SÉRIE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkTextMuted,
                    letterSpacing = 0.6.sp
                )
                Text(
                    text = "${cells.size} Células em Série",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkTextPrimary
                )
            }

            // Highlighting Min (Blue) and Max (Red) Callouts + Maximize Button
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (maxCell != null) {
                    val maxGlobal = (maxCell.segment - 1) * 24 + maxCell.index
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "▲ MÁX (#$maxGlobal · S${maxCell.segment})",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = ChartMaxRed
                        )
                        Text(
                            text = "%.3f V".format(maxCell.voltage),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = ChartMaxRed,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                if (minCell != null) {
                    val minGlobal = (minCell.segment - 1) * 24 + minCell.index
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "▼ MÍN (#$minGlobal · S${minCell.segment})",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = ChartMinBlue
                        )
                        Text(
                            text = "%.3f V".format(minCell.voltage),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = ChartMinBlue,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                if (maxCell != null && minCell != null) {
                    val delta = maxCell.voltage - minCell.voltage
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "DESVIO Δ",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = DarkTextSecondary
                        )
                        Text(
                            text = "${(delta * 1000f).toInt()} mV",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (delta > 0.05f) ChartWarnAmber else StatusOkGlow,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Maximize Button
                IconButton(
                    onClick = {
                        if (onMaximize != null) {
                            onMaximize()
                        } else {
                            isMaximized = true
                        }
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(DarkSurfaceElevated)
                        .border(1.dp, DarkBorder, RoundedCornerShape(6.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = "Maximizar Gráfico",
                        tint = LartYellow,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Selected cell info banner (when touched)
        selectedCell?.let { cell ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(DarkSurfaceElevated)
                    .border(1.dp, LartYellow, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Célula #${cell.index} (Seg ${cell.segment}, Slave ${cell.slave}): %.4f V".format(cell.voltage),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = LartYellow
                )
                if (cell.balancing) {
                    Text(text = "A BALANCEAR", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = LartYellow)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Standard Scrollable Bar Chart
        VoltageChartCanvas(
            cells = cells,
            vMinLimit = vMinLimit,
            vMaxLimit = vMaxLimit,
            vWarnLow = vWarnLow,
            vWarnHigh = vWarnHigh,
            minCell = minCell,
            maxCell = maxCell,
            chartHeight = 230.dp,
            barWidthDp = 5.5.dp,
            barGapDp = 1.dp,
            segGapDp = 14.dp,
            onCellSelected = { selectedCell = it }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Legend row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendIndicator(color = ChartMaxRed, label = "Máxima (▲)")
                LegendIndicator(color = ChartMinBlue, label = "Mínima (▼)")
                LegendIndicator(color = ChartOkGreen, label = "Normal")
            }
            Text(
                text = "Toca numa barra para ver a célula",
                fontSize = 10.sp,
                color = DarkTextMuted
            )
        }
    }

    // LOCAL FULLSCREEN FALLBACK (IF onMaximize NOT PROVIDED)
    if (isMaximized) {
        Dialog(
            onDismissRequest = { isMaximized = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            FullscreenCellChartOverlay(
                cells = cells,
                vMinLimit = vMinLimit,
                vMaxLimit = vMaxLimit,
                vWarnLow = vWarnLow,
                vWarnHigh = vWarnHigh,
                onClose = { isMaximized = false }
            )
        }
    }
}

@Composable
fun FullscreenCellChartOverlay(
    cells: List<Cell>,
    vMinLimit: Float = 2.50f,
    vMaxLimit: Float = 4.30f,
    vWarnLow: Float = 2.80f,
    vWarnHigh: Float = 4.15f,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCell by remember { mutableStateOf<Cell?>(null) }

    // Find global minimum and maximum healthy cells (ENTIRE PACK)
    val validCells = cells.filter { it.status != Severity.UNKNOWN && !it.openWire && it.voltage > 0f }
    val minCell = validCells.minByOrNull { it.voltage }
    val maxCell = validCells.maxByOrNull { it.voltage }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color(0xFF070A0F)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isLandscape = maxWidth > maxHeight

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = if (isLandscape) 14.dp else 12.dp, vertical = if (isLandscape) 4.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (isLandscape) 4.dp else 8.dp)
            ) {
                // Fullscreen Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(end = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "TENSÃO DAS CÉLULAS",
                                fontSize = if (isLandscape) 12.sp else 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = DarkTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(LartYellow.copy(alpha = 0.2f))
                                    .border(1.dp, LartYellow.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "ECRÃ INTEIRO",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LartYellow
                                )
                            }
                        }
                        if (!isLandscape) {
                            Text(
                                text = "${cells.size} Células em Série (144s) · 6 Segmentos",
                                fontSize = 11.sp,
                                color = DarkTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (maxCell != null) {
                            val maxGlobal = (maxCell.segment - 1) * 24 + maxCell.index
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "▲ MÁX (#$maxGlobal · S${maxCell.segment})",
                                    fontSize = if (isLandscape) 8.sp else 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ChartMaxRed
                                )
                                Text(
                                    text = "%.3f V".format(maxCell.voltage),
                                    fontSize = if (isLandscape) 12.sp else 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = ChartMaxRed,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        if (minCell != null) {
                            val minGlobal = (minCell.segment - 1) * 24 + minCell.index
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "▼ MÍN (#$minGlobal · S${minCell.segment})",
                                    fontSize = if (isLandscape) 8.sp else 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ChartMinBlue
                                )
                                Text(
                                    text = "%.3f V".format(minCell.voltage),
                                    fontSize = if (isLandscape) 12.sp else 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = ChartMinBlue,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Button(
                            onClick = onClose,
                            colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated, contentColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(1.dp, LartYellow),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(if (isLandscape) 30.dp else 34.dp)
                        ) {
                            Icon(Icons.Default.FullscreenExit, contentDescription = "Fechar", modifier = Modifier.size(14.dp), tint = LartYellow)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Fechar", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LartYellow)
                        }
                    }
                }

                // Selected Banner
                selectedCell?.let { cell ->
                    val globalIdx = (cell.segment - 1) * 24 + cell.index
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(DarkSurfaceElevated)
                            .border(1.dp, LartYellow, RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Célula #$globalIdx (Segmento ${cell.segment} · #${cell.index}) · Slave ${cell.slave}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = DarkTextPrimary
                            )
                            Text(
                                text = if (cell.balancing) "Balanceamento ativo (A descarregar)" else "Status: ${cell.status}",
                                fontSize = 9.sp,
                                color = if (cell.balancing) LartYellow else DarkTextSecondary
                            )
                        }
                        Text(
                            text = "%.4f V".format(cell.voltage),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (cell.balancing) LartYellow else ChartOkGreen,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Maximized Chart (takes 100% of remaining area)
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    VoltageChartCanvas(
                        cells = cells,
                        vMinLimit = vMinLimit,
                        vMaxLimit = vMaxLimit,
                        vWarnLow = vWarnLow,
                        vWarnHigh = vWarnHigh,
                        minCell = minCell,
                        maxCell = maxCell,
                        chartHeight = Dp.Unspecified,
                        barWidthDp = if (isLandscape) 6.dp else 5.dp,
                        barGapDp = 1.dp,
                        segGapDp = if (isLandscape) 14.dp else 10.dp,
                        onCellSelected = { selectedCell = it }
                    )
                }

                // Maximized Legend
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(DarkSurface)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LegendIndicator(color = ChartMaxRed, label = "Máx Bateria (▲)")
                        LegendIndicator(color = ChartMinBlue, label = "Mín Bateria (▼)")
                        LegendIndicator(color = ChartOkGreen, label = "Normal")
                        LegendIndicator(color = LartYellow, label = "Balanceamento")
                    }
                    Text("Scroll horizontal para ver todos os segmentos", fontSize = 9.sp, color = DarkTextMuted)
                }
            }
        }
    }
}

@Composable
private fun VoltageChartCanvas(
    cells: List<Cell>,
    vMinLimit: Float,
    vMaxLimit: Float,
    vWarnLow: Float,
    vWarnHigh: Float,
    minCell: Cell?,
    maxCell: Cell?,
    chartHeight: Dp,
    barWidthDp: Dp,
    barGapDp: Dp,
    segGapDp: Dp,
    onCellSelected: (Cell) -> Unit
) {
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (chartHeight != Dp.Unspecified) Modifier.height(chartHeight) else Modifier.fillMaxHeight())
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0C1017))
            .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
            .horizontalScroll(scrollState)
    ) {
        val totalCells = cells.size.coerceAtLeast(144)
        val nSegments = 6
        val totalWidthDp = 70.dp + ((barWidthDp + barGapDp) * totalCells) + (segGapDp * nSegments)

        Canvas(
            modifier = Modifier
                .width(totalWidthDp)
                .fillMaxHeight()
                .pointerInput(cells) {
                    detectTapGestures { offset ->
                        val marginLeft = 45.dp.toPx()
                        val bwPx = barWidthDp.toPx()
                        val bgPx = barGapDp.toPx()
                        val sgPx = segGapDp.toPx()
                        val perSeg = 24

                        for (i in cells.indices) {
                            val seg = i / perSeg
                            val segOffset = seg * sgPx
                            val x = marginLeft + segOffset + (i * (bwPx + bgPx))
                            if (offset.x in x..(x + bwPx)) {
                                onCellSelected(cells[i])
                                break
                            }
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height

            val marginLeft = 45.dp.toPx()
            val marginRight = 20.dp.toPx()
            val marginTop = 26.dp.toPx()
            val marginBottom = 28.dp.toPx()

            val chartH = h - marginTop - marginBottom
            val scaleMin = vMinLimit
            val scaleMax = vMaxLimit
            val span = (scaleMax - scaleMin).coerceAtLeast(0.1f)

            fun yOf(v: Float): Float {
                val clamped = v.coerceIn(scaleMin, scaleMax)
                return marginTop + chartH * (1.0f - ((clamped - scaleMin) / span))
            }

            // Grid lines & Y Axis labels
            val ticks = 5
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#94949E")
                textSize = 10.dp.toPx()
                typeface = android.graphics.Typeface.MONOSPACE
                textAlign = android.graphics.Paint.Align.RIGHT
            }

            for (i in 0..ticks) {
                val v = scaleMin + (span * i / ticks)
                val y = yOf(v)
                drawLine(
                    color = ChartGridLine,
                    start = Offset(marginLeft, y),
                    end = Offset(w - marginRight, y),
                    strokeWidth = 1.dp.toPx()
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "%.2f".format(v),
                    marginLeft - 6.dp.toPx(),
                    y + 3.dp.toPx(),
                    textPaint
                )
            }

            // UV Threshold Line (Under Voltage, e.g. 2.80V)
            val yUv = yOf(vWarnLow)
            drawLine(
                color = ChartWarnAmber.copy(alpha = 0.6f),
                start = Offset(marginLeft, yUv),
                end = Offset(w - marginRight, yUv),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            )

            // OV Threshold Line (Over Voltage, e.g. 4.15V)
            val yOv = yOf(vWarnHigh)
            drawLine(
                color = ChartWarnAmber.copy(alpha = 0.6f),
                start = Offset(marginLeft, yOv),
                end = Offset(w - marginRight, yOv),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            )

            // Bars & Segment Separators
            val bwPx = barWidthDp.toPx()
            val bgPx = barGapDp.toPx()
            val sgPx = segGapDp.toPx()
            val perSeg = 24
            val baselineY = marginTop + chartH

            // Baseline
            drawLine(
                color = ChartAxisLine,
                start = Offset(marginLeft, baselineY),
                end = Offset(w - marginRight, baselineY),
                strokeWidth = 1.5.dp.toPx()
            )

            val arrowPaint = android.graphics.Paint().apply {
                textSize = 11.dp.toPx()
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = android.graphics.Paint.Align.CENTER
            }

            val segLabelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#C4C4CC")
                textSize = 10.dp.toPx()
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = android.graphics.Paint.Align.CENTER
            }

            for (s in 0 until nSegments) {
                val segStartX = marginLeft + (s * sgPx) + (s * perSeg * (bwPx + bgPx))
                val segEndX = segStartX + (perSeg * (bwPx + bgPx)) - bgPx

                // Segment label below baseline
                drawContext.canvas.nativeCanvas.drawText(
                    "SEG ${s + 1}",
                    (segStartX + segEndX) / 2f,
                    baselineY + 18.dp.toPx(),
                    segLabelPaint
                )

                // Vertical separator between segments
                if (s > 0) {
                    val sepX = segStartX - (sgPx / 2f)
                    drawLine(
                        color = ChartAxisLine,
                        start = Offset(sepX, marginTop),
                        end = Offset(sepX, baselineY),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // Mid-segment slave separator (dashed)
                val midSlaveX = segStartX + (12 * (bwPx + bgPx)) - (bgPx / 2f)
                drawLine(
                    color = ChartAxisLine.copy(alpha = 0.35f),
                    start = Offset(midSlaveX, marginTop + 10f),
                    end = Offset(midSlaveX, baselineY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                )
            }

            // Draw each Cell Bar
            cells.forEachIndexed { idx, cell ->
                val seg = idx / perSeg
                val segOffset = seg * sgPx
                val x = marginLeft + segOffset + (idx * (bwPx + bgPx))
                val y = yOf(cell.voltage)
                val barH = (baselineY - y).coerceAtLeast(0f)

                val isMax = (maxCell != null && cell.id == maxCell.id)
                val isMin = (minCell != null && cell.id == minCell.id)

                // Top highlight for balancing cells
                if (cell.balancing) {
                    drawRect(
                        color = LartYellow.copy(alpha = 0.7f),
                        topLeft = Offset(x, marginTop),
                        size = Size(bwPx, y - marginTop)
                    )
                }

                // Bar color according to rules:
                // Max is RED, Min is BLUE, open-wire is PURPLE, normal is GREEN
                val barColor = when {
                    cell.openWire -> ChartOpenWirePurple
                    isMax -> ChartMaxRed
                    isMin -> ChartMinBlue
                    cell.status == Severity.FAULT -> StatusFault
                    cell.status == Severity.WARN -> StatusWarn
                    else -> ChartOkGreen
                }

                drawRect(
                    color = barColor,
                    topLeft = Offset(x, y),
                    size = Size(bwPx, barH)
                )

                // Draw Extremes Marker (▲ on Max in Red, ▼ on Min in Blue)
                if (isMax) {
                    arrowPaint.color = android.graphics.Color.parseColor("#FF453A")
                    drawContext.canvas.nativeCanvas.drawText("▲", x + bwPx / 2f, y - 4.dp.toPx(), arrowPaint)
                } else if (isMin) {
                    arrowPaint.color = android.graphics.Color.parseColor("#0A84FF")
                    drawContext.canvas.nativeCanvas.drawText("▼", x + bwPx / 2f, y - 4.dp.toPx(), arrowPaint)
                }
            }
        }
    }
}

@Composable
private fun LegendIndicator(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, fontSize = 10.sp, color = DarkTextSecondary)
    }
}
