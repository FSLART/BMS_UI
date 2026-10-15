package pt.fslart.bms.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class LiveJsonDecoder(var car: CarProfile = CarProfile.TEK26E) {

    private var lastAmsTimestamp: Long = 0L
    private var rxCount: Long = 0L
    private var droppedSamples: Long = 0L
    private var lastSeq: Long? = null
    private var chgEnergyWh: Float = 0.0f
    private var chgStarted: Long? = null
    private var chgLast: Long? = null

    companion object {
        val AMS_STATES = listOf(
            "BALANCING", "CHARGING", "IDLE", "ONMISSION",
            "STARTUP", "DISCHARGE_TEST", "RESET_ISA", "FAULT"
        )

        val PRECHARGE_STATES = listOf(
            "START", "OPEN_ALL", "SWITCH_HVNEG", "WAIT_FOR_AIR_NEG_TO_CLOSE",
            "CHECKING_AIR_NEG_IS_CLOSED", "SWITCH_PRECHARGE", "WAIT_FOR_PRECHARGE_TO_CLOSE",
            "CHECKING_PRECHARGE_IS_CLOSED", "VERIFY_CURRENT", "VERIFY_BUS_VOLT",
            "SWITCH_HVPOS", "WAIT_FOR_AIR_POS_TO_CLOSE", "CHECKING_AIR_POS_IS_CLOSED",
            "TURN_OFF_PRECHARGE", "WAIT_FOR_PRECHARGE_TO_OPEN", "CHECKING_PRECHARGE_IS_OPEN",
            "HV_ON", "WRONG", "KILL", "RX_CAN"
        )
        const val HV_ON = "HV_ON"

        val CHARGER_STATES = listOf("WAIT_HV", "PRESTART_STOP", "CHARGING", "STOPPING", "DONE")

        private fun nameFromTable(table: List<String>, value: Any?): String {
            if (value is String) return value
            if (value is Number) {
                val idx = value.toInt()
                if (idx in table.indices) return table[idx]
                return idx.toString()
            }
            return ""
        }

        private fun bit(mask: Int?, n: Int): Boolean {
            if (mask == null) return false
            return (mask and (1 shl n)) != 0
        }
    }

    fun isLive(now: Long = System.currentTimeMillis()): Boolean {
        return lastAmsTimestamp > 0 && (now - lastAmsTimestamp) <= 3500L
    }

    fun decodeLine(jsonLine: String, now: Long = System.currentTimeMillis()): BmsState? {
        val trimmed = jsonLine.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null
        }

        return try {
            val root = JsonParser.parseString(trimmed).asJsonObject
            processJson(root, now)
        } catch (e: Exception) {
            null
        }
    }

    private fun processJson(raw: JsonObject, now: Long): BmsState {
        rxCount++
        lastAmsTimestamp = now

        // Check sequence / dropped samples
        val jsonMeta = raw.getAsJsonObject("json")
        if (jsonMeta != null) {
            val sent = if (jsonMeta.has("sent") && !jsonMeta.get("sent").isJsonNull) jsonMeta.get("sent").asLong else null
            if (sent != null) {
                val prev = lastSeq
                if (prev != null && sent > prev + 1) {
                    droppedSamples += (sent - prev - 1)
                }
                lastSeq = sent
            }
        }

        val bms = raw.getAsJsonObject("bms") ?: JsonObject()
        val ae = raw.getAsJsonObject("ams_error") ?: JsonObject()
        val pre = raw.getAsJsonObject("precharge") ?: JsonObject()
        val packObj = raw.getAsJsonObject("pack") ?: JsonObject()
        val bal = raw.getAsJsonObject("balancing") ?: JsonObject()
        val board = raw.getAsJsonObject("board") ?: JsonObject()
        val soc = raw.getAsJsonObject("soc") ?: JsonObject()
        val chg = raw.getAsJsonObject("charger") ?: JsonObject()
        val i1 = raw.getAsJsonObject("isa_pack") ?: JsonObject()
        val i2 = raw.getAsJsonObject("isa_handcart") ?: JsonObject()

        val pecFlagsArray = raw.getAsJsonArray("pec_flags")
        val cellMvArray = raw.getAsJsonArray("cell_mV")
        val ntcDcArray = raw.getAsJsonArray("ntc_dC")
        val owCellArray = raw.getAsJsonArray("ow_cell_mask")
        val owNtcArray = raw.getAsJsonArray("ow_ntc_mask")
        val balMaskArray = bal.getAsJsonArray("mask")

        val slavesDetected = if (packObj.has("slaves_found") && !packObj.get("slaves_found").isJsonNull) {
            packObj.get("slaves_found").asInt
        } else car.slaveCount

        // 1. Build Cells
        val cells = mutableListOf<Cell>()
        val limits = car.limits

        for (si in 0 until car.slaveCount) {
            val perSeg = car.slavesPerSegment
            val segment = (si / perSeg) + 1
            val first = (si % perSeg) * car.cellsPerSlave

            val row = if (cellMvArray != null && si < cellMvArray.size() && cellMvArray.get(si).isJsonArray) {
                cellMvArray.get(si).asJsonArray
            } else null

            val owMask = if (owCellArray != null && si < owCellArray.size()) {
                owCellArray.get(si).asInt
            } else 0

            val balMask = if (balMaskArray != null && si < balMaskArray.size()) {
                balMaskArray.get(si).asInt
            } else 0

            val pecFlag = if (pecFlagsArray != null && si < pecFlagsArray.size()) {
                pecFlagsArray.get(si).asInt
            } else 0

            val trust = (pecFlag and 1) == 0 && si < slavesDetected

            for (ch in 0 until car.cellsPerSlave) {
                val mv = if (row != null && ch < row.size() && !row.get(ch).isJsonNull) {
                    row.get(ch).asInt
                } else null

                val v = if (mv != null && mv > 0) mv / 1000.0f else null
                val openWire = bit(owMask, ch)
                val balancing = bit(balMask, ch)

                val status = when {
                    v == null || !trust -> Severity.UNKNOWN
                    openWire -> Severity.FAULT
                    v >= limits.vMax || v <= limits.vMin -> Severity.FAULT
                    v >= limits.vWarnHigh || v <= limits.vWarnLow -> Severity.WARN
                    else -> Severity.OK
                }

                val idx = first + ch + 1
                val cellId = "cell_%02d_%02d".format(segment, idx)
                cells.add(
                    Cell(
                        id = cellId,
                        segment = segment,
                        index = idx,
                        slave = si + 1,
                        slaveChannel = ch + 1,
                        parallel = car.parallelStrings,
                        voltage = v ?: 0.0f,
                        balancing = balancing,
                        openWire = openWire,
                        status = status
                    )
                )
            }
        }

        // 2. Build Thermistors
        val thermistors = mutableListOf<Thermistor>()
        for (si in 0 until car.slaveCount) {
            val perSeg = car.slavesPerSegment
            val segment = (si / perSeg) + 1
            val row = if (ntcDcArray != null && si < ntcDcArray.size() && ntcDcArray.get(si).isJsonArray) {
                ntcDcArray.get(si).asJsonArray
            } else null

            val owMask = if (owNtcArray != null && si < owNtcArray.size()) {
                owNtcArray.get(si).asInt
            } else 0

            val pecFlag = if (pecFlagsArray != null && si < pecFlagsArray.size()) {
                pecFlagsArray.get(si).asInt
            } else 0

            val trust = (pecFlag and 4) == 0 && (pecFlag and 8) == 0 && si < slavesDetected

            for (ti in 0 until car.tempsPerSlave) {
                var temp = if (row != null && ti < row.size() && !row.get(ti).isJsonNull) {
                    row.get(ti).asFloat / 10.0f
                } else null

                // Broken thermistors exclusion (e.g. "3:3", "3:4" on TEK-26e)
                if (car.brokenThermistors.contains("${si + 1}:${ti + 1}")) {
                    temp = null
                }
                if (bit(owMask, ti) || !trust) {
                    temp = null
                }

                val status = when {
                    temp == null -> Severity.UNKNOWN
                    temp >= limits.tempFault -> Severity.FAULT
                    temp >= limits.tempWarn -> Severity.WARN
                    else -> Severity.OK
                }

                thermistors.add(
                    Thermistor(
                        slave = si + 1,
                        index = ti + 1,
                        segment = segment,
                        temperature = temp,
                        status = status
                    )
                )
            }
        }

        // 3. Build Segments
        val segments = mutableListOf<Segment>()
        for (s in 1..car.nSegments) {
            val group = cells.filter { it.segment == s }
            val validVolts = group.filter { it.status != Severity.UNKNOWN }.map { it.voltage }
            val segTemps = thermistors.filter { it.segment == s && it.temperature != null }.map { it.temperature!! }

            val segV = if (validVolts.isNotEmpty()) validVolts.sum() else 0.0f
            val cellMin = if (validVolts.isNotEmpty()) validVolts.minOrNull() ?: 0.0f else 0.0f
            val cellMax = if (validVolts.isNotEmpty()) validVolts.maxOrNull() ?: 0.0f else 0.0f
            val tMax = if (segTemps.isNotEmpty()) segTemps.maxOrNull() ?: 0.0f else 0.0f
            val tAvg = if (segTemps.isNotEmpty()) segTemps.average().toFloat() else 0.0f

            val segStatus = Severity.worst(
                group.map { it.status } + thermistors.filter { it.segment == s }.map { it.status }
            )

            segments.add(
                Segment(
                    id = s,
                    name = "Segmento $s",
                    voltage = (segV * 1000f).roundToInt() / 1000f,
                    tempMax = (tMax * 10f).roundToInt() / 10f,
                    tempAvg = (tAvg * 10f).roundToInt() / 10f,
                    cellVMin = (cellMin * 10000f).roundToInt() / 10000f,
                    cellVMax = (cellMax * 10000f).roundToInt() / 10000f,
                    balancingActive = group.any { it.balancing },
                    status = segStatus
                )
            )
        }

        // 4. Build Pack
        val vsum = if (packObj.has("voltage_sum_mV") && !packObj.get("voltage_sum_mV").isJsonNull) {
            packObj.get("voltage_sum_mV").asFloat / 1000f
        } else {
            val isaU = if (i1.has("voltage_mV") && !i1.get("voltage_mV").isJsonNull) i1.get("voltage_mV").asFloat / 1000f else null
            isaU ?: cells.filter { it.status != Severity.UNKNOWN }.map { it.voltage }.sum()
        }

        val isaI = if (i1.has("current_mA") && !i1.get("current_mA").isJsonNull) {
            i1.get("current_mA").asFloat / 1000f
        } else 0.0f

        val isaP = if (i1.has("power_W") && !i1.get("power_W").isJsonNull) {
            i1.get("power_W").asFloat / 1000f // in kW
        } else {
            (vsum * isaI) / 1000f
        }

        val socPct = if (soc.has("percent_x100") && !soc.get("percent_x100").isJsonNull) {
            soc.get("percent_x100").asFloat / 100f
        } else 0.0f

        val usedChargeAs = if (soc.has("used_charge_As") && !soc.get("used_charge_As").isJsonNull) {
            max(0.0f, soc.get("used_charge_As").asFloat)
        } else 0.0f
        val energyUsedWh = (usedChargeAs * vsum) / 3600f

        val allValidCellV = cells.filter { it.status != Severity.UNKNOWN }.map { it.voltage }
        val allValidTemps = thermistors.mapNotNull { it.temperature }

        val cMin = if (packObj.has("cell_min_mV") && !packObj.get("cell_min_mV").isJsonNull) {
            packObj.get("cell_min_mV").asFloat / 1000f
        } else allValidCellV.minOrNull() ?: 0.0f

        val cMax = if (packObj.has("cell_max_mV") && !packObj.get("cell_max_mV").isJsonNull) {
            packObj.get("cell_max_mV").asFloat / 1000f
        } else allValidCellV.maxOrNull() ?: 0.0f

        val tMin = if (packObj.has("temp_min_cC") && !packObj.get("temp_min_cC").isJsonNull) {
            packObj.get("temp_min_cC").asFloat / 100f
        } else allValidTemps.minOrNull() ?: 0.0f

        val tMax = if (packObj.has("temp_max_cC") && !packObj.get("temp_max_cC").isJsonNull) {
            packObj.get("temp_max_cC").asFloat / 100f
        } else allValidTemps.maxOrNull() ?: 0.0f

        val pack = Pack(
            voltage = (vsum * 100f).roundToInt() / 100f,
            current = (isaI * 100f).roundToInt() / 100f,
            power = (isaP * 10f).roundToInt() / 10f,
            soc = (socPct * 10f).roundToInt() / 10f,
            energyUsed = (energyUsedWh * 10f).roundToInt() / 10f,
            cellVMin = (cMin * 10000f).roundToInt() / 10000f,
            cellVMax = (cMax * 10000f).roundToInt() / 10000f,
            cellVDelta = ((cMax - cMin) * 10000f).roundToInt() / 10000f,
            tempMax = (tMax * 10f).roundToInt() / 10f,
            tempMin = (tMin * 10f).roundToInt() / 10f,
            status = Severity.worst(cells.map { it.status } + thermistors.map { it.status })
        )

        // 5. Safety
        val prechargeStateCode = if (pre.has("state") && !pre.get("state").isJsonNull) pre.get("state").asInt else 0
        val prechargeStateName = nameFromTable(PRECHARGE_STATES, prechargeStateCode)
        val brainStateCode = if (bms.has("state") && !bms.get("state").isJsonNull) bms.get("state").asInt else 2
        val masterStateName = nameFromTable(AMS_STATES, brainStateCode)

        val amsErrorActive = if (ae.has("active") && !ae.get("active").isJsonNull) ae.get("active").asInt != 0 else false
        val airPos = if (pre.has("air_pos") && !pre.get("air_pos").isJsonNull) pre.get("air_pos").asInt != 0 else false
        val airNeg = if (pre.has("air_neg") && !pre.get("air_neg").isJsonNull) pre.get("air_neg").asInt != 0 else false
        val sdcClosed = if (pre.has("sdc_closed") && !pre.get("sdc_closed").isJsonNull) pre.get("sdc_closed").asInt != 0 else false

        val safety = Safety(
            available = listOf("ams_ok", "air_positive", "air_negative", "precharge_done", "sdc_closed"),
            sdcClosed = sdcClosed,
            amsOk = !amsErrorActive,
            airPositive = airPos,
            airNegative = airNeg,
            prechargeDone = (prechargeStateName == HV_ON),
            masterState = masterStateName,
            prechargeState = prechargeStateName
        )

        // 6. AMS Board
        val fanPwm = if (board.has("fan_pwm") && !board.get("fan_pwm").isJsonNull) {
            (board.get("fan_pwm").asFloat / 255.0f * 100.0f * 10f).roundToInt() / 10f
        } else null

        val mcuTemp = if (board.has("mcu_temp_dC") && !board.get("mcu_temp_dC").isJsonNull) {
            board.get("mcu_temp_dC").asFloat / 10.0f
        } else null

        val ams = Ams(
            fanPwm = fanPwm,
            mcuTemperature = mcuTemp,
            pecError = if (packObj.has("any_pec_error") && !packObj.get("any_pec_error").isJsonNull) {
                packObj.get("any_pec_error").asInt != 0
            } else false,
            faultCounter = if (bms.has("active_faults") && !bms.get("active_faults").isJsonNull) {
                bms.get("active_faults").asInt
            } else 0,
            runtimeS = if (bms.has("runtime_s") && !bms.get("runtime_s").isJsonNull) {
                bms.get("runtime_s").asFloat
            } else null,
            slavesDetected = slavesDetected
        )

        // 7. Charger & Mode
        val chgStateCode = if (chg.has("state") && !chg.get("state").isJsonNull) chg.get("state").asInt else 0
        val chgStateName = nameFromTable(CHARGER_STATES, chgStateCode)
        val isCharging = (chgStateName == "CHARGING")
        val handcartSwitch = if (chg.has("handcart_switch") && !chg.get("handcart_switch").isJsonNull) chg.get("handcart_switch").asInt != 0 else false
        val statusOk = if (chg.has("status_ok") && !chg.get("status_ok").isJsonNull) chg.get("status_ok").asInt != 0 else false
        val chgPresent = handcartSwitch || statusOk

        val chgRawCurrent = if (chg.has("requested_current_raw") && !chg.get("requested_current_raw").isJsonNull) {
            chg.get("requested_current_raw").asFloat / 10.0f
        } else 0.0f

        val i2Ma = if (i2.has("current_mA") && !i2.get("current_mA").isJsonNull) i2.get("current_mA").asFloat else 0.0f
        val i2Mv = if (i2.has("voltage_mV") && !i2.get("voltage_mV").isJsonNull) i2.get("voltage_mV").asFloat else 0.0f

        if (isCharging) {
            if (chgStarted == null) {
                chgStarted = now
                chgEnergyWh = 0.0f
            }
            val last = chgLast
            if (last != null) {
                val gapSeconds = (now - last) / 1000.0f
                if (gapSeconds in 0.1f..5.0f) {
                    val watts = (abs(i2Ma) / 1000f) * (i2Mv / 1000f)
                    chgEnergyWh += (watts * gapSeconds / 3600.0f)
                }
            }
            chgLast = now
        } else {
            chgStarted = null
            chgLast = null
        }

        val charger = Charger(
            present = chgPresent,
            outputVoltage = (i2Mv / 1000.0f * 10f).roundToInt() / 10f,
            outputCurrent = (abs(i2Ma) / 1000.0f * 100f).roundToInt() / 100f,
            requestedCurrent = (chgRawCurrent * 10f).roundToInt() / 10f,
            enabled = handcartSwitch,
            energyWh = (chgEnergyWh * 10f).roundToInt() / 10f,
            sessionS = if (chgStarted != null) ((now - chgStarted!!) / 1000.0f) else 0.0f
        )

        // 8. Faults
        val faults = mutableListOf<Fault>()
        val faultListArray = ae.getAsJsonArray("faults")
        if (faultListArray != null) {
            for (fElem in faultListArray) {
                val fStr = fElem.asString
                faults.add(
                    Fault(
                        code = fStr,
                        severity = Severity.FAULT,
                        message = fStr.replace('_', ' ').replaceFirstChar { it.uppercase() }
                    )
                )
            }
        }

        if (slavesDetected < car.slaveCount) {
            faults.add(
                Fault(
                    code = "SLAVES_MISSING",
                    severity = Severity.FAULT,
                    message = "Apenas $slavesDetected de ${car.slaveCount} slaves detetados"
                )
            )
        }

        if (ams.pecError) {
            faults.add(
                Fault(
                    code = "PEC_ERROR",
                    severity = Severity.WARN,
                    message = "Erro de CRC/PEC detetado em transmissões ADBMS"
                )
            )
        }

        for (cell in cells) {
            if (cell.openWire) {
                faults.add(
                    Fault(
                        code = "OPEN_WIRE_${cell.slave}_${cell.slaveChannel}",
                        severity = Severity.FAULT,
                        message = "Fio aberto detetado: Slave ${cell.slave}, Célula ${cell.slaveChannel}"
                    )
                )
            }
        }

        return BmsState(
            timestamp = now,
            isStale = false,
            isDemo = false,
            isConnected = true,
            pack = pack,
            safety = safety,
            ams = ams,
            mode = if (chgPresent) "charger" else "car",
            isCharging = isCharging,
            charger = charger,
            segments = segments,
            cells = cells,
            thermistors = thermistors,
            faults = faults,
            car = car,
            rxCount = rxCount,
            droppedSamples = droppedSamples
        )
    }
}
