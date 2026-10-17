package pt.fslart.bms.service

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
            ChargingNotificationService.stop(context)
        } catch (_: Exception) {}
    }
}

/**
 * Robust charging notification for Samsung One UI and all modern Android devices.
 *
 * Displays a yellow badge/balloon in the status bar and notification shade:
 * - Collapsed: Shows ONLY the battery voltage: "⚡ 580.4 V"
 * - Expanded (on click): Expands to show Tensão, SoC %, Corrente (+30A), Potência (kW) e Temperatura.
 * - Pinned: Persistent (ongoing) while battery is charging.
 */
object ChargingNotificationService {

    private const val TAG = "ChargingNotif"
    const val CHANNEL_ID = "bms_charging_status_v6"
    const val NOTIFICATION_ID = 2601

    private var isChannelCreated = false

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (isChannelCreated) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
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
                        vibrationPattern = longArrayOf(0, 180, 80, 180)
                        setSound(soundUri, audioAttributes)
                        setShowBadge(true)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                    manager.createNotificationChannel(channel)
                }
                isChannelCreated = true
            } catch (e: Exception) {
                Log.w(TAG, "Error configuring notification channel: ${e.message}")
            }
        }
    }

    fun startOrUpdate(context: Context, state: BmsState) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(context, notificationManager)

            val voltage = state.pack.voltage
            val current = state.pack.current
            val power = state.pack.power
            val soc = state.pack.soc.toInt().coerceIn(0, 100)
            val tempMax = state.pack.tempMax
            val carName = state.car.name

            // Intents
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

            // Collapsed: Yellow title with JUST the voltage: "⚡ 580.4 V"
            val collapsedTitle = "⚡ ${"%.1f".format(voltage)} V"
            val collapsedSubtitle = "🔋 $soc% SoC · ⚡ +${"%.1f".format(current)} A (${"%.2f".format(power)} kW)"

            // Expanded: Full breakdown with Tensão, SoC, Corrente, Potência, Temp
            val expandedDetails = StringBuilder()
                .append("🔋 Nível de Bateria (SoC): $soc%\n")
                .append("⚡ Corrente de Carga: +${"%.2f".format(current)} A (${"%.2f".format(power)} kW)\n")
                .append("🔌 Tensão Total do Pack: ${"%.1f".format(voltage)} V\n")
                .append("🌡️ Temperatura Máxima: ${"%.1f".format(tempMax)} °C\n")
                .append("🏁 Estação de Carga: Handcart T26 / EV Europe")
                .toString()

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bolt_notification)
                .setContentTitle(collapsedTitle)
                .setContentText(collapsedSubtitle)
                .setSubText("⚡ ${"%.1f".format(voltage)} V")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .setBigContentTitle("⚡ ${"%.1f".format(voltage)} V · $carName (A Carregar)")
                        .setSummaryText("🔋 $soc% SoC · +${"%.1f".format(current)} A")
                        .bigText(expandedDetails)
                )
                .setContentIntent(pendingIntent)
                .setProgress(100, soc, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setColor(Color.parseColor("#FFD60A"))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_MAX)
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

            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Safe notification post error: ${e.message}", e)
        }
    }

    fun stop(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Safe notification cancel error: ${e.message}", e)
        }
    }
}
