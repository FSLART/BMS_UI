package pt.fslart.bms.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pt.fslart.bms.ble.*
import pt.fslart.bms.model.*
import pt.fslart.bms.simulator.BmsSimulator
import pt.fslart.bms.service.ChargingNotificationService
import pt.fslart.bms.util.ChargingAlertManager

enum class ScreenTab {
    CONNECT,
    DASHBOARD,
    CELLS,
    TEMPS,
    CHECKLIST,
    CHARGER,
    MODEL_3D,
    CONSOLE
}

class BmsViewModel(application: Application) : AndroidViewModel(application) {

    private val bleManager = BleManager(application, viewModelScope)
    private val bleScanner = BleScanner(application)
    private val chargingAlertManager = ChargingAlertManager(application)
    private var simulator = BmsSimulator(CarProfile.TEK26E)
    private var simulatorJob: Job? = null

    // Car Selection
    private val _selectedCar = MutableStateFlow(CarProfile.TEK26E)
    val selectedCar: StateFlow<CarProfile> = _selectedCar.asStateFlow()

    // BLE configuration
    private val _bleAddress = MutableStateFlow(CarProfile.TEK26E.bleAddress)
    val bleAddress: StateFlow<String> = _bleAddress.asStateFlow()

    // Demo Mode toggle
    private val _isDemoMode = MutableStateFlow(false)
    val isDemoMode: StateFlow<Boolean> = _isDemoMode.asStateFlow()

    // Navigation Tab
    private val _currentTab = MutableStateFlow(ScreenTab.CONNECT)
    val currentTab: StateFlow<ScreenTab> = _currentTab.asStateFlow()

    // Selected Segment filter
    private val _selectedSegmentId = MutableStateFlow<Int?>(null)
    val selectedSegmentId: StateFlow<Int?> = _selectedSegmentId.asStateFlow()

    // Connection state & BMS telemetry state
    val connectionState = bleManager.connectionState
    val statusMessage = bleManager.statusMessage
    val logLines = bleManager.logLines
    val discoveredDevices = bleScanner.devices
    val isScanning = bleScanner.isScanning

    private val _bmsState = MutableStateFlow(BmsState(car = CarProfile.TEK26E))
    val bmsState: StateFlow<BmsState> = _bmsState.asStateFlow()

    // Telemetry History for Sparkline
    private val _currentHistory = MutableStateFlow<List<Float>>(emptyList())
    val currentHistory: StateFlow<List<Float>> = _currentHistory.asStateFlow()

    private val _voltageHistory = MutableStateFlow<List<Float>>(emptyList())
    val voltageHistory: StateFlow<List<Float>> = _voltageHistory.asStateFlow()

    private var previousChargingState: Boolean = false

