package com.self.phonetodo

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.self.phonetodo.model.Todo
import com.self.phonetodo.permission.XiaomiPermissionHelper
import com.self.phonetodo.service.PhoneTodoForegroundService
import com.self.phonetodo.ui.importer.ImportScreen
import com.self.phonetodo.ui.settings.SettingsScreen
import com.self.phonetodo.ui.stats.StatsScreen
import com.self.phonetodo.ui.theme.PhoneToDoTheme
import com.self.phonetodo.ui.todo.TodoEditorSheet
import com.self.phonetodo.ui.todo.TodoScreen
import com.self.phonetodo.update.AppUpdater
import com.self.phonetodo.update.ReleaseUpdateInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

private enum class MainTab { TODOS, STATS }
private enum class OverlayPage { NONE, IMPORT, SETTINGS }

private data class UndoBannerState(
    val id: Long,
    val message: String,
    val onUndo: () -> Unit,
)

class MainActivity : ComponentActivity() {

    private var hasOverlayPermState by mutableStateOf(false)
    private var requestedTabState by mutableStateOf<MainTab?>(null)
    private var resumeTickState by mutableIntStateOf(0)

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        ensureMonitorServiceRunning()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !XiaomiPermissionHelper.hasNotificationPermission(this)
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleIntentExtras(intent)
        hasOverlayPermState = XiaomiPermissionHelper.hasOverlayPermission(this)
        ensureMonitorServiceRunning()

