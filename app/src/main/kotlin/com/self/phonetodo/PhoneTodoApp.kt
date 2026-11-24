package com.self.phonetodo

import android.app.Application
import androidx.glance.appwidget.updateAll
import com.self.phonetodo.data.SettingsRepository
import com.self.phonetodo.data.TodoRepository
import com.self.phonetodo.data.UsageRepository
import com.self.phonetodo.overlay.UnlockBannerController
import com.self.phonetodo.reminder.NotificationHelper
import com.self.phonetodo.reminder.ReminderScheduler
import com.self.phonetodo.sync.GithubSyncManager
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
    lateinit var githubSyncManager: GithubSyncManager
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
            usageRepository = usageRepository,
            settingsRepository = settingsRepository,
        )
        githubSyncManager = GithubSyncManager(
            todoRepository = todoRepository,
            settingsRepository = settingsRepository,
        )

        todoRepository.onDataMutated = { data, triggerSync ->
            reminderScheduler.syncAll(data, fireMissed = false)
            refreshWidgetAsync()
            if (triggerSync && settingsRepository.settings.value.githubSyncEnabled) {
                scheduleDebouncedSync()
            }
        }

        reminderScheduler.syncAll(fireMissed = true)
        usageRepository.refresh()
    }

    fun scheduleDebouncedSync() {
        syncDebounceJob?.cancel()
        syncDebounceJob = appScope.launch {
            delay(3_000L)
            githubSyncManager.syncNow()
        }
    }

    fun syncIfStale(minIntervalMs: Long = 60_000L) {
        val s = settingsRepository.settings.value
        if (!s.githubSyncEnabled) return
        val last = s.lastSyncAt ?: 0L
        if (System.currentTimeMillis() - last < minIntervalMs) return
        appScope.launch {
            githubSyncManager.syncNow()
        }
    }

    fun refreshWidgetAsync() {
        appScope.launch {
            runCatching { PhoneTodoWidget().updateAll(this@PhoneTodoApp) }
        }
    }
}
