package pt.fslart.bms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
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
import pt.fslart.bms.model.Segment
import pt.fslart.bms.model.Severity
import pt.fslart.bms.ui.theme.*

@Composable
fun SegmentCard(
    segment: Segment,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val statusColor = when (segment.status) {
        Severity.OK -> StatusOk
        Severity.WARN -> StatusWarn
        Severity.FAULT -> StatusFault
        Severity.UNKNOWN -> StatusUnknown
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurfaceCard)
            .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = segment.name,
                        color = DarkTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (segment.balancingActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(LartYellow.copy(alpha = 0.2f))
                                .border(1.dp, LartYellow, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "A BALANCEAR",
                                color = LartYellow,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column {
                        Text(text = "TENSÃO", fontSize = 9.sp, color = DarkTextMuted, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "%.2f V".format(segment.voltage),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = DarkTextPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Column {
                        Text(text = "CÉLULAS (MIN / MAX)", fontSize = 9.sp, color = DarkTextMuted, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "%.3f - %.3f V".format(segment.cellVMin, segment.cellVMax),
                            fontSize = 13.sp,
                            color = DarkTextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Column {
                        Text(text = "TEMP MÁX", fontSize = 9.sp, color = DarkTextMuted, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "%.1f °C".format(segment.tempMax),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (segment.tempMax >= 50f) StatusWarn else DarkTextPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Ver segmento",
                tint = DarkTextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