    init {
        // Collect real BLE state
        viewModelScope.launch {
            bleManager.bmsState.collect { state ->
                if (state != null && !_isDemoMode.value) {
                    _bmsState.value = state
                    appendHistory(state.pack.current, state.pack.voltage)
                    chargingAlertManager.onChargingStateUpdated(state.isCharging)
                    if (state.isCharging) {
                        ChargingNotificationService.startOrUpdate(getApplication(), state)
                    } else if (previousChargingState) {
                        ChargingNotificationService.stop(getApplication())
                    }
                    previousChargingState = state.isCharging
                }
            }
        }

        // When connection state switches to connected, jump to Dashboard
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                if (state == BleConnectionState.CONNECTED) {
                    _currentTab.value = ScreenTab.DASHBOARD
                }
            }
        }
    }

    fun selectCar(car: CarProfile) {
        _selectedCar.value = car
        _bleAddress.value = car.bleAddress
        simulator = BmsSimulator(car)
        bleManager.setCar(car)
        if (_bmsState.value.isDemo) {
            _bmsState.value = _bmsState.value.copy(car = car)
        }
    }

    fun setBleAddress(address: String) {
        _bleAddress.value = address
    }

    fun setTab(tab: ScreenTab) {
        _currentTab.value = tab
    }

    fun selectSegment(segmentId: Int?) {
        _selectedSegmentId.value = segmentId
        if (segmentId != null) {
            _currentTab.value = ScreenTab.CELLS
        }
    }

    fun startBleScan() {
        bleScanner.startScan()
    }

    fun stopBleScan() {
        bleScanner.stopScan()
    }

    fun connectBle(targetAddress: String? = null) {
        val mac = targetAddress ?: _bleAddress.value
        if (_isDemoMode.value) {
            setDemoMode(false)
        }
        bleScanner.stopScan()
        bleManager.connect(mac, _selectedCar.value)
    }

    fun disconnect() {
        if (_isDemoMode.value) {
            setDemoMode(false)
        }
        bleManager.disconnect()
        _currentTab.value = ScreenTab.CONNECT
    }

    fun toggleDemoMode() {
        setDemoMode(!_isDemoMode.value)
    }

    fun setDemoMode(enabled: Boolean, initialChargingOverride: Boolean? = null) {
        _isDemoMode.value = enabled
        if (enabled) {
            bleManager.disconnect()
            simulator = BmsSimulator(_selectedCar.value).apply {
                if (initialChargingOverride != null) {
                    manualChargingOverride = initialChargingOverride
                }
            }
            simulatorJob?.cancel()
            simulatorJob = viewModelScope.launch {
                if (_currentTab.value == ScreenTab.CONNECT) {
                    _currentTab.value = ScreenTab.DASHBOARD
                }
                while (isActive) {
                    val fakeState = simulator.step()
                    _bmsState.value = fakeState
                    bleManager.injectSimulatorState(fakeState)
                    appendHistory(fakeState.pack.current, fakeState.pack.voltage)
                    chargingAlertManager.onChargingStateUpdated(fakeState.isCharging)
                    if (fakeState.isCharging) {
                        ChargingNotificationService.startOrUpdate(getApplication(), fakeState)
                    } else if (previousChargingState) {
                        ChargingNotificationService.stop(getApplication())
                    }
                    previousChargingState = fakeState.isCharging
                    delay(1000L) // 1 sample per second like the real RN4871
                }
            }
        } else {
            simulatorJob?.cancel()
            simulatorJob = null
            ChargingNotificationService.stop(getApplication())
            _bmsState.value = BmsState(car = _selectedCar.value)
            _currentTab.value = ScreenTab.CONNECT
        }
    }

    private fun appendHistory(current: Float, voltage: Float) {
        val maxLen = 45
        val curList = _currentHistory.value.toMutableList()
        val voltList = _voltageHistory.value.toMutableList()

        if (curList.size >= maxLen) curList.removeAt(0)
        if (voltList.size >= maxLen) voltList.removeAt(0)

        curList.add(current)
        voltList.add(voltage)

        _currentHistory.value = curList
        _voltageHistory.value = voltList
    }

    // Fullscreen 3D Overlay Request
    data class Fullscreen3DRequest(
        val modelFile: String,
        val title: String?,
        val selectedSegment: Int = 1,
        val isAutoRotate: Boolean = false
    )

    private val _fullscreen3D = MutableStateFlow<Fullscreen3DRequest?>(null)
    val fullscreen3D: StateFlow<Fullscreen3DRequest?> = _fullscreen3D.asStateFlow()

    fun openFullscreen3D(modelFile: String, title: String?, selectedSegment: Int = 1, isAutoRotate: Boolean = false) {
        _fullscreen3D.value = Fullscreen3DRequest(modelFile, title, selectedSegment, isAutoRotate)
    }

    fun closeFullscreen3D() {
        _fullscreen3D.value = null
    }

    // Fullscreen Cell Chart Overlay
    private val _isCellChartFullscreen = MutableStateFlow(false)
    val isCellChartFullscreen: StateFlow<Boolean> = _isCellChartFullscreen.asStateFlow()

    fun openCellChartFullscreen() {
        _isCellChartFullscreen.value = true
    }

    fun closeCellChartFullscreen() {
        _isCellChartFullscreen.value = false
    }

    // Fullscreen Thermistor Chart Overlay
    private val _isThermChartFullscreen = MutableStateFlow(false)
    val isThermChartFullscreen: StateFlow<Boolean> = _isThermChartFullscreen.asStateFlow()

    fun openThermChartFullscreen() {
        _isThermChartFullscreen.value = true
    }

    fun closeThermChartFullscreen() {
        _isThermChartFullscreen.value = false
    }

    // Thermal View State for 3D Viewer
    private val _isThermalView3D = MutableStateFlow(false)
    val isThermalView3D: StateFlow<Boolean> = _isThermalView3D.asStateFlow()

    fun toggleThermalView3D() {
        _isThermalView3D.value = !_isThermalView3D.value
    }

    fun setThermalView3D(enabled: Boolean) {
        _isThermalView3D.value = enabled
    }

    fun triggerChargingAlert(start: Boolean) {
        if (start) chargingAlertManager.triggerChargingStartedAlert()
        else chargingAlertManager.triggerChargingStoppedAlert()
    }

    fun toggleManualCharging() {
        val currentlyCharging = _bmsState.value.isCharging
        val newCharging = !currentlyCharging

        if (newCharging && !_isDemoMode.value && bleManager.connectionState.value != BleConnectionState.CONNECTED) {
            setDemoMode(true, initialChargingOverride = true)
        } else {
            simulator.manualChargingOverride = newCharging
        }

        val currentState = _bmsState.value
        val packVoltage = if (currentState.pack.voltage > 10f) currentState.pack.voltage else 580.4f
        val packSoc = if (currentState.pack.soc > 0f) currentState.pack.soc else 92.0f

        val updatedPack = currentState.pack.copy(
            voltage = packVoltage,
            soc = packSoc,
            current = if (newCharging) -30.0f else 0.0f,
            power = if (newCharging) (packVoltage * 30.0f) / 1000f else 0.0f
        )
        val updatedState = currentState.copy(
            isCharging = newCharging,
            pack = updatedPack,
            charger = currentState.charger.copy(
                present = newCharging,
                enabled = newCharging,
                outputCurrent = if (newCharging) 30.0f else 0.0f,
                outputVoltage = if (newCharging) packVoltage else 0.0f
            )
        )
        _bmsState.value = updatedState
        if (newCharging) {
            ChargingNotificationService.startOrUpdate(getApplication(), updatedState)
            chargingAlertManager.triggerChargingStartedAlert()
        } else {
            ChargingNotificationService.stop(getApplication())
            chargingAlertManager.triggerChargingStoppedAlert()
        }
    }

    override fun onCleared() {
        super.onCleared()
        simulatorJob?.cancel()
        bleManager.disconnect()
        bleScanner.stopScan()
        ChargingNotificationService.stop(getApplication())
    }
}
