package com.self.phonetodo.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.self.phonetodo.PhoneTodoApp
import kotlin.math.abs

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? PhoneTodoApp ?: return
        when (intent.action) {
            ACTION_TEST_STRONG_ALERT -> {
                NotificationHelper.sendTestNotification(context, strong = true)
                return
            }
        }

        val todoId = intent.getStringExtra(EXTRA_TODO_ID) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, NotificationHelper.notificationIdFor(todoId))

        when (intent.action) {
            ACTION_FIRE_REMINDER -> {
                val firedAt = intent.getLongExtra(EXTRA_FIRED_AT, System.currentTimeMillis())
                val todo = app.todoRepository.data.value.activeTodos().firstOrNull { it.id == todoId }
                val expectedAt = todo?.reminder?.nextAt
                // Ignore stale alarms if the user already rescheduled/cleared/completed this todo
                if (todo != null && !todo.done && expectedAt != null && abs(expectedAt - firedAt) <= 10_000L) {
                    NotificationHelper.showTodoReminder(context, todo, late = false)
                    app.todoRepository.advanceReminderAfterFired(todo.id, expectedAt)
                }
            }

            ACTION_MARK_DONE -> {
                val todo = app.todoRepository.data.value.activeTodos().firstOrNull { it.id == todoId }
                if (todo != null && !todo.done) {
                    app.todoRepository.toggleTodoDone(todoId)
                }
                NotificationHelper.cancelReminderNotification(context, notifId)
            }

            ACTION_SNOOZE_10M -> {
                app.todoRepository.snoozeReminder(todoId, 10L)
                NotificationHelper.cancelReminderNotification(context, notifId)
            }
        }
    }

    companion object {
        const val ACTION_FIRE_REMINDER = "com.self.phonetodo.ACTION_FIRE_REMINDER"
        const val ACTION_MARK_DONE = "com.self.phonetodo.ACTION_MARK_DONE"
        const val ACTION_SNOOZE_10M = "com.self.phonetodo.ACTION_SNOOZE_10M"
        const val ACTION_TEST_STRONG_ALERT = "com.self.phonetodo.ACTION_TEST_STRONG_ALERT"

        const val EXTRA_TODO_ID = "extra_todo_id"
        const val EXTRA_TODO_TEXT = "extra_todo_text"
        const val EXTRA_FIRED_AT = "extra_fired_at"
        const val EXTRA_NOTIF_ID = "extra_notif_id"
        const val EXTRA_IS_RECURRING = "extra_is_recurring"
    }
}
