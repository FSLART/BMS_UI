package pt.fslart.bms.simulator

import pt.fslart.bms.model.*
import java.util.Random
import kotlin.math.*

class BmsSimulator(
    var car: CarProfile = CarProfile.TEK26E,
    seed: Long = 42L
) {
    companion object {
        const val CHARGER_MAX_A = 6.0f
        const val CHARGER_MAX_V = 600.0f
        const val DRIVE_S = 50.0f
        const val CHARGE_S = 30.0f
        const val CYCLE_S = DRIVE_S + CHARGE_S
    }

    private val rng = Random(seed)
    private val t0 = System.currentTimeMillis()
    private var lastStepTime = t0

    var soc = 92.0f
    private var energyUsedWh = 0.0f
    private var chgEnergyWh = 0.0f
    private var chgStarted: Long? = null

    private val vOffset = FloatArray(car.seriesCount) { (rng.nextGaussian() * 0.012).toFloat() }
    private val tOffset = FloatArray(car.seriesCount) { (rng.nextGaussian() * 1.2).toFloat() }
    private val weakCellIndex = rng.nextInt(car.seriesCount).also {
        vOffset[it] -= 0.085f
        tOffset[it] += 5.5f
    }

    private val thOffset = FloatArray(car.totalThermistors) { (rng.nextGaussian() * 1.5).toFloat() }.also {
        if (it.isNotEmpty()) {
            it[rng.nextInt(it.size)] += 6.5f
        }
    }

    var manualChargingOverride: Boolean? = null

    fun isChargingPhase(tSeconds: Float): Boolean {
        manualChargingOverride?.let { return it }
        return (tSeconds % CYCLE_S) >= DRIVE_S
    }

    private fun calculateCurrent(tSeconds: Float): Float {
        return if (isChargingPhase(tSeconds)) {
            val taper = max(0.15f, min(1.0f, (100.0f - soc) / 15.0f))
            -CHARGER_MAX_A * taper + (rng.nextGaussian() * 0.05).toFloat()
        } else {
            val lap = tSeconds % 45.0f
            val base = (60.0 * sin(lap / 45.0 * 2 * PI)).toFloat()
            val burst = (90.0 * max(0.0, sin(lap / 7.0 * 2 * PI)).pow(3.0)).toFloat()
            val regen = if (lap in 30.0f..33.0f) -65.0f else 0.0f
            base + burst + regen + (rng.nextGaussian() * 2.5).toFloat()
        }
    }

    fun step(): BmsState {
        val now = System.currentTimeMillis()
        val dt = max(0.05f, (now - lastStepTime) / 1000.0f)
        lastStepTime = now
        val t = (now - t0) / 1000.0f

        val current = calculateCurrent(t)
        val limits = car.limits

        // Coulomb counting
        soc = max(5.0f, min(100.0f, soc - (current * dt) / (car.packCapacityAh * 3600.0f) * 100.0f))
        energyUsedWh += max(0.0f, current) * car.nominalPackV * dt / 3600.0f

        val socFrac = soc / 100.0f
        val ocv = 3.30f + 0.62f * socFrac + 0.28f * (socFrac.pow(3))
        val irDrop = current * 0.0009f

        val heat = 24.0f + 20.0f * (abs(current) / 150.0f) + 6.0f * sin(t / 60.0f)
        val ambient = 25.0f + 8.0f * min(1.0f, t / 300.0f)

        // Cells
        val cells = mutableListOf<Cell>()
        val perSeg = car.cellsPerSegment
        val perSlave = car.cellsPerSlave
        val slavesPerSeg = car.slavesPerSegment

        for (i in 0 until car.seriesCount) {
            val seg = i / perSeg
            val idx = i % perSeg
            val slave = seg * slavesPerSeg + idx / perSlave + 1
            val channel = idx % perSlave + 1

            var v = ocv - irDrop + vOffset[i] + (rng.nextGaussian() * 0.0015).toFloat()
            v = min(v, limits.vMax + 0.02f)

            val openWire = false
            val isWeak = (i == weakCellIndex)
            val balancing = isWeak && isChargingPhase(t)

            val status = when {
                v >= limits.vMax || v <= limits.vMin -> Severity.FAULT
                v >= limits.vWarnHigh || v <= limits.vWarnLow -> Severity.WARN
                else -> Severity.OK
            }

            cells.add(
                Cell(
                    id = "cell_%02d_%02d".format(seg + 1, idx + 1),
                    segment = seg + 1,
                    index = idx + 1,
                    slave = slave,
                    slaveChannel = channel,
                    parallel = car.parallelStrings,
                    voltage = (v * 10000f).roundToInt() / 10000f,
                    balancing = balancing,
                    openWire = openWire,
                    status = status
                )
            )
        }

        // Thermistors
        val thermistors = mutableListOf<Thermistor>()
        for (si in 0 until car.slaveCount) {
            val segment = (si / car.slavesPerSegment) + 1
            for (ti in 0 until car.tempsPerSlave) {
                val thIdx = si * car.tempsPerSlave + ti
                var temp = ambient + heat * 0.35f + thOffset[thIdx] + (rng.nextGaussian() * 0.2).toFloat()

                // Broken thermistors on TEK-26e
                if (car.brokenThermistors.contains("${si + 1}:${ti + 1}")) {
                    temp = 0.0f
                }

                val status = when {
                    temp == 0.0f -> Severity.UNKNOWN
                    temp >= limits.tempFault -> Severity.FAULT
                    temp >= limits.tempWarn -> Severity.WARN
                    else -> Severity.OK
                }

                thermistors.add(
                    Thermistor(
                        slave = si + 1,
                        index = ti + 1,
                        segment = segment,
                        temperature = if (temp > 0f) (temp * 10f).roundToInt() / 10f else null,
                        status = status
                    )
                )
            }
        }

        // Segments
        val segments = mutableListOf<Segment>()
        for (s in 1..car.nSegments) {
            val segCells = cells.filter { it.segment == s }
            val segVolts = segCells.map { it.voltage }
            val segTemps = thermistors.filter { it.segment == s && it.temperature != null }.map { it.temperature!! }

            val segV = segVolts.sum()
            val minV = segVolts.minOrNull() ?: 0.0f
            val maxV = segVolts.maxOrNull() ?: 0.0f
            val maxT = segTemps.maxOrNull() ?: 0.0f
            val avgT = if (segTemps.isNotEmpty()) segTemps.average().toFloat() else 0.0f

            segments.add(
                Segment(
                    id = s,
                    name = "Segmento $s",
                    voltage = (segV * 1000f).roundToInt() / 1000f,
                    tempMax = (maxT * 10f).roundToInt() / 10f,
                    tempAvg = (avgT * 10f).roundToInt() / 10f,
                    cellVMin = (minV * 10000f).roundToInt() / 10000f,
                    cellVMax = (maxV * 10000f).roundToInt() / 10000f,
                    balancingActive = segCells.any { it.balancing },
                    status = Severity.worst(segCells.map { it.status })
                )
            )
        }

        val allVolts = cells.map { it.voltage }
        val allTemps = thermistors.mapNotNull { it.temperature }
        val packVoltage = allVolts.sum()
        val packPowerKw = (packVoltage * current) / 1000.0f
        val cMin = allVolts.minOrNull() ?: 0.0f
        val cMax = allVolts.maxOrNull() ?: 0.0f

        val pack = Pack(
            voltage = (packVoltage * 100f).roundToInt() / 100f,
            current = (current * 100f).roundToInt() / 100f,
            power = (packPowerKw * 10f).roundToInt() / 10f,
            soc = (soc * 10f).roundToInt() / 10f,
            energyUsed = (energyUsedWh * 10f).roundToInt() / 10f,
            cellVMin = (cMin * 10000f).roundToInt() / 10000f,
            cellVMax = (cMax * 10000f).roundToInt() / 10000f,
            cellVDelta = ((cMax - cMin) * 10000f).roundToInt() / 10000f,
            tempMax = (allTemps.maxOrNull() ?: 0.0f),
            tempMin = (allTemps.minOrNull() ?: 0.0f),
            status = Severity.worst(cells.map { it.status } + thermistors.map { it.status })
        )

        val charging = isChargingPhase(t)
        if (charging) {
            if (chgStarted == null) chgStarted = now
            chgEnergyWh += abs(current) * packVoltage * dt / 3600.0f
        } else {
            chgStarted = null
        }

        val charger = Charger(
            present = charging,
            outputVoltage = if (charging) (packVoltage * 10f).roundToInt() / 10f else 0.0f,
            outputCurrent = if (charging) (abs(current) * 100f).roundToInt() / 100f else 0.0f,
            requestedCurrent = if (charging) CHARGER_MAX_A else 0.0f,
            enabled = charging,
            energyWh = (chgEnergyWh * 10f).roundToInt() / 10f,
            sessionS = if (chgStarted != null) ((now - chgStarted!!) / 1000.0f) else 0.0f
        )

        val safety = Safety(
            sdcClosed = true,
            amsOk = true,
            airPositive = true,
            airNegative = true,
            prechargeDone = true,
            masterState = if (charging) "CHARGING" else "ONMISSION",
            prechargeState = LiveJsonDecoder.HV_ON
        )

        val ams = Ams(
            fanPwm = min(100f, max(20f, (heat - 25f) * 3f)),
            mcuTemperature = 32.0f + (heat * 0.2f),
            pecError = false,
            faultCounter = 0,
            runtimeS = t,
            slavesDetected = car.slaveCount
        )

        val faults = mutableListOf<Fault>()
        if (pack.tempMax >= limits.tempWarn) {
            faults.add(
                Fault(
                    code = "TEMP_WARN",
                    severity = Severity.WARN,
                    message = "Temperatura máxima atingiu ${pack.tempMax}°C"
                )
            )
        }

        return BmsState(
            timestamp = now,
            isStale = false,
            isDemo = true,
            isConnected = true,
            connectionDeviceName = "Simulador BMS (Modo Demo)",
            connectionAddress = "DEMO-MODE",
            pack = pack,
            safety = safety,
            ams = ams,
            mode = if (charging) "charger" else "car",
            isCharging = charging,
            charger = charger,
            segments = segments,
            cells = cells,
            thermistors = thermistors,
            faults = faults,
            car = car,
            rxCount = (t).toLong(),
            droppedSamples = 0
        )
    }
}
