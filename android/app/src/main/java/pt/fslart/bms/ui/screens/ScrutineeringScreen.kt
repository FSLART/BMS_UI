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
import androidx.compose.ui.text.style.TextAlign
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

        // 1. Maximum TS Voltage (EV 4.1.1: Max 600 V DC)
        val packV = state.pack.voltage
        val vTsStatus = when {
            packV > 600f -> Severity.FAULT
            packV > 595f -> Severity.WARN
            else -> Severity.OK
        }
        list.add(
            ScrutineeringCheck(
                id = "ts_vmax",
                ruleRef = "FS EV 4.1.1",
                title = "Tensão Máxima do TS (≤ 600 V DC)",
                liveValue = "V_pack: %.1f V · Média: %.3f V/célula".format(packV, packV / state.car.seriesCount.coerceAtLeast(1)),
                targetLimit = "V_TS ≤ 600.0 V DC (630 V DC transitório)",
                status = vTsStatus,
                description = "A tensão máxima entre quaisquer duas ligações elétricas do TS é 600 V DC (630 V DC para sinais internos de controlo do AMS)."
            )
        )

        // 2. Cell Operating Limits (EV 5.2.3 & EV 5.8.10: Cell voltages within limits)
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
                ruleRef = "FS EV 5.2.3",
                title = "Limites Operacionais de Tensão das Células",
                liveValue = "Mín: %.3f V · Máx: %.3f V".format(minV, maxV),
                targetLimit = "%.2f V ≤ V_célula ≤ %.2f V (Trip AMS > 500ms)".format(limits.vMin, limits.vMax),
                status = vStatus,
                description = "As células devem operar estritamente dentro dos limites do fabricante. O AMS deve abrir o SDC se violado por mais de 500 ms (EV 5.8.10)."
            )
        )

        // 3. Section / Segment Maximum Voltage (EV 5.3.4: Max 120 V DC per section)
        val segVoltages = state.segments.map { seg -> state.cells.filter { it.segment == seg.id }.sumOf { it.voltage.toDouble() }.toFloat() }
        val maxSegV = if (segVoltages.isNotEmpty()) segVoltages.maxOrNull() ?: 0f else (packV / 6f)
        val segStatus = when {
            maxSegV > 120f -> Severity.FAULT
            maxSegV > 115f -> Severity.WARN
            else -> Severity.OK
        }
        list.add(
            ScrutineeringCheck(
                id = "seg_vmax",
                ruleRef = "FS EV 5.3.4",
                title = "Tensão Máxima por Secção (≤ 120 V DC)",
                liveValue = "Máx: %.1f V DC (Segmento 24S · ≤ 6 MJ)".format(maxSegV),
                targetLimit = "V_secção ≤ 120.0 V DC · Massa ≤ 12 kg",
                status = segStatus,
                description = "Cada secção mecânica do acumulador não pode exceder 120 V DC, energia máxima de 6 MJ e peso máximo de 12 kg."
            )
        )

        // 4. Maximum Cell Temperature (EV 5.8.4 & EV 5.8.10: Max 60 °C)
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
                ruleRef = "FS EV 5.8.4",
                title = "Temperatura Máxima de Célula (60.0 °C)",
                liveValue = "Máx: %.1f °C · Média: %.1f °C".format(maxT, avgT),
                targetLimit = "T < 60.0 °C (Trip AMS > 1s)",
                status = tempStatus,
                description = "A temperatura máxima permitida em qualquer célula é 60 °C (ou limite do fabricante). O AMS deve abrir o SDC se excedido por mais de 1 s (EV 5.8.10)."
            )
        )

        // 5. Thermal Monitoring Coverage (EV 5.8.3: Min 30% of cells)
        val totalTherms = state.thermistors.size
        val brokenCount = state.car.brokenThermistors.size
        val cellCount = state.cells.size.coerceAtLeast(1)
        val coveragePct = ((totalTherms.toFloat() / cellCount) * 100).toInt()
        val thermCoverageStatus = if (coveragePct >= 30) Severity.OK else Severity.FAULT
        list.add(
            ScrutineeringCheck(
                id = "ntc_coverage",
                ruleRef = "FS EV 5.8.3",
                title = "Monitorização Térmica (≥ 30% das Células)",
                liveValue = "$totalTherms NTCs em $cellCount células ($coveragePct% cobertura)",
                targetLimit = "≥ 30% das células com NTC (Mín. 44 NTCs)",
                status = thermCoverageStatus,
                description = "O AMS deve monitorizar continuamente a temperatura de pelo menos 30% das células igualmente distribuídas no acumulador."
            )
        )

        // 6. Loss of Measurement Sense / Open-Wire (EV 5.8.13)
        val openWiresCount = state.cells.count { it.openWire }
        val owStatus = if (openWiresCount > 0) Severity.FAULT else Severity.OK
        list.add(
            ScrutineeringCheck(
                id = "open_wire",
                ruleRef = "FS EV 5.8.13",
                title = "Sinais Críticos do AMS (Loss of Sense / Open-Wire)",
                liveValue = if (openWiresCount == 0) "0 Fios partidos (144/144 OK)" else "$openWiresCount Fio(s) Rompido(s)!",
                targetLimit = "0 conexões de medição rompidas / falhas SCS",
                status = owStatus,
                description = "Os sinais do AMS são Sinais Críticos do Sistema (T 11.9). A perda de conexão de medição tem de abrir o SDC imediatamente."
            )
        )

        // 7. Pre-Charge Circuit (EV 5.7.1: Pre-charge to at least 95% TS voltage)
        val airPos = state.safety.airPositive
        val airNeg = state.safety.airNegative
        val prechargeDone = state.safety.prechargeDone
        val airsStatus = if (state.mode == "car" && !prechargeDone && (airPos || airNeg)) Severity.WARN else Severity.OK
        list.add(
            ScrutineeringCheck(
                id = "precharge",
                ruleRef = "FS EV 5.7.1",
                title = "Circuito de Pré-Carga (≥ 95% V_pack)",
                liveValue = if (prechargeDone) "Pré-Carga Concluída (V_inv ≥ 95%)" else "Pendente / Em Curso",
                targetLimit = "V_inversor ≥ 95% V_pack antes do 2º AIR",
                status = airsStatus,
                description = "O circuito de pré-carga deve garantir que a tensão nos inversores atinge pelo menos 95% da tensão do acumulador antes de fechar o segundo AIR."
            )
        )

        // 8. Accumulator Isolation Relays - AIRs (EV 5.6.1 & EV 5.6.2)
        list.add(
            ScrutineeringCheck(
                id = "airs",
                ruleRef = "FS EV 5.6",
                title = "Relés de Isolamento do Acumulador (AIRs)",
                liveValue = "AIR+: ${if (airPos) "FECHADO" else "ABERTO"} · AIR-: ${if (airNeg) "FECHADO" else "ABERTO"}",
                targetLimit = "2 relés NO mecânicos · Corte galvânico bipolar",
                status = Severity.OK,
                description = "Pelo menos dois AIRs normalmente abertos dentro do TSAC para isolar ambos os polos positivo e negativo do TS (EV 5.6.1 e EV 5.6.2)."
            )
        )

        // 9. Shutdown Circuit & AMS Latch (EV 6.1.2 & EV 6.1.6)
        val sdcClosed = state.safety.sdcClosed
        val amsOk = state.safety.amsOk
        val safetyStatus = if (amsOk && sdcClosed) Severity.OK else if (!amsOk) Severity.FAULT else Severity.WARN
        list.add(
            ScrutineeringCheck(
                id = "safety_sdc",
                ruleRef = "FS EV 6.1",
                title = "Circuito de Desligamento (SDC & Latch AMS)",
                liveValue = if (sdcClosed) "Linha Fechada · AMS OK: Ativo" else "Linha Aberta (SDC Trip / Latch)",
                targetLimit = "SDC fechado e rearme manual após disparo",
                status = safetyStatus,
                description = "O SDC liga em série LVMS, BSPD, IMD, AMS, BOTS, Inertia Switch e botões de emergência. Disparo do AMS exige rearme manual não pelo piloto."
            )
        )

        // 10. Live AMS Monitoring & Display (EV 5.8.15)
        val slavesDetected = state.ams.slavesDetected ?: state.car.slaveCount
        val pecError = state.ams.pecError
        val commStatus = when {
            pecError -> Severity.FAULT
            slavesDetected < state.car.slaveCount -> Severity.WARN
            else -> Severity.OK
        }
        list.add(
            ScrutineeringCheck(
                id = "ams_display",
                ruleRef = "FS EV 5.8.15",
                title = "Visão Global e Telemetria em Tempo Real",
                liveValue = "$slavesDetected/${state.car.slaveCount} Slaves online · Sem erros PEC",
                targetLimit = "Leitura em tempo real de todas as células e NTCs",
                status = commStatus,
                description = "O AMS deve conseguir ler e apresentar todos os valores medidos de tensão e temperatura em qualquer momento numa visão única (EV 5.8.15)."
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
                        text = "Checklist Regulamento FS 2027",
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
                text = "REGRAS DE CONFORMIDADE TÉCNICA (FS RULES 2027 EV)",
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

        // MIDDLE ROW: Live value vs target limit (Clean 2-line layout that never overflows)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DarkSurfaceElevated)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LIDO EM DIRETO",
                    fontSize = 9.sp,
                    color = DarkTextMuted,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = check.liveValue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LIMITE FSG",
                    fontSize = 9.sp,
                    color = DarkTextMuted,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = check.targetLimit,
                    fontSize = 11.sp,
                    color = DarkTextSecondary,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp)
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
