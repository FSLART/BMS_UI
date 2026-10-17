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
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import pt.fslart.bms.MainActivity
import pt.fslart.bms.R
import pt.fslart.bms.model.BmsState

/**
 * BroadcastReceiver triggered when the user taps "Fechar" on the charging notification.
 */
class ChargingNotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(ChargingNotificationService.NOTIFICATION_ID)
        } catch (_: Exception) {}
    }
}

/**
 * Robust, 100% crash-free charging notification manager designed for Samsung One UI and modern Android.
 *
 * Uses standard NotificationManager without fragile Foreground Services, preventing all
 * ForegroundServiceStartNotAllowedException, SecurityException, and OS watchdog kills.
 *
 * - Importance: HIGH (triggers heads-up pop-up balloon on Samsung One UI when charging begins)
 * - OnlyAlertOnce: true (alerts once on start, subsequent 1-second updates are smooth & silent)
 * - Ongoing: true (stays pinned during charging)
 */
object ChargingNotificationService {

    private const val TAG = "ChargingNotif"
    const val CHANNEL_ID = "bms_charging_status_v4"
    const val NOTIFICATION_ID = 2601

    private val OLD_CHANNELS = listOf(
        "bms_charging_status_channel",
        "bms_charging_status_channel_v2",
        "bms_charging_status_channel_v3"
    )

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                for (oldId in OLD_CHANNELS) {
                    try { manager.deleteNotificationChannel(oldId) } catch (_: Exception) {}
                }

                val existing = manager.getNotificationChannel(CHANNEL_ID)
                if (existing == null) {
                    val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    val audioAttributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build()

                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        "Monitor de Carga do Acumulador",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Notificação contínua estilo Spotify com telemetria em tempo real durante a carga do acumulador."
                        enableLights(true)
                        lightColor = Color.YELLOW
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 150, 75, 150)
                        setSound(soundUri, audioAttributes)
                        setShowBadge(true)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
                    manager.createNotificationChannel(channel)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error configuring notification channel: ${e.message}")
            }
        }
    }

    private fun buildNotification(
        context: Context,
        voltage: Float,
        current: Float,
        power: Float,
        soc: Int,
        tempMax: Float,
        carName: String
    ): Notification {
        val clickIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(context, ChargingNotificationDismissReceiver::class.java)
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "⚡ $carName a Carregar · $soc% (%.1f V)".format(voltage)
        val shortSummary = "+%.1f A · %.2f kW · Máx: %.1f °C".format(current, power, tempMax)

        val expandedDetails = StringBuilder()
            .append("🔋 Nível de Bateria: $soc%\n")
            .append("⚡ Corrente de Carga: +%.2f A (%.2f kW)\n".format(current, power))
            .append("🔌 Tensão Total do Pack: %.1f V\n".format(voltage))
            .append("🌡️ Temperatura Máxima: %.1f °C\n".format(tempMax))
            .append("🏁 Estação de Carga: Handcart T26 / EV Europe")
            .toString()

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bolt_notification)
            .setContentTitle(title)
            .setContentText(shortSummary)
            .setSubText("LART BMS · Carga Ativa")
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedDetails))
            .setContentIntent(pendingIntent)
            .setProgress(100, soc.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(Color.parseColor("#FFD60A"))
            .setColorized(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(
                R.drawable.ic_bolt_notification,
                "Abrir Monitor",
                pendingIntent
            )
            .addAction(
                R.drawable.ic_close_notification,
                "Fechar",
                dismissPendingIntent
            )
            .build()
    }

    /**
     * Posts or updates the persistent charging notification.
     * Completely crash-proof and safe from background start restrictions.
     */
    fun startOrUpdate(context: Context, state: BmsState) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(context, notificationManager)

            val notification = buildNotification(
                context = context,
                voltage = state.pack.voltage,
                current = state.pack.current,
                power = state.pack.power,
                soc = state.pack.soc.toInt(),
                tempMax = state.pack.tempMax,
                carName = state.car.name
            )

            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Safe notification post error: ${e.message}", e)
        }
    }

    /**
     * Dismisses the persistent charging notification.
     */
    fun stop(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Safe notification cancel error: ${e.message}", e)
        }
    }
}
