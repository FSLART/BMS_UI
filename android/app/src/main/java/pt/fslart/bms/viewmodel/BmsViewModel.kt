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

enum class ScreenTab {
    CONNECT,
    DASHBOARD,
    CELLS,
    TEMPS,
    CHARGER,
    CONSOLE
}

class BmsViewModel(application: Application) : AndroidViewModel(application) {

    private val bleManager = BleManager(application, viewModelScope)
    private val bleScanner = BleScanner(application)
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

    init {
        // Collect real BLE state
        viewModelScope.launch {
            bleManager.bmsState.collect { state ->
                if (state != null && !_isDemoMode.value) {
                    _bmsState.value = state
                    appendHistory(state.pack.current, state.pack.voltage)
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

    fun setDemoMode(enabled: Boolean) {
        _isDemoMode.value = enabled
        if (enabled) {
            bleManager.disconnect()
            simulator = BmsSimulator(_selectedCar.value)
            simulatorJob?.cancel()
            simulatorJob = viewModelScope.launch {
                _currentTab.value = ScreenTab.DASHBOARD
                while (isActive) {
                    val fakeState = simulator.step()
                    _bmsState.value = fakeState
                    bleManager.injectSimulatorState(fakeState)
                    appendHistory(fakeState.pack.current, fakeState.pack.voltage)
                    delay(1000L) // 1 sample per second like the real RN4871
                }
            }
        } else {
            simulatorJob?.cancel()
            simulatorJob = null
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

    override fun onCleared() {
        super.onCleared()
        simulatorJob?.cancel()
        bleManager.disconnect()
        bleScanner.stopScan()
    }
}