        val app = application as PhoneTodoApp
        setContent {
            val settings by app.settingsRepository.settings.collectAsState()
            PhoneToDoTheme(themeMode = settings.themeMode) {
                PhoneTodoRoot(
                    app = app,
                    activity = this,
                    hasOverlayPermission = hasOverlayPermState,
                    resumeTick = resumeTickState,
                    requestedTab = requestedTabState,
                    onRequestedTabConsumed = { requestedTabState = null },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasOverlayPermState = XiaomiPermissionHelper.hasOverlayPermission(this)
        resumeTickState++
        val app = application as PhoneTodoApp
        app.usageRepository.refresh()
        app.reminderScheduler.syncAll(fireMissed = true)
        app.syncIfStale()
        ensureMonitorServiceRunning()
    }

    private fun ensureMonitorServiceRunning() {
        val app = application as PhoneTodoApp
        if (app.settingsRepository.settings.value.keepAliveServiceEnabled) {
            PhoneTodoForegroundService.start(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntentExtras(intent)
    }

    private fun handleIntentExtras(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_OPEN_TAB) == "stats") {
            requestedTabState = MainTab.STATS
        }
    }

    companion object {
        const val EXTRA_OPEN_TAB = "extra_open_tab"
    }
}

@Composable
private fun PhoneTodoRoot(
    app: PhoneTodoApp,
    activity: MainActivity,
    hasOverlayPermission: Boolean,
    resumeTick: Int,
    requestedTab: MainTab?,
    onRequestedTabConsumed: () -> Unit,
) {
    val todoData by app.todoRepository.data.collectAsState()
    val usageOverview by app.usageRepository.overview.collectAsState()
    val settings by app.settingsRepository.settings.collectAsState()
    val isMonitorRunning by PhoneTodoForegroundService.isRunning.collectAsState()
    val scope = rememberCoroutineScope()

    var currentTab by remember { mutableStateOf(MainTab.TODOS) }
    var overlayPage by remember { mutableStateOf(OverlayPage.NONE) }
    var selectedCategoryFilter by remember { mutableStateOf<String?>(null) }
    var latestRelease by remember { mutableStateOf<ReleaseUpdateInfo?>(null) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var undoBanner by remember { mutableStateOf<UndoBannerState?>(null) }

    var showEditorSheet by remember { mutableStateOf(false) }
    var editingTodo by remember { mutableStateOf<Todo?>(null) }

    // Live 30s clock tick + immediate refresh on resume so header date, overdue red flags, and midnight carryover stay live
    LaunchedEffect(resumeTick) {
        nowMillis = System.currentTimeMillis()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    // Auto-dismiss 3.5s Undo pill
    LaunchedEffect(undoBanner?.id) {
        if (undoBanner != null) {
            delay(3_500L)
            undoBanner = null
        }
    }

    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            currentTab = requestedTab
            overlayPage = OverlayPage.NONE
            onRequestedTabConsumed()
        }
    }

    // Quiet background check for a newer GitHub Release on launch
    LaunchedEffect(settings.githubUpdateRepo) {
        if (settings.githubUpdateRepo.isNotBlank()) {
            AppUpdater.checkLatestRelease(settings.githubUpdateRepo).onSuccess { info ->
                latestRelease = info
            }
        }
    }

    LaunchedEffect(currentTab) {
        if (currentTab == MainTab.STATS) {
            app.usageRepository.refresh()
        }
    }

    // Back gesture from Stats tab returns cleanly to Todos tab
    BackHandler(enabled = overlayPage == OverlayPage.NONE && !showEditorSheet && currentTab != MainTab.TODOS) {
        currentTab = MainTab.TODOS
    }

    val currentLocalDate = remember(nowMillis) {
        Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    val todayFormatted = remember(currentLocalDate) {
        val wd = when (currentLocalDate.dayOfWeek) {
            DayOfWeek.MONDAY -> "星期一"
            DayOfWeek.TUESDAY -> "星期二"
            DayOfWeek.WEDNESDAY -> "星期三"
            DayOfWeek.THURSDAY -> "星期四"
            DayOfWeek.FRIDAY -> "星期五"
            DayOfWeek.SATURDAY -> "星期六"
            DayOfWeek.SUNDAY -> "星期日"
        }
        "${currentLocalDate.monthValue}月${currentLocalDate.dayOfMonth}日  $wd"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Main app layer (kept mounted when Settings/Import open so tab, pager page, and scroll state survive)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // Editorial Top Header (calm, left-aligned, generous breathing room)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (currentTab == MainTab.TODOS) "今天" else "用机节律",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = todayFormatted,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (settings.githubSyncEnabled) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                val res = app.githubSyncManager.syncNow()
                                val msg = res.getOrElse { it.message ?: "同步失败" }
                                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) {
                        Icon(
                            Icons.Rounded.Sync,
                            contentDescription = "同步",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                IconButton(onClick = { overlayPage = OverlayPage.SETTINGS }) {
                    Box {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (latestRelease?.isNewer == true) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondary),
                            )
                        }
                    }
                }
            }

            // Main content area
            Box(modifier = Modifier.weight(1f)) {
                when (currentTab) {
                    MainTab.TODOS -> TodoScreen(
                        todoData = todoData,
                        usageOverview = usageOverview,
                        settings = settings,
                        nowMillis = nowMillis,
                        hasOverlayPermission = hasOverlayPermission,
                        isMonitorRunning = isMonitorRunning,
                        isTopScreen = overlayPage == OverlayPage.NONE && !showEditorSheet,
                        selectedCategoryFilter = selectedCategoryFilter,
                        onSelectCategoryFilter = { selectedCategoryFilter = it },
                        onToggleTodo = { id ->
                            val prev = app.todoRepository.toggleTodoDone(id)
                            if (prev != null && !prev.done) {
                                val label = prev.text.take(14) + if (prev.text.length > 14) "…" else ""
                                undoBanner = UndoBannerState(
                                    id = System.nanoTime(),
                                    message = "已完成「$label」",
                                    onUndo = { app.todoRepository.restoreTodo(prev) },
                                )
                            }
                        },
                        onEditTodo = {
                            editingTodo = it
                            showEditorSheet = true
                        },
                        onAddCategory = { app.todoRepository.addCategory(it) },
                        onToggleShowDone = {
                            app.settingsRepository.update { it.copy(showDone = !it.showDone) }
                        },
                        onOpenStats = { currentTab = MainTab.STATS },
                        onRequestOverlayPermission = {
                            XiaomiPermissionHelper.openPermissionPage(activity, "overlay")
                        },
                        onRestartMonitor = {
                            app.settingsRepository.update { it.copy(keepAliveServiceEnabled = true) }
                            PhoneTodoForegroundService.start(activity)
                        },
                    )

                    MainTab.STATS -> StatsScreen(
                        overview = usageOverview,
                        settings = settings,
                        onRequestUsagePermission = {
                            XiaomiPermissionHelper.openPermissionPage(activity, "usage_stats")
                        },
                    )
                }
            }
        }

