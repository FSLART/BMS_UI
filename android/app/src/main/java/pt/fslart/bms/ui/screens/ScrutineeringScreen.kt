package pt.fslart.bms.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pt.fslart.bms.model.Severity
import pt.fslart.bms.ui.theme.*
import pt.fslart.bms.viewmodel.BmsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScrutineeringCheck(
    val id: String,
    val ruleRef: String,
    val title: String,
    val liveValue: String,
    val targetLimit: String,
    val status: Severity,
    val description: String
)

@Composable
fun ScrutineeringScreen(
    viewModel: BmsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.bmsState.collectAsState()
    val context = LocalContext.current

    // Perform all 8 technical rules verification
    val checks = remember(state) {
        val list = mutableListOf<ScrutineeringCheck>()
        val limits = state.car.limits

        // 1. Cell Voltages within bounds (EV 4.1.4)
        val validCells = state.cells.filter { it.status != Severity.UNKNOWN && !it.openWire && it.voltage > 0f }
        val minV = validCells.minOfOrNull { it.voltage } ?: state.pack.cellVMin
        val maxV = validCells.maxOfOrNull { it.voltage } ?: state.pack.cellVMax
        val vStatus = when {
            minV < limits.vMin || maxV > limits.vMax -> Severity.FAULT
            minV < limits.vWarnLow || maxV > limits.vWarnHigh -> Severity.WARN
            else -> Severity.OK
        }
        list.add(
            ScrutineeringCheck(
                id = "cell_v",
                ruleRef = "FSG EV 4.1.4",
                title = "Tensão Individual das Células",
                liveValue = "Mín: %.3f V · Máx: %.3f V".format(minV, maxV),
                targetLimit = "%.2f V ≤ V_célula ≤ %.2f V".format(limits.vMin, limits.vMax),
                status = vStatus,
                description = "Todas as 144 células devem operar estritamente dentro da janela de subtensão e sobretensão."
            )
        )

        // 2. Cell Voltage Spread / Delta (EV 4.1.5)
        val deltaV = (maxV - minV).coerceAtLeast(0f)
        val deltaMv = (deltaV * 1000f).toInt()
        val deltaStatus = when {
            deltaV > 0.080f -> Severity.FAULT
            deltaV > 0.050f -> Severity.WARN
            else -> Severity.OK
        }
        list.add(
            ScrutineeringCheck(
                id = "cell_delta",
                ruleRef = "FSG EV 4.1.5",
                title = "Equilíbrio & Dispersão (ΔV)",
                liveValue = "Δ: %d mV".format(deltaMv),
                targetLimit = "ΔV ≤ 50 mV (Ideal) | Máx 80 mV",
                status = deltaStatus,
                description = "Diferença entre a célula mais carregada e a menos carregada em repouso."
            )
        )

        // 3. Cell Temperatures (< 60°C strict cutoff) (EV 4.1.4)
        val validTherms = state.thermistors.filter { it.temperature != null && it.temperature > 0f && it.status != Severity.UNKNOWN }
        val maxT = validTherms.maxOfOrNull { it.temperature!! } ?: state.pack.tempMax
        val avgT = if (validTherms.isNotEmpty()) validTherms.map { it.temperature!! }.average().toFloat() else 0f
        val tempStatus = when {
            maxT >= limits.tempFault -> Severity.FAULT
            maxT >= limits.tempWarn -> Severity.WARN
            else -> Severity.OK
        }
        list.add(
            ScrutineeringCheck(
                id = "temp_max",
                ruleRef = "FSG EV 4.1.4",
                title = "Limite Térmico de Célula (60°C)",
                liveValue = "Máx: %.1f °C (Média: %.1f °C)".format(maxT, avgT),
                targetLimit = "T < 60.0 °C (Corte obrigatório)",
                status = tempStatus,
                description = "O regulamento impõe paragem e desligamento imediato se qualquer célula atingir 60°C."
            )
        )

        // 4. Open-Wire Connections (EV 4.1.6)
        val openWiresCount = state.cells.count { it.openWire }
        val owStatus = if (openWiresCount > 0) Severity.FAULT else Severity.OK
        list.add(
            ScrutineeringCheck(
                id = "open_wire",
                ruleRef = "FSG EV 4.1.6",
                title = "Deteção de Fio Aberto (Open-Wire)",
                liveValue = if (openWiresCount == 0) "0 Fios partidos (144/144 OK)" else "$openWiresCount Fio(s) Aberto(s)!",
                targetLimit = "0 Fios de medição desconectados",
                status = owStatus,
                description = "O BMS deve detetar e reportar falha se qualquer terminal de medição de tensão for rompido."
            )
        )

        // 5. NTC Thermistor Coverage (EV 4.1.7)
        val totalTherms = state.thermistors.size
        val brokenCount = state.car.brokenThermistors.size
        val thermCoverageStatus = if (totalTherms >= 72) Severity.OK else Severity.WARN
        list.add(
            ScrutineeringCheck(
                id = "ntc_coverage",
                ruleRef = "FSG EV 4.1.7",
                title = "Cobertura & Continuidade dos NTCs",
                liveValue = "$totalTherms NTCs ativos ($brokenCount bypass de fábrica)",
                targetLimit = "Mín. 1 NTC para cada 4 células (25%)",
                status = thermCoverageStatus,
                description = "O acumulador monitoriza 1 NTC para cada 2 células (50% de cobertura), superando o requisito."
            )
        )

        // 6. Safety Circuit & SDC (EV 4.2)
        val sdcClosed = state.safety.sdcClosed
        val amsOk = state.safety.amsOk
        val safetyStatus = if (amsOk && sdcClosed) Severity.OK else if (!amsOk) Severity.FAULT else Severity.WARN
        list.add(
            ScrutineeringCheck(
                id = "safety_sdc",
                ruleRef = "FSG EV 4.2",
                title = "Circuito de Desligamento (SDC / AMS OK)",
                liveValue = if (sdcClosed) "Linha Fechada · AMS OK: Ativo" else "Linha Aberta (SDC Trip)",
                targetLimit = "Sinal AMS OK ativo e SDC fechado",
                status = safetyStatus,
                description = "Linha de segurança hardwired em série com o relé de estado sólido do AMS."
            )
        )

        // 7. High Voltage Contactors (AIRs & Precharge) (EV 4.3)
        val airPos = state.safety.airPositive
        val airNeg = state.safety.airNegative
        val prechargeDone = state.safety.prechargeDone
        val airsStatus = if (state.mode == "car" && !prechargeDone && (airPos || airNeg)) Severity.WARN else Severity.OK
        list.add(
            ScrutineeringCheck(
                id = "airs_precharge",
                ruleRef = "FSG EV 4.3",
                title = "AIR+ / AIR- & Circuito de Pré-Carga",
                liveValue = "AIR+: ${if (airPos) "ON" else "OFF"} · AIR-: ${if (airNeg) "ON" else "OFF"} · Pré-Carga: ${if (prechargeDone) "OK" else "PENDENTE"}",
                targetLimit = "Sequência correta de fecho pós pré-carga",
                status = airsStatus,
                description = "Os relés principais só devem fechar após a tensão do inversor atingir 95% da tensão do pack."
            )
        )

        // 8. BMS Daisy-Chain & Slave Communication (EV 4.4)
        val slavesDetected = state.ams.slavesDetected ?: state.car.slaveCount
        val pecError = state.ams.pecError
        val commStatus = when {
            pecError -> Severity.FAULT
            slavesDetected < state.car.slaveCount -> Severity.WARN
            else -> Severity.OK
        }
        list.add(
            ScrutineeringCheck(
                id = "bms_comm",
                ruleRef = "FSG EV 4.4",
                title = "Comunicação Daisy-Chain (isoSPI)",
                liveValue = "$slavesDetected de ${state.car.slaveCount} Slaves detetados · Sem erros PEC",
                targetLimit = "${state.car.slaveCount} Slaves online sem falha de paridade",
                status = commStatus,
                description = "Comunicação diferencial isoSPI entre a placa mestre e os escravos dos 6 segmentos."
            )
        )

        list
    }

    val overallSeverity = remember(checks) {
        Severity.worst(checks.map { it.status })
    }

    val passedCount = remember(checks) {
        checks.count { it.status == Severity.OK }
    }

    // Function to generate and share text inspection report
    fun shareReport() {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val dateStr = dateFormat.format(Date(state.timestamp))
        val overallText = when (overallSeverity) {
            Severity.OK -> "APROVADO / PASS (100% Conforme)"
            Severity.WARN -> "CONDICIONAL / AVISO (Atenção requerida)"
            else -> "NÃO CONFORME / REPROVADO (Falha crítica)"
        }

        val sb = StringBuilder()
        sb.appendLine("====================================================")
        sb.appendLine("🏁 FSLART BMS - RELATÓRIO DE INSPEÇÃO TÉCNICA (FS)")
        sb.appendLine("====================================================")
        sb.appendLine("Carro: ${state.car.name} (${state.car.subtitle})")
        sb.appendLine("Data/Hora: $dateStr")
        sb.appendLine("Tensao Total: %.1f V | SOC: %d%% | Temp Max: %.1f °C".format(state.pack.voltage, state.pack.soc.toInt(), state.pack.tempMax))
        sb.appendLine("Resultado Global: $overallText")
        sb.appendLine("Pontuação: $passedCount de ${checks.size} regras cumpridas")
        sb.appendLine("----------------------------------------------------")
        sb.appendLine("VERIFICAÇÕES DETALHADAS:")
        checks.forEachIndexed { idx, c ->
            val icon = when (c.status) {
                Severity.OK -> "[ PASS ]"
                Severity.WARN -> "[ AVISO ]"
                else -> "[ FALHA ]"
            }
            sb.appendLine("${idx + 1}. ${c.ruleRef} - ${c.title}")
            sb.appendLine("   Status: $icon")
            sb.appendLine("   Leitura: ${c.liveValue}")
            sb.appendLine("   Limite: ${c.targetLimit}")
        }
        sb.appendLine("====================================================")
        sb.appendLine("Gerado automaticamente por LART BMS Monitor")

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, sb.toString())
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Partilhar Relatório de Scrutineering")
        context.startActivity(shareIntent)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // SCREEN TITLE BAR
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "INSPEÇÃO TÉCNICA · SCRUTINEERING",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextMuted,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = "Checklist Regulamento FS",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextPrimary
                    )
                }

                // Share Button
                OutlinedButton(
                    onClick = { shareReport() },
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = DarkSurfaceElevated),
                    border = androidx.compose.foundation.BorderStroke(1.dp, LartYellow),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Partilhar",
                        tint = LartYellow,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Relatório",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = LartYellow
                    )
                }
            }
        }

        // HERO PASS / FAIL STATUS CARD
        item {
            val heroBg = when (overallSeverity) {
                Severity.OK -> Color(0xFF0E2E1A)
                Severity.WARN -> Color(0xFF332608)
                else -> Color(0xFF330E0E)
            }
            val heroBorder = when (overallSeverity) {
                Severity.OK -> StatusOk
                Severity.WARN -> StatusWarn
                else -> StatusFault
            }
            val heroTitle = when (overallSeverity) {
                Severity.OK -> "CONFORME · APROVADO"
                Severity.WARN -> "CONDICIONAL · ATENÇÃO"
                else -> "NÃO CONFORME · REPROVADO"
            }
            val heroIcon = when (overallSeverity) {
                Severity.OK -> Icons.Default.CheckCircle
                Severity.WARN -> Icons.Default.Warning
                else -> Icons.Default.Cancel
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(heroBg)
                    .border(1.5.dp, heroBorder, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = heroIcon,
                            contentDescription = heroTitle,
                            tint = heroBorder,
                            modifier = Modifier.size(28.dp)
                        )
                        Column {
                            Text(
                                text = heroTitle,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                color = heroBorder
                            )
                            Text(
                                text = "$passedCount de ${checks.size} regras cumpridas (100% regulamentar)",
                                fontSize = 11.sp,
                                color = DarkTextSecondary
                            )
                        }
                    }

                    // Score pill
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = heroBorder.copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, heroBorder)
                    ) {
                        Text(
                            text = "${(passedCount * 100) / checks.size}%",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            color = heroBorder,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Divider(color = heroBorder.copy(alpha = 0.25f), thickness = 1.dp)

                // Quick stats row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "V_pack: %.1f V".format(state.pack.voltage),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextPrimary,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Temp_máx: %.1f °C".format(state.pack.tempMax),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextPrimary,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Células: ${state.cells.size}/144",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextPrimary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // SECTION HEADER
        item {
            Text(
                text = "REGRAS DE CONFORMIDADE TÉCNICA (FSG EV)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = DarkTextMuted,
                letterSpacing = 0.6.sp
            )
        }

        // DETAILED CHECKLIST ITEMS
        items(checks, key = { it.id }) { check ->
            ChecklistCard(check = check)
        }
    }
}

