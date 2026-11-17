package pt.fslart.bms.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat
import pt.fslart.bms.BmsApplication
import pt.fslart.bms.MainActivity
import pt.fslart.bms.R
import pt.fslart.bms.model.BmsState
import pt.fslart.bms.util.ChargingEtaCalculator
import pt.fslart.bms.viewmodel.DataSource
import kotlin.math.abs
import kotlin.math.roundToInt

/** Tudo o que a notificacao precisa de saber num instante. */
data class NotificationSnapshot(
    val state: BmsState,
    val source: DataSource,
    /** Problema de ligacao a mostrar (null = dados em direto). */
    val linkProblem: String?,
    /** "Testar balao" com dados reais, sem carga a decorrer. */
    val isPreview: Boolean,
    /** Primeira publicacao desta sessao de carga: toca, vibra e aparece no topo. */
    val alert: Boolean,
    /** Ja chegou pelo menos uma amostra desta fonte. */
    val hasData: Boolean
)

/**
 * As duas formas da notificacao da app, sempre com o mesmo ID:
 *
 * - **Balao de carga** (canal de importancia alta): aparece sozinho quando a
 *   carga comeca, com tensao, SoC, corrente, temperatura e previsao, e e
 *   atualizado a cada amostra que chega do BMS.
 * - **Monitorizacao** (canal silencioso): a notificacao do servico em primeiro
 *   plano que mantem a ligacao Bluetooth viva com o ecra bloqueado.
 */
object BmsNotifications {
    private const val TAG = "BmsNotifications"

    const val NOTIFICATION_ID = 2601

    /** Canal ja existente nas versoes anteriores: mantem as preferencias do utilizador. */
    const val CHANNEL_BALLOON = "bms_charging_status_v6"
    const val CHANNEL_MONITOR = "bms_link_monitor_v1"

    const val ACTION_CLOSE_BALLOON = "pt.fslart.bms.action.CLOSE_BALLOON"
    const val ACTION_DISCONNECT = "pt.fslart.bms.action.DISCONNECT"
    const val ACTION_DISMISSED = "pt.fslart.bms.action.NOTIFICATION_DISMISSED"

    const val EXTRA_OPEN_TAB = "pt.fslart.bms.extra.OPEN_TAB"
    const val TAB_CHARGER = "charger"
    const val TAB_DASHBOARD = "dashboard"

    private const val REQ_OPEN_CHARGER = 10
    private const val REQ_OPEN_DASHBOARD = 11
    private const val REQ_CLOSE = 12
    private const val REQ_DISCONNECT = 13
    private const val REQ_DISMISSED = 14

    private val BALLOON_COLOR = Color.parseColor("#FFD60A")

    @Volatile private var channelsReady = false

