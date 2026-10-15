package pt.fslart.bms.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import pt.fslart.bms.model.Cell
import pt.fslart.bms.ui.components.CellItem
import pt.fslart.bms.ui.theme.*
import pt.fslart.bms.viewmodel.BmsViewModel

@Composable
fun CellsScreen(
    viewModel: BmsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.bmsState.collectAsState()
    val selectedSegmentId by viewModel.selectedSegmentId.collectAsState()

    var selectedCellForModal by remember { mutableStateOf<Cell?>(null) }

    val filteredCells = remember(state.cells, selectedSegmentId) {
        if (selectedSegmentId == null) {
            state.cells
        } else {
            state.cells.filter { it.segment == selectedSegmentId }
        }
    }

    val validVolts = remember(filteredCells) {
        filteredCells.filter { it.status != pt.fslart.bms.model.Severity.UNKNOWN }.map { it.voltage }
    }
    val minV = validVolts.minOrNull() ?: 0.0f
    val maxV = validVolts.maxOrNull() ?: 0.0f
    val deltaV = maxV - minV
    val avgV = if (validVolts.isNotEmpty()) validVolts.average().toFloat() else 0.0f

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // HEADER & STATS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "MATRIZ DE CÉLULAS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkTextMuted,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "${filteredCells.size} de ${state.cells.size} Células",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkTextPrimary
                )
            }

            // Stat pills
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("MIN / MAX", fontSize = 9.sp, color = DarkTextMuted, fontWeight = FontWeight.Bold)
                    Text(
                        text = "%.3f - %.3f V".format(minV, maxV),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = LartBlueAccent,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("DESVIO Δ", fontSize = 9.sp, color = DarkTextMuted, fontWeight = FontWeight.Bold)
                    Text(
                        text = "${(deltaV * 1000f).toInt()} mV",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (deltaV > 0.05f) StatusWarn else StatusOkGlow,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // SEGMENT FILTER CHIPS (SCROLLABLE HORIZONTALLY)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = selectedSegmentId == null,
                onClick = { viewModel.selectSegment(null) },
                label = { Text("Todos (144)") },
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

        // LEGEND BAR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DarkSurfaceElevated)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendDot(color = StatusOk, label = "Normal")
                LegendDot(color = StatusWarn, label = "Aviso")
                LegendDot(color = StatusFault, label = "Falha")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(LartYellow)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Borda amarela = Em balanceamento",
                    fontSize = 10.sp,
                    color = LartYellow,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // ADAPTIVE CELLS GRID (4 COLUMNS ON PHONE, EXPANDABLE)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 68.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            items(filteredCells) { cell ->
                CellItem(
                    cell = cell,
                    vLow = state.car.limits.vWarnLow,
                    vHigh = state.car.limits.vWarnHigh,
                    onClick = { selectedCellForModal = cell }
                )
            }
        }
    }

    // CELL DETAILS MODAL SHEET
    selectedCellForModal?.let { cell ->
        AlertDialog(
            onDismissRequest = { selectedCellForModal = null },
            title = {
                Text(
                    text = "Célula #${cell.index} (${cell.id})",
                    color = DarkTextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailRow(label = "Tensão Medida", value = "%.4f V".format(cell.voltage), highlight = true)
                    DetailRow(label = "Segmento", value = "Segmento ${cell.segment}")
                    DetailRow(label = "Módulo Slave", value = "Slave ${cell.slave} (Canal ${cell.slaveChannel})")
                    DetailRow(label = "Células em Paralelo", value = "${cell.parallel}p (${state.car.cellModel})")
                    DetailRow(
                        label = "Estado de Balanceamento",
                        value = if (cell.balancing) "ATIVO (A descarregar)" else "Inativo",
                        color = if (cell.balancing) LartYellow else DarkTextSecondary
                    )
                    DetailRow(
                        label = "Fio de Medição (Open-Wire)",
                        value = if (cell.openWire) "FALHA / FIO ABERTO" else "Conectado",
                        color = if (cell.openWire) StatusFault else StatusOk
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { selectedCellForModal = null },
                    colors = ButtonDefaults.buttonColors(containerColor = LartYellow, contentColor = Color.Black)
                ) {
                    Text("Fechar", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(14.dp)
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, fontSize = 10.sp, color = DarkTextSecondary)
    }
}

@Composable
private fun DetailRow(label: String, value: String, highlight: Boolean = false, color: Color = DarkTextPrimary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = DarkTextSecondary, fontSize = 12.sp)
        Text(
            text = value,
            color = if (highlight) LartYellow else color,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