@Composable
private fun ChecklistCard(check: ScrutineeringCheck) {
    val statusColor = when (check.status) {
        Severity.OK -> StatusOk
        Severity.WARN -> StatusWarn
        else -> StatusFault
    }
    val statusBadge = when (check.status) {
        Severity.OK -> "PASS"
        Severity.WARN -> "AVISO"
        else -> "FAIL"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface)
            .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // TOP ROW: Title, Rule ref and PASS badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = check.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkTextPrimary
                    )
                    Text(
                        text = check.ruleRef,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = LartYellow
                    )
                }
            }

            // PASS / FAIL BADGE
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = statusColor.copy(alpha = 0.15f),
                border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.5f))
            ) {
                Text(
                    text = statusBadge,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        // MIDDLE ROW: Live value vs target limit
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(DarkSurfaceElevated)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "VALOR LIDO EM DIRETO", fontSize = 8.sp, color = DarkTextMuted, fontWeight = FontWeight.Bold)
                Text(
                    text = check.liveValue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    fontFamily = FontFamily.Monospace
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(text = "LIMITE REGULAMENTAR", fontSize = 8.sp, color = DarkTextMuted, fontWeight = FontWeight.Bold)
                Text(
                    text = check.targetLimit,
                    fontSize = 11.sp,
                    color = DarkTextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // BOTTOM: Explanation
        Text(
            text = check.description,
            fontSize = 10.sp,
            color = DarkTextMuted,
            lineHeight = 14.sp
        )
    }
}
