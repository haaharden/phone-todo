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
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.self.phonetodo.MainActivity
import com.self.phonetodo.R
import com.self.phonetodo.data.UsageRepository
import com.self.phonetodo.model.Todo

object NotificationHelper {

    const val CHANNEL_PERSISTENT = "phone_todo_persistent_v3"
    private const val LEGACY_CHANNEL_PERSISTENT_V2 = "phone_todo_persistent_v2"
    const val CHANNEL_REMINDER = "phone_todo_reminder_v2"
    const val CHANNEL_STRONG_ALERT = "phone_todo_strong_alert_v2"
    const val CHANNEL_UNLOCK_FALLBACK = "phone_todo_unlock_fallback_v2"

    const val NOTIFICATION_ID_FOREGROUND = 1001
    const val NOTIFICATION_ID_UNLOCK_FALLBACK = 1002
    private const val BRAND_COLOR = 0xFF0F6B5B.toInt()

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Remove older persistent channel that showed on lock screen
        runCatching { nm.deleteNotificationChannel(LEGACY_CHANNEL_PERSISTENT_V2) }

        val persistent = NotificationChannel(
            CHANNEL_PERSISTENT,
            "后台运行保活（可关闭此项隐藏）",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "仅用于后台监听解锁与用机时长；可在系统通知中单独关闭此通道以彻底隐藏，不影响待办提醒与解锁胶囊"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }

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

        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val strong = NotificationChannel(
            CHANNEL_STRONG_ALERT,
            "重要待办强提醒（闹钟级）",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "锁屏全屏响铃提醒，绝不错过重要事项"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
            setSound(
                alarmSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }

        nm.createNotificationChannel(persistent)
        nm.createNotificationChannel(unlockFallback)
        nm.createNotificationChannel(reminder)
        nm.createNotificationChannel(strong)
    }

    fun buildPersistentNotification(
        context: Context,
        unlocks: Int,
        screenMillis: Long,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_TAB, "stats")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val timeText = UsageRepository.formatDurationShort(screenMillis)
        val title = "解锁 ${unlocks}次 · 用机 $timeText"

        return NotificationCompat.Builder(context, CHANNEL_PERSISTENT)
            .setSmallIcon(R.drawable.ic_stat_phonetodo)
            .setColor(BRAND_COLOR)
            .setContentTitle(title)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
            .build()
    }

    fun openPersistentChannelSettings(context: Context) {
        ensureChannels(context)
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_PERSISTENT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            val fallback = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(fallback) }
        }
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

    fun showTodoReminder(
        context: Context,
        todo: Todo,
        late: Boolean = false,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = notificationIdFor(todo.id)

        val openAppIntent = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("todoId", todo.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val doneIntent = PendingIntent.getBroadcast(
            context,
            notifId + 1,
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_MARK_DONE
                putExtra(ReminderReceiver.EXTRA_TODO_ID, todo.id)
                putExtra(ReminderReceiver.EXTRA_NOTIF_ID, notifId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val snoozeIntent = PendingIntent.getBroadcast(
            context,
            notifId + 2,
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_SNOOZE_10M
                putExtra(ReminderReceiver.EXTRA_TODO_ID, todo.id)
                putExtra(ReminderReceiver.EXTRA_NOTIF_ID, notifId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val channel = if (todo.strongAlert && !late) CHANNEL_STRONG_ALERT else CHANNEL_REMINDER
        val title = when {
            late -> "错过的待办提醒"
            todo.strongAlert -> "重要待办强提醒"
            else -> "待办提醒"
        }

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_phonetodo)
            .setColor(BRAND_COLOR)
            .setContentTitle(title)
            .setContentText(todo.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(todo.text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(if (todo.strongAlert) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "标记完成", doneIntent)
            .addAction(0, "推迟 10 分钟", snoozeIntent)

        if (todo.strongAlert && !late) {
            val fullScreenIntent = PendingIntent.getActivity(
                context,
                notifId + 3,
                Intent(context, StrongAlertActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(ReminderReceiver.EXTRA_TODO_ID, todo.id)
                    putExtra(ReminderReceiver.EXTRA_TODO_TEXT, todo.text)
                    putExtra(ReminderReceiver.EXTRA_NOTIF_ID, notifId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setFullScreenIntent(fullScreenIntent, true)
            builder.setOngoing(true)
        }

        runCatching { nm.notify(notifId, builder.build()) }
    }

    fun sendTestNotification(context: Context, strong: Boolean) {
        val sample = Todo(
            id = "test_reminder_${System.currentTimeMillis()}",
            text = if (strong) "重要待办强提醒测试：验证锁屏亮屏与铃声" else "Phone ToDo 准点提醒通道正常",
            strongAlert = strong,
        )
        showTodoReminder(context, sample, late = false)
    }

    fun cancelReminderNotification(context: Context, notifId: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(notifId)
    }

    fun notificationIdFor(todoId: String): Int = (todoId.hashCode() and 0x0FFFFFFF) + 2000
}
