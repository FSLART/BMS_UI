package pt.fslart.bms.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pt.fslart.bms.model.BmsState
import pt.fslart.bms.model.CarProfile
import pt.fslart.bms.model.LiveJsonDecoder
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class BleConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Ligacao BLE ao RN4871 do AMS master.
 *
 * A maquina de estados (ligar, configurar, religar) corre toda na main thread:
 * os callbacks do GATT chegam em threads de binder e sao reencaminhados para la.
 * A excecao sao os bytes de telemetria, tratados logo no binder com o buffer
 * protegido por lock; as linhas completas sao descodificadas em sequencia numa
 * unica corrotina.
 *
 * Religacao automatica: enquanto o utilizador quiser estar ligado ([connect]
 * sem [disconnect]), qualquer queda -- sinal perdido, RN4871 a reiniciar,
 * timeout, ligacao muda sem dados -- agenda nova tentativa. Nos primeiros
 * minutos tenta ligacoes diretas com espera crescente (com o CPU acordado, para
 * os temporizadores correrem com o ecra desligado); depois passa a uma ligacao
 * pendente `autoConnect`, que o proprio Android completa quando o BMS voltar.
 */
class BleManager(
    context: Context,
    private val scope: CoroutineScope
) {
    private val TAG = "BleManager"

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private val wakeLock: PowerManager.WakeLock? =
        (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LartBms:BleReconnect")
            ?.apply { setReferenceCounted(false) }

    var decoder = LiveJsonDecoder(CarProfile.TEK26E)
        private set

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _bmsState = MutableStateFlow<BmsState?>(null)
    val bmsState: StateFlow<BmsState?> = _bmsState.asStateFlow()

    private val _statusMessage = MutableStateFlow("Desligado")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private val _rawLines = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val rawLines: SharedFlow<String> = _rawLines.asSharedFlow()

    /** Tentativa de religacao em curso (0 = sem quedas desde que o utilizador ligou). */
    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    // ---- Estado da ligacao (so mexido na main thread) ----
    private var gatt: BluetoothGatt? = null
    @Volatile private var generation = 0
    @Volatile private var subscribedGeneration = -1
    @Volatile private var targetAddress: String? = null
    @Volatile private var deviceName: String = "RN4871"
    private var wantConnected = false
    private var everSubscribed = false
    private var attempt = 0
    private var linkLostAt = 0L
    private var discoveryStartedGeneration = -1
    private var cccdRetries = 0
    private var missingCharFailures = 0
    private var notifyChar: BluetoothGattCharacteristic? = null

    private var reconnectJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var setupTimeoutJob: Job? = null
    private var watchdogJob: Job? = null
    private var stableJob: Job? = null

    @Volatile private var lastRxBytesAt = 0L

    // ---- Dados ----
    private val buffer = ByteArrayOutputStream()
    private val bufferLock = Any()
    private val lines = Channel<String>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var rejectedLines = 0L

    @Volatile var bytesIn: Long = 0L
        private set
    @Volatile var linesIn: Long = 0L
        private set
    @Volatile var packetCount: Long = 0L
        private set

    /** O utilizador quer estar ligado (e por isso religa-se sozinho). */
    val isReconnectEnabled: Boolean get() = wantConnected

    init {
        // Uma so corrotina a descodificar: o decoder guarda estado entre linhas.
        scope.launch(Dispatchers.Default) {
            for (line in lines) {
                try {
                    decodeAndPublish(line)
                } catch (e: Exception) {
                    Log.e(TAG, "Erro a descodificar linha: ${e.message}", e)
                }
            }
        }
    }

    fun setCar(car: CarProfile) {
        decoder.car = car
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val entry = "[$timestamp] $msg"
        _logLines.update { current -> (current + entry).takeLast(300) }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    // =========================================================================
    // API publica
    // =========================================================================

    /** Ligar a este endereco e manter a ligacao (religa sozinho em caso de queda). */
    fun connect(macAddress: String, car: CarProfile = CarProfile.TEK26E) = onMain {
        val cleanAddress = macAddress.trim().uppercase()
        if (!BluetoothAdapter.checkBluetoothAddress(cleanAddress)) {
            wantConnected = false
            _statusMessage.value = "Endereço MAC inválido ($cleanAddress)"
            _connectionState.value = BleConnectionState.ERROR
            return@onMain
        }

        setCar(car)
        reconnectJob?.cancel()
        wantConnected = true
        everSubscribed = false
        targetAddress = cleanAddress
        attempt = 0
        linkLostAt = 0L
        missingCharFailures = 0
        _reconnectAttempt.value = 0
        bytesIn = 0L
        linesIn = 0L
        packetCount = 0L
        log("Pedido de ligação BLE a $cleanAddress")
        openGatt()
    }

    /** O utilizador desligou: fecha tudo e nao religa. */
    fun disconnect() = onMain {
        val hadLink = wantConnected || gatt != null
        wantConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        cancelTimers()
        closeGatt()
        releaseWakeLock()
        attempt = 0
        _reconnectAttempt.value = 0
        if (hadLink) log("Ligação BLE terminada pelo utilizador")
        _connectionState.value = BleConnectionState.DISCONNECTED
        _statusMessage.value = "Desligado"
    }

    // =========================================================================
    // Ligacao
    // =========================================================================

    @SuppressLint("MissingPermission")
    private fun openGatt() {
        val address = targetAddress ?: return
        cancelTimers()
        closeGatt()

        val bt = adapter
        if (bt == null) {
            stopWithError("Este telemóvel não tem Bluetooth disponível")
            return
        }
        if (!hasConnectPermission()) {
            log("ERRO: permissão BLUETOOTH_CONNECT não concedida")
            stopWithError("Falta a permissão de Bluetooth (Definições > Apps > LART BMS > Permissões)")
            return
        }
        if (!bt.isEnabled) {
            log("O Bluetooth do telemóvel está desligado — a aguardar")
            scheduleReconnect("Bluetooth do telemóvel desligado")
            return
        }

        val device = try {
            bt.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            stopWithError("Endereço BLE inválido ($address)")
            return
        }
        deviceName = safeName(device) ?: "RN4871"

        // Primeiros minutos depois de uma queda: ligacoes diretas, rapidas, com
        // o CPU acordado. Depois: ligacao pendente que o Android completa
        // sozinho quando o BMS voltar a anunciar (sem gastar bateria).
        val lostFor = if (linkLostAt == 0L) 0L else SystemClock.elapsedRealtime() - linkLostAt
        val background = attempt > 0 && lostFor > BleConstants.FAST_RECONNECT_WINDOW_MS

        val gen = ++generation
        discoveryStartedGeneration = -1
        _connectionState.value = BleConnectionState.CONNECTING
        _statusMessage.value = when {
            attempt == 0 -> "A ligar a $deviceName ($address)…"
            background -> "À espera do BMS — liga-se sozinho quando estiver ao alcance"
            else -> "A religar ao BMS… (tentativa $attempt)"
        }
        log(
            when {
                attempt == 0 -> "A abrir ligação GATT a $address"
                background -> "Religação em segundo plano (autoConnect) a $address"
                else -> "Tentativa de religação #$attempt a $address"
            }
        )
        synchronized(bufferLock) { buffer.reset() }

        if (background) releaseWakeLock() else if (attempt > 0) acquireWakeLock()

        val newGatt = try {
            device.connectGatt(appContext, background, GattCallback(gen), BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            log("ERRO de permissão ao ligar: ${e.message}")
            null
        }
        if (newGatt == null) {
            handleLinkLoss("o Android recusou abrir a ligação")
            return
        }
        gatt = newGatt

        if (!background) {
            connectTimeoutJob = scope.launch {
                delay(BleConstants.CONNECT_TIMEOUT_MS)
                if (gen == generation && _connectionState.value != BleConnectionState.CONNECTED) {
                    log("O RN4871 não respondeu em ${BleConstants.CONNECT_TIMEOUT_MS / 1000} s")
                    handleLinkLoss("o RN4871 não respondeu (desligado, fora de alcance ou ligado a outro aparelho)")
                }
            }
        }
    }

    private inner class GattCallback(private val gen: Int) : BluetoothGattCallback() {
        private val isCurrent: Boolean get() = gen == generation

        override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
            if (g == null) return
            onMain { if (isCurrent) onConnectionChange(g, gen, status, newState) }
        }

        override fun onMtuChanged(g: BluetoothGatt?, mtu: Int, status: Int) {
            if (g == null) return
            onMain { if (isCurrent) onMtu(g, gen, mtu, status) }
        }

        override fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
            if (g == null) return
            onMain { if (isCurrent) onServices(g, gen, status) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (g == null || descriptor == null) return
            onMain { if (isCurrent) onDescriptorWritten(g, gen, descriptor, status) }
        }

        // Android 13+ (API 33)
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (isCurrent) onBytes(g, gen, value)
        }

        // Android 12 e anteriores
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            @Suppress("DEPRECATION")
            val value = characteristic?.value ?: return
            if (g != null && isCurrent) onBytes(g, gen, value.copyOf())
        }
    }

    @SuppressLint("MissingPermission")
    private fun onConnectionChange(g: BluetoothGatt, gen: Int, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            connectTimeoutJob?.cancel()
            log("Ligação física estabelecida. A negociar MTU…")
            _statusMessage.value = "Ligado ao $deviceName. A configurar o canal de dados…"

            setupTimeoutJob?.cancel()
            setupTimeoutJob = scope.launch {
                delay(BleConstants.SETUP_TIMEOUT_MS)
                if (gen == generation && subscribedGeneration != gen) {
                    log("A configuração GATT não terminou em ${BleConstants.SETUP_TIMEOUT_MS / 1000} s")
                    handleLinkLoss("o canal de dados não ficou configurado")
                }
            }

            val bonded = try {
                g.device.bondState == BluetoothDevice.BOND_BONDED
            } catch (e: SecurityException) {
                false
            }
            scope.launch {
                // Com emparelhamento o Android ainda esta a cifrar a ligacao.
                delay(if (bonded) 1_000L else 150L)
                if (gen != generation) return@launch
                val mtuRequested = try {
                    g.requestMtu(BleConstants.MTU_REQUEST)
                } catch (e: SecurityException) {
                    false
                }
                if (!mtuRequested) {
                    log("Pedido de MTU recusado — a descobrir serviços diretamente")
                    startDiscovery(g, gen)
                    return@launch
                }
                // Alguns telemoveis nunca chamam onMtuChanged: nao ficar preso.
                delay(BleConstants.MTU_FALLBACK_MS)
                if (gen == generation && discoveryStartedGeneration != gen) {
                    log("Sem resposta ao MTU — a descobrir serviços na mesma")
                    startDiscovery(g, gen)
                }
            }
            return
        }

        val reason = describeStatus(status)
        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            log("A ligação BLE caiu: $reason (status=$status)")
        } else {
            log("Estado GATT inesperado: newState=$newState status=$status ($reason)")
        }
        handleLinkLoss(reason)
    }

    private fun onMtu(g: BluetoothGatt, gen: Int, mtu: Int, status: Int) {
        log("MTU negociado: $mtu bytes (status $status)")
        startDiscovery(g, gen)
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery(g: BluetoothGatt, gen: Int) {
        if (discoveryStartedGeneration == gen) return
        discoveryStartedGeneration = gen
        scope.launch {
            delay(100L)
            if (gen != generation) return@launch
            val started = try {
                g.discoverServices()
            } catch (e: SecurityException) {
                false
            }
            if (!started) {
                log("discoverServices() recusado pelo Android")
                handleLinkLoss("falha a descobrir os serviços")
            }
        }
    }

    private fun onServices(g: BluetoothGatt, gen: Int, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            log("Falha na descoberta de serviços (status $status)")
            handleLinkLoss("falha na descoberta de serviços ($status)")
            return
        }

        if (attempt == 0) {
            log("Serviços descobertos (${g.services.size}):")
            for (svc in g.services) {
                log("  Svc: ${svc.uuid}")
                for (c in svc.characteristics) {
                    val p = c.properties
                    val notify = (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    val indicate = (p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    log("    Char: ${c.uuid} [notify=$notify, ind=$indicate, props=0x${p.toString(16)}]")
                }
            }
        }

        val characteristic = pickNotifyCharacteristic(g)
        if (characteristic == null) {
            missingCharFailures++
            log("ERRO: nenhuma característica de notificação utilizável (vez $missingCharFailures)")
            if (missingCharFailures >= 3) {
                stopWithError("Este dispositivo não tem o UART do RN4871")
            } else {
                handleLinkLoss("serviço UART do RN4871 não encontrado")
            }
            return
        }

        notifyChar = characteristic
        cccdRetries = 0
        scope.launch {
            delay(150L)
            if (gen == generation) enableNotifications(g, gen, characteristic)
        }
    }

    private fun pickNotifyCharacteristic(g: BluetoothGatt): BluetoothGattCharacteristic? {
        val notifyMask = BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE

        // 1. Servico UART transparente do RN4871
        g.getService(BleConstants.SERVICE_RN4871)?.let { rnService ->
            rnService.getCharacteristic(BleConstants.CHAR_NOTIFY)?.let { return it }
            rnService.characteristics.firstOrNull { (it.properties and notifyMask) != 0 }?.let {
                log("Característica NOTIFY alternativa no serviço RN4871: ${it.uuid}")
                return it
            }
        }

        // 2. A caracteristica exata em qualquer servico
        for (svc in g.services) {
            for (c in svc.characteristics) {
                if (c.uuid == BleConstants.CHAR_NOTIFY) return c
            }
        }

        // 3. A primeira que notifique (sem o Service Changed 0x2A05)
        for (svc in g.services) {
            for (c in svc.characteristics) {
                if ((c.properties and notifyMask) != 0 && !c.uuid.toString().lowercase().startsWith("00002a05")) {
                    log("A usar característica de notificação alternativa: ${c.uuid} (serviço ${svc.uuid})")
                    return c
                }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt, gen: Int, characteristic: BluetoothGattCharacteristic) {
        log("A ativar notificações em ${characteristic.uuid}…")
        val localOk = try {
            g.setCharacteristicNotification(characteristic, true)
        } catch (e: SecurityException) {
            false
        }
        if (!localOk) {
            log("setCharacteristicNotification devolveu false")
            handleLinkLoss("falha ao ativar as notificações")
            return
        }

        val descriptor = characteristic.getDescriptor(BleConstants.CCCD_DESCRIPTOR)
            ?: characteristic.descriptors.firstOrNull { it.uuid.toString().contains("2902", ignoreCase = true) }
        if (descriptor == null) {
            log("AVISO: descritor CCCD (0x2902) não encontrado — a assumir notificações ativas")
            onSubscribed(g, gen)
            return
        }

        val props = characteristic.properties
        val onlyIndicate = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0 &&
            (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        val value = if (onlyIndicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }

        // descriptor.value tambem preenchido: alguns Samsung leem-no mesmo na API nova.
        @Suppress("DEPRECATION")
        descriptor.value = value
        val writeStarted = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
        } catch (e: SecurityException) {
            false
        }
        if (!writeStarted) {
            retryCccd(g, gen, "o Android não aceitou escrever o CCCD")
        }
    }

    private fun retryCccd(g: BluetoothGatt, gen: Int, reason: String) {
        val characteristic = notifyChar
        if (characteristic != null && cccdRetries < 2) {
            cccdRetries++
            log("$reason — nova tentativa ($cccdRetries/2)")
            scope.launch {
                delay(600L)
                if (gen == generation) enableNotifications(g, gen, characteristic)
            }
        } else {
            handleLinkLoss(reason)
        }
    }

    private fun onDescriptorWritten(g: BluetoothGatt, gen: Int, descriptor: BluetoothGattDescriptor, status: Int) {
        if (!descriptor.uuid.toString().contains("2902", ignoreCase = true)) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            log("Notificações ativadas no RN4871 (CCCD OK)")
            onSubscribed(g, gen)
        } else {
            retryCccd(g, gen, "o RN4871 recusou ativar as notificações (${describeStatus(status)})")
        }
    }

    @SuppressLint("MissingPermission")
    private fun onSubscribed(g: BluetoothGatt, gen: Int) {
        if (gen != generation || subscribedGeneration == gen) return
        subscribedGeneration = gen
        everSubscribed = true
        setupTimeoutJob?.cancel()
        missingCharFailures = 0
        lastRxBytesAt = SystemClock.elapsedRealtime()
        releaseWakeLock()

        _connectionState.value = BleConnectionState.CONNECTED
        _statusMessage.value = if (attempt > 0) {
            "Religado ao $deviceName — a receber telemetria"
        } else {
            "Ligado ao $deviceName — a receber telemetria"
        }
        log(if (attempt > 0) "Ligação restabelecida (tentativa $attempt)" else "Ligado e subscrito. A aguardar telemetria…")

        // Prioridade alta so depois de tudo configurado: pedi-la logo ao ligar
        // atropelava a troca de MTU e a descoberta de servicos.
        try {
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        } catch (_: SecurityException) {
        }

        startWatchdog(gen)
        stableJob?.cancel()
        val packetsAtSubscribe = packetCount
        stableJob = scope.launch {
            delay(BleConstants.STABLE_LINK_MS)
            // So conta como estavel se a telemetria estiver mesmo a chegar: uma
            // ligacao muda nao pode repor a espera entre tentativas.
            if (gen == generation && attempt != 0 && packetCount > packetsAtSubscribe) {
                attempt = 0
                linkLostAt = 0L
                _reconnectAttempt.value = 0
            }
        }
    }

    /** Ligacao "ligada" mas sem um byte ha demasiado tempo = ligacao morta. */
    private fun startWatchdog(gen: Int) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && gen == generation) {
                delay(2_000L)
                val silentMs = SystemClock.elapsedRealtime() - lastRxBytesAt
                if (gen == generation && silentMs >= BleConstants.DATA_SILENCE_TIMEOUT_MS) {
                    log("Sem dados do RN4871 há ${silentMs / 1000} s — a reiniciar a ligação")
                    handleLinkLoss("sem dados há ${silentMs / 1000} s")
                    return@launch
                }
            }
        }
    }

    private fun handleLinkLoss(reason: String) {
        cancelTimers()
        closeGatt()
        if (!wantConnected) {
            releaseWakeLock()
            _connectionState.value = BleConnectionState.DISCONNECTED
            _statusMessage.value = "Desligado"
            return
        }
        scheduleReconnect(reason)
    }

    private fun scheduleReconnect(reason: String) {
        reconnectJob?.cancel()
        if (attempt == 0) linkLostAt = SystemClock.elapsedRealtime()
        attempt++
        _reconnectAttempt.value = attempt

        val lostFor = SystemClock.elapsedRealtime() - linkLostAt
        val delayMs = if (lostFor > BleConstants.FAST_RECONNECT_WINDOW_MS) {
            BleConstants.RECONNECT_SLOW_MS
        } else {
            BleConstants.RECONNECT_BACKOFF_MS.getOrElse(attempt - 1) { BleConstants.RECONNECT_BACKOFF_MS.last() }
        }
        if (lostFor <= BleConstants.FAST_RECONNECT_WINDOW_MS) acquireWakeLock()

        _connectionState.value = BleConnectionState.CONNECTING
        val prefix = if (everSubscribed) "Ligação perdida" else "Sem ligação"
        _statusMessage.value = "$prefix: $reason. A tentar de novo em ${(delayMs + 999) / 1000} s…"
        log("Religação automática dentro de ${delayMs / 1000.0} s (tentativa $attempt)")
        reconnectJob = scope.launch {
            delay(delayMs)
            if (wantConnected) openGatt()
        }
    }

    private fun stopWithError(message: String) {
        wantConnected = false
        reconnectJob?.cancel()
        cancelTimers()
        closeGatt()
        releaseWakeLock()
        _statusMessage.value = message
        _connectionState.value = BleConnectionState.ERROR
    }

    private fun cancelTimers() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        setupTimeoutJob?.cancel()
        setupTimeoutJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        stableJob?.cancel()
        stableJob = null
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        generation++ // callbacks atrasados da ligacao antiga passam a ser ignorados
        notifyChar = null
        try {
            g.disconnect()
        } catch (_: Exception) {
        }
        try {
            g.close()
        } catch (_: Exception) {
        }
    }

    // =========================================================================
    // Dados
    // =========================================================================

    private fun onBytes(g: BluetoothGatt, gen: Int, bytes: ByteArray) {
        lastRxBytesAt = SystemClock.elapsedRealtime()
        bytesIn += bytes.size
        packetCount++

        if (subscribedGeneration != gen) {
            // Dados antes do onDescriptorWrite (alguns Samsung): ja esta subscrito.
            onMain { onSubscribed(g, gen) }
        }
        if (packetCount <= 3 || packetCount % 1000 == 0L) {
            log("RX bloco #$packetCount: ${bytes.size} B (total $bytesIn B)")
        }

        val complete = ArrayList<String>(2)
        synchronized(bufferLock) {
            var start = 0
            for (i in bytes.indices) {
                if (bytes[i] == NEWLINE) {
                    buffer.write(bytes, start, i - start)
                    val line = String(buffer.toByteArray(), Charsets.UTF_8)
                        .trim { it.isWhitespace() || it == '\u0000' }
                    buffer.reset()
                    if (line.isNotEmpty()) complete.add(line)
                    start = i + 1
                }
            }
            if (start < bytes.size) buffer.write(bytes, start, bytes.size - start)

            if (buffer.size() > BleConstants.LINE_BUFFER_MAX) {
                log("Aviso: ${buffer.size()} B sem fim de linha — descartados")
                buffer.reset()
            }
        }

        for (line in complete) {
            linesIn++
            if (linesIn <= 3 || linesIn % 120 == 0L) {
                log("Linha #$linesIn recebida (${line.length} chars)")
            }
            _rawLines.tryEmit(line)
            lines.trySend(line)
        }
    }

    private fun decodeAndPublish(line: String) {
        val state = decoder.decodeLine(line)
        if (state != null) {
            _bmsState.value = state.copy(
                connectionAddress = targetAddress ?: "",
                connectionDeviceName = deviceName,
                isConnected = true,
                isDemo = false
            )
            val rx = decoder.rxCount
            if (rx <= 3 || rx % 60 == 0L) {
                log(
                    "Telemetria #$rx: ${"%.1f".format(state.pack.voltage)} V, " +
                        "${"%.1f".format(state.pack.current)} A, AMS=${state.safety.masterState}, " +
                        "carga=${if (state.isCharging) "SIM" else "não"}"
                )
            }
        } else if (line.startsWith("AMS_ERROR")) {
            log("AMS: $line")
        } else {
            rejectedLines++
            if (rejectedLines <= 5 || rejectedLines % 50 == 0L) {
                log("Aviso: linha não descodificada #$rejectedLines (${line.length} chars): ${line.take(60)}…")
            }
        }
    }

    // =========================================================================
    // Utilitarios
    // =========================================================================

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String? = try {
        device.name
    } catch (e: SecurityException) {
        null
    }

    private fun acquireWakeLock() {
        try {
            wakeLock?.acquire(BleConstants.RECONNECT_WAKELOCK_MS)
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock.release()
        } catch (_: Exception) {
        }
    }

    private fun describeStatus(status: Int): String = when (status) {
        0 -> "ligação terminada"
        5, 15 -> "o RN4871 exige emparelhamento/encriptação ($status)"
        8 -> "sinal perdido (timeout de supervisão)"
        19 -> "o RN4871 terminou a ligação"
        22 -> "o telemóvel terminou a ligação"
        34 -> "timeout de procedimento do rádio"
        62 -> "falha a estabelecer a ligação"
        133 -> "erro GATT 133 (fora de alcance ou ocupado)"
        147 -> "timeout ao ligar"
        else -> "código $status"
    }

    private companion object {
        const val NEWLINE: Byte = 0x0A
    }
}