        // Sleek 3.5s Undo Pill above the bottom dock (rescues accidental completion/deletion)
        AnimatedVisibility(
            visible = undoBanner != null && overlayPage == OverlayPage.NONE,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 84.dp),
        ) {
            val activeUndo = undoBanner
            if (activeUndo != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background,
                    shadowElevation = 6.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = activeUndo.message,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                activeUndo.onUndo()
                                undoBanner = null
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "撤销",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        // Floating Translucent Capsule Bottom Dock + Circular Primary FAB
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier.padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DockPillButton(
                        label = "待办",
                        selected = currentTab == MainTab.TODOS,
                        onClick = { currentTab = MainTab.TODOS },
                    )
                    DockPillButton(
                        label = "统计",
                        selected = currentTab == MainTab.STATS,
                        onClick = { currentTab = MainTab.STATS },
                    )
                }
            }

            if (currentTab == MainTab.TODOS) {
                Spacer(Modifier.width(14.dp))
                Surface(
                    onClick = {
                        editingTodo = null
                        showEditorSheet = true
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shadowElevation = 8.dp,
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = "新建待办",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }

        // Secondary full-screen overlay pages with layered back-gesture support (Import -> Settings -> Home)
        if (overlayPage != OverlayPage.NONE) {
            BackHandler {
                overlayPage = if (overlayPage == OverlayPage.IMPORT) OverlayPage.SETTINGS else OverlayPage.NONE
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                when (overlayPage) {
                    OverlayPage.IMPORT -> ImportScreen(
                        existingTodoIds = todoData.todos.map { it.id }.toSet(),
                        defaultTimeZone = settings.defaultTimeZone,
                        onBack = { overlayPage = OverlayPage.SETTINGS },
                        onConfirmImport = { cats, todos ->
                            app.todoRepository.bulkImport(cats, todos)
                        },
                    )

                    OverlayPage.SETTINGS -> SettingsScreen(
                        settings = settings,
                        todoData = todoData,
                        hasSavedGithubToken = app.settingsRepository.hasGithubToken(),
                        isMonitorRunning = isMonitorRunning,
                        latestRelease = latestRelease,
                        onReleaseChecked = { latestRelease = it },
                        onUpdateSettings = { transform -> app.settingsRepository.update(transform) },
                        onToggleKeepAlive = { enabled ->
                            app.settingsRepository.update { it.copy(keepAliveServiceEnabled = enabled) }
                            if (enabled) PhoneTodoForegroundService.start(activity)
                            else PhoneTodoForegroundService.stop(activity)
                        },
                        onPreviewUnlockBanner = {
                            if (!XiaomiPermissionHelper.hasOverlayPermission(activity)) {
                                Toast.makeText(activity, "请先开启「显示悬浮窗」权限", Toast.LENGTH_SHORT).show()
                                XiaomiPermissionHelper.openPermissionPage(activity, "overlay")
                            } else {
                                app.unlockBannerController.onPhoneUnlocked(forcePreview = true)
                            }
                        },
                        onGithubConnectAndSync = { repo, token ->
                            app.githubSyncManager.connectAndSync(repo, token)
                        },
                        onGithubSyncNow = { app.githubSyncManager.syncNow() },
                        onGithubDisconnect = { app.githubSyncManager.disconnect() },
                        onOpenImport = { overlayPage = OverlayPage.IMPORT },
                        onBack = { overlayPage = OverlayPage.NONE },
                    )

                    OverlayPage.NONE -> Unit
                }
            }
        }

        if (showEditorSheet) {
            val defaultCat = when (selectedCategoryFilter) {
                null, "__inbox__" -> null
                else -> selectedCategoryFilter
            }
            TodoEditorSheet(
                editingTodo = editingTodo,
                categories = todoData.activeCategories(),
                defaultCategoryId = defaultCat,
                defaultTimeZone = settings.defaultTimeZone,
                onDismiss = { showEditorSheet = false },
                onSave = { text, catId, reminder, strongAlert, pinned, autoSaved ->
                    val target = editingTodo
                    if (target == null) {
                        app.todoRepository.addTodo(
                            text = text,
                            categoryId = catId,
                            reminder = reminder,
                            strongAlert = strongAlert,
                            pinned = pinned,
                        )
                    } else {
                        app.todoRepository.updateTodo(
                            target.copy(
                                text = text.trim(),
                                categoryId = catId,
                                reminder = reminder,
                                strongAlert = strongAlert,
                                pinned = pinned,
                            ),
                        )
                    }
                    showEditorSheet = false
                    if (autoSaved) {
                        Toast.makeText(activity, "已自动保存", Toast.LENGTH_SHORT).show()
                    }
                },
                onDelete = editingTodo?.let { target ->
                    {
                        val deleted = app.todoRepository.deleteTodo(target.id)
                        showEditorSheet = false
                        if (deleted != null) {
                            val label = deleted.text.take(14) + if (deleted.text.length > 14) "…" else ""
                            undoBanner = UndoBannerState(
                                id = System.nanoTime(),
                                message = "已删除「$label」",
                                onUndo = { app.todoRepository.restoreTodo(deleted) },
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun DockPillButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.surface,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
