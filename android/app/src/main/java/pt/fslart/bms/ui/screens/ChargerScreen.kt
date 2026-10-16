package pt.fslart.bms.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
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
import pt.fslart.bms.ui.components.KpiCard
import pt.fslart.bms.ui.components.Model3DViewer
import pt.fslart.bms.ui.theme.*
import pt.fslart.bms.viewmodel.BmsViewModel

enum class ChargerScreenMode {
    METRICS,
    MODEL_3D
}

@Composable
fun ChargerScreen(
    viewModel: BmsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.bmsState.collectAsState()
    val charger = state.charger
    var viewMode by remember { mutableStateOf(ChargerScreenMode.METRICS) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ESTAÇÃO DE CARREGAMENTO",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextMuted,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = "Handcart T26 / EV Europe",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextPrimary
                    )
                }

                // Mode Toggle: [ 📊 Painel | 🧊 3D ]
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
                            .background(if (viewMode == ChargerScreenMode.METRICS) LartYellow else Color.Transparent)
                            .clickable { viewMode = ChargerScreenMode.METRICS }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ElectricBolt,
                                contentDescription = "Painel",
                                tint = if (viewMode == ChargerScreenMode.METRICS) Color.Black else DarkTextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Painel",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (viewMode == ChargerScreenMode.METRICS) Color.Black else DarkTextSecondary
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (viewMode == ChargerScreenMode.MODEL_3D) LartYellow else Color.Transparent)
                            .clickable { viewMode = ChargerScreenMode.MODEL_3D }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = "Modelo 3D",
                                tint = if (viewMode == ChargerScreenMode.MODEL_3D) Color.Black else DarkTextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "3D",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (viewMode == ChargerScreenMode.MODEL_3D) Color.Black else DarkTextSecondary
                            )
                        }
                    }
                }
            }
        }

        if (viewMode == ChargerScreenMode.MODEL_3D) {
            // 3D TROLLEY VIEWER
            item {
                Model3DViewer(
                    modelFile = "tek26e_charger.glb",
                    bmsState = state,
                    height = 360.dp,
                    title = "Handcart T26 com Acumulador em Carga",
                    onMaximize = {
                        viewModel.openFullscreen3D(
                            modelFile = "tek26e_charger.glb",
                            title = "Handcart T26 com Acumulador em Carga"
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // CHARGING STATUS BANNER
        item {
            val isCharging = state.isCharging
            val bannerBg = if (isCharging) Color(0xFF142B1E) else DarkSurface
            val bannerBorder = if (isCharging) StatusOk else DarkBorder

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(bannerBg)
                    .border(1.dp, bannerBorder, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isCharging) "A CARREGAR ACUMULADOR" else "CARREGADOR EM ESPERA",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isCharging) StatusOkGlow else DarkTextSecondary
                    )
                    Text(
                        text = if (isCharging) "Corrente de carga controlada por CAN" else "À espera de comando de ativação",
                        fontSize = 11.sp,
                        color = DarkTextMuted
                    )
                }

                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (isCharging) StatusOk else DarkTextMuted)
                )
            }
        }

        // EXPLODED BATTERY & LIGHTNING STRIKE ANIMATION TRIGGER CARD
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF1B1602), Color(0xFF261F03), Color(0xFF0F1A12))
                        )
                    )
                    .border(1.2.dp, LartYellow.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                    .clickable { viewModel.triggerChargingAnimation() }
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(LartYellow.copy(alpha = 0.22f))
                                .border(1.dp, LartYellow, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ElectricBolt,
                                contentDescription = "Relâmpago",
                                tint = LartYellow,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "ANIMAÇÃO DE CARGA EXPLODIDA",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = LartYellow,
                                letterSpacing = 0.6.sp
                            )
                            Text(
                                text = "Ver Montagem 3D & Relâmpago ⚡",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        }
                    }

                    Surface(
                        color = LartYellow,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "VER",
                                color = Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }

        // METRICS SUMMARY
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurface)
                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = "MÉTRICAS DA SESSÃO",
                    fontSize = 10.sp,
                    color = DarkTextMuted,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Energia Fornecida", fontSize = 11.sp, color = DarkTextSecondary)
                        Text(
                            text = "%.1f Wh".format(charger.energyWh),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = DarkTextPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text("Potência Instantânea", fontSize = 11.sp, color = DarkTextSecondary)
                        val powerKw = (charger.outputVoltage * charger.outputCurrent) / 1000f
                        Text(
                            text = "%.2f kW".format(powerKw),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = LartYellow,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                val minutes = (charger.sessionS / 60).toInt()
                val seconds = (charger.sessionS % 60).toInt()
                Text(
                    text = "Tempo decorrido: %02d min %02d s".format(minutes, seconds),
                    fontSize = 12.sp,
                    color = DarkTextMuted,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // CHARGER READOUTS
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                KpiCard(
                    label = "Tensão Saída",
                    value = "%.1f".format(charger.outputVoltage),
                    unit = "V",
                    accentColor = LartBlueAccent,
                    modifier = Modifier.weight(1f)
                )
                KpiCard(
                    label = "Corrente Saída",
                    value = "%.2f".format(charger.outputCurrent),
                    unit = "A",
                    accentColor = LartYellow,
                    modifier = Modifier.weight(1f)
                )
                KpiCard(
                    label = "Corrente Pedida",
                    value = "%.1f".format(charger.requestedCurrent),
                    unit = "A",
                    accentColor = DarkTextPrimary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
