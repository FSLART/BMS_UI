package pt.fslart.bms.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ChargingAlertManager(private val context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var previousChargingState: Boolean? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun onChargingStateUpdated(isCharging: Boolean) {
        val prev = previousChargingState
        if (prev == null) {
            // Initial state: record without ringing
            previousChargingState = isCharging
            return
        }

        if (!prev && isCharging) {
            // TRANSITION: STARTED CHARGING
            previousChargingState = true
            triggerChargingStartedAlert()
        } else if (prev && !isCharging) {
            // TRANSITION: STOPPED CHARGING
            previousChargingState = false
            triggerChargingStoppedAlert()
        }
    }

    fun triggerChargingStartedAlert() {
        // 1. Vibration: 2 energetic pulses
        try {
            vibrator?.let { v ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val timings = longArrayOf(0, 180, 100, 320)
                    val amplitudes = intArrayOf(0, 200, 0, 255)
                    v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(longArrayOf(0, 180, 100, 320), -1)
                }
            }
        } catch (_: Exception) {}

        // 2. Sound: Ascending electronic tone
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 95)
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 350)
                Thread.sleep(380)
                tone.release()
            } catch (_: Exception) {}
        }

    }

    fun triggerChargingStoppedAlert() {
        // 1. Vibration: 3 warning pulses
        try {
            vibrator?.let { v ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val timings = longArrayOf(0, 300, 100, 300, 100, 450)
                    val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
                    v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(longArrayOf(0, 300, 100, 300, 100, 450), -1)
                }
            }
        } catch (_: Exception) {}

        // 2. Sound: Warning tone
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tone = ToneGenerator(AudioManager.STREAM_ALARM, 95)
                tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500)
                Thread.sleep(520)
                tone.release()
            } catch (_: Exception) {}
        }
    }
}
