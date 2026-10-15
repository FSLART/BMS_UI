package pt.fslart.bms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pt.fslart.bms.model.Ams
import pt.fslart.bms.model.Safety
import pt.fslart.bms.ui.theme.*

@Composable
fun SafetyGrid(
    safety: Safety,
    ams: Ams,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurface)
            .border(1.dp, DarkBorder, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(
            text = "SEGURANÇA & AMS",
            color = DarkTextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Safety Status Pills in Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SafetyPill(
                label = "AMS",
                active = safety.amsOk,
                okText = "OK",
                failText = "ERRO",
                modifier = Modifier.weight(1f)
            )
            SafetyPill(
                label = "SDC",
                active = safety.sdcClosed,
                okText = "FECHADO",
                failText = "ABERTO",
                modifier = Modifier.weight(1f)
            )
            SafetyPill(
                label = "PRÉ-CARGA",
                active = safety.prechargeDone,
                okText = "HV ON",
                failText = safety.prechargeState,
                modifier = Modifier.weight(1.3f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SafetyPill(
                label = "AIR+",
                active = safety.airPositive,
                okText = "ON",
                failText = "OFF",
                modifier = Modifier.weight(1f)
            )
            SafetyPill(
                label = "AIR−",
                active = safety.airNegative,
                okText = "ON",
                failText = "OFF",
                modifier = Modifier.weight(1f)
            )
            SafetyPill(
                label = "ESTADO",
                active = safety.masterState != "FAULT",
                okText = safety.masterState,
                failText = "FALHA",
                modifier = Modifier.weight(1.3f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Readouts row (FAN, MCU Temp, Slaves)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DarkSurfaceElevated)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StateMetric(
                label = "VENTOINHA",
                value = if (ams.fanPwm != null) "${ams.fanPwm.toInt()}%" else "—"
            )
            StateMetric(
                label = "MCU MASTER",
                value = if (ams.mcuTemperature != null) "%.1f °C".format(ams.mcuTemperature) else "—"
            )
            StateMetric(
                label = "SLAVES",
                value = if (ams.slavesDetected != null) "${ams.slavesDetected}" else "—",
                color = if (ams.slavesDetected != null && ams.slavesDetected < 12) StatusWarn else DarkTextPrimary
            )
            StateMetric(
                label = "PEC CRC",
                value = if (ams.pecError) "FALHA" else "OK",
                color = if (ams.pecError) StatusFault else StatusOk
            )
        }
    }
}

@Composable
fun SafetyPill(
    label: String,
    active: Boolean,
    okText: String,
    failText: String,
    modifier: Modifier = Modifier
) {
    val bgColor = if (active) Color(0xFF142B1E) else Color(0xFF331718)
    val dotColor = if (active) StatusOk else StatusFault
    val textColor = if (active) StatusOkGlow else StatusFault

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, dotColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = DarkTextMuted,
                letterSpacing = 0.4.sp
            )
            Text(
                text = if (active) okText else failText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun StateMetric(
    label: String,
    value: String,
    color: Color = DarkTextPrimary
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = DarkTextMuted,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = color
        )
    }
}
