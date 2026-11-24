package com.self.phonetodo.service

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.self.phonetodo.PhoneTodoApp
import com.self.phonetodo.reminder.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Persistent foreground service keeping unlock detection, exact screen-time tracking,
 * live status notification, and reliable alarms alive on Xiaomi HyperOS.
 *
 * Dual-path unlock detection handles Xiaomi's under-display fingerprint direct unlock
 * where ACTION_USER_PRESENT is occasionally skipped or fires before keyguard dismissal settles.
 */
class PhoneTodoForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var receiverRegistered = false
    private var unlockProbeJob: Job? = null

    @Volatile
    private var awaitingUnlock: Boolean = false

    @Volatile
    private var lastUnlockHandledAt: Long = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val app = applicationContext as? PhoneTodoApp ?: return
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    awaitingUnlock = true
                    unlockProbeJob?.cancel()
                    app.usageRepository.recordScreenOff()
                    app.unlockBannerController.dismissAnimated()
                    updatePersistentNotification()
                    app.refreshWidgetAsync()
                }

                Intent.ACTION_USER_PRESENT -> {
                    handlePhoneUnlocked(app)
                }

                Intent.ACTION_SCREEN_ON -> {
                    app.reminderScheduler.syncAll(fireMissed = true)
                    updatePersistentNotification()
                    // Probe keyguard state on Xiaomi HyperOS in case direct fingerprint unlock
                    // or no-lock-screen bypasses ACTION_USER_PRESENT
                    probeKeyguardUnlock(app)
                }
            }
        }
    }

    private fun probeKeyguardUnlock(app: PhoneTodoApp) {
        unlockProbeJob?.cancel()
        unlockProbeJob = scope.launch {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            for (waitMs in listOf(220L, 450L, 700L, 1000L)) {
                delay(waitMs)
                if (!awaitingUnlock) return@launch
                if (!pm.isInteractive) return@launch
                if (!km.isKeyguardLocked) {
                    handlePhoneUnlocked(app)
                    return@launch
                }
            }
        }
    }

    @Synchronized
    private fun handlePhoneUnlocked(app: PhoneTodoApp) {
        val now = System.currentTimeMillis()
        // Prevent double-trigger if both ACTION_USER_PRESENT and SCREEN_ON keyguard probe fire
        if (now - lastUnlockHandledAt < 2_000L) return
        lastUnlockHandledAt = now
        awaitingUnlock = false
        unlockProbeJob?.cancel()

        app.usageRepository.recordUnlock(now)
        app.unlockBannerController.onPhoneUnlocked(forcePreview = false)
        updatePersistentNotification()
        app.refreshWidgetAsync()
    }

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        NotificationHelper.ensureChannels(this)
        val app = applicationContext as PhoneTodoApp

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        awaitingUnlock = !pm.isInteractive || km.isKeyguardLocked
        app.usageRepository.ensureActiveSessionIfInteractive(pm.isInteractive && !km.isKeyguardLocked)

        val notification = buildCurrentNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID_FOREGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_FOREGROUND, notification)
        }

        registerScreenReceiver()
        startPeriodicTick()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        _isRunning.value = true
        updatePersistentNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        _isRunning.value = false
        unlockProbeJob?.cancel()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private fun startPeriodicTick() {
        scope.launch {
            while (isActive) {
                delay(60_000L)
                val app = applicationContext as PhoneTodoApp
                app.usageRepository.refresh()
                app.reminderScheduler.syncAll(fireMissed = true)
                updatePersistentNotification()
            }
        }
    }

    private fun buildCurrentNotification() = run {
        val app = applicationContext as PhoneTodoApp
        val overview = app.usageRepository.overview.value
        val pending = app.todoRepository.pendingCount()
        val topText = app.todoRepository.topBannerTodos(1).firstOrNull()?.text
        NotificationHelper.buildPersistentNotification(
            context = this,
            unlocks = overview.todayUnlocks,
            screenMillis = overview.todayScreenMillis,
            pendingTodos = pending,
            topTodoText = topText,
        )
    }

    private fun updatePersistentNotification() {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NotificationHelper.NOTIFICATION_ID_FOREGROUND, buildCurrentNotification())
        }
    }

    companion object {
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, PhoneTodoForegroundService::class.java)
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, PhoneTodoForegroundService::class.java))
            }
        }
    }
}
