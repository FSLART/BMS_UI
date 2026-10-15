package pt.fslart.bms.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import pt.fslart.bms.model.Severity
import pt.fslart.bms.ui.theme.*
import pt.fslart.bms.viewmodel.BmsViewModel

@Composable
fun ThermistorsScreen(
    viewModel: BmsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.bmsState.collectAsState()

    val validTemps = state.thermistors.mapNotNull { it.temperature }
    val maxT = validTemps.maxOrNull() ?: 0.0f
    val minT = validTemps.minOrNull() ?: 0.0f
    val avgT = if (validTemps.isNotEmpty()) validTemps.average().toFloat() else 0.0f

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // HEADER & SUMMARY
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "TEMPERATURAS NTC",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextMuted,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = "${state.thermistors.size} Sensores (6 por Slave)",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextPrimary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("MÁXIMA", fontSize = 9.sp, color = DarkTextMuted, fontWeight = FontWeight.Bold)
                        Text(
                            text = "%.1f °C".format(maxT),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (maxT >= 50f) StatusWarn else DarkTextPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("MÉDIA", fontSize = 9.sp, color = DarkTextMuted, fontWeight = FontWeight.Bold)
                        Text(
                            text = "%.1f °C".format(avgT),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = LartBlueAccent,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // SLAVES GROUPING (12 SLAVES)
        val groupedBySlave = state.thermistors.groupBy { it.slave }
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
                    val slaveTemps = ntcList.mapNotNull { it.temperature }
                    val sMax = slaveTemps.maxOrNull() ?: 0.0f
                    Text(
                        text = "Máx: %.1f °C".format(sMax),
                        fontSize = 12.sp,
                        color = if (sMax >= 50f) StatusWarn else DarkTextSecondary,
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
