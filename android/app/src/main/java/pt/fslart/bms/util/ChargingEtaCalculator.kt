package pt.fslart.bms.util

import pt.fslart.bms.model.BmsState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Detailed charging estimation results for Formula Student battery pack.
 */
data class ChargingEta(
    val currentSoc: Float,
    val chargeCurrentA: Float,
    val packCapacityAh: Float,
    val remainingAhTo80: Float,
    val remainingAhTo100: Float,
    val remainingKwhTo80: Float,
    val remainingKwhTo100: Float,
    val minutesTo80: Int,
    val minutesTo100: Int,
    val completionTime80: String,
    val completionTime100: String,
    val chargingPowerKw: Float,
    val isAlreadyAbove80: Boolean,
    val isFullyCharged: Boolean,
    val isCvPhase: Boolean
) {
    fun formattedTimeTo80(): String {
        if (isAlreadyAbove80) return "Atingido (≥80%)"
        val hours = minutesTo80 / 60
        val mins = minutesTo80 % 60
        return if (hours > 0) "${hours}h ${mins}m" else "${mins} min"
    }

    fun formattedTimeTo100(): String {
        if (isFullyCharged) return "Carga Completa"
        val hours = minutesTo100 / 60
        val mins = minutesTo100 % 60
        return if (hours > 0) "${hours}h ${mins}m" else "${mins} min"
    }
}

/**
 * Calculates accurate charging ETA based on pack capacity, SOC, and the standard 6.0 A current.
 */
object ChargingEtaCalculator {
    const val DEFAULT_CHARGING_CURRENT_A = 6.0f

    fun calculate(state: BmsState, customCurrentA: Float? = null): ChargingEta {
        val soc = state.pack.soc.coerceIn(0f, 100f)
        val packCapacityAh = if (state.car.packCapacityAh > 0f) state.car.packCapacityAh else 13.5f
        val nominalPackV = if (state.car.nominalPackV > 0f) state.car.nominalPackV else 518.4f
        val totalEnergyKwh = (nominalPackV * packCapacityAh) / 1000f

        // Priority: customCurrent > measured positive/negative charger current > 6.0A default
        val measuredCurrent = abs(state.pack.current)
        val currentA = when {
            customCurrentA != null && customCurrentA > 0.1f -> customCurrentA
            measuredCurrent >= 1.0f -> measuredCurrent
            abs(state.charger.outputCurrent) >= 1.0f -> abs(state.charger.outputCurrent)
            else -> DEFAULT_CHARGING_CURRENT_A
        }

        val remainingSocTo80 = max(0f, 80f - soc)
        val remainingSocTo100 = max(0f, 100f - soc)

        val remainingAhTo80 = (remainingSocTo80 / 100f) * packCapacityAh
        val remainingAhTo100 = (remainingSocTo100 / 100f) * packCapacityAh

        val remainingKwhTo80 = (remainingSocTo80 / 100f) * totalEnergyKwh
        val remainingKwhTo100 = (remainingSocTo100 / 100f) * totalEnergyKwh

        // Constant Current (CC) phase to 80%
        val hoursTo80 = if (currentA > 0.1f) remainingAhTo80 / currentA else 0f
        val minsTo80 = (hoursTo80 * 60f).roundToInt()

        // 80% to 100% Constant Voltage (CV) taper & cell balancing phase:
        // Current ramps down exponentially, averaging ~70% of nominal current
        val hoursTo100 = if (currentA > 0.1f) {
            val ccHours = (max(0f, min(80f, 100f) - min(80f, soc)) / 100f * packCapacityAh) / currentA
            val cvHours = if (soc < 80f) {
                (0.20f * packCapacityAh) / (currentA * 0.70f)
            } else {
                (remainingSocTo100 / 100f * packCapacityAh) / (currentA * 0.70f)
            }
            ccHours + cvHours
        } else {
            0f
        }
        val minsTo100 = (hoursTo100 * 60f).roundToInt()

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val now = System.currentTimeMillis()
        val completion80 = timeFormat.format(Date(now + minsTo80 * 60 * 1000L))
        val completion100 = timeFormat.format(Date(now + minsTo100 * 60 * 1000L))

        val currentVoltage = if (state.pack.voltage > 10f) state.pack.voltage else nominalPackV
        val powerKw = (currentVoltage * currentA) / 1000f

        return ChargingEta(
            currentSoc = soc,
            chargeCurrentA = currentA,
            packCapacityAh = packCapacityAh,
            remainingAhTo80 = remainingAhTo80,
            remainingAhTo100 = remainingAhTo100,
            remainingKwhTo80 = remainingKwhTo80,
            remainingKwhTo100 = remainingKwhTo100,
            minutesTo80 = minsTo80,
            minutesTo100 = minsTo100,
            completionTime80 = completion80,
            completionTime100 = completion100,
            chargingPowerKw = powerKw,
            isAlreadyAbove80 = soc >= 80f,
            isFullyCharged = soc >= 99.5f,
            isCvPhase = soc >= 80f
        )
    }
}
