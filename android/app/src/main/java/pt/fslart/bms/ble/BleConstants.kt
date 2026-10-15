package pt.fslart.bms.ble

import java.util.UUID

object BleConstants {
    // Microchip RN4871 Transparent UART Service and Characteristics
    val SERVICE_RN4871: UUID = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455")
    val CHAR_NOTIFY: UUID = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616") // Device -> App
    val CHAR_WRITE: UUID = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3")  // App -> Device
    val CCCD_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Default pre-configured hardware address in TEK-26e
    const val DEFAULT_TEK26E_MAC = "40:84:32:61:CE:13"

    // Recognized advertising names
    val BMS_DEVICE_NAMES = listOf("RN4871", "LART Accumulator", "ACU_V3", "BMS_MASTER")

    const val LINE_BUFFER_MAX = 16384
    const val CONNECT_TIMEOUT_MS = 10000L
}
