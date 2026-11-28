package com.self.phonetodo.reminder

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.self.phonetodo.PhoneTodoApp

/**
 * High-contrast floating alarm alert card rendered via TYPE_APPLICATION_OVERLAY as a resilient
 * HyperOS fallback whenever Android/MIUI suppresses full-screen [StrongAlertActivity] launch.
 */
object StrongAlertOverlayController {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var currentView: View? = null
    private var activeTodoId: String? = null
    private var currentWindowManager: WindowManager? = null

    fun show(
        context: Context,
        todoId: String,
        todoText: String,
        notifId: Int,
        isRecurring: Boolean,
    ) {
        val appCtx = context.applicationContext
        if (!Settings.canDrawOverlays(appCtx)) return

        mainHandler.post {
            // If StrongAlertActivity already made it to the foreground, skip duplicate overlay
            if (StrongAlertActivity.isVisibleFor(todoId)) return@post
            dismissImmediateLocked()

            val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dp = appCtx.resources.displayMetrics.density
            fun Int.dp(): Int = (this * dp).toInt()

            val card = LinearLayout(appCtx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 22.dp().toFloat()
                    setColor(Color.parseColor("#F513191D"))
                    setStroke(1.dp(), Color.parseColor("#66F0A93B"))
                }
                elevation = 20.dp().toFloat()
                setPadding(18.dp(), 16.dp(), 18.dp(), 14.dp())
            }

            // Header badge row
            val headerRow = LinearLayout(appCtx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headerRow.addView(
                View(appCtx).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#F0A93B"))
                    }
                    layoutParams = LinearLayout.LayoutParams(9.dp(), 9.dp()).apply { marginEnd = 8.dp() }
                },
            )
            headerRow.addView(
                TextView(appCtx).apply {
                    text = "重要待办强提醒"
                    setTextColor(Color.parseColor("#F0A93B"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            headerRow.addView(
                TextView(appCtx).apply {
                    text = "关闭响铃"
                    setTextColor(Color.parseColor("#9BA6AC"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(8.dp(), 4.dp(), 2.dp(), 4.dp())
                    setOnClickListener {
                        StrongAlertAudioController.stop(todoId)
                        NotificationHelper.cancelReminderNotification(appCtx, notifId)
                        dismiss(todoId)
                    }
                },
            )
            card.addView(headerRow)

            // Todo title
            card.addView(
                TextView(appCtx).apply {
                    text = todoText.ifBlank { "待办事项已到提醒时间" }
                    setTextColor(Color.parseColor("#F3F6F5"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    maxLines = 3
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = 10.dp()
                        bottomMargin = 14.dp()
                    }
                },
            )

            // Action buttons row
            val actionRow = LinearLayout(appCtx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            fun makeActionButton(label: String, bgColor: Int, textColor: Int, strokeColor: Int?): TextView {
                return TextView(appCtx).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setTextColor(textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 12.dp().toFloat()
                        setColor(bgColor)
                        if (strokeColor != null) setStroke(1.dp(), strokeColor)
                    }
                    setPadding(12.dp(), 11.dp(), 12.dp(), 11.dp())
                }
            }

            val snoozeBtn = makeActionButton(
                label = "推迟 10 分钟",
                bgColor = Color.parseColor("#222B31"),
                textColor = Color.parseColor("#DCE4E3"),
                strokeColor = Color.parseColor("#3B474F"),
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 10.dp()
                }
                setOnClickListener {
                    StrongAlertAudioController.stop(todoId)
                    val app = appCtx as? PhoneTodoApp
                    if (todoId.isNotBlank() && !todoId.startsWith("test_reminder_")) {
                        app?.todoRepository?.snoozeReminder(todoId, 10L)
                    }
                    NotificationHelper.cancelReminderNotification(appCtx, notifId)
                    dismiss(todoId)
                }
            }

            val doneBtn = makeActionButton(
                label = if (isRecurring) "完成本次" else "已完成",
                bgColor = Color.parseColor("#3DB59E"),
                textColor = Color.parseColor("#07231E"),
                strokeColor = null,
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    StrongAlertAudioController.stop(todoId)
                    val app = appCtx as? PhoneTodoApp
                    if (todoId.isNotBlank() && !todoId.startsWith("test_reminder_")) {
                        val existing = app?.todoRepository?.data?.value?.activeTodos()?.firstOrNull { it.id == todoId }
                        if (existing != null && !existing.done) {
                            app.todoRepository.toggleTodoDone(todoId)
                        }
                    }
                    NotificationHelper.cancelReminderNotification(appCtx, notifId)
                    dismiss(todoId)
                }
            }

            actionRow.addView(snoozeBtn)
            actionRow.addView(doneBtn)
            card.addView(actionRow)

            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

            val horizontalMargin = 16.dp()
            val screenWidth = appCtx.resources.displayMetrics.widthPixels
            val params = WindowManager.LayoutParams(
                (screenWidth - horizontalMargin * 2).coerceAtLeast(260.dp()),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = getStatusBarHeightPx(appCtx) + 18.dp()
            }

            runCatching {
                card.alpha = 0f
                card.translationY = -28.dp().toFloat()
                wm.addView(card, params)
                currentView = card
                activeTodoId = todoId
                currentWindowManager = wm
                card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(DecelerateInterpolator(1.8f))
                    .start()
            }
        }
    }

    fun dismiss(todoId: String? = null) {
        mainHandler.post {
            if (todoId != null && activeTodoId != null && activeTodoId != todoId) {
                return@post
            }
            dismissImmediateLocked()
        }
    }

    private fun dismissImmediateLocked() {
        val v = currentView ?: return
        val wm = currentWindowManager
        currentView = null
        activeTodoId = null
        currentWindowManager = null
        runCatching { wm?.removeViewImmediate(v) }
    }

    private fun getStatusBarHeightPx(context: Context): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else 96
    }
}
