package com.self.phonetodo.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.self.phonetodo.PhoneTodoApp

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? PhoneTodoApp ?: return
        app.reminderScheduler.syncAll(fireMissed = true)
        if (app.settingsRepository.settings.value.keepAliveServiceEnabled) {
            PhoneTodoForegroundService.start(context)
        }
    }
}
