package pt.fslart.bms.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import pt.fslart.bms.MainActivity
import pt.fslart.bms.R
import pt.fslart.bms.model.BmsState

/**
 * Spotify-style persistent Foreground Service notification that remains active in the
 * notification drawer when the app is in the background or minimized while charging.
 *
 * Displays real-time live telemetry: SoC %, Pack Voltage, Charging Current (+30A),
 * Power (kW), Max Temperature and an ongoing charging progress bar.
 */
class ChargingNotificationService : Service {

    constructor() : super()

    companion object {
        const val CHANNEL_ID = "bms_charging_status_channel"
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

        /**
         * Starts the persistent charging notification or updates it with fresh telemetry.
         */
        fun startOrUpdate(context: Context, state: BmsState) {
            val intent = Intent(context, ChargingNotificationService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_VOLTAGE, state.pack.voltage)
                putExtra(EXTRA_CURRENT, state.pack.current)
                putExtra(EXTRA_POWER, state.pack.power)
                putExtra(EXTRA_SOC, state.pack.soc)
                putExtra(EXTRA_TEMP_MAX, state.pack.tempMax)
                putExtra(EXTRA_CAR_NAME, state.car.name)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        /**
         * Stops the persistent charging notification when charging ceases.
         */
        fun stop(context: Context) {
            if (!isServiceRunning) return
            val intent = Intent(context, ChargingNotificationService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
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

            val notification = buildChargingNotification(
                voltage = voltage,
                current = current,
                power = power,
                soc = soc,
                tempMax = tempMax,
                carName = carName
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }

        return START_STICKY
    }

    private fun buildChargingNotification(
        voltage: Float,
        current: Float,
        power: Float,
        soc: Int,
        tempMax: Float,
        carName: String
    ): Notification {
        val clickIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop charging / silence action
        val stopIntent = Intent(this, ChargingNotificationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
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
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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

    private fun stopNotification() {
        isServiceRunning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Monitor de Carga do Acumulador",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificação contínua estilo Spotify com telemetria em tempo real durante a carga do acumulador."
                enableLights(true)
                lightColor = Color.YELLOW
                enableVibration(false)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
