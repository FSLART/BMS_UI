package pt.fslart.bms.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pt.fslart.bms.ui.theme.*

@Composable
fun TelemetrySparkline(
    currentHistory: List<Float>,
    voltageHistory: List<Float>,
    currentLive: Float,
    voltageLive: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurface)
            .border(1.dp, DarkBorder, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CORRENTE & TENSÃO (TEMPO REAL)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = DarkTextSecondary,
                letterSpacing = 0.5.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(LartYellow))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "I: %.1f A".format(currentLive),
                        color = LartYellow,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(LartBlueAccent))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "V: %.1f V".format(voltageLive),
                        color = LartBlueAccent,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(85.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DarkSurfaceElevated)
                .border(1.dp, DarkBorder.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 6.dp)) {
                val w = size.width
                val h = size.height

                // Draw center zero reference line for current
                drawLine(
                    color = DarkBorder,
                    start = Offset(0f, h * 0.5f),
                    end = Offset(w, h * 0.5f),
                    strokeWidth = 1.dp.toPx()
                )

                // Draw current trace (Yellow, can be positive/negative)
                if (currentHistory.size >= 2) {
                    val maxAbsI = currentHistory.map { kotlin.math.abs(it) }.maxOrNull()?.coerceAtLeast(30f) ?: 100f
                    val pathI = Path()
                    val stepX = w / (currentHistory.size - 1).toFloat()

                    currentHistory.forEachIndexed { idx, value ->
                        val x = idx * stepX
                        // Map 0 to h*0.5, +maxAbsI to 0, -maxAbsI to h
                        val normalized = (-value / (maxAbsI * 2f)) + 0.5f
                        val y = (normalized * h).coerceIn(0f, h)
                        if (idx == 0) pathI.moveTo(x, y) else pathI.lineTo(x, y)
                    }

                    drawPath(
                        path = pathI,
                        color = LartYellow,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }

                // Draw voltage trace (Blue accent)
                if (voltageHistory.size >= 2) {
                    val minV = (voltageHistory.minOrNull() ?: 450f) - 5f
                    val maxV = (voltageHistory.maxOrNull() ?: 600f) + 5f
                    val deltaV = (maxV - minV).coerceAtLeast(10f)

                    val pathV = Path()
                    val stepX = w / (voltageHistory.size - 1).toFloat()

                    voltageHistory.forEachIndexed { idx, value ->
                        val x = idx * stepX
                        val normalized = 1.0f - ((value - minV) / deltaV)
                        val y = (normalized * h).coerceIn(0f, h)
                        if (idx == 0) pathV.moveTo(x, y) else pathV.lineTo(x, y)
                    }

                    drawPath(
                        path = pathV,
                        color = LartBlueAccent.copy(alpha = 0.85f),
                        style = Stroke(width = 1.8.dp.toPx())
                    )
                }
            }
        }
    }
}
