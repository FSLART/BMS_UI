package pt.fslart.bms.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
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
    val context = LocalContext.current
    val areNotificationsEnabled = remember(context) {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

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

        if (!areNotificationsEnabled) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF332A00))
                        .border(1.dp, LartYellow, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🔔 NOTIFICAÇÕES DESATIVADAS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = LartYellow
                        )
                        Text(
                            text = "Ativa as notificações para ver o balão amarelo no topo do ecrã e telemetria contínua.",
                            fontSize = 10.sp,
                            color = DarkTextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LartYellow, contentColor = Color.Black),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("Ativar", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // CHARGING STATUS BANNER
        item {
            val isCharging = state.isCharging
            val bannerBg = if (isCharging) Color(0xFF142B1E) else DarkSurface
            val bannerBorder = if (isCharging) StatusOk else DarkBorder

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(bannerBg)
                    .border(1.dp, bannerBorder, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                            text = if (isCharging) "Corrente de carga ativa · Balão de estado visível" else "À espera de comando ou clique em Simular",
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

                // TEST / SIMULATION TOGGLE BUTTON
                Button(
                    onClick = { viewModel.toggleManualCharging() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCharging) StatusFault else LartYellow,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = if (isCharging) Icons.Default.Close else Icons.Default.ElectricBolt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isCharging) "⏹️ PARAR CARGA (FECHAR BALÃO)" else "⚡ SIMULAR / ATIVAR CARGA (VER BALÃO)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
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
