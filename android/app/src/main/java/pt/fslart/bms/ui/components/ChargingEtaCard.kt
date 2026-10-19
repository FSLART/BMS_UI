package pt.fslart.bms.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pt.fslart.bms.model.BmsState
import pt.fslart.bms.ui.theme.*
import pt.fslart.bms.util.ChargingEta
import pt.fslart.bms.util.ChargingEtaCalculator

@Composable
fun ChargingEtaCard(
    state: BmsState,
    modifier: Modifier = Modifier
) {
    var selectedCurrent by remember { mutableStateOf<Float?>(null) }
    var showCurrentSelector by remember { mutableStateOf(false) }

    val eta: ChargingEta = remember(state.pack.soc, state.pack.voltage, state.pack.current, state.car, selectedCurrent) {
        ChargingEtaCalculator.calculate(state, selectedCurrent)
    }

    val availableCurrents = listOf(3.0f, 6.0f, 10.0f, 12.0f)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.2.dp, LartYellow.copy(alpha = 0.8f), RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14181E))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // HEADER
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(LartYellow.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = "ETA",
                            tint = LartYellow,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "PREVISÃO DE CARGA (ETA)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = LartYellow,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "Handcart T26 · %.1f A".format(eta.chargeCurrentA),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = DarkTextPrimary
                        )
                    }
                }

                // Current Switcher Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceElevated)
                        .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
                        .clickable { showCurrentSelector = !showCurrentSelector }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Ajustar Corrente",
                            tint = LartYellow,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "%.0f A".format(eta.chargeCurrentA),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = DarkTextPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // OPTIONAL CURRENT SELECTOR
            AnimatedVisibility(visible = showCurrentSelector) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E232B))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "Ajustar Corrente do Carregador:",
                        fontSize = 10.sp,
                        color = DarkTextMuted,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        availableCurrents.forEach { amp ->
                            val isSelected = (selectedCurrent == amp) || (selectedCurrent == null && amp == 6.0f)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) LartYellow else DarkSurfaceElevated)
                                    .border(1.dp, if (isSelected) LartYellow else DarkBorder, RoundedCornerShape(6.dp))
                                    .clickable {
                                        selectedCurrent = amp
                                        showCurrentSelector = false
                                    }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (amp == 6.0f) "6A (Fixo)" else "%.0fA".format(amp),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.Black else DarkTextSecondary
                                )
                            }
                        }
                    }
                }
            }

            // DUAL MAIN ESTIMATION BLOCKS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // BLOCK 1: 80% TARGET (FAST CHARGE / PIT)
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, if (eta.isAlreadyAbove80) StatusOk.copy(alpha = 0.5f) else Color(0xFF2C323D), RoundedCornerShape(10.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2028))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "ATÉ 80% SoC",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (eta.isAlreadyAbove80) StatusOkGlow else LartBlueAccent
                            )
                            if (eta.isAlreadyAbove80) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "OK",
                                    tint = StatusOkGlow,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }

                        Text(
                            text = eta.formattedTimeTo80(),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (eta.isAlreadyAbove80) StatusOkGlow else Color.White,
                            fontFamily = FontFamily.Monospace
                        )

                        if (!eta.isAlreadyAbove80) {
                            Text(
                                text = "Previsto: ${eta.completionTime80}",
                                fontSize = 11.sp,
                                color = DarkTextSecondary
                            )
                            Text(
                                text = "Faltam %.1f kWh (%.1f Ah)".format(eta.remainingKwhTo80, eta.remainingAhTo80),
                                fontSize = 9.sp,
                                color = DarkTextMuted
                            )
                        } else {
                            Text(
                                text = "Pronto para Pista",
                                fontSize = 11.sp,
                                color = DarkTextSecondary
                            )
                        }
                    }
                }

                // BLOCK 2: 100% TARGET (FULL CHARGE & BALANCING)
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, if (eta.isFullyCharged) StatusOk.copy(alpha = 0.5f) else LartYellow.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2028))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "ATÉ 100% SoC",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = LartYellow
                            )
                            if (eta.isFullyCharged) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Full",
                                    tint = StatusOkGlow,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }

                        Text(
                            text = eta.formattedTimeTo100(),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (eta.isFullyCharged) StatusOkGlow else LartYellow,
                            fontFamily = FontFamily.Monospace
                        )

                        if (!eta.isFullyCharged) {
                            Text(
                                text = "Previsto: ${eta.completionTime100}",
                                fontSize = 11.sp,
                                color = DarkTextSecondary
                            )
                            Text(
                                text = "Faltam %.1f kWh (%.1f Ah)".format(eta.remainingKwhTo100, eta.remainingAhTo100),
                                fontSize = 9.sp,
                                color = DarkTextMuted
                            )
                        } else {
                            Text(
                                text = "Acumulador Balanceado",
                                fontSize = 11.sp,
                                color = DarkTextSecondary
                            )
                        }
                    }
                }
            }

            // PROGRESS BAR WITH 80% & 100% TARGET MARKERS
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color(0xFF222730))
                ) {
                    // Filled portion
                    val progressFraction = (eta.currentSoc / 100f).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = progressFraction)
                            .clip(RoundedCornerShape(7.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        LartBlueAccent,
                                        LartYellow,
                                        if (eta.currentSoc >= 80f) StatusOkGlow else LartYellow
                                    )
                                )
                            )
                    )

                    // 80% Marker line
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = 0.80f)
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(Color.White.copy(alpha = 0.8f))
                        )
                    }
                }

                // Markers legend
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "0%",
                        fontSize = 9.sp,
                        color = DarkTextMuted,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Atual: %.1f%%".format(eta.currentSoc),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "80% (CC)",
                        fontSize = 9.sp,
                        color = LartBlueAccent,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "100%",
                        fontSize = 9.sp,
                        color = LartYellow,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // CV PHASE ALERT IF ACTIVE
            if (eta.isCvPhase && !eta.isFullyCharged) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF2A2208))
                        .border(1.dp, LartYellow.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = LartYellow,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Fase CV (Tensão Constante) & Balanceamento de Células ativo.",
                        fontSize = 10.sp,
                        color = LartYellow,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // TECHNICAL FOOTER SPECS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Potência: %.2f kW".format(eta.chargingPowerKw),
                    fontSize = 10.sp,
                    color = DarkTextMuted,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Taxa: +%.1f%% / hora".format((eta.chargeCurrentA / eta.packCapacityAh) * 100f),
                    fontSize = 10.sp,
                    color = DarkTextMuted,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Pack: %.1f Ah (%.2f kWh)".format(eta.packCapacityAh, (state.car.nominalPackV * eta.packCapacityAh) / 1000f),
                    fontSize = 10.sp,
                    color = DarkTextMuted,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
