package com.self.phonetodo.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.self.phonetodo.MainActivity
import com.self.phonetodo.R
import com.self.phonetodo.data.UsageRepository
import com.self.phonetodo.model.RepeatKind
import com.self.phonetodo.model.Todo
import com.self.phonetodo.model.TodoData

object NotificationHelper {

    const val CHANNEL_REMINDER = "phone_todo_reminder_v2"
    const val CHANNEL_STRONG_ALERT = "phone_todo_strong_alert_v3"
    const val CHANNEL_UNLOCK_FALLBACK = "phone_todo_unlock_fallback_v2"

    private const val LEGACY_NOTIFICATION_ID_FOREGROUND = 1001
    const val NOTIFICATION_ID_UNLOCK_FALLBACK = 1002
    private const val BRAND_COLOR = 0xFF0F6B5B.toInt()
    internal const val STRONG_ALERT_MAX_DELAY_MS = 30 * 60_000L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Remove all legacy persistent service notifications & stale channels cleanly
        runCatching { nm.cancel(LEGACY_NOTIFICATION_ID_FOREGROUND) }
        runCatching { nm.deleteNotificationChannel("phone_todo_persistent_v2") }
        runCatching { nm.deleteNotificationChannel("phone_todo_persistent_v3") }
        runCatching { nm.deleteNotificationChannel("phone_todo_strong_alert_v2") }

