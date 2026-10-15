package pt.fslart.bms.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.FormatListBulleted
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
import pt.fslart.bms.model.Severity
import pt.fslart.bms.ui.components.ThermistorBarChart
import pt.fslart.bms.ui.theme.*
import pt.fslart.bms.viewmodel.BmsViewModel

enum class ThermistorsViewMode {
    CHART,
    LIST
}

@Composable
fun ThermistorsScreen(
    viewModel: BmsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.bmsState.collectAsState()
    val selectedSegmentId by viewModel.selectedSegmentId.collectAsState()

    var viewMode by remember { mutableStateOf(ThermistorsViewMode.CHART) }

    val filteredThermistors = remember(state.thermistors, selectedSegmentId) {
        if (selectedSegmentId == null) {
            state.thermistors
        } else {
            state.thermistors.filter { it.segment == selectedSegmentId }
        }
    }

    val validTemps = remember(filteredThermistors, state.car.brokenThermistors) {
        filteredThermistors
            .filter { !state.car.brokenThermistors.contains("${it.slave}:${it.index}") }
            .mapNotNull { it.temperature }
    }
    val maxT = validTemps.maxOrNull() ?: 0.0f
    val minT = validTemps.minOrNull() ?: 0.0f
    val avgT = if (validTemps.isNotEmpty()) validTemps.average().toFloat() else 0.0f
    val deltaT = maxT - minT

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // TOP BAR: HEADER + VIEW MODE TOGGLE (BAR CHART vs LIST)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "TEMPERATURAS NTC",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkTextMuted,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "${filteredThermistors.size} de ${state.thermistors.size} Sensores",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkTextPrimary
                )
            }

            // Mode Toggle: [ 📊 Gráfico | ⊞ Lista ]
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkSurfaceElevated)
                    .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
                    .padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (viewMode == ThermistorsViewMode.CHART) LartYellow else Color.Transparent)
                        .clickable { viewMode = ThermistorsViewMode.CHART }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.BarChart,
                            contentDescription = "Gráfico",
                            tint = if (viewMode == ThermistorsViewMode.CHART) Color.Black else DarkTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Gráfico",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (viewMode == ThermistorsViewMode.CHART) Color.Black else DarkTextSecondary
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (viewMode == ThermistorsViewMode.LIST) LartYellow else Color.Transparent)
                        .clickable { viewMode = ThermistorsViewMode.LIST }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.FormatListBulleted,
                            contentDescription = "Lista",
                            tint = if (viewMode == ThermistorsViewMode.LIST) Color.Black else DarkTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Lista",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (viewMode == ThermistorsViewMode.LIST) Color.Black else DarkTextSecondary
                        )
                    }
                }
            }
        }

        // SEGMENT FILTER CHIPS
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = selectedSegmentId == null,
                onClick = { viewModel.selectSegment(null) },
                label = { Text("Todos (${state.thermistors.size.coerceAtLeast(72)})") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = LartYellow,
                    selectedLabelColor = Color.Black,
                    containerColor = DarkSurface,
                    labelColor = DarkTextSecondary
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selectedSegmentId == null,
                    borderColor = DarkBorder,
                    selectedBorderColor = LartYellow
                )
            )

            for (s in 1..state.car.nSegments) {
                val isSel = selectedSegmentId == s
                FilterChip(
                    selected = isSel,
                    onClick = { viewModel.selectSegment(if (isSel) null else s) },
                    label = { Text("Seg $s") },
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

        // VIEW CONTENT
        if (viewMode == ThermistorsViewMode.CHART) {
            // 📊 1. GROUPED BAR CHART (WITH MAXIMIZE OPTION)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ThermistorBarChart(
                    thermistors = filteredThermistors,
                    tMinLimit = 0.0f,
                    tMaxLimit = 70.0f,
                    tWarn = state.car.limits.tempWarn,
                    tFault = state.car.limits.tempFault,
                    brokenThermistors = state.car.brokenThermistors,
                    modifier = Modifier.fillMaxWidth()
                )

                // Quick stats card
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurface)
                        .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "TEMP. MÍNIMA", fontSize = 9.sp, color = DarkTextMuted, fontWeight = FontWeight.Bold)
                        Text(
                            text = "%.1f °C".format(minT),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF0A84FF),
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "TEMP. MÉDIA", fontSize = 9.sp, color = DarkTextMuted, fontWeight = FontWeight.Bold)
                        Text(
                            text = "%.1f °C".format(avgT),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            color = LartBlueAccent,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "DESVIO Δ", fontSize = 9.sp, color = DarkTextMuted, fontWeight = FontWeight.Bold)
                        Text(
                            text = "%.1f °C".format(deltaT),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            color = if (deltaT > 5f) Color(0xFFFFD60A) else StatusOkGlow,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "TEMP. MÁXIMA", fontSize = 9.sp, color = DarkTextMuted, fontWeight = FontWeight.Bold)
                        Text(
                            text = "%.1f °C".format(maxT),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            color = if (maxT >= state.car.limits.tempWarn) Color(0xFFFF453A) else StatusOkGlow,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        } else {
            // ⊞ 2. LIST VIEW (GROUPED BY SLAVES)
            val groupedBySlave = remember(filteredThermistors) {
                filteredThermistors.groupBy { it.slave }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(groupedBySlave.keys.sorted()) { slaveId ->
                    val ntcList = groupedBySlave[slaveId] ?: emptyList()
                    val segment = ntcList.firstOrNull()?.segment ?: ((slaveId - 1) / 2 + 1)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkSurface)
                            .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SLAVE $slaveId (Segmento $segment)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = DarkTextPrimary
                            )
                            val slaveTemps = ntcList
                                .filter { !state.car.brokenThermistors.contains("${it.slave}:${it.index}") }
                                .mapNotNull { it.temperature }
                            val sMax = slaveTemps.maxOrNull() ?: 0.0f
                            Text(
                                text = "Máx: %.1f °C".format(sMax),
                                fontSize = 12.sp,
                                color = if (sMax >= state.car.limits.tempWarn) StatusWarn else DarkTextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ntcList.forEach { ntc ->
                                val isBroken = state.car.brokenThermistors.contains("${ntc.slave}:${ntc.index}")
                                val bg = when {
                                    isBroken -> Color(0xFF262A30)
                                    ntc.status == Severity.FAULT -> StatusFault
                                    ntc.status == Severity.WARN -> StatusWarn
                                    ntc.temperature != null -> DarkSurfaceElevated
                                    else -> DarkSurfaceElevated
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(bg)
                                        .border(1.dp, DarkBorder, RoundedCornerShape(6.dp))
                                        .padding(vertical = 6.dp, horizontal = 2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "NTC ${ntc.index}",
                                            fontSize = 8.sp,
                                            color = DarkTextMuted,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = when {
                                                isBroken -> "BYPASS"
                                                ntc.temperature != null -> "%.1f°".format(ntc.temperature)
                                                else -> "—"
                                            },
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isBroken) DarkTextMuted else Color.White,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
