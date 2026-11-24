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
import android.text.TextUtils
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
import com.self.phonetodo.data.TodoRepository
import com.self.phonetodo.data.UsageRepository
import com.self.phonetodo.model.AppSettings
import com.self.phonetodo.model.Todo
import com.self.phonetodo.reminder.NotificationHelper
import java.time.LocalTime
import kotlin.math.abs

/**
 * Flagship Obsidian-Dial top floating capsule for Xiaomi 13 Ultra.
 * Shows unlock telemetry on the left and pending task(s) with instant check-off on the right.
 * Automatically falls back to a heads-up notification if overlay permission is missing.
 */
class UnlockBannerController(
    private val context: Context,
    private val todoRepository: TodoRepository,
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
            if (!settings.unlockBannerEnabled) return
            if (isInQuietHours(settings)) return
            if (isInPhoneCall()) return
            val now = System.currentTimeMillis()
            val cooldownMs = settings.unlockBannerCooldownSeconds * 1000L
            if (cooldownMs > 0 && now - lastShownAt < cooldownMs) return
        }

        val overview = usageRepository.refresh()
        val topTodos = todoRepository.topBannerTodos(settings.unlockBannerMaxTodos)

        if (!Settings.canDrawOverlays(context)) {
            if (!forcePreview) {
                NotificationHelper.showUnlockFallbackNotification(
                    context = context,
                    unlocks = overview.todayUnlocks.coerceAtLeast(1),
                    screenMillis = overview.todayScreenMillis,
                    topTodo = topTodos.firstOrNull(),
                    needsOverlayPermission = true,
                )
            }
            return
        }

        // Wait 320ms on real unlock so HyperOS keyguard transition settles cleanly before attaching overlay
        val delayMs = if (forcePreview) 0L else 320L
        mainHandler.postDelayed({
            showBannerInternal(settings, forcePreview)
        }, delayMs)
    }

    private fun isInQuietHours(settings: AppSettings): Boolean {
        if (!settings.quietHoursEnabled) return false
        val now = LocalTime.now()
        val curMin = now.hour * 60 + now.minute
        val start = settings.quietStartMinutes
        val end = settings.quietEndMinutes
        return if (start <= end) {
            curMin in start until end
        } else {
            curMin >= start || curMin < end
        }
    }

    private fun isInPhoneCall(): Boolean {
        return runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            @Suppress("DEPRECATION")
            val state = tm?.callState ?: TelephonyManager.CALL_STATE_IDLE
            state != TelephonyManager.CALL_STATE_IDLE
        }.getOrDefault(false)
    }

    private fun showBannerInternal(settings: AppSettings, forcePreview: Boolean) {
        removeCurrentViewImmediate()

        val overview = usageRepository.refresh()
        val topTodos = todoRepository.topBannerTodos(settings.unlockBannerMaxTodos)
        lastShownAt = System.currentTimeMillis()

        val dp = context.resources.displayMetrics.density
        fun Int.dp(): Int = (this * dp).toInt()

        val statusBarHeight = getStatusBarHeightPx()
        val unlockWarn = overview.todayUnlocks >= settings.dailyUnlockWarningThreshold
        val screenMins = (overview.todayScreenMillis / 60_000L).toInt()
        val timeWarn = screenMins >= settings.dailyScreenMinutesWarningThreshold
        val accentHex = if (unlockWarn || timeWarn) "#F0A93B" else "#3DB59E"
        val accentColor = Color.parseColor(accentHex)

        // Outer frosted obsidian capsule
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bgAlpha = (settings.unlockBannerOpacity.coerceIn(0.75f, 1.0f) * 255).toInt()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24.dp().toFloat()
                setColor(Color.argb(bgAlpha, 15, 19, 22))
                setStroke(1.dp(), Color.argb(56, 255, 255, 255))
            }
            elevation = 16.dp().toFloat()
            setPadding(16.dp(), 12.dp(), 14.dp(), 12.dp())
        }

        // Left telemetry dial: big tabular unlock number + screen time
        val dialCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumWidth = 64.dp()
        }

        val unlocksDisplay = overview.todayUnlocks.coerceAtLeast(if (forcePreview) 1 else overview.todayUnlocks)
        val countNum = TextView(context).apply {
            text = "$unlocksDisplay"
            setTextColor(accentColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            fontFeatureSettings = "tnum"
            includeFontPadding = false
        }
        dialCol.addView(countNum)

        val dialSub = TextView(context).apply {
            val timeStr = UsageRepository.formatDurationShort(overview.todayScreenMillis)
            text = "次解锁 $timeStr"
            setTextColor(Color.parseColor("#9BA4AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            fontFeatureSettings = "tnum"
            setPadding(0, 2.dp(), 0, 0)
        }
        dialCol.addView(dialSub)
        root.addView(dialCol)

        // Delicate vertical hairline separator
        val divider = View(context).apply {
            setBackgroundColor(Color.argb(38, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(1.dp(), 34.dp()).apply {
                marginStart = 12.dp()
                marginEnd = 12.dp()
            }
        }
        root.addView(divider)

        // Right column: top pending tasks
        val taskCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        if (topTodos.isEmpty()) {
            val emptyTitle = TextView(context).apply {
                text = "当前无待办"
                setTextColor(Color.parseColor("#EEF2F1"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val emptyHint = TextView(context).apply {
                text = "放下手机，把注意力留给当下"
                setTextColor(Color.parseColor("#8C969C"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, 2.dp(), 0, 0)
            }
            taskCol.addView(emptyTitle)
            taskCol.addView(emptyHint)
        } else {
            topTodos.forEachIndexed { idx, todo ->
                taskCol.addView(buildTodoRow(todo, dp, topPad = if (idx == 0) 0 else 6))
            }
        }
        root.addView(taskCol)

        // Swipe left/right/up to dismiss; tap to open Phone ToDo
        var downX = 0f
        var downY = 0f
        var dragging = false
        root.setOnTouchListener { v, event ->
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
                    if (abs(dx) > 12 || abs(dy) > 12) dragging = true
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
                    if (!dragging) {
                        dismissAnimated()
                        openMainApp()
                    } else if (abs(dx) > 84.dp() || dy < -36.dp()) {
                        dismissAnimated()
                    } else {
                        v.animate().translationX(0f).translationY(0f).alpha(1f).setDuration(160).start()
                        scheduleAutoDismiss(settings.unlockBannerAutoDismissSeconds)
                    }
                    true
                }
                else -> false
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight + 12.dp()
            width = context.resources.displayMetrics.widthPixels - 28.dp()
        }

        val added = runCatching {
            root.alpha = 0f
            root.translationY = -28.dp().toFloat()
            windowManager.addView(root, params)
            currentView = root
            root.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(240)
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
                topTodo = topTodos.firstOrNull(),
                needsOverlayPermission = false,
            )
        }
    }

    private fun buildTodoRow(todo: Todo, dp: Float, topPad: Int): View {
        fun Int.dp(): Int = (this * dp).toInt()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, topPad.dp(), 0, 0)
        }

        if (todo.pinned) {
            val pinDot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#F0A93B"))
                }
                layoutParams = LinearLayout.LayoutParams(6.dp(), 6.dp()).apply {
                    marginEnd = 6.dp()
                }
            }
            row.addView(pinDot)
        }

        val title = TextView(context).apply {
            text = todo.text
            setTextColor(Color.parseColor("#F1F5F4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 10.dp()
            }
        }
        row.addView(title)

        val checkBtn = TextView(context).apply {
            text = "✓"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#3DB59E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(34, 61, 181, 158))
                setStroke(1.dp(), Color.parseColor("#3DB59E"))
            }
            layoutParams = LinearLayout.LayoutParams(26.dp(), 26.dp())
            setOnClickListener {
                todoRepository.toggleTodoDone(todo.id)
                dismissAnimated()
            }
        }
        row.addView(checkBtn)

        return row
    }

    private fun scheduleAutoDismiss(seconds: Int) {
        cancelAutoDismiss()
        val delayMs = seconds.coerceIn(2, 15) * 1000L
        val r = Runnable { dismissAnimated() }
        dismissRunnable = r
        mainHandler.postDelayed(r, delayMs)
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
            .translationY(-42f)
            .setDuration(180)
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

    private fun openMainApp() {
        runCatching {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
        }
    }

    private fun getStatusBarHeightPx(): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else 96
    }
}
