package com.self.phonetodo.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.self.phonetodo.MainActivity
import com.self.phonetodo.data.TodoRepository
import com.self.phonetodo.model.Todo
import com.self.phonetodo.model.TodoData

/**
 * Uses AlarmManager.setAlarmClock() so Android Doze and Xiaomi HyperOS
 * wake the device reliably at the exact reminder timestamp.
 */
class ReminderScheduler(
    private val context: Context,
    private val todoRepository: TodoRepository,
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    @Synchronized
    fun syncAll(data: TodoData = todoRepository.data.value, fireMissed: Boolean = false) {
        val now = System.currentTimeMillis()
        val activePending = data.activeTodos().filter { !it.done && it.reminder?.nextAt != null }
        val activeIdSet = activePending.map { it.id }.toSet()

        // Cancel alarms for ALL inactive/completed/deleted/reminder-cleared todos (survives process restarts!)
        for (todo in data.todos) {
            if (todo.id !in activeIdSet) {
                cancelAlarm(todo.id)
            }
        }

        val missed = mutableListOf<Pair<Todo, Long>>()
        for (todo in activePending) {
            val nextAt = todo.reminder?.nextAt ?: continue
            if (nextAt <= now) {
                cancelAlarm(todo.id)
                if (fireMissed) {
                    missed += todo to nextAt
                }
                continue
            }
            scheduleExactAlarm(todo.id, nextAt)
        }

        // Fire missed reminders cleanly after updating scheduled alarms
        for ((todo, nextAt) in missed) {
            NotificationHelper.showTodoReminder(context, todo, late = (now - nextAt) > 90_000L)
            todoRepository.advanceReminderAfterFired(todo.id, nextAt)
        }
    }

    private fun scheduleExactAlarm(todoId: String, triggerAtMs: Long) {
        val fireIntent = PendingIntent.getBroadcast(
            context,
            requestCodeFor(todoId),
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_FIRE_REMINDER
                putExtra(ReminderReceiver.EXTRA_TODO_ID, todoId)
                putExtra(ReminderReceiver.EXTRA_FIRED_AT, triggerAtMs)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val showIntent = PendingIntent.getActivity(
            context,
            requestCodeFor(todoId) + 1,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        runCatching {
            if (canExact) {
                // setAlarmClock has the highest wake priority across MIUI/HyperOS/Doze
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAtMs, showIntent),
                    fireIntent,
                )
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, fireIntent)
            }
        }
    }

    fun cancelAlarm(todoId: String) {
        val pi = PendingIntent.getBroadcast(
            context,
            requestCodeFor(todoId),
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_FIRE_REMINDER
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pi != null) {
            alarmManager.cancel(pi)
            pi.cancel()
        }
    }

    private fun requestCodeFor(todoId: String): Int = (todoId.hashCode() and 0x0FFFFFFF) + 10_000
}
