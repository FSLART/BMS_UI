package pt.fslart.bms.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pt.fslart.bms.ui.components.*
import pt.fslart.bms.ui.theme.*
import pt.fslart.bms.viewmodel.BmsViewModel

@Composable
fun DashboardScreen(
    viewModel: BmsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.bmsState.collectAsState()
    val currentHistory by viewModel.currentHistory.collectAsState()
    val voltageHistory by viewModel.voltageHistory.collectAsState()
    val selectedCar by viewModel.selectedCar.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // TOP STATUS BAR
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = selectedCar.name,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = DarkTextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (state.isDemo) LartYellow.copy(alpha = 0.2f) else StatusOk.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (state.isDemo) "DEMO" else "BLE LIVE",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (state.isDemo) LartYellow else StatusOkGlow
                            )
                        }
                    }
                    Text(
                        text = if (state.isDemo) "Simulador ativo" else state.connectionAddress.ifEmpty { selectedCar.bleAddress },
                        fontSize = 11.sp,
                        color = DarkTextMuted,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Button(
                    onClick = { viewModel.disconnect() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DarkSurfaceElevated,
                        contentColor = DarkTextPrimary
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(text = "Desligar", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // PRIMARY KPIS (ADAPTIVE 2-ROW GRID)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KpiCard(
                        label = "Tensão do Pack",
                        value = "%.1f".format(state.pack.voltage),
                        unit = "V",
                        accentColor = LartBlueAccent,
                        modifier = Modifier.weight(1f)
                    )
                    KpiCard(
                        label = "Corrente",
                        value = "%.1f".format(state.pack.current),
                        unit = "A",
                        accentColor = LartYellow,
                        modifier = Modifier.weight(1f)
                    )
                    KpiCard(
                        label = "Potência",
                        value = "%.1f".format(state.pack.power),
                        unit = "kW",
                        accentColor = if (state.pack.power < 0) StatusOk else LartYellow,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KpiCard(
                        label = "Estado de Carga",
                        value = "${state.pack.soc.toInt()}",
                        unit = "%",
                        accentColor = when {
                            state.pack.soc < 20f -> StatusFault
                            state.pack.soc < 40f -> StatusWarn
                            else -> StatusOkGlow
                        },
                        modifier = Modifier.weight(1f)
                    )
                    KpiCard(
                        label = "Temp Máxima",
                        value = "%.1f".format(state.pack.tempMax),
                        unit = "°C",
                        accentColor = when {
                            state.pack.tempMax >= 55f -> StatusFault
                            state.pack.tempMax >= 45f -> StatusWarn
                            else -> DarkTextPrimary
                        },
                        modifier = Modifier.weight(1f)
                    )
                    KpiCard(
                        label = "Desvio Células",
                        value = "${(state.pack.cellVDelta * 1000f).toInt()}",
                        unit = "mV",
                        accentColor = when {
                            state.pack.cellVDelta >= 0.080f -> StatusFault
                            state.pack.cellVDelta >= 0.040f -> StatusWarn
                            else -> DarkTextPrimary
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // TELEMETRY LIVE SPARKLINE
        item {
            TelemetrySparkline(
                currentHistory = currentHistory,
                voltageHistory = voltageHistory,
                currentLive = state.pack.current,
                voltageLive = state.pack.voltage,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ACTIVE FAULTS WARNING (IF ANY)
        if (state.faults.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(StatusFaultBg)
                        .border(1.dp, StatusFault.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Alerta",
                            tint = StatusFault,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "FALHAS ATIVAS (${state.faults.size})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = StatusFault
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    state.faults.forEach { fault ->
                        Text(
                            text = "• ${fault.message}",
                            fontSize = 12.sp,
                            color = DarkTextPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // SAFETY & AMS STATE
        item {
            SafetyGrid(
                safety = state.safety,
                ams = state.ams,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 3D CAD CARD (SolidWorks)
        item {
            Model3DViewer(
                modelFile = "tek26e_closed.glb",
                height = 260.dp,
                title = "CARRO TEK-26e 3D · SOLIDWORKS CAD",
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ACCUMULATOR SEGMENTS OVERVIEW
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SEGMENTOS DO ACUMULADOR",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkTextMuted,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "${state.segments.size} Segmentos",
                    fontSize = 11.sp,
                    color = DarkTextSecondary
                )
            }
        }

        items(state.segments) { segment ->
            SegmentCard(
                segment = segment,
                onClick = { viewModel.selectSegment(segment.id) }
            )
        }
    }
}
