package pt.fslart.bms.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import pt.fslart.bms.MainActivity
import pt.fslart.bms.R
import pt.fslart.bms.model.BmsState

/**
 * Spotify/Alarm-style persistent charging notification that stays pinned and updates live
 * on Samsung One UI and Android devices during battery charging.
 *
 * Uses IMPORTANCE_HIGH to display a heads-up banner ("balão na barra de cima") on trigger,
 * and updates live telemetry smoothly and silently (setOnlyAlertOnce=true) with SoC %,
 * voltage, +30A current, kW, max temp, and ongoing charging progress bar.
 */
class ChargingNotificationService : Service() {

    companion object {
        private const val TAG = "ChargingNotifService"
        const val CHANNEL_ID = "bms_charging_status_channel_v3"
        private const val OLD_CHANNEL_ID_V1 = "bms_charging_status_channel"
        private const val OLD_CHANNEL_ID_V2 = "bms_charging_status_channel_v2"
        const val NOTIFICATION_ID = 2601

        const val ACTION_START_OR_UPDATE = "pt.fslart.bms.service.ACTION_START_OR_UPDATE"
        const val ACTION_STOP = "pt.fslart.bms.service.ACTION_STOP"

        const val EXTRA_VOLTAGE = "extra_voltage"
        const val EXTRA_CURRENT = "extra_current"
        const val EXTRA_POWER = "extra_power"
        const val EXTRA_SOC = "extra_soc"
        const val EXTRA_TEMP_MAX = "extra_temp_max"
        const val EXTRA_CAR_NAME = "extra_car_name"

        @Volatile
        var isServiceRunning = false
            private set

        @Volatile
        private var fgsStartFailedInBackground = false

        fun resetBackgroundFailure() {
            fgsStartFailedInBackground = false
        }

        /**
         * Creates or updates the notification channel with IMPORTANCE_HIGH so Samsung shows the popup banner.
         */
        fun ensureChannel(context: Context, manager: NotificationManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    manager.deleteNotificationChannel(OLD_CHANNEL_ID_V1)
                    manager.deleteNotificationChannel(OLD_CHANNEL_ID_V2)
                } catch (_: Exception) {}

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
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
                    manager.createNotificationChannel(channel)
                }
            }
        }

        /**
         * Builds the Spotify-style ongoing notification.
         */
        fun buildNotification(
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

            val stopIntent = Intent(context, ChargingNotificationService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                context,
                1,
                stopIntent,
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
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .addAction(
                    R.drawable.ic_bolt_notification,
                    "Abrir Monitor",
                    pendingIntent
                )
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Fechar Notificação",
                    stopPendingIntent
                )
                .build()
        }

        /**
         * Starts the persistent charging notification or updates it with fresh telemetry.
         * Safe against background start exceptions and Samsung One UI restrictions.
         */
        fun startOrUpdate(context: Context, state: BmsState) {
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

            // 1. Post/update directly via NotificationManager!
            // This works 100% of the time, in background or foreground, without throwing FGS exceptions!
            try {
                notificationManager.notify(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to post notification via NotificationManager", e)
            }

            // 2. Start Foreground Service if not already running and not blocked by background execution restrictions
            if (!isServiceRunning && !fgsStartFailedInBackground) {
                val intent = Intent(context, ChargingNotificationService::class.java).apply {
                    action = ACTION_START_OR_UPDATE
                    putExtra(EXTRA_VOLTAGE, state.pack.voltage)
                    putExtra(EXTRA_CURRENT, state.pack.current)
                    putExtra(EXTRA_POWER, state.pack.power)
                    putExtra(EXTRA_SOC, state.pack.soc.toInt())
                    putExtra(EXTRA_TEMP_MAX, state.pack.tempMax)
                    putExtra(EXTRA_CAR_NAME, state.car.name)
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } catch (e: Exception) {
                    fgsStartFailedInBackground = true
                    Log.w(TAG, "Foreground service start prevented by OS in background (notification remains active): ${e.message}")
                }
            }
        }

        /**
         * Stops the persistent charging notification when charging ceases.
         */
        fun stop(context: Context) {
            fgsStartFailedInBackground = false
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try {
                notificationManager.cancel(NOTIFICATION_ID)
            } catch (_: Exception) {}

            if (isServiceRunning) {
                val intent = Intent(context, ChargingNotificationService::class.java).apply {
                    action = ACTION_STOP
                }
                try {
                    context.startService(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(this, notificationManager)
        isServiceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopNotification()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_START_OR_UPDATE) {
            val voltage = intent.getFloatExtra(EXTRA_VOLTAGE, 0f)
            val current = intent.getFloatExtra(EXTRA_CURRENT, 0f)
            val power = intent.getFloatExtra(EXTRA_POWER, 0f)
            val soc = intent.getIntExtra(EXTRA_SOC, 0)
            val tempMax = intent.getFloatExtra(EXTRA_TEMP_MAX, 0f)
            val carName = intent.getStringExtra(EXTRA_CAR_NAME) ?: "TEK-26e"

            val notification = buildNotification(
                context = this,
                voltage = voltage,
                current = current,
                power = power,
                soc = soc,
                tempMax = tempMax,
                carName = carName
            )

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val hasBle = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    val fgsType = if (hasBle) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    }
                    startForeground(NOTIFICATION_ID, notification, fgsType)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                Log.w(TAG, "startForeground failed (fallback to regular ongoing notification): ${e.message}")
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        }

        return START_STICKY
    }

    private fun stopNotification() {
        isServiceRunning = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
        try {
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
