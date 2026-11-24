package com.self.phonetodo.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.self.phonetodo.MainActivity
import com.self.phonetodo.data.SettingsRepository
import com.self.phonetodo.data.UsageRepository
import com.self.phonetodo.model.AppSettings
import com.self.phonetodo.reminder.NotificationHelper
import java.time.LocalTime
import kotlin.math.abs

/**
 * Ultra-minimal Obsidian top floating capsule shown on phone unlock.
 * Displays ONLY today's unlock count and screen usage duration — zero clutter.
 */
class UnlockBannerController(
    private val context: Context,
    private val usageRepository: UsageRepository,
    private val settingsRepository: SettingsRepository,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentView: View? = null
    private var dismissRunnable: Runnable? = null

    @Volatile
    private var lastShownAt: Long = 0L

    fun onPhoneUnlocked(forcePreview: Boolean = false) {
        val settings = settingsRepository.settings.value
        if (!forcePreview) {
            if (!settings.unlockBannerEnabled || isInQuietHours(settings) || isInPhoneCall()) return
            val cooldownMs = settings.unlockBannerCooldownSeconds * 1000L
            if (cooldownMs > 0 && System.currentTimeMillis() - lastShownAt < cooldownMs) return
        }

        val overview = usageRepository.refresh()
        if (!Settings.canDrawOverlays(context)) {
            if (!forcePreview) {
                NotificationHelper.showUnlockFallbackNotification(
                    context = context,
                    unlocks = overview.todayUnlocks.coerceAtLeast(1),
                    screenMillis = overview.todayScreenMillis,
                    needsOverlayPermission = true,
                )
            }
            return
        }

        val delayMs = if (forcePreview) 0L else 320L
        mainHandler.postDelayed({ showCapsuleInternal(settings, forcePreview) }, delayMs)
    }

    private fun showCapsuleInternal(settings: AppSettings, forcePreview: Boolean) {
        removeCurrentViewImmediate()

        val overview = usageRepository.refresh()
        lastShownAt = System.currentTimeMillis()

        val dp = context.resources.displayMetrics.density
        fun Int.dp(): Int = (this * dp).toInt()

        val unlocksDisplay = overview.todayUnlocks.coerceAtLeast(if (forcePreview) 1 else overview.todayUnlocks)
        val timeStr = UsageRepository.formatDurationShort(overview.todayScreenMillis)
        val warnUnlock = unlocksDisplay >= settings.dailyUnlockWarningThreshold
        val warnTime = (overview.todayScreenMillis / 60_000L) >= settings.dailyScreenMinutesWarningThreshold
        val accentColor = Color.parseColor(if (warnUnlock || warnTime) "#F0A93B" else "#3DB59E")

        val capsule = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bgAlpha = (settings.unlockBannerOpacity.coerceIn(0.75f, 1.0f) * 255).toInt()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24.dp().toFloat()
                setColor(Color.argb(bgAlpha, 14, 18, 21))
                setStroke(1.dp(), Color.argb(60, 255, 255, 255))
            }
            elevation = 16.dp().toFloat()
            setPadding(18.dp(), 11.dp(), 18.dp(), 11.dp())
        }

        // Subtle pulse indicator dot
        capsule.addView(
            View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accentColor)
                }
                layoutParams = LinearLayout.LayoutParams(7.dp(), 7.dp()).apply { marginEnd = 10.dp() }
            },
        )

        // Unlock count
        capsule.addView(
            TextView(context).apply {
                text = "今日第 $unlocksDisplay 次打开"
                setTextColor(Color.parseColor("#F1F5F4"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                fontFeatureSettings = "tnum"
            },
        )

        // Hairline separator
        capsule.addView(
            View(context).apply {
                setBackgroundColor(Color.argb(55, 255, 255, 255))
                layoutParams = LinearLayout.LayoutParams(1.dp(), 13.dp()).apply {
                    marginStart = 12.dp()
                    marginEnd = 12.dp()
                }
            },
        )

        // Screen time
        capsule.addView(
            TextView(context).apply {
                text = "已用 $timeStr"
                setTextColor(accentColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                fontFeatureSettings = "tnum"
            },
        )

        // Swipe up/left/right to dismiss, tap to open Stats
        var downX = 0f
        var downY = 0f
        var dragging = false
        capsule.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    cancelAutoDismiss()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > 10 || abs(dy) > 10) dragging = true
                    if (abs(dx) >= abs(dy)) {
                        v.translationX = dx
                        v.alpha = (1f - abs(dx) / (v.width.coerceAtLeast(1) * 0.8f)).coerceIn(0.2f, 1f)
                    } else if (dy < 0) {
                        v.translationY = dy
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    when {
                        !dragging -> {
                            dismissAnimated()
                            openStatsScreen()
                        }
                        abs(dx) > 72.dp() || dy < -28.dp() -> dismissAnimated()
                        else -> {
                            v.animate().translationX(0f).translationY(0f).alpha(1f).setDuration(150).start()
                            scheduleAutoDismiss(settings.unlockBannerAutoDismissSeconds)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = getStatusBarHeightPx() + 12.dp()
        }

        val added = runCatching {
            capsule.alpha = 0f
            capsule.translationY = -24.dp().toFloat()
            windowManager.addView(capsule, params)
            currentView = capsule
            capsule.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
            scheduleAutoDismiss(settings.unlockBannerAutoDismissSeconds)
            true
        }.getOrDefault(false)

        if (!added && !forcePreview) {
            NotificationHelper.showUnlockFallbackNotification(
                context = context,
                unlocks = unlocksDisplay,
                screenMillis = overview.todayScreenMillis,
                needsOverlayPermission = false,
            )
        }
    }

    private fun isInQuietHours(settings: AppSettings): Boolean {
        if (!settings.quietHoursEnabled) return false
        val now = LocalTime.now()
        val cur = now.hour * 60 + now.minute
        return if (settings.quietStartMinutes <= settings.quietEndMinutes) {
            cur in settings.quietStartMinutes until settings.quietEndMinutes
        } else {
            cur >= settings.quietStartMinutes || cur < settings.quietEndMinutes
        }
    }

    private fun isInPhoneCall(): Boolean = runCatching {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        @Suppress("DEPRECATION")
        (tm?.callState ?: TelephonyManager.CALL_STATE_IDLE) != TelephonyManager.CALL_STATE_IDLE
    }.getOrDefault(false)

    private fun scheduleAutoDismiss(seconds: Int) {
        cancelAutoDismiss()
        val r = Runnable { dismissAnimated() }
        dismissRunnable = r
        mainHandler.postDelayed(r, seconds.coerceIn(2, 12) * 1000L)
    }

    private fun cancelAutoDismiss() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
    }

    fun dismissAnimated() {
        cancelAutoDismiss()
        val v = currentView ?: return
        currentView = null
        v.animate()
            .alpha(0f)
            .translationY(-32f)
            .setDuration(160)
            .setListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        runCatching { windowManager.removeViewImmediate(v) }
                    }
                },
            )
            .start()
    }

    private fun removeCurrentViewImmediate() {
        cancelAutoDismiss()
        val v = currentView ?: return
        currentView = null
        runCatching { windowManager.removeViewImmediate(v) }
    }

    private fun openStatsScreen() {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_OPEN_TAB, "stats")
                },
            )
        }
    }

    private fun getStatusBarHeightPx(): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else 96
    }
}
