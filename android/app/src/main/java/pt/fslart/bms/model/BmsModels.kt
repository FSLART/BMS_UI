package pt.fslart.bms.model

enum class Severity {
    OK,
    WARN,
    FAULT,
    UNKNOWN;

    companion object {
        fun worst(severities: Iterable<Severity>): Severity {
            var result = OK
            for (s in severities) {
                if (s == FAULT) return FAULT
                if (s == WARN && result != FAULT) result = WARN
                if (s == UNKNOWN && result == OK) result = UNKNOWN
            }
            return result
        }
    }
}

data class CellLimits(
    val vMin: Float = 2.50f,
    val vMax: Float = 4.20f,
    val vWarnLow: Float = 2.80f,
    val vWarnHigh: Float = 4.15f,
    val tempWarn: Float = 50.0f,
    val tempFault: Float = 60.0f
)

data class CarProfile(
    val id: String,
    val name: String,
    val subtitle: String,
    val year: String,
    val bleAddress: String,
    val brokenThermistors: List<String> = emptyList(),
    val nSegments: Int = 6,
    val cellsPerSegment: Int = 24,
    val parallelStrings: Int = 3,
    val cellModel: String = "Molicel P45B",
    val nominalCellV: Float = 3.60f,
    val cellCapacityAh: Float = 4.5f,
    val slavesPerSegment: Int = 2,
    val cellsPerSlave: Int = 12,
    val tempsPerSlave: Int = 6,
    val limits: CellLimits = CellLimits()
) {
    val seriesCount: Int get() = nSegments * cellsPerSegment
    val totalCells: Int get() = seriesCount * parallelStrings
    val slaveCount: Int get() = nSegments * slavesPerSegment
    val totalThermistors: Int get() = slaveCount * tempsPerSlave
    val nominalPackV: Float get() = seriesCount * nominalCellV
    val packCapacityAh: Float get() = cellCapacityAh * parallelStrings
    val totalEnergyKwh: Float get() = (nominalPackV * packCapacityAh) / 1000f

    companion object {
        val TEK26E = CarProfile(
            id = "tek26e",
            name = "TEK-26e",
            subtitle = "Formula Student 2026",
            year = "2026",
            bleAddress = "40:84:32:61:CE:13", // Pre-configured default BLE MAC
            brokenThermistors = listOf("3:3", "3:4"),
            nSegments = 6,
            cellsPerSegment = 24,
            parallelStrings = 3,
            cellModel = "Molicel P45B",
            nominalCellV = 3.60f,
            cellCapacityAh = 4.5f,
            slavesPerSegment = 2,
            cellsPerSlave = 12,
            tempsPerSlave = 6,
            limits = CellLimits(
                vMin = 2.50f,
                vMax = 4.20f,
                vWarnLow = 2.80f,
                vWarnHigh = 4.15f,
                tempWarn = 50.0f,
                tempFault = 60.0f
            )
        )

        val TEK26E_EVO = CarProfile(
            id = "tek26e_evo",
            name = "TEK-26e EVO",
            subtitle = "Formula Student EVO",
            year = "2026",
            bleAddress = "40:84:32:61:CE:14",
            nSegments = 6,
            cellsPerSegment = 24,
            parallelStrings = 3,
            cellModel = "Molicel P45B",
            nominalCellV = 3.60f,
            cellCapacityAh = 4.5f,
            slavesPerSegment = 2,
            cellsPerSlave = 12,
            tempsPerSlave = 6,
            limits = CellLimits()
        )

        val T28 = CarProfile(
            id = "t28",
            name = "T-28",
            subtitle = "Legacy Prototype",
            year = "2024",
            bleAddress = "40:84:32:61:CE:10",
            nSegments = 6,
            cellsPerSegment = 24,
            parallelStrings = 2,
            cellModel = "Samsung 30Q",
            nominalCellV = 3.60f,
            cellCapacityAh = 3.0f,
            slavesPerSegment = 2,
            cellsPerSlave = 12,
            tempsPerSlave = 6,
            limits = CellLimits()
        )

        val ALL_CARS = listOf(TEK26E, TEK26E_EVO, T28)
    }
}

data class Cell(
    val id: String,
    val segment: Int,
    val index: Int,
    val slave: Int,
    val slaveChannel: Int,
    val parallel: Int,
    val voltage: Float,
    val balancing: Boolean = false,
    val openWire: Boolean = false,
    val status: Severity = Severity.OK
)

data class Thermistor(
    val slave: Int,
    val index: Int,
    val segment: Int,
    val temperature: Float?,
    val status: Severity = Severity.OK
)

data class Segment(
    val id: Int,
    val name: String,
    val voltage: Float,
    val tempMax: Float,
    val tempAvg: Float,
    val cellVMin: Float,
    val cellVMax: Float,
    val balancingActive: Boolean,
    val status: Severity
)

data class Pack(
    val voltage: Float = 0.0f,
    val current: Float = 0.0f,
    val power: Float = 0.0f,
    val soc: Float = 0.0f,
    val energyUsed: Float = 0.0f,
    val cellVMin: Float = 0.0f,
    val cellVMax: Float = 0.0f,
    val cellVDelta: Float = 0.0f,
    val tempMax: Float = 0.0f,
    val tempMin: Float = 0.0f,
    val status: Severity = Severity.OK
)

data class Safety(
    val available: List<String> = listOf("ams_ok", "air_positive", "air_negative", "precharge_done"),
    val sdcClosed: Boolean = false,
    val amsOk: Boolean = true,
    val airPositive: Boolean = false,
    val airNegative: Boolean = false,
    val prechargeDone: Boolean = false,
    val masterState: String = "IDLE",
    val prechargeState: String = "OPEN_ALL"
)

data class Ams(
    val fanPwm: Float? = null,
    val mcuTemperature: Float? = null,
    val pecError: Boolean = false,
    val faultCounter: Int = 0,
    val runtimeS: Float? = null,
    val slavesDetected: Int? = null
)

data class Charger(
    val present: Boolean = false,
    val outputVoltage: Float = 0.0f,
    val outputCurrent: Float = 0.0f,
    val requestedCurrent: Float = 0.0f,
    val enabled: Boolean = false,
    val faults: List<String> = emptyList(),
    val energyWh: Float = 0.0f,
    val sessionS: Float = 0.0f
)

data class Fault(
    val code: String,
    val severity: Severity,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class BmsState(
    val timestamp: Long = System.currentTimeMillis(),
    val isStale: Boolean = false,
    val isDemo: Boolean = false,
    val isConnected: Boolean = false,
    val connectionDeviceName: String = "",
    val connectionAddress: String = "",
    val pack: Pack = Pack(),
    val safety: Safety = Safety(),
    val ams: Ams = Ams(),
    val mode: String = "car", // "car" or "charger"
    val isCharging: Boolean = false,
    val charger: Charger = Charger(),
    val segments: List<Segment> = emptyList(),
    val cells: List<Cell> = emptyList(),
    val thermistors: List<Thermistor> = emptyList(),
    val faults: List<Fault> = emptyList(),
    val car: CarProfile = CarProfile.TEK26E,
    val rxCount: Long = 0,
    val droppedSamples: Long = 0
)
