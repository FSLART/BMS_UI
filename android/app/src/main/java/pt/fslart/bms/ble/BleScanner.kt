package pt.fslart.bms.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscoveredDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val isBmsDevice: Boolean = false
)

class BleScanner(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val scanResultsMap = mutableMapOf<String, DiscoveredDevice>()

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val dev = result.device ?: return
            val address = dev.address ?: return
            val name = dev.name ?: result.scanRecord?.deviceName ?: "(Sem nome)"
            val rssi = result.rssi

            val isBms = BleConstants.BMS_DEVICE_NAMES.any { name.contains(it, ignoreCase = true) } ||
                    address.equals(BleConstants.DEFAULT_TEK26E_MAC, ignoreCase = true)

            scanResultsMap[address] = DiscoveredDevice(
                name = name,
                address = address,
                rssi = rssi,
                isBmsDevice = isBms
            )

            // Prioritize BMS devices at top, then RSSI
            _devices.value = scanResultsMap.values.sortedWith(
                compareByDescending<DiscoveredDevice> { it.isBmsDevice }
                    .thenByDescending { it.rssi }
            )
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan(): Boolean {
        val scanner = adapter?.bluetoothLeScanner ?: return false
        if (_isScanning.value) return true

        scanResultsMap.clear()
        _devices.value = emptyList()

        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, callback)
            _isScanning.value = true
            return true
        } catch (e: Exception) {
            _isScanning.value = false
            return false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!_isScanning.value) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: Exception) {
            // ignore
        } finally {
            _isScanning.value = false
        }
    }
}
