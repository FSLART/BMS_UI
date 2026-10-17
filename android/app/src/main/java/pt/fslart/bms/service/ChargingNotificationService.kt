package pt.fslart.bms.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.RingtoneManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
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
 * Manages the charging notification and Samsung One UI status bar chip / floating card
 * using native Android MediaSession and Notification.MediaStyle.
 *
 * In Samsung One UI:
 * - Collapsed in status bar: displays the yellow chip with only voltage: "⚡ 580.4 V"
 * - When tapped / expanded: reveals full card with Tensão, SoC %, Corrente (+A), kW, Temp e Barra de Progresso.
 */
object ChargingNotificationService {

    private const val TAG = "ChargingNotif"
    const val CHANNEL_ID = "bms_charging_media_channel_v2"
    const val NOTIFICATION_ID = 2601

    private var mediaSession: MediaSession? = null
    private var isChannelCreated = false

    private fun ensureMediaSession(context: Context): MediaSession {
        if (mediaSession == null) {
            mediaSession = MediaSession(context, "LartBmsChargingSession").apply {
                isActive = true
                setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS or MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
                setCallback(object : MediaSession.Callback() {
                    override fun onStop() {
                        stop(context)
                    }
                    override fun onPause() {
                        stop(context)
                    }
                })
            }
        }
        return mediaSession!!
    }

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
                        vibrationPattern = longArrayOf(0, 150, 75, 150)
                        setSound(soundUri, audioAttributes)
                        setShowBadge(true)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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

            val session = ensureMediaSession(context)

            val voltage = state.pack.voltage
            val current = state.pack.current
            val power = state.pack.power
            val soc = state.pack.soc.toInt().coerceIn(0, 100)
            val tempMax = state.pack.tempMax
            val carName = state.car.name

            // 1. Update MediaSession Metadata
            // Samsung One UI status bar chip displays METADATA_KEY_TITLE: "⚡ 580.4 V"
            // When clicked/expanded: displays TITLE, ARTIST, ALBUM, and progress bar!
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "⚡ %.1f V".format(voltage))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "SoC: $soc% · Corrente: +%.1f A".format(current))
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "$carName · %.2f kW · Máx: %.1f °C".format(power, tempMax))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 100L)
                .build()
            session.setMetadata(metadata)

            val playbackState = PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, soc.toLong(), 1.0f)
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                .build()
            session.setPlaybackState(playbackState)

            // 2. Pending Intents
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

            // 3. Build Notification with Native MediaStyle
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }

            builder
                .setSmallIcon(R.drawable.ic_bolt_notification)
                .setContentTitle("⚡ %.1f V".format(voltage))
                .setContentText("SoC: $soc% · Corrente: +%.1f A (%.2f kW)".format(current, power))
                .setSubText("LART BMS · $carName")
                .setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(session.sessionToken)
                        .setShowActionsInCompactView(0)
                )
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setColor(Color.parseColor("#FFD60A"))
                .setColorized(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(context, R.drawable.ic_bolt_notification),
                        "Abrir Monitor",
                        pendingIntent
                    ).build()
                )
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(context, R.drawable.ic_close_notification),
                        "Fechar",
                        dismissPendingIntent
                    ).build()
                )

            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Safe notification post error: ${e.message}", e)
        }
    }

    fun stop(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        } catch (e: Exception) {
            Log.e(TAG, "Safe notification cancel error: ${e.message}", e)
        }
    }
}
