package pt.fslart.bms.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pt.fslart.bms.ui.components.Model3DViewer
import pt.fslart.bms.ui.theme.*
import pt.fslart.bms.viewmodel.BmsViewModel

enum class Model3DType(
    val title: String,
    val fileName: String,
    val subtitle: String
) {
    CAR_CLOSED("Carro", "tek26e_closed.glb", "Carroçaria e chassis montados"),
    PACK_OPEN("Acumulador", "tek26e_open_light.glb", "Tampa aberta · 6 Segmentos à vista"),
    SEGMENT("Segmento", "seguemento.glb", "Módulo de 24 células e placas BMS Slave"),
    CHARGER("Trolley Carga", "tek26e_charger.glb", "Acumulador montado no carro de transporte")
}

@Composable
fun Model3DScreen(
    viewModel: BmsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.bmsState.collectAsState()
    var selectedModel by remember { mutableStateOf(Model3DType.CAR_CLOSED) }
    var selectedSegmentNum by remember { mutableStateOf(1) }

    val segmentData = remember(state.segments, selectedSegmentNum) {
        state.segments.find { it.id == selectedSegmentNum }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // HEADER
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "VISUALIZADOR 3D CAD",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkTextMuted,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = selectedModel.title + " · " + state.car.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkTextPrimary
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(LartYellow.copy(alpha = 0.15f))
                    .border(1.dp, LartYellow.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "GLTF 2.0 SOLIDWORKS",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = LartYellow
                )
            }
        }

        // MODEL SELECTOR TABS
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Model3DType.values().forEach { type ->
                val isSel = selectedModel == type
                FilterChip(
                    selected = isSel,
                    onClick = { selectedModel = type },
                    label = {
                        Text(
                            text = type.title,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = LartYellow,
                        selectedLabelColor = Color.Black,
                        containerColor = DarkSurface,
                        labelColor = DarkTextSecondary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSel,
                        borderColor = DarkBorder,
                        selectedBorderColor = LartYellow
                    )
                )
            }
        }

        // TELEMETRY LIVE HUD OVERLAY
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(DarkSurface)
                .border(1.dp, DarkBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("TENSÃO PACK", fontSize = 8.sp, color = DarkTextMuted, fontWeight = FontWeight.Bold)
                Text(
                    text = "%.1f V".format(state.pack.voltage),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = LartYellow,
                    fontFamily = FontFamily.Monospace
                )
            }
            Column {
                Text("ESTADO SOC", fontSize = 8.sp, color = DarkTextMuted, fontWeight = FontWeight.Bold)
                Text(
                    text = "${state.pack.soc.toInt()}%",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = StatusOkGlow,
                    fontFamily = FontFamily.Monospace
                )
            }
            Column {
                Text("TEMP. MÁX", fontSize = 8.sp, color = DarkTextMuted, fontWeight = FontWeight.Bold)
                Text(
                    text = "%.1f °C".format(state.pack.tempMax),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (state.pack.tempMax >= 50f) StatusWarn else DarkTextPrimary,
                    fontFamily = FontFamily.Monospace
                )
            }
            Column {
                Text("POTÊNCIA", fontSize = 8.sp, color = DarkTextMuted, fontWeight = FontWeight.Bold)
                Text(
                    text = "%.1f kW".format(state.pack.power),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = DarkTextPrimary,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // THE 3D VIEWER CANVAS
        Model3DViewer(
            modelFile = selectedModel.fileName,
            height = 340.dp,
            showControls = true,
            title = "${selectedModel.title} — ${selectedModel.subtitle}",
            modifier = Modifier.fillMaxWidth()
        )

        // MODEL CONTEXTUAL DETAILS
        when (selectedModel) {
            Model3DType.SEGMENT -> {
                // Segment selection chips
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurface)
                        .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "INSPEÇÃO DE SEGMENTO",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextMuted
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (s in 1..state.car.nSegments) {
                            val isSel = selectedSegmentNum == s
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) LartYellow else DarkSurfaceElevated)
                                    .border(1.dp, if (isSel) LartYellow else DarkBorder, RoundedCornerShape(8.dp))
                                    .clickable { selectedSegmentNum = s }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "Segmento $s",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSel) Color.Black else DarkTextSecondary
                                )
                            }
                        }
                    }

                    if (segmentData != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Tensão Total", fontSize = 10.sp, color = DarkTextMuted)
                                Text("%.2f V".format(segmentData.voltage), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LartYellow, fontFamily = FontFamily.Monospace)
                            }
                            Column {
                                Text("Célula Mín", fontSize = 10.sp, color = DarkTextMuted)
                                Text("%.3f V".format(segmentData.cellVMin), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0A84FF), fontFamily = FontFamily.Monospace)
                            }
                            Column {
                                Text("Célula Máx", fontSize = 10.sp, color = DarkTextMuted)
                                Text("%.3f V".format(segmentData.cellVMax), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF453A), fontFamily = FontFamily.Monospace)
                            }
                            Column {
                                Text("Temp Máx", fontSize = 10.sp, color = DarkTextMuted)
                                Text("%.1f °C".format(segmentData.tempMax), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = StatusWarn, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }

            Model3DType.PACK_OPEN -> {
                // Breakdown of the 6 segments inside the open accumulator
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurface)
                        .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "ACUMULADOR · 6 SEGMENTOS SÉRIE (144s3p)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextMuted
                    )

                    state.segments.take(6).forEach { seg ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(DarkSurfaceElevated)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(seg.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DarkTextPrimary)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = "%.2f V".format(seg.voltage),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LartYellow,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "%.1f °C".format(seg.tempMax),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (seg.tempMax >= 50f) StatusWarn else DarkTextSecondary,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            Model3DType.CHARGER -> {
                // Charger specs and status
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurface)
                        .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "SISTEMA DE CARREGAMENTO (HANDCART)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextMuted
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Tensão Saída", fontSize = 10.sp, color = DarkTextMuted)
                            Text("%.1f V".format(state.charger.outputVoltage), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LartYellow, fontFamily = FontFamily.Monospace)
                        }
                        Column {
                            Text("Corrente", fontSize = 10.sp, color = DarkTextMuted)
                            Text("%.1f A".format(state.charger.outputCurrent), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DarkTextPrimary, fontFamily = FontFamily.Monospace)
                        }
                        Column {
                            Text("Energia Sessão", fontSize = 10.sp, color = DarkTextMuted)
                            Text("%.1f Wh".format(state.charger.energyWh), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = StatusOkGlow, fontFamily = FontFamily.Monospace)
                        }
                        Column {
                            Text("Estado", fontSize = 10.sp, color = DarkTextMuted)
                            Text(if (state.isCharging) "A CARREGAR" else "STANDBY", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (state.isCharging) StatusOk else DarkTextSecondary)
                        }
                    }
                }
            }

            Model3DType.CAR_CLOSED -> {
                // Vehicle Specs card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurface)
                        .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "ESPECIFICAÇÕES DO VEÍCULO",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextMuted
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Modelo de Células", fontSize = 12.sp, color = DarkTextSecondary)
                        Text(state.car.cellModel, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DarkTextPrimary)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Topologia do Pack", fontSize = 12.sp, color = DarkTextSecondary)
                        Text("${state.car.seriesCount}s ${state.car.parallelStrings}p (${state.car.totalCells} células)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DarkTextPrimary)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Capacidade Nominal", fontSize = 12.sp, color = DarkTextSecondary)
                        Text("%.2f kWh (%.1f Ah)".format(state.car.totalEnergyKwh, state.car.packCapacityAh), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LartYellow)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Módulos Slave BMS", fontSize = 12.sp, color = DarkTextSecondary)
                        Text("${state.car.slaveCount} Slaves (${state.car.totalThermistors} NTCs)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DarkTextPrimary)
                    }
                }
            }
        }
    }
}
