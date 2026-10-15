package pt.fslart.bms.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pt.fslart.bms.model.BmsState
import pt.fslart.bms.model.CarProfile
import pt.fslart.bms.model.LiveJsonDecoder
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

enum class BleConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

class BleManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val TAG = "BleManager"

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var decoder = LiveJsonDecoder(CarProfile.TEK26E)

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

    // Multi-fragment packet buffer
    private val buffer = ByteArrayOutputStream()
    private val bufferLock = Any()

    var bytesIn: Long = 0L
        private set
    var linesIn: Long = 0L
        private set

    fun setCar(car: CarProfile) {
        decoder.car = car
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
        val entry = "[$timestamp] $msg"
        val current = _logLines.value.toMutableList()
        if (current.size > 200) {
            current.removeAt(0)
        }
        current.add(entry)
        _logLines.value = current
    }

    @SuppressLint("MissingPermission")
    fun connect(macAddress: String, car: CarProfile = CarProfile.TEK26E) {
        val cleanAddress = macAddress.trim().uppercase()
        if (cleanAddress.isEmpty()) {
            _statusMessage.value = "Endereço MAC inválido"
            _connectionState.value = BleConnectionState.ERROR
            return
        }

        disconnect()
        setCar(car)

        val device: BluetoothDevice = try {
            adapter?.getRemoteDevice(cleanAddress) ?: run {
                _statusMessage.value = "Adaptador Bluetooth indisponível"
                _connectionState.value = BleConnectionState.ERROR
                return
            }
        } catch (e: Exception) {
            _statusMessage.value = "Endereço BLE inválido ($cleanAddress)"
            _connectionState.value = BleConnectionState.ERROR
            return
        }

        _connectionState.value = BleConnectionState.CONNECTING
        _statusMessage.value = "A ligar a ${device.name ?: cleanAddress}…"
        log("Iniciando ligação BLE a $cleanAddress")

        synchronized(bufferLock) {
            buffer.reset()
        }

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            gatt?.let { g ->
                log("A desligar BLE")
                g.disconnect()
                g.close()
            }
        } catch (e: Exception) {
            log("Erro ao desligar: ${e.message}")
        } finally {
            gatt = null
            _connectionState.value = BleConnectionState.DISCONNECTED
            _statusMessage.value = "Desligado"
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("GATT status erro: $status no estado $newState")
                _statusMessage.value = "Falha de ligação (código $status)"
                _connectionState.value = BleConnectionState.ERROR
                disconnect()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("BLE Conectado. A solicitar MTU 512…")
                    _statusMessage.value = "Conectado. A negociar MTU…"
                    // Request MTU 512 for larger packet buffers
                    val mtuSuccess = g?.requestMtu(512) ?: false
                    if (!mtuSuccess) {
                        // Fallback directly to discoverServices
                        log("Falha ao pedir MTU, a descobrir serviços…")
                        g?.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("BLE Desconectado pelo periférico")
                    _statusMessage.value = "Dispositivo desligou-se"
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    disconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            log("MTU negociado para $mtu (status: $status). A descobrir serviços…")
            gatt?.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || g == null) {
                log("Falha na descoberta de serviços: $status")
                _statusMessage.value = "Erro na descoberta de serviços"
                _connectionState.value = BleConnectionState.ERROR
                return
            }

            log("Serviços descobertos (${g.services.size} serviços)")
            val notifyChar = pickNotifyCharacteristic(g)

            if (notifyChar == null) {
                log("Nenhuma característica de notificação utilizável encontrada")
                _statusMessage.value = "Sem característica de dados RN4871"
                _connectionState.value = BleConnectionState.ERROR
                disconnect()
                return
            }

            enableNotifications(g, notifyChar)
        }

        // Support for newer Android versions (API 33+)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingBytes(value)
        }

        // Support for older Android versions
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            characteristic?.value?.let { handleIncomingBytes(it) }
        }
    }

    private fun pickNotifyCharacteristic(g: BluetoothGatt): BluetoothGattCharacteristic? {
        // 1. Try exact Microchip RN4871 service + notify characteristic
        val rnService = g.getService(BleConstants.SERVICE_RN4871)
        if (rnService != null) {
            val c = rnService.getCharacteristic(BleConstants.CHAR_NOTIFY)
            if (c != null) {
                log("Característica RN4871 encontrada: ${c.uuid}")
                return c
            }
        }

        // 2. Try any characteristic that matches CHAR_NOTIFY
        for (svc in g.services) {
            val c = svc.getCharacteristic(BleConstants.CHAR_NOTIFY)
            if (c != null) {
                log("Característica NOTIFY encontrada no serviço ${svc.uuid}: ${c.uuid}")
                return c
            }
        }

        // 3. Fallback: find first characteristic with NOTIFY or INDICATE property
        for (svc in g.services) {
            for (c in svc.characteristics) {
                val props = c.properties
                if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                    (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                    if (!c.uuid.toString().lowercase().startsWith("00002a05")) {
                        log("Usando característica de notificação alternativa: ${c.uuid}")
                        return c
                    }
                }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val success = g.setCharacteristicNotification(characteristic, true)
        if (!success) {
            log("Falha ao registar notificação na characteristic")
            _statusMessage.value = "Falha ao ativar notificações"
            return
        }

        val descriptor = characteristic.getDescriptor(BleConstants.CCCD_DESCRIPTOR)
            ?: characteristic.descriptors.firstOrNull()

        if (descriptor != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
            log("Descriptor CCCD subscrito com sucesso")
        } else {
            log("Aviso: Descriptor CCCD não encontrado")
        }

        _connectionState.value = BleConnectionState.CONNECTED
        _statusMessage.value = "Ligado ao RN4871"
        log("Ligação BLE ativa e a receber dados do acumulador!")
    }

    private fun handleIncomingBytes(bytes: ByteArray) {
        bytesIn += bytes.size

        synchronized(bufferLock) {
            buffer.write(bytes)

            val currentBytes = buffer.toByteArray()
            var searchStart = 0

            while (true) {
                // Find '\n' (0x0A)
                var nlIndex = -1
                for (i in searchStart until currentBytes.size) {
                    if (currentBytes[i] == 0x0A.toByte()) {
                        nlIndex = i
                        break
                    }
                }

                if (nlIndex < 0) break

                // Extract line
                val lineLength = nlIndex - searchStart
                val lineBytes = ByteArray(lineLength)
                System.arraycopy(currentBytes, searchStart, lineBytes, 0, lineLength)
                searchStart = nlIndex + 1

                val lineString = String(lineBytes, StandardCharsets.UTF_8).trim()
                if (lineString.isNotEmpty()) {
                    linesIn++
                    processDecodedLine(lineString)
                }
            }

            // Keep remaining incomplete bytes
            if (searchStart > 0) {
                val remainingLen = currentBytes.size - searchStart
                buffer.reset()
                if (remainingLen > 0) {
                    buffer.write(currentBytes, searchStart, remainingLen)
                }
            }

            // Buffer overflow guard
            if (buffer.size() > BleConstants.LINE_BUFFER_MAX) {
                log("Aviso: Buffer de BLE ultrapassou ${BleConstants.LINE_BUFFER_MAX} bytes sem fim de linha. Descartado.")
                buffer.reset()
            }
        }
    }

    private fun processDecodedLine(line: String) {
        _rawLines.tryEmit(line)

        scope.launch(Dispatchers.Default) {
            val state = decoder.decodeLine(line)
            if (state != null) {
                val enrichedState = state.copy(
                    connectionAddress = gatt?.device?.address ?: "",
                    connectionDeviceName = gatt?.device?.name ?: "RN4871",
                    isConnected = true,
                    isDemo = false
                )
                _bmsState.value = enrichedState
            }
        }
    }

    fun injectSimulatorState(state: BmsState) {
        _bmsState.value = state
    }
}