        val unlockFallback = NotificationChannel(
            CHANNEL_UNLOCK_FALLBACK,
            "解锁顶部提醒（悬浮窗备用）",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "当未开启悬浮窗权限时，每次解锁以系统顶部横幅通知形式显示今日解锁与用机时长"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val reminder = NotificationChannel(
            CHANNEL_REMINDER,
            "待办准点提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "待办到期时的横幅与铃声通知"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 150, 250)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        // Keep channel non-silent + IMPORTANCE_HIGH + BYPASS_DND so HyperOS never demotes heads-up/lockscreen alerts;
        // continuous looping USAGE_ALARM audio & vibration are driven directly by StrongAlertAudioController.
        val chimeSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val strong = NotificationChannel(
            CHANNEL_STRONG_ALERT,
            "重要待办强提醒（闹钟级）",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "锁屏亮屏与弹窗响铃强提醒，绝不错过重要事项"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            setSound(
                chimeSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }

        nm.createNotificationChannel(unlockFallback)
        nm.createNotificationChannel(reminder)
        nm.createNotificationChannel(strong)
    }

    fun showUnlockFallbackNotification(
        context: Context,
        unlocks: Int,
        screenMillis: Long,
        needsOverlayPermission: Boolean,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = PendingIntent.getActivity(
            context,
            1002,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_TAB, "stats")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val timeStr = UsageRepository.formatDurationShort(screenMillis)
        val builder = NotificationCompat.Builder(context, CHANNEL_UNLOCK_FALLBACK)
            .setSmallIcon(R.drawable.ic_stat_phonetodo)
            .setColor(BRAND_COLOR)
            .setContentTitle("今日第 $unlocks 次解锁 · 已用 $timeStr")
            .setContentText(if (needsOverlayPermission) "开启悬浮窗后将直接以顶部轻量胶囊弹出" else "点击查看今日用机统计")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(4_500L)
            .setContentIntent(openIntent)

        if (needsOverlayPermission) {
            val grantIntent = PendingIntent.getActivity(
                context,
                1003,
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "开启悬浮窗", grantIntent)
        }

        runCatching { nm.notify(NOTIFICATION_ID_UNLOCK_FALLBACK, builder.build()) }
    }

    internal fun shouldTriggerStrongAlert(strongAlert: Boolean, delayMs: Long): Boolean =
        strongAlert && delayMs <= STRONG_ALERT_MAX_DELAY_MS

    fun showTodoReminder(
        context: Context,
        todo: Todo,
        late: Boolean = false,
        delayMs: Long = if (late) 91_000L else 0L,
    ) {
        val appCtx = context.applicationContext
        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = notificationIdFor(todo.id)
        val triggerStrong = shouldTriggerStrongAlert(todo.strongAlert, delayMs)

        val openAppIntent = PendingIntent.getActivity(
            appCtx,
            notifId,
            Intent(appCtx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("todoId", todo.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val doneIntent = PendingIntent.getBroadcast(
            appCtx,
            notifId + 1,
            Intent(appCtx, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_MARK_DONE
                putExtra(ReminderReceiver.EXTRA_TODO_ID, todo.id)
                putExtra(ReminderReceiver.EXTRA_NOTIF_ID, notifId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val snoozeIntent = PendingIntent.getBroadcast(
            appCtx,
            notifId + 2,
            Intent(appCtx, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_SNOOZE_10M
                putExtra(ReminderReceiver.EXTRA_TODO_ID, todo.id)
                putExtra(ReminderReceiver.EXTRA_NOTIF_ID, notifId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val channel = if (triggerStrong) CHANNEL_STRONG_ALERT else CHANNEL_REMINDER
        val title = when {
            triggerStrong && late -> "重要待办强提醒（稍晚送达）"
            triggerStrong -> "重要待办强提醒"
            late -> "错过的待办提醒"
            else -> "待办提醒"
        }

        val isRecurring = todo.reminder != null && todo.reminder.repeat.kind != RepeatKind.NONE
        val doneActionLabel = if (isRecurring) "完成本次" else "标记完成"

        val builder = NotificationCompat.Builder(appCtx, channel)
            .setSmallIcon(R.drawable.ic_stat_phonetodo)
            .setColor(BRAND_COLOR)
            .setContentTitle(title)
            .setContentText(todo.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(todo.text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(if (triggerStrong) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .addAction(0, doneActionLabel, doneIntent)
            .addAction(0, "推迟 10 分钟", snoozeIntent)

        var strongActivityIntent: Intent? = null
        if (triggerStrong) {
            StrongAlertAudioController.wakeScreenBriefly(appCtx)
            StrongAlertAudioController.start(appCtx, todo.id)

            val activityIntent = Intent(appCtx, StrongAlertActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(ReminderReceiver.EXTRA_TODO_ID, todo.id)
                putExtra(ReminderReceiver.EXTRA_TODO_TEXT, todo.text)
                putExtra(ReminderReceiver.EXTRA_NOTIF_ID, notifId)
                putExtra(ReminderReceiver.EXTRA_IS_RECURRING, isRecurring)
            }
            strongActivityIntent = activityIntent

            val fullScreenIntent = PendingIntent.getActivity(
                appCtx,
                notifId + 3,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val dismissAlarmIntent = PendingIntent.getBroadcast(
                appCtx,
                notifId + 4,
                Intent(appCtx, ReminderReceiver::class.java).apply {
                    action = ReminderReceiver.ACTION_DISMISS_ALARM
                    putExtra(ReminderReceiver.EXTRA_TODO_ID, todo.id)
                    putExtra(ReminderReceiver.EXTRA_NOTIF_ID, notifId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setFullScreenIntent(fullScreenIntent, true)
            builder.setOngoing(true)
            builder.addAction(0, "关闭响铃", dismissAlarmIntent)
        }

        runCatching { nm.notify(notifId, builder.build()) }

        // Multi-layer strong alert presentation:
        // 1) Directly launch StrongAlertActivity (works immediately when unlocked or with overlay/MIUI popup perm)
        // 2) If HyperOS/Android suppresses background Activity launch, fall back after 450ms to the floating overlay card
        if (triggerStrong && strongActivityIntent != null) {
            runCatching { appCtx.startActivity(strongActivityIntent) }
            mainHandler.postDelayed(
                {
                    if (!StrongAlertActivity.isVisibleFor(todo.id) &&
                        StrongAlertAudioController.isActiveFor(todo.id)
                    ) {
                        StrongAlertOverlayController.show(
                            context = appCtx,
                            todoId = todo.id,
                            todoText = todo.text,
                            notifId = notifId,
                            isRecurring = isRecurring,
                        )
                    }
                },
                450L,
            )
        }
    }

    fun sendTestNotification(context: Context, strong: Boolean) {
        val sample = Todo(
            id = "test_reminder_${System.currentTimeMillis()}",
            text = if (strong) "重要待办强提醒测试：验证亮屏、响铃与强提醒弹窗" else "Phone ToDo 准点提醒通道正常",
            strongAlert = strong,
        )
        showTodoReminder(context, sample, late = false, delayMs = 0L)
    }

    fun cancelReminderNotification(context: Context, notifId: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.cancel(notifId) }
    }

    fun cancelTodoNotification(context: Context, todoId: String) {
        StrongAlertAudioController.stop(todoId)
        StrongAlertOverlayController.dismiss(todoId)
        cancelReminderNotification(context, notificationIdFor(todoId))
    }

    /**
     * Dismisses already-posted status-bar reminder notifications (and any active strong-alert audio/overlay)
     * whose todo has been completed (including recurring "complete this cycle"), finished permanently,
     * deleted, snoozed, rescheduled, or cleared — whether locally or via cross-device GitHub sync.
     */
    fun cancelStaleReminderNotifications(context: Context, data: TodoData) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val activeIds = runCatching { nm.activeNotifications.map { it.id }.toSet() }.getOrNull()
        val idsToCancel = computeNotificationIdsToCancel(data.todos, activeIds)
        for (id in idsToCancel) {
            runCatching { nm.cancel(id) }
        }
        val activeAlarmTodoId = StrongAlertAudioController.activeTodoIdOrNull()
        if (activeAlarmTodoId != null) {
            val matched = data.todos.firstOrNull { it.id == activeAlarmTodoId }
            if (matched != null && shouldClearTodoNotification(matched)) {
                StrongAlertAudioController.stop(activeAlarmTodoId)
                StrongAlertOverlayController.dismiss(activeAlarmTodoId)
            }
        }
    }

    internal fun shouldClearTodoNotification(todo: Todo): Boolean {
        if (todo.deletedAt != null || todo.done) return true
        val rem = todo.reminder ?: return true
        return rem.lastFiredAt == null
    }

    internal fun computeNotificationIdsToCancel(
        todos: List<Todo>,
        activeNotificationIds: Set<Int>?,
    ): Set<Int> {
        if (activeNotificationIds != null && activeNotificationIds.isEmpty()) return emptySet()
        val keepIds = todos
            .asSequence()
            .filter { !shouldClearTodoNotification(it) }
            .map { notificationIdFor(it.id) }
            .toSet()
        val staleIds = todos
            .asSequence()
            .filter { shouldClearTodoNotification(it) }
            .map { notificationIdFor(it.id) }
            .filter { it !in keepIds }
            .toSet()
        return if (activeNotificationIds != null) {
            staleIds.intersect(activeNotificationIds)
        } else {
            staleIds
        }
    }

    fun notificationIdFor(todoId: String): Int = (todoId.hashCode() and 0x0FFFFFFF) + 2000
}
