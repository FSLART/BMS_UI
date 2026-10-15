package pt.fslart.bms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import pt.fslart.bms.model.Cell
import pt.fslart.bms.model.Severity
import pt.fslart.bms.ui.theme.*

@Composable
fun CellItem(
    cell: Cell,
    vLow: Float = 2.80f,
    vHigh: Float = 4.15f,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cellBg = getCellColor(cell.voltage, cell.status, vLow, vHigh)
    val isFault = cell.status == Severity.FAULT || cell.openWire
    val borderColor = when {
        cell.balancing -> LartYellow
        isFault -> StatusFault
        else -> DarkBorder.copy(alpha = 0.5f)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(cellBg)
            .border(if (cell.balancing || isFault) 1.5.dp else 0.5.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#${cell.index}",
                    fontSize = 9.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )

                if (cell.balancing) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(2.dp))
                            .background(LartYellow)
                            .padding(horizontal = 2.dp)
                    ) {
                        Text(
                            text = "BAL",
                            fontSize = 7.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = if (cell.status == Severity.UNKNOWN) "—.——" else "%.3f".format(cell.voltage),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontFamily = FontFamily.Monospace
            )

            if (cell.openWire) {
                Text(
                    text = "ABERTO",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            }
        }
    }
}
