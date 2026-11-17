package pt.fslart.bms.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pt.fslart.bms.BmsApplication
import pt.fslart.bms.ble.BleConnectionState
import pt.fslart.bms.model.BmsState
import pt.fslart.bms.model.CarProfile
import pt.fslart.bms.network.RelayConnectionState

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

enum class DataSource {
    BLE,
    SERVER,
    SIMULATOR,
    NONE
}

/**
 * Estado dos ecras. A ligacao, a telemetria e a notificacao vivem na
 * [pt.fslart.bms.session.BmsSession] (ao nivel do processo), para continuarem
 * com o ecra bloqueado; aqui fica so o que e da interface.
 */
class BmsViewModel(application: Application) : AndroidViewModel(application) {

    private val session = (application as BmsApplication).session

    val relayManager = session.relayManager

    // Fonte de dados ativa
    val activeDataSource: StateFlow<DataSource> = session.activeDataSource

    // Nuvem
    val serverViewerState = relayManager.viewerState
    val serverRelayState = relayManager.relayState
    val serverUrl = relayManager.serverUrl
    val isRelayEnabled = relayManager.isRelayEnabled
    val packetsSent = relayManager.packetsSent
    val packetsReceived = relayManager.packetsReceived
    val serverError = relayManager.lastError

    // Carro, BLE e demo
    val selectedCar: StateFlow<CarProfile> = session.selectedCar
    val bleAddress: StateFlow<String> = session.bleAddress
    val isDemoMode: StateFlow<Boolean> = session.isDemoMode

    val connectionState = session.bleManager.connectionState
    val statusMessage = session.bleManager.statusMessage
    val logLines = session.bleManager.logLines
    val reconnectAttempt = session.bleManager.reconnectAttempt
    val discoveredDevices = session.bleScanner.devices
    val isScanning = session.bleScanner.isScanning

    // Telemetria
    val bmsState: StateFlow<BmsState> = session.bmsState
    val currentHistory: StateFlow<List<Float>> = session.currentHistory
    val voltageHistory: StateFlow<List<Float>> = session.voltageHistory
    val dataAgeSeconds: StateFlow<Int> = session.dataAgeSeconds
    val isBalloonPreview: StateFlow<Boolean> = session.balloonPreview

    // Navegacao: se a sessao ja esta ligada (app reaberta), comecar no painel.
    private val _currentTab = MutableStateFlow(
        if (session.activeDataSource.value == DataSource.NONE) ScreenTab.CONNECT else ScreenTab.DASHBOARD
    )
    val currentTab: StateFlow<ScreenTab> = _currentTab.asStateFlow()

    private val _selectedSegmentId = MutableStateFlow<Int?>(null)
    val selectedSegmentId: StateFlow<Int?> = _selectedSegmentId.asStateFlow()

    init {
        // Primeira ligacao BLE: saltar para o painel. As religacoes automaticas
        // nao mexem no ecra em que o utilizador esta.
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state == BleConnectionState.CONNECTED && _currentTab.value == ScreenTab.CONNECT) {
                    _currentTab.value = ScreenTab.DASHBOARD
                }
            }
        }

        viewModelScope.launch {
            serverViewerState.collect { state ->
                if (state == RelayConnectionState.CONNECTED &&
                    activeDataSource.value == DataSource.SERVER &&
                    _currentTab.value == ScreenTab.CONNECT
                ) {
                    _currentTab.value = ScreenTab.DASHBOARD
                }
            }
        }

        // Desligado por fora do ecra (ex.: botao Desligar da notificacao).
        viewModelScope.launch {
            var previous = activeDataSource.value
            activeDataSource.collect { source ->
                if (source == DataSource.NONE && previous != DataSource.NONE) {
                    _currentTab.value = ScreenTab.CONNECT
                }
                previous = source
            }
        }
    }

    fun selectCar(car: CarProfile) = session.selectCar(car)

    fun setBleAddress(address: String) = session.setBleAddress(address)

    fun setTab(tab: ScreenTab) {
        _currentTab.value = tab
    }

    fun selectSegment(segmentId: Int?) {
        _selectedSegmentId.value = segmentId
        if (segmentId != null) {
            _currentTab.value = ScreenTab.CELLS
        }
    }

    fun startBleScan() = session.startBleScan()

    fun stopBleScan() = session.stopBleScan()

    fun connectBle(targetAddress: String? = null) = session.connectBle(targetAddress)

    fun connectServer(customUrl: String? = null) = session.connectServer(customUrl)

    fun disconnectServer() {
        session.disconnectServer()
        _currentTab.value = ScreenTab.CONNECT
    }

    fun setServerUrl(url: String) = session.setServerUrl(url)

    fun setRelayEnabled(enabled: Boolean) = session.setRelayEnabled(enabled)

    fun disconnect() {
        session.disconnect()
        _currentTab.value = ScreenTab.CONNECT
    }

    fun toggleDemoMode() {
        setDemoMode(!isDemoMode.value)
    }

    fun setDemoMode(enabled: Boolean, initialChargingOverride: Boolean? = null) {
        session.setDemoMode(enabled, initialChargingOverride)
        if (enabled) {
            if (_currentTab.value == ScreenTab.CONNECT) _currentTab.value = ScreenTab.DASHBOARD
        } else {
            _currentTab.value = ScreenTab.CONNECT
        }
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

    fun triggerChargingAlert(start: Boolean) = session.triggerChargingAlert(start)

    /**
     * Botao de teste do balao no ecra de carga: com o BMS ligado mostra o
     * balao com os dados reais; sem ligacao simula uma carga.
     */
    fun toggleManualCharging() {
        val startedDemo = session.toggleChargingTest()
        if (startedDemo && _currentTab.value == ScreenTab.CONNECT) {
            _currentTab.value = ScreenTab.DASHBOARD
        }
    }

    override fun onCleared() {
        super.onCleared()
        // A ligacao real (BLE/nuvem) continua no servico em primeiro plano.
        session.onUiCleared()
    }
}
