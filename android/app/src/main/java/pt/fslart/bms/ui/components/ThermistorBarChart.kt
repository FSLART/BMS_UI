package pt.fslart.bms.ui.components

import androidx.compose.foundation.BorderStroke
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
import pt.fslart.bms.model.Severity
import pt.fslart.bms.model.Thermistor
import pt.fslart.bms.ui.theme.*

@Composable
fun ThermistorBarChart(
    thermistors: List<Thermistor>,
    tMinLimit: Float = 0.0f,
    tMaxLimit: Float = 70.0f,
    tWarn: Float = 50.0f,
    tFault: Float = 60.0f,
    brokenThermistors: List<String> = emptyList(),
    onMaximize: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isMaximized by remember { mutableStateOf(false) }
    var selectedNtc by remember { mutableStateOf<Thermistor?>(null) }

    // Find global minimum and maximum healthy thermistors
    val validThermistors = thermistors.filter {
        it.temperature != null && it.temperature > 0f &&
        !brokenThermistors.contains("${it.slave}:${it.index}") &&
        it.status != Severity.UNKNOWN
    }
    val minNtc = validThermistors.minByOrNull { it.temperature!! }
    val maxNtc = validThermistors.maxByOrNull { it.temperature!! }

    // Standard Embedded Card
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurface)
            .border(1.dp, DarkBorder, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        // ROW 1: TITLE & ACTION BUTTON
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "TEMPERATURAS NTC POR SEGMENTO",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkTextMuted,
                    letterSpacing = 0.6.sp
                )
                Text(
                    text = "${thermistors.size} Sensores NTC",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkTextPrimary
                )
            }

            // Sleek Maximize Button
            Surface(
                onClick = {
                    if (onMaximize != null) {
                        onMaximize()
                    } else {
                        isMaximized = true
                    }
                },
                shape = RoundedCornerShape(8.dp),
                color = DarkSurfaceElevated,
                border = BorderStroke(1.dp, LartYellow.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = "Maximizar Gráfico",
                        tint = LartYellow,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Maximizar",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = LartYellow
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ROW 2: STAT CARDS (MÁX, MÍN, DESVIO)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (maxNtc != null && maxNtc.temperature != null) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ChartMaxRed.copy(alpha = 0.10f))
                        .border(1.dp, ChartMaxRed.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "▲ MÁX (S${maxNtc.slave}·T${maxNtc.index})",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = ChartMaxRed,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "%.1f °C".format(maxNtc.temperature),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = ChartMaxRed,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            if (minNtc != null && minNtc.temperature != null) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ChartMinBlue.copy(alpha = 0.10f))
                        .border(1.dp, ChartMinBlue.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "▼ MÍN (S${minNtc.slave}·T${minNtc.index})",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = ChartMinBlue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "%.1f °C".format(minNtc.temperature),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = ChartMinBlue,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            if (maxNtc?.temperature != null && minNtc?.temperature != null) {
                val delta = maxNtc.temperature!! - minNtc.temperature!!
                val isWarn = delta > 5.0f
                val deltaColor = if (isWarn) ChartWarnAmber else StatusOkGlow
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(deltaColor.copy(alpha = 0.10f))
                        .border(1.dp, deltaColor.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "DESVIO Δ",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextSecondary,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "%.1f °C".format(delta),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = deltaColor,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Selected NTC info banner (when touched)
        selectedNtc?.let { ntc ->
            val isBroken = brokenThermistors.contains("${ntc.slave}:${ntc.index}")
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
                    text = "NTC #${ntc.index} (Seg ${ntc.segment}, Slave ${ntc.slave}): ${if (isBroken) "BYPASS / AVARIADO" else if (ntc.temperature != null) "%.1f °C".format(ntc.temperature) else "—"}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (isBroken) ChartOpenWirePurple else LartYellow
                )
                Text(
                    text = when {
                        isBroken -> "AVARIADO"
                        ntc.temperature != null && ntc.temperature >= tFault -> "FALHA OT"
                        ntc.temperature != null && ntc.temperature >= tWarn -> "AVISO OT"
                        else -> "NORMAL"
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        isBroken -> ChartOpenWirePurple
                        ntc.temperature != null && ntc.temperature >= tFault -> StatusFault
                        ntc.temperature != null && ntc.temperature >= tWarn -> StatusWarn
                        else -> StatusOk
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Standard Chart Body
        NtcChartCanvas(
            thermistors = thermistors,
            tMinLimit = tMinLimit,
            tMaxLimit = tMaxLimit,
            tWarn = tWarn,
            tFault = tFault,
            brokenThermistors = brokenThermistors,
            minNtc = minNtc,
            maxNtc = maxNtc,
            chartHeight = 230.dp,
            barWidthDp = 9.dp,
            barGapDp = 2.dp,
            segGapDp = 18.dp,
            onNtcSelected = { selectedNtc = it }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Legend row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LegendIndicator(color = ChartMaxRed, label = "Máx (▲)")
                LegendIndicator(color = ChartMinBlue, label = "Mín (▼)")
                LegendIndicator(color = ChartOkGreen, label = "Normal")
                LegendIndicator(color = ChartOpenWirePurple, label = "Bypass")
            }
            Text(
                text = "Toca numa barra",
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
            FullscreenThermChartOverlay(
                thermistors = thermistors,
                tMinLimit = tMinLimit,
                tMaxLimit = tMaxLimit,
                tWarn = tWarn,
                tFault = tFault,
                brokenThermistors = brokenThermistors,
                onClose = { isMaximized = false }
            )
        }
    }
}

@Composable
fun FullscreenThermChartOverlay(
    thermistors: List<Thermistor>,
    tMinLimit: Float = 0.0f,
    tMaxLimit: Float = 70.0f,
    tWarn: Float = 50.0f,
    tFault: Float = 60.0f,
    brokenThermistors: List<String> = emptyList(),
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedNtc by remember { mutableStateOf<Thermistor?>(null) }

    // Find global minimum and maximum healthy thermistors
    val validThermistors = thermistors.filter {
        it.temperature != null && it.temperature > 0f &&
        !brokenThermistors.contains("${it.slave}:${it.index}") &&
        it.status != Severity.UNKNOWN
    }
    val minNtc = validThermistors.minByOrNull { it.temperature!! }
    val maxNtc = validThermistors.maxByOrNull { it.temperature!! }

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
                if (isLandscape) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false).padding(end = 10.dp)
                        ) {
                            Text(
                                text = "TEMPERATURAS NTC",
                                fontSize = 12.sp,
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

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (maxNtc != null && maxNtc.temperature != null) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "▲ MÁX (S${maxNtc.slave}·T${maxNtc.index})",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ChartMaxRed
                                    )
                                    Text(
                                        text = "%.1f °C".format(maxNtc.temperature),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = ChartMaxRed,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            if (minNtc != null && minNtc.temperature != null) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "▼ MÍN (S${minNtc.slave}·T${minNtc.index})",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ChartMinBlue
                                    )
                                    Text(
                                        text = "%.1f °C".format(minNtc.temperature),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = ChartMinBlue,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            Button(
                                onClick = onClose,
                                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated, contentColor = Color.White),
                                border = BorderStroke(1.dp, LartYellow),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Default.FullscreenExit, contentDescription = "Fechar", modifier = Modifier.size(14.dp), tint = LartYellow)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Fechar", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LartYellow)
                            }
                        }
                    }
                } else {
                    // Portrait Fullscreen Header: 2 clean rows
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "TEMPERATURAS NTC",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DarkTextPrimary
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
                            Text(
                                text = "${thermistors.size} Sensores · 6 Segmentos · 12 Slaves",
                                fontSize = 11.sp,
                                color = DarkTextSecondary
                            )
                        }

                        Button(
                            onClick = onClose,
                            colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated, contentColor = Color.White),
                            border = BorderStroke(1.dp, LartYellow),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.FullscreenExit, contentDescription = "Fechar", modifier = Modifier.size(14.dp), tint = LartYellow)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Fechar", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LartYellow)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (maxNtc != null && maxNtc.temperature != null) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(ChartMaxRed.copy(alpha = 0.12f))
                                    .border(1.dp, ChartMaxRed.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("▲ MÁX (S${maxNtc.slave}·T${maxNtc.index})", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ChartMaxRed)
                                Text("%.1f °C".format(maxNtc.temperature), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = ChartMaxRed, fontFamily = FontFamily.Monospace)
                            }
                        }
                        if (minNtc != null && minNtc.temperature != null) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(ChartMinBlue.copy(alpha = 0.12f))
                                    .border(1.dp, ChartMinBlue.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("▼ MÍN (S${minNtc.slave}·T${minNtc.index})", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ChartMinBlue)
                                Text("%.1f °C".format(minNtc.temperature), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = ChartMinBlue, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }

                // Selected Banner
                selectedNtc?.let { ntc ->
                    val isBroken = brokenThermistors.contains("${ntc.slave}:${ntc.index}")
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
                                text = "NTC #${ntc.index} · Segmento ${ntc.segment} · Slave ${ntc.slave}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = DarkTextPrimary
                            )
                            Text(
                                text = if (isBroken) "Estado: Bypass de fábrica (Avariado)" else "Status: ${ntc.status}",
                                fontSize = 9.sp,
                                color = if (isBroken) ChartOpenWirePurple else DarkTextSecondary
                            )
                        }
                        Text(
                            text = if (isBroken) "BYPASS" else if (ntc.temperature != null) "%.1f °C".format(ntc.temperature) else "—",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isBroken) ChartOpenWirePurple else if (ntc.temperature != null && ntc.temperature >= tWarn) ChartMaxRed else ChartOkGreen,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Maximized Chart (takes 100% of remaining area)
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    NtcChartCanvas(
                        thermistors = thermistors,
                        tMinLimit = tMinLimit,
                        tMaxLimit = tMaxLimit,
                        tWarn = tWarn,
                        tFault = tFault,
                        brokenThermistors = brokenThermistors,
                        minNtc = minNtc,
                        maxNtc = maxNtc,
                        chartHeight = Dp.Unspecified,
                        barWidthDp = if (isLandscape) 10.dp else 12.dp,
                        barGapDp = 2.dp,
                        segGapDp = if (isLandscape) 16.dp else 20.dp,
                        onNtcSelected = { selectedNtc = it }
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
                        LegendIndicator(color = ChartMaxRed, label = "Máx (▲)")
                        LegendIndicator(color = ChartMinBlue, label = "Mín (▼)")
                        LegendIndicator(color = ChartOkGreen, label = "Normal")
                        LegendIndicator(color = ChartWarnAmber, label = "Aviso (≥50°C)")
                        LegendIndicator(color = ChartOpenWirePurple, label = "Bypass")
                    }
                    Text("Scroll horizontal para ver todos os segmentos", fontSize = 9.sp, color = DarkTextMuted)
                }
            }
        }
    }
}

