package pt.fslart.bms.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import pt.fslart.bms.ui.components.KpiCard
import pt.fslart.bms.ui.theme.*
import pt.fslart.bms.viewmodel.BmsViewModel

@Composable
fun ChargerScreen(
    viewModel: BmsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.bmsState.collectAsState()
    val charger = state.charger

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = "ESTAÇÃO DE CARREGAMENTO",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkTextMuted,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "Handcart / EV Europe OEM3",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkTextPrimary
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
                        text = if (charger.present) "Handcart conectado ao CAN2" else "Nenhum sinal do handcart detetado",
                        fontSize = 11.sp,
                        color = DarkTextMuted
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isCharging) StatusOk else DarkSurfaceElevated)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isCharging) "ON" else "IDLE",
                        color = if (isCharging) Color.Black else DarkTextSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // LARGE ENERGY METER
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurfaceCard)
                    .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "ENERGIA CARREGADA (SESSÃO)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DarkTextSecondary,
                    letterSpacing = 0.6.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.Bottom) {
                    val kwh = charger.energyWh / 1000.0f
                    Text(
                        text = "%.2f".format(kwh),
                        fontSize = 54.sp,
                        fontWeight = FontWeight.Black,
                        color = LartYellow,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "kWh",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
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
