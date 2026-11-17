package pt.fslart.bms.session

import android.app.Application
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pt.fslart.bms.ble.BleConnectionState
import pt.fslart.bms.ble.BleManager
import pt.fslart.bms.ble.BleScanner
import pt.fslart.bms.model.BmsState
import pt.fslart.bms.model.CarProfile
import pt.fslart.bms.network.RelayConnectionState
import pt.fslart.bms.network.TelemetryRelayManager
import pt.fslart.bms.service.BmsMonitorService
import pt.fslart.bms.service.BmsNotifications
import pt.fslart.bms.service.NotificationSnapshot
import pt.fslart.bms.simulator.BmsSimulator
import pt.fslart.bms.util.ChargingAlertManager
import pt.fslart.bms.viewmodel.DataSource

/**
 * Sessao de monitorizacao: dona da ligacao (Bluetooth, nuvem ou simulador), do
 * estado mostrado, da detecao de carga e da notificacao.
 *
 * Vive no processo e nao no ViewModel. Antes a ligacao e o balao dependiam do
 * ecra estar aberto; agora o [BmsMonitorService] mantem esta sessao viva com o
 * ecra bloqueado e a app fechada, e o balao e atualizado a cada amostra real.
 */
class BmsSession(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val bleManager = BleManager(app, scope)
    val bleScanner = BleScanner(app)
    val relayManager = TelemetryRelayManager(app, scope)
    private val chargingAlertManager = ChargingAlertManager(app)
    private var simulator = BmsSimulator(CarProfile.TEK26E)
    private var simulatorJob: Job? = null

    private val _activeDataSource = MutableStateFlow(DataSource.NONE)
    val activeDataSource: StateFlow<DataSource> = _activeDataSource.asStateFlow()

    private val _selectedCar = MutableStateFlow(CarProfile.TEK26E)
    val selectedCar: StateFlow<CarProfile> = _selectedCar.asStateFlow()

    private val _bleAddress = MutableStateFlow(CarProfile.TEK26E.bleAddress)
    val bleAddress: StateFlow<String> = _bleAddress.asStateFlow()

    private val _isDemoMode = MutableStateFlow(false)
    val isDemoMode: StateFlow<Boolean> = _isDemoMode.asStateFlow()

    private val _bmsState = MutableStateFlow(BmsState(car = CarProfile.TEK26E))
    val bmsState: StateFlow<BmsState> = _bmsState.asStateFlow()

    private val _currentHistory = MutableStateFlow<List<Float>>(emptyList())
    val currentHistory: StateFlow<List<Float>> = _currentHistory.asStateFlow()

    private val _voltageHistory = MutableStateFlow<List<Float>>(emptyList())
    val voltageHistory: StateFlow<List<Float>> = _voltageHistory.asStateFlow()

    /** "Testar balao" com um BMS real ligado: balao com os dados reais, sem carga. */
    private val _balloonPreview = MutableStateFlow(false)
    val balloonPreview: StateFlow<Boolean> = _balloonPreview.asStateFlow()

    /** Segundos desde a ultima amostra real (-1 = ainda nenhuma). */
    private val _dataAgeSeconds = MutableStateFlow(-1)
    val dataAgeSeconds: StateFlow<Int> = _dataAgeSeconds.asStateFlow()

    private var lastStateAt = 0L
    private var lastCharging = false

    // ---- Notificacao ----
    private enum class NotifMode { NONE, MONITOR, BALLOON }

    private var monitorService: BmsMonitorService? = null
    private var notifMode = NotifMode.NONE
    private var lastNotifAt = 0L
    /** O utilizador fechou o balao nesta sessao de carga. */
    private var balloonDismissed = false
    /** O utilizador apagou a notificacao: nao a repor ate a carga mudar de estado. */
    private var notificationSuppressed = false

    init {
        // Restos de uma execucao anterior (processo morto com a notificacao no ecra).
        BmsNotifications.cancel(app)

        scope.launch {
            bleManager.bmsState.collect { state ->
                if (state != null && _activeDataSource.value == DataSource.BLE) publishState(state, live = true)
            }
        }
        scope.launch {
            bleManager.rawLines.collect { line ->
                if (_activeDataSource.value == DataSource.BLE) relayManager.publishRawLine(line)
            }
        }
        scope.launch {
            relayManager.incomingState.collect { state ->
                if (_activeDataSource.value == DataSource.SERVER) publishState(state, live = true)
            }
        }
        scope.launch {
            bleManager.connectionState.collect {
                if (_activeDataSource.value == DataSource.BLE) renderNotification(force = true)
            }
        }
        scope.launch {
            relayManager.viewerState.collect {
                if (_activeDataSource.value == DataSource.SERVER) renderNotification(force = true)
            }
        }
        scope.launch {
            while (isActive) {
                delay(1_000L)
                tickDataAge()
            }
        }
    }

    // =========================================================================
    // Fontes de dados
    // =========================================================================

    fun selectCar(car: CarProfile) {
        _selectedCar.value = car
        _bleAddress.value = car.bleAddress
        simulator = BmsSimulator(car)
        bleManager.setCar(car)
        relayManager.setCar(car)
        if (_bmsState.value.isDemo) {
            _bmsState.value = _bmsState.value.copy(car = car)
        }
    }

    fun setBleAddress(address: String) {
        _bleAddress.value = address
    }

    fun startBleScan() {
        bleScanner.startScan()
    }

    fun stopBleScan() {
        bleScanner.stopScan()
    }

    fun connectBle(targetAddress: String? = null) {
        val mac = (targetAddress ?: _bleAddress.value).trim().uppercase()
        if (_isDemoMode.value) stopDemo()
        relayManager.disconnectViewer()
        bleScanner.stopScan()
        bleManager.connect(mac, _selectedCar.value)
        if (bleManager.isReconnectEnabled) {
            rememberWantedBle(mac, _selectedCar.value)
            switchSource(DataSource.BLE)
        } else {
            // Falhou logo (permissao, MAC invalido, sem Bluetooth): o erro fica no ecra.
            forgetWantedBle()
            if (_activeDataSource.value == DataSource.BLE) switchSource(DataSource.NONE)
        }
    }

    fun connectServer(customUrl: String? = null) {
        if (_isDemoMode.value) stopDemo()
        bleManager.disconnect()
        forgetWantedBle()
        switchSource(DataSource.SERVER)
        relayManager.startViewer(customUrl)
    }

    fun disconnectServer() {
        relayManager.disconnectViewer()
        if (_activeDataSource.value == DataSource.SERVER) switchSource(DataSource.NONE)
        _bmsState.value = BmsState(car = _selectedCar.value)
    }

    /** Desligar tudo (botao Desligar da app ou da notificacao). */
    fun disconnect() {
        if (_isDemoMode.value) stopDemo()
        bleManager.disconnect()
        forgetWantedBle()
        relayManager.disconnectAll()
        switchSource(DataSource.NONE)
        _bmsState.value = BmsState(car = _selectedCar.value)
    }

    fun setServerUrl(url: String) {
        relayManager.setServerUrl(url)
    }

    fun setRelayEnabled(enabled: Boolean) {
        relayManager.setRelayEnabled(enabled)
    }

    fun setDemoMode(enabled: Boolean, initialChargingOverride: Boolean? = null) {
        if (!enabled) {
            stopDemo()
            return
        }
        bleManager.disconnect()
        forgetWantedBle()
        relayManager.disconnectViewer()
        _isDemoMode.value = true
        switchSource(DataSource.SIMULATOR)
        simulator = BmsSimulator(_selectedCar.value).apply {
            if (initialChargingOverride != null) manualChargingOverride = initialChargingOverride
        }
        simulatorJob?.cancel()
        simulatorJob = scope.launch {
            while (isActive) {
                publishState(simulator.step(), live = false)
                delay(1_000L) // uma amostra por segundo, como o RN4871
            }
        }
    }

    private fun stopDemo() {
        simulatorJob?.cancel()
        simulatorJob = null
        _isDemoMode.value = false
        if (_activeDataSource.value == DataSource.SIMULATOR) switchSource(DataSource.NONE)
        _bmsState.value = BmsState(car = _selectedCar.value)
        renderNotification(force = true)
    }

    fun hasRealSource(): Boolean =
        _activeDataSource.value == DataSource.BLE || _activeDataSource.value == DataSource.SERVER

    private fun switchSource(source: DataSource, manageService: Boolean = true) {
        val previous = _activeDataSource.value
        _activeDataSource.value = source
        if (previous != source) {
            lastStateAt = 0L
            _dataAgeSeconds.value = -1
            lastCharging = false
            chargingAlertManager.reset()
            balloonDismissed = false
            notificationSuppressed = false
            _balloonPreview.value = false
        }
        if (manageService) {
            if (source == DataSource.BLE || source == DataSource.SERVER) startMonitorService() else stopMonitorService()
        }
        renderNotification(force = true)
    }

    /** Uma amostra nova de qualquer fonte: estado, historico, alertas e balao. */
    private fun publishState(state: BmsState, live: Boolean) {
        val charging = state.isCharging
        if (charging != lastCharging) {
            lastCharging = charging
            // Nova fase (comecou ou acabou a carga): o balao volta a poder aparecer.
            balloonDismissed = false
            notificationSuppressed = false
            if (charging) _balloonPreview.value = false
        }
        _bmsState.value = state
        if (live) {
            lastStateAt = SystemClock.elapsedRealtime()
            _dataAgeSeconds.value = 0
        }
        appendHistory(state.pack.current, state.pack.voltage)
        chargingAlertManager.onChargingStateUpdated(charging)
        renderNotification()
    }

    private fun tickDataAge() {
        if (!hasRealSource()) {
            if (_dataAgeSeconds.value != -1) _dataAgeSeconds.value = -1
            return
        }
        val age = if (lastStateAt == 0L) -1 else ((SystemClock.elapsedRealtime() - lastStateAt) / 1000L).toInt()
        _dataAgeSeconds.value = age
        if (age >= STALE_AFTER_S) renderNotification()
    }

    private fun appendHistory(current: Float, voltage: Float) {
        _currentHistory.value = (_currentHistory.value + current).takeLast(HISTORY_LEN)
        _voltageHistory.value = (_voltageHistory.value + voltage).takeLast(HISTORY_LEN)
    }

    // =========================================================================
    // Carga: alertas e teste do balao
    // =========================================================================

    fun triggerChargingAlert(start: Boolean) {
        if (start) chargingAlertManager.triggerChargingStartedAlert()
        else chargingAlertManager.triggerChargingStoppedAlert()
    }

    /**
     * Botao de teste do ecra de carga.
     *
     * Com um BMS real ligado mostra/esconde o balao com os dados reais (que
     * continuam a ser atualizados a cada amostra). Sem ligacao, simula uma
     * carga como antes.
     *
     * @return true se arrancou o simulador.
     */
    fun toggleChargingTest(): Boolean {
        if (hasRealSource()) {
            val enable = !_balloonPreview.value
            _balloonPreview.value = enable
            balloonDismissed = false
            notificationSuppressed = false
            if (enable) chargingAlertManager.triggerChargingStartedAlert()
            renderNotification(force = true)
            return false
        }

        val newCharging = !_bmsState.value.isCharging
        var startedDemo = false
        if (newCharging && !_isDemoMode.value) {
            setDemoMode(true, initialChargingOverride = true)
            startedDemo = true
        } else {
            simulator.manualChargingOverride = newCharging
        }

        // Reflexo imediato no ecra; o simulador confirma na amostra seguinte.
        val current = _bmsState.value
        val packVoltage = if (current.pack.voltage > 10f) current.pack.voltage else 520.0f
        val packSoc = if (current.pack.soc > 0f) current.pack.soc else 55.0f
        publishState(
            current.copy(
                isCharging = newCharging,
                pack = current.pack.copy(
                    voltage = packVoltage,
                    soc = packSoc,
                    current = if (newCharging) -6.0f else 0.0f,
                    power = if (newCharging) (packVoltage * 6.0f) / 1000f else 0.0f
                ),
                charger = current.charger.copy(
                    present = newCharging,
                    enabled = newCharging,
                    outputCurrent = if (newCharging) 6.0f else 0.0f,
                    outputVoltage = if (newCharging) packVoltage else 0.0f
                )
            ),
            live = false
        )
        return startedDemo
    }

    // =========================================================================
    // Notificacao
    // =========================================================================

    fun onBalloonClosedByUser() {
        if (_balloonPreview.value) _balloonPreview.value = false else balloonDismissed = true
        renderNotification(force = true)
    }

    fun onNotificationDismissed() {
        if (notifMode == NotifMode.BALLOON) {
            if (_balloonPreview.value) _balloonPreview.value = false else balloonDismissed = true
        }
        notificationSuppressed = true
        notifMode = NotifMode.NONE
    }

    fun attachService(service: BmsMonitorService) {
        monitorService = service
        notificationSuppressed = false
        // O servico acabou de mostrar a notificacao do modo atual.
        notifMode = if (wantsBalloon()) NotifMode.BALLOON else NotifMode.MONITOR
        lastNotifAt = SystemClock.elapsedRealtime()
    }

    fun detachService(service: BmsMonitorService) {
        if (monitorService === service) monitorService = null
        notifMode = NotifMode.NONE
    }

    /** A notificacao com que o servico entra em primeiro plano. */
    fun buildServiceNotification(): Notification {
        val balloon = wantsBalloon()
        val snap = snapshot(alert = balloon && notifMode != NotifMode.BALLOON)
        return if (balloon) BmsNotifications.buildBalloon(app, snap) else BmsNotifications.buildMonitor(app, snap)
    }

    private fun wantsBalloon(): Boolean =
        _activeDataSource.value != DataSource.NONE &&
            (_bmsState.value.isCharging || _balloonPreview.value) &&
            !balloonDismissed

    private fun desiredMode(): NotifMode = when {
        _activeDataSource.value == DataSource.NONE || notificationSuppressed -> NotifMode.NONE
        wantsBalloon() -> NotifMode.BALLOON
        monitorService != null -> NotifMode.MONITOR
        else -> NotifMode.NONE
    }

    private fun renderNotification(force: Boolean = false) {
        val mode = desiredMode()
        val changed = mode != notifMode
        val now = SystemClock.elapsedRealtime()
        val minInterval = if (mode == NotifMode.BALLOON) BALLOON_MIN_INTERVAL_MS else MONITOR_MIN_INTERVAL_MS
        if (!changed && !force && now - lastNotifAt < minInterval) return

        when (mode) {
            NotifMode.NONE -> if (changed || force) BmsNotifications.cancel(app)
            NotifMode.MONITOR -> post(BmsNotifications.buildMonitor(app, snapshot(alert = false)))
            // So a primeira publicacao da carga toca e aparece no topo; as
            // seguintes atualizam os valores em silencio.
            NotifMode.BALLOON -> post(BmsNotifications.buildBalloon(app, snapshot(alert = changed)))
        }
        notifMode = mode
        lastNotifAt = now
    }

    private fun post(notification: Notification) {
        val service = monitorService
        if (service != null) service.showNotification(notification) else BmsNotifications.post(app, notification)
    }

    private fun snapshot(alert: Boolean): NotificationSnapshot {
        val state = _bmsState.value
        val source = _activeDataSource.value
        return NotificationSnapshot(
            state = state,
            source = source,
            linkProblem = linkProblem(),
            isPreview = _balloonPreview.value && !state.isCharging,
            alert = alert,
            hasData = source == DataSource.SIMULATOR || lastStateAt != 0L
        )
    }

    private fun linkProblem(): String? {
        val age = if (lastStateAt == 0L) -1L else (SystemClock.elapsedRealtime() - lastStateAt) / 1000L
        return when (_activeDataSource.value) {
            DataSource.BLE -> when (bleManager.connectionState.value) {
                BleConnectionState.ERROR -> "⚠️ ${bleManager.statusMessage.value}"
                BleConnectionState.CONNECTING, BleConnectionState.DISCONNECTED ->
                    if (lastStateAt == 0L) "🔄 A ligar ao BMS…" else "🔄 Ligação perdida — a religar ao BMS…"
                BleConnectionState.CONNECTED -> when {
                    age < 0 -> "⏳ Ligado — à espera da primeira amostra…"
                    age >= STALE_AFTER_S -> "⚠️ Sem dados do BMS há $age s"
                    else -> null
                }
            }
            DataSource.SERVER -> when {
                relayManager.viewerState.value != RelayConnectionState.CONNECTED -> "🔄 A religar à nuvem…"
                age < 0 -> "⏳ À espera de telemetria da nuvem…"
                age >= STALE_AFTER_S -> "⚠️ Sem dados da nuvem há $age s"
                else -> null
            }
            else -> null
        }
    }

    // =========================================================================
    // Servico e reposicao
    // =========================================================================

    private fun startMonitorService() {
        try {
            app.startService(Intent(app, BmsMonitorService::class.java))
        } catch (e: Exception) {
            // So acontece com a app em segundo plano; a ligacao continua sem servico.
            Log.w(TAG, "Não foi possível arrancar o serviço de monitorização: ${e.message}")
        }
    }

    private fun stopMonitorService() {
        try {
            app.stopService(Intent(app, BmsMonitorService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Erro a parar o serviço de monitorização: ${e.message}")
        }
    }

    /**
     * O Android matou o processo e reiniciou o servico: retomar a ligacao
     * Bluetooth que o utilizador tinha pedido.
     */
    fun restoreAfterProcessDeath(): Boolean {
        if (hasRealSource()) return true
        val mac = prefs.getString(KEY_WANTED_MAC, null) ?: return false
        val carId = prefs.getString(KEY_WANTED_CAR, null)
        val car = CarProfile.ALL_CARS.firstOrNull { it.id == carId } ?: CarProfile.TEK26E
        selectCar(car)
        _bleAddress.value = mac
        bleManager.connect(mac, car)
        if (!bleManager.isReconnectEnabled) {
            forgetWantedBle()
            return false
        }
        switchSource(DataSource.BLE, manageService = false)
        return true
    }

    /** O ecra fechou (Activity destruida). A ligacao real continua no servico. */
    fun onUiCleared() {
        bleScanner.stopScan()
        if (_isDemoMode.value) stopDemo()
    }

    private fun rememberWantedBle(mac: String, car: CarProfile) {
        prefs.edit().putString(KEY_WANTED_MAC, mac).putString(KEY_WANTED_CAR, car.id).apply()
    }

    private fun forgetWantedBle() {
        prefs.edit().remove(KEY_WANTED_MAC).remove(KEY_WANTED_CAR).apply()
    }

    private companion object {
        const val TAG = "BmsSession"
        const val PREFS_NAME = "bms_session"
        const val KEY_WANTED_MAC = "wanted_ble_mac"
        const val KEY_WANTED_CAR = "wanted_car_id"

        const val HISTORY_LEN = 45
        const val STALE_AFTER_S = 5
        const val BALLOON_MIN_INTERVAL_MS = 900L
        const val MONITOR_MIN_INTERVAL_MS = 4_500L
    }
}
