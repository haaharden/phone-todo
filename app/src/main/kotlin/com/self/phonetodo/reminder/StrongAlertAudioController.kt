package com.self.phonetodo.reminder

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Process-wide alarm audio, vibration, and wake-lock controller for Strong Alerts.
 *
 * Guarantees audible + tactile alarm delivery on Xiaomi HyperOS even when the OS suppresses
 * background Activity launch or fullScreenIntent, while preventing duplicate overlapping audio
 * when [StrongAlertActivity] is visible.
 */
object StrongAlertAudioController {

    private const val MAX_RING_DURATION_MS = 60_000L
    private const val SCREEN_WAKE_LOCK_MS = 15_000L

    private val lock = Any()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private var activeTodoId: String? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var autoStopRunnable: Runnable? = null

    /** Keeps CPU awake without turning the screen on yet (lets watch bridges dispatch while screen is still off). */
    fun acquireCpuWakeLockBriefly(context: Context, durationMs: Long = SCREEN_WAKE_LOCK_MS) {
        val appCtx = context.applicationContext
        synchronized(lock) {
            releaseCpuWakeLockLocked()
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneToDo:StrongAlertCpu")
            runCatching {
                wl.setReferenceCounted(false)
                wl.acquire(durationMs.coerceIn(3_000L, 30_000L))
                cpuWakeLock = wl
            }
        }
    }

    fun wakeScreenBriefly(context: Context, durationMs: Long = SCREEN_WAKE_LOCK_MS) {
        val appCtx = context.applicationContext
        synchronized(lock) {
            releaseWakeLockLocked()
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = runCatching {
                @Suppress("DEPRECATION")
                pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "PhoneToDo:StrongAlertWake",
                )
            }.getOrElse {
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneToDo:StrongAlertCpu")
            }
            runCatching {
                wl.setReferenceCounted(false)
                wl.acquire(durationMs.coerceIn(3_000L, 30_000L))
                wakeLock = wl
            }
        }
    }

    fun start(context: Context, todoId: String) {
        val appCtx = context.applicationContext
        synchronized(lock) {
            // Avoid restarting audio if this exact todo is already ringing
            if (activeTodoId == todoId && ringtone?.isPlaying == true) {
                return
            }
            stopAudioLocked()
            activeTodoId = todoId

            runCatching {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ringtone = RingtoneManager.getRingtone(appCtx, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    isLooping = true
                    play()
                }
            }

            runCatching {
                vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (appCtx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    appCtx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 450, 300, 450, 800), 0),
                )
            }

            val stopTask = Runnable { stop(todoId) }
            autoStopRunnable = stopTask
            mainHandler.postDelayed(stopTask, MAX_RING_DURATION_MS)
        }
    }

    fun isActiveFor(todoId: String): Boolean = synchronized(lock) {
        activeTodoId == todoId
    }

    fun activeTodoIdOrNull(): String? = synchronized(lock) { activeTodoId }

    fun stop(todoId: String? = null) {
        synchronized(lock) {
            if (todoId != null && activeTodoId != null && activeTodoId != todoId) {
                return
            }
            stopAudioLocked()
            releaseWakeLockLocked()
            releaseCpuWakeLockLocked()
            activeTodoId = null
        }
    }

    private fun stopAudioLocked() {
        autoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        autoStopRunnable = null
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    private fun releaseWakeLockLocked() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun releaseCpuWakeLockLocked() {
        runCatching {
            if (cpuWakeLock?.isHeld == true) {
                cpuWakeLock?.release()
            }
        }
        cpuWakeLock = null
    }
}
