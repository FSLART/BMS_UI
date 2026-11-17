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

    /** MTU pedido. O Android 14+ usa sempre 517 independentemente do valor. */
    const val MTU_REQUEST = 517

    /** Tempo maximo para o RN4871 aceitar a ligacao (o Android desiste aos ~30 s). */
    const val CONNECT_TIMEOUT_MS = 20_000L

    /** Tempo maximo entre ligar e ter as notificacoes ativas (MTU + servicos + CCCD). */
    const val SETUP_TIMEOUT_MS = 15_000L

    /** Se o MTU nao responder, descobrir servicos na mesma ao fim disto. */
    const val MTU_FALLBACK_MS = 3_000L

    /**
     * O firmware manda uma linha por segundo. Tanto tempo sem um unico byte
     * numa ligacao "ligada" quer dizer ligacao morta: religar.
     */
    const val DATA_SILENCE_TIMEOUT_MS = 15_000L

    /** Ligacao estavel ha este tempo = repor o contador de tentativas. */
    const val STABLE_LINK_MS = 10_000L

    /** Espera entre tentativas de religacao (a ultima repete-se). */
    val RECONNECT_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 3_000L, 5_000L, 5_000L, 10_000L)

    /**
     * Durante este tempo depois de uma queda tenta-se religar depressa, com o
     * CPU acordado (wakelock) para os temporizadores correrem com o ecra
     * desligado. Passado isto, fica uma ligacao pendente (autoConnect) que o
     * Android completa sozinho quando o BMS voltar, sem gastar bateria.
     */
    const val FAST_RECONNECT_WINDOW_MS = 10 * 60_000L

    /** Validade de cada aquisicao do wakelock de religacao (renovado a cada tentativa). */
    const val RECONNECT_WAKELOCK_MS = 60_000L

    /** Espera entre tentativas depois da janela rapida. */
    const val RECONNECT_SLOW_MS = 30_000L
}
