package pt.fslart.bms.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import pt.fslart.bms.BmsApplication

/**
 * Servico em primeiro plano enquanto ha uma ligacao real (Bluetooth ou nuvem).
 *
 * Sem ele, o Android (e o "congelador" de apps da Samsung) congela a app
 * poucos segundos depois de o ecra bloquear: os callbacks do Bluetooth deixam
 * de chegar, a ligacao cai e o balao fica parado com os ultimos valores. Com
 * ele, a ligacao e a notificacao continuam vivas durante a carga inteira.
 *
 * Nao tem logica propria: a [pt.fslart.bms.session.BmsSession] decide o que
 * mostrar e chama [showNotification].
 */
class BmsMonitorService : Service() {

    private var inForeground = false
    /** O Android recusou o primeiro plano: nao insistir a cada atualizacao. */
    private var foregroundDenied = false

    private val session get() = (application as BmsApplication).session

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val s = session
        if (intent == null) {
            // Reiniciado pelo sistema depois de o processo ter sido morto:
            // retomar a ligacao Bluetooth que o utilizador tinha pedido.
            if (!s.restoreAfterProcessDeath()) {
                stopSelf()
                return START_NOT_STICKY
            }
        } else if (!s.hasRealSource()) {
            stopSelf()
            return START_NOT_STICKY
        }

        foregroundDenied = false
        goForeground(s.buildServiceNotification())
        s.attachService(this)
        return START_STICKY
    }

    /** Publica (ou atualiza) a notificacao do servico. */
    fun showNotification(notification: Notification) {
        if (!inForeground && !foregroundDenied && goForeground(notification)) return
        BmsNotifications.post(this, notification)
    }

    private fun goForeground(notification: Notification): Boolean {
        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
            ServiceCompat.startForeground(this, BmsNotifications.NOTIFICATION_ID, notification, type)
            inForeground = true
            true
        } catch (e: Exception) {
            // Ex.: Android 12+ a recusar primeiro plano com a app em segundo plano.
            Log.w(TAG, "Não foi possível passar a primeiro plano: ${e.message}")
            inForeground = false
            foregroundDenied = true
            false
        }
    }

    override fun onDestroy() {
        session.detachService(this)
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        inForeground = false
        super.onDestroy()
    }

    private companion object {
        const val TAG = "BmsMonitorService"
    }
}
