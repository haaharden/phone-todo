package com.self.phonetodo

import android.app.Application
import androidx.glance.appwidget.updateAll
import com.self.phonetodo.data.SettingsRepository
import com.self.phonetodo.data.TodoRepository
import com.self.phonetodo.data.UsageRepository
import com.self.phonetodo.overlay.UnlockBannerController
import com.self.phonetodo.reminder.NotificationHelper
import com.self.phonetodo.reminder.ReminderScheduler
import com.self.phonetodo.service.PhoneTodoForegroundService
import com.self.phonetodo.sync.GoogleDriveSyncManager
import com.self.phonetodo.widget.PhoneTodoWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PhoneTodoApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var todoRepository: TodoRepository
        private set
    lateinit var usageRepository: UsageRepository
        private set
    lateinit var reminderScheduler: ReminderScheduler
        private set
    lateinit var unlockBannerController: UnlockBannerController
        private set
    lateinit var googleDriveSyncManager: GoogleDriveSyncManager
        private set

    private var syncDebounceJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)

        settingsRepository = SettingsRepository(this)
        todoRepository = TodoRepository(this)
        usageRepository = UsageRepository(this)
        reminderScheduler = ReminderScheduler(this, todoRepository)
        unlockBannerController = UnlockBannerController(
            context = this,
            todoRepository = todoRepository,
            usageRepository = usageRepository,
            settingsRepository = settingsRepository,
        )
        googleDriveSyncManager = GoogleDriveSyncManager(
            context = this,
            todoRepository = todoRepository,
            settingsRepository = settingsRepository,
        )

        todoRepository.onDataMutated = { data, triggerSync ->
            reminderScheduler.syncAll(data, fireMissed = false)
            refreshWidgetAsync()
            if (triggerSync && settingsRepository.settings.value.googleSyncEnabled) {
                scheduleDebouncedSync()
            }
        }

        reminderScheduler.syncAll(fireMissed = true)
        usageRepository.refresh()

        if (settingsRepository.settings.value.keepAliveServiceEnabled) {
            PhoneTodoForegroundService.start(this)
        }
    }

    fun scheduleDebouncedSync() {
        syncDebounceJob?.cancel()
        syncDebounceJob = appScope.launch {
            delay(3_000L)
            googleDriveSyncManager.syncNow()
        }
    }

    fun refreshWidgetAsync() {
        appScope.launch {
            runCatching { PhoneTodoWidget().updateAll(this@PhoneTodoApp) }
        }
    }
}