    fun ensureChannels(context: Context) {
        if (channelsReady) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            if (manager.getNotificationChannel(CHANNEL_BALLOON) == null) {
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                val channel = NotificationChannel(
                    CHANNEL_BALLOON,
                    "Monitor de Carga do Acumulador",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Balão com a telemetria em tempo real durante a carga do acumulador."
                    enableLights(true)
                    lightColor = Color.YELLOW
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 180, 80, 180)
                    setSound(soundUri, audioAttributes)
                    setShowBadge(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(channel)
            }
            if (manager.getNotificationChannel(CHANNEL_MONITOR) == null) {
                val channel = NotificationChannel(
                    CHANNEL_MONITOR,
                    "Ligação ao BMS em segundo plano",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Mantém a ligação Bluetooth ao acumulador ativa com o ecrã bloqueado."
                    enableVibration(false)
                    setSound(null, null)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(channel)
            }
            channelsReady = true
        } catch (e: Exception) {
            Log.w(TAG, "Erro a criar canais de notificação: ${e.message}")
        }
    }

    // =========================================================================
    // Balao de carga
    // =========================================================================

    fun buildBalloon(context: Context, snap: NotificationSnapshot): Notification {
        ensureChannels(context)
        val state = snap.state
        val pack = state.pack
        val eta = ChargingEtaCalculator.calculate(state)
        val soc = pack.soc.roundToInt().coerceIn(0, 100)
        val voltage = pack.voltage

        // Corrente medida; sem medicao (ISA calada) mostra-se a estimativa, assinalada.
        val measuredA = when {
            abs(pack.current) >= 0.5f -> abs(pack.current)
            state.charger.outputCurrent >= 0.5f -> state.charger.outputCurrent
            else -> null
        }
        val shownA = measuredA ?: if (snap.isPreview) 0f else eta.chargeCurrentA
        val currentText = when {
            measuredA != null -> "+%.1f A".format(measuredA)
            snap.isPreview -> "%.1f A (sem carga)".format(0f)
            else -> "~%.0f A (estimado)".format(eta.chargeCurrentA)
        }
        val powerKw = voltage * shownA / 1000f
        val deltaMv = (pack.cellVDelta * 1000f).roundToInt()

        val title = "⚡ %.1f V · ⏱️ %s".format(voltage, eta.formattedTimeTo100())
        val text = snap.linkProblem
            ?: "🔋 $soc% · $currentText · fim às ${eta.completionTime100}"
        val subText = when {
            snap.isPreview -> "Teste do balão (dados reais)"
            snap.source == DataSource.SIMULATOR -> "Simulador"
            else -> "A carregar"
        }

        val details = StringBuilder()
        snap.linkProblem?.let { details.append(it).append('\n') }
        details.append("🔋 Nível de bateria (SoC): $soc%\n")
        details.append("⏱️ Até 100%: ${eta.formattedTimeTo100()} (às ${eta.completionTime100})\n")
        details.append("⚡ Até 80%: ${eta.formattedTimeTo80()}")
        if (!eta.isAlreadyAbove80) details.append(" (às ${eta.completionTime80})")
        details.append('\n')
        details.append("🔌 Corrente: $currentText · %.2f kW\n".format(powerKw))
        details.append("🔋 Pack: %.1f V · células %.3f–%.3f V (Δ %d mV)\n".format(voltage, pack.cellVMin, pack.cellVMax, deltaMv))
        details.append("🌡️ Temperatura máx.: %.1f °C".format(pack.tempMax))
        if (state.charger.energyWh > 0f || state.charger.sessionS > 0f) {
            val minutes = (state.charger.sessionS / 60f).toInt()
            details.append("\n🔄 Nesta carga: %.0f Wh em %d min".format(state.charger.energyWh, minutes))
        }

        val bigTitle = "⚡ %.1f V · %s (%s)".format(
            voltage,
            state.car.name,
            if (snap.isPreview) "teste" else "a carregar"
        )
        val summary = "🔋 $soc% · ⏱️ ${eta.formattedTimeTo100()} restantes"

        return NotificationCompat.Builder(context, CHANNEL_BALLOON)
            .setSmallIcon(R.drawable.ic_bolt_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(subText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(bigTitle)
                    .setSummaryText(summary)
                    .bigText(details.toString())
            )
            .setContentIntent(openAppIntent(context, TAB_CHARGER))
            .setDeleteIntent(actionIntent(context, ACTION_DISMISSED, REQ_DISMISSED))
            .setProgress(100, soc, !snap.hasData)
            .setOngoing(true)
            .setOnlyAlertOnce(!snap.alert)
            .setColor(BALLOON_COLOR)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setWhen(state.timestamp)
            .setShowWhen(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_bolt_notification, "Abrir Monitor", openAppIntent(context, TAB_CHARGER))
            .addAction(
                R.drawable.ic_close_notification,
                if (snap.isPreview) "Fechar teste" else "Fechar",
                actionIntent(context, ACTION_CLOSE_BALLOON, REQ_CLOSE)
            )
            .build()
    }

    // =========================================================================
    // Monitorizacao (servico em primeiro plano)
    // =========================================================================

    fun buildMonitor(context: Context, snap: NotificationSnapshot): Notification {
        ensureChannels(context)
        val state = snap.state
        val pack = state.pack
        val via = when (snap.source) {
            DataSource.SERVER -> "Nuvem"
            DataSource.SIMULATOR -> "Simulador"
            else -> "Bluetooth"
        }
        val title = "LART BMS · ${state.car.name} · $via"
        val text = snap.linkProblem ?: if (!snap.hasData) {
            "⏳ À espera de telemetria…"
        } else {
            "🔋 %d%% · ⚡ %.1f V · 🌡️ %.1f °C · Δ %d mV".format(
                pack.soc.roundToInt().coerceIn(0, 100),
                pack.voltage,
                pack.tempMax,
                (pack.cellVDelta * 1000f).roundToInt()
            )
        }

        return NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_bolt_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\nO balão de carga aparece sozinho quando o carregamento começar."))
            .setContentIntent(openAppIntent(context, TAB_DASHBOARD))
            .setDeleteIntent(actionIntent(context, ACTION_DISMISSED, REQ_DISMISSED))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setColor(BALLOON_COLOR)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_bolt_notification, "Abrir", openAppIntent(context, TAB_DASHBOARD))
            .addAction(R.drawable.ic_close_notification, "Desligar", actionIntent(context, ACTION_DISCONNECT, REQ_DISCONNECT))
            .build()
    }

    // =========================================================================
    // Publicacao
    // =========================================================================

    fun post(context: Context, notification: Notification) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Erro a publicar notificação: ${e.message}", e)
        }
    }

    fun cancel(context: Context) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Erro a cancelar notificação: ${e.message}", e)
        }
    }

    private fun openAppIntent(context: Context, tab: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TAB, tab)
        }
        val requestCode = if (tab == TAB_CHARGER) REQ_OPEN_CHARGER else REQ_OPEN_DASHBOARD
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, BmsNotificationActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/** Botoes da notificacao (Fechar / Desligar) e o deslizar para a apagar. */
class BmsNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val session = (context.applicationContext as? BmsApplication)?.session ?: return
        when (intent?.action) {
            BmsNotifications.ACTION_CLOSE_BALLOON -> session.onBalloonClosedByUser()
            BmsNotifications.ACTION_DISCONNECT -> session.disconnect()
            BmsNotifications.ACTION_DISMISSED -> session.onNotificationDismissed()
        }
    }
}