@Composable
private fun NtcChartCanvas(
    thermistors: List<Thermistor>,
    tMinLimit: Float,
    tMaxLimit: Float,
    tWarn: Float,
    tFault: Float,
    brokenThermistors: List<String>,
    minNtc: Thermistor?,
    maxNtc: Thermistor?,
    chartHeight: Dp,
    barWidthDp: Dp,
    barGapDp: Dp,
    segGapDp: Dp,
    onNtcSelected: (Thermistor) -> Unit
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
        val totalNtc = thermistors.size.coerceAtLeast(72)
        val nSegments = 6
        val totalWidthDp = 70.dp + ((barWidthDp + barGapDp) * totalNtc) + (segGapDp * nSegments)

        Canvas(
            modifier = Modifier
                .width(totalWidthDp)
                .fillMaxHeight()
                .pointerInput(thermistors) {
                    detectTapGestures { offset ->
                        val marginLeft = 45.dp.toPx()
                        val bwPx = barWidthDp.toPx()
                        val bgPx = barGapDp.toPx()
                        val sgPx = segGapDp.toPx()
                        val perSeg = if (thermistors.isNotEmpty()) (thermistors.size / nSegments).coerceAtLeast(1) else 12

                        for (i in thermistors.indices) {
                            val seg = i / perSeg
                            val segOffset = seg * sgPx
                            val x = marginLeft + segOffset + (i * (bwPx + bgPx))
                            if (offset.x in x..(x + bwPx)) {
                                onNtcSelected(thermistors[i])
                                break
                            }
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height

            val marginLeft = 45.dp.toPx()
            val marginRight = 40.dp.toPx()
            val marginTop = 26.dp.toPx()
            val marginBottom = 28.dp.toPx()

            val chartH = h - marginTop - marginBottom
            val scaleMin = tMinLimit
            val scaleMax = tMaxLimit
            val span = (scaleMax - scaleMin).coerceAtLeast(10f)

            fun yOf(v: Float): Float {
                val clamped = v.coerceIn(scaleMin, scaleMax)
                return marginTop + chartH * (1.0f - ((clamped - scaleMin) / span))
            }

            // Grid lines & Y Axis labels (0°C, 14°C, 28°C, 42°C, 56°C, 70°C)
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
                    "${v.toInt()}°",
                    marginLeft - 6.dp.toPx(),
                    y + 3.dp.toPx(),
                    textPaint
                )
            }

            // Over-Temperature Warning Line (e.g. 50°C)
            if (tWarn in scaleMin..scaleMax) {
                val yWarn = yOf(tWarn)
                drawLine(
                    color = ChartWarnAmber.copy(alpha = 0.65f),
                    start = Offset(marginLeft, yWarn),
                    end = Offset(w - marginRight, yWarn),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                )
                val warnLabelPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#FFD60A")
                    textSize = 9.dp.toPx()
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    textAlign = android.graphics.Paint.Align.LEFT
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "OT ${tWarn.toInt()}°C",
                    w - marginRight + 6.dp.toPx(),
                    yWarn + 3.dp.toPx(),
                    warnLabelPaint
                )
            }

            // Over-Temperature Fault Line (e.g. 60°C)
            if (tFault in scaleMin..scaleMax) {
                val yFault = yOf(tFault)
                drawLine(
                    color = ChartMaxRed.copy(alpha = 0.65f),
                    start = Offset(marginLeft, yFault),
                    end = Offset(w - marginRight, yFault),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                )
                val faultLabelPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#FF453A")
                    textSize = 9.dp.toPx()
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    textAlign = android.graphics.Paint.Align.LEFT
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "FALHA ${tFault.toInt()}°C",
                    w - marginRight + 6.dp.toPx(),
                    yFault + 3.dp.toPx(),
                    faultLabelPaint
                )
            }

            // Bars & Segment Separators
            val bwPx = barWidthDp.toPx()
            val bgPx = barGapDp.toPx()
            val sgPx = segGapDp.toPx()
            val perSeg = if (thermistors.isNotEmpty()) (thermistors.size / nSegments).coerceAtLeast(1) else 12
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

                // Mid-segment slave separator (dashed) - divides the 2 slaves in each segment
                val midSlaveCount = perSeg / 2
                val midSlaveX = segStartX + (midSlaveCount * (bwPx + bgPx)) - (bgPx / 2f)
                drawLine(
                    color = ChartAxisLine.copy(alpha = 0.35f),
                    start = Offset(midSlaveX, marginTop + 10f),
                    end = Offset(midSlaveX, baselineY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                )
            }

            // Draw each NTC Bar
            thermistors.forEachIndexed { idx, ntc ->
                val seg = idx / perSeg
                val segOffset = seg * sgPx
                val x = marginLeft + segOffset + (idx * (bwPx + bgPx))

                val isBroken = brokenThermistors.contains("${ntc.slave}:${ntc.index}")
                val temp = ntc.temperature

                if (isBroken || temp == null) {
                    // Empty or Broken NTC slot
                    drawRect(
                        color = if (isBroken) ChartOpenWirePurple.copy(alpha = 0.45f) else Color(0x22787880),
                        topLeft = Offset(x, marginTop),
                        size = Size(bwPx, chartH)
                    )
                } else {
                    val y = yOf(temp)
                    val barH = (baselineY - y).coerceAtLeast(0f)

                    val isMax = (maxNtc != null && ntc.slave == maxNtc.slave && ntc.index == maxNtc.index)
                    val isMin = (minNtc != null && ntc.slave == minNtc.slave && ntc.index == minNtc.index)

                    val barColor = when {
                        isMax -> ChartMaxRed
                        isMin -> ChartMinBlue
                        ntc.status == Severity.FAULT || temp >= tFault -> StatusFault
                        ntc.status == Severity.WARN || temp >= tWarn -> StatusWarn
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
