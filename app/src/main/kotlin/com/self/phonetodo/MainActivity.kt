package com.self.phonetodo

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

private enum class MainTab { TODOS, STATS }
private enum class OverlayPage { NONE, IMPORT, SETTINGS }

class MainActivity : ComponentActivity() {

    private var hasOverlayPermState by mutableStateOf(false)

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

        handleOAuthIntent(intent)
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
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasOverlayPermState = XiaomiPermissionHelper.hasOverlayPermission(this)
        val app = application as PhoneTodoApp
        app.usageRepository.refresh()
        app.reminderScheduler.syncAll(fireMissed = true)
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
        handleOAuthIntent(intent)
    }

    private fun handleOAuthIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "com.self.phonetodo" && uri.host == "oauth2callback") {
            val app = application as PhoneTodoApp
            lifecycleScope.launch {
                val res = app.googleDriveSyncManager.handleOAuthRedirect(uri)
                res.onSuccess { email ->
                    Toast.makeText(this@MainActivity, "已连接 Google Drive：$email", Toast.LENGTH_SHORT).show()
                    app.googleDriveSyncManager.syncNow()
                }.onFailure { err ->
                    Toast.makeText(this@MainActivity, err.message ?: "Google 授权失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

@Composable
private fun PhoneTodoRoot(
    app: PhoneTodoApp,
    activity: MainActivity,
    hasOverlayPermission: Boolean,
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

    var showEditorSheet by remember { mutableStateOf(false) }
    var editingTodo by remember { mutableStateOf<Todo?>(null) }

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

    when (overlayPage) {
        OverlayPage.IMPORT -> {
            ImportScreen(
                existingTodoIds = todoData.todos.map { it.id }.toSet(),
                defaultTimeZone = settings.defaultTimeZone,
                onBack = { overlayPage = OverlayPage.NONE },
                onConfirmImport = { cats, todos ->
                    app.todoRepository.bulkImport(cats, todos)
                },
            )
            return
        }

        OverlayPage.SETTINGS -> {
            SettingsScreen(
                settings = settings,
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
                onGoogleSignIn = {
                    app.googleDriveSyncManager.startOAuthSignIn(activity).onFailure { err ->
                        Toast.makeText(activity, err.message ?: "启动 Google 登录失败", Toast.LENGTH_LONG).show()
                    }
                },
                onGoogleSyncNow = { app.googleDriveSyncManager.syncNow() },
                onGoogleSignOut = { app.googleDriveSyncManager.signOut() },
                onBack = { overlayPage = OverlayPage.NONE },
            )
            return
        }

        OverlayPage.NONE -> Unit
    }

    val todayFormatted = remember {
        val d = LocalDate.now()
        val wd = when (d.dayOfWeek) {
            DayOfWeek.MONDAY -> "星期一"
            DayOfWeek.TUESDAY -> "星期二"
            DayOfWeek.WEDNESDAY -> "星期三"
            DayOfWeek.THURSDAY -> "星期四"
            DayOfWeek.FRIDAY -> "星期五"
            DayOfWeek.SATURDAY -> "星期六"
            DayOfWeek.SUNDAY -> "星期日"
        }
        "${d.monthValue}月${d.dayOfMonth}日  $wd"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
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

                if (settings.googleSyncEnabled) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                val res = app.googleDriveSyncManager.syncNow()
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

                IconButton(onClick = { overlayPage = OverlayPage.IMPORT }) {
                    Icon(
                        Icons.Rounded.FileUpload,
                        contentDescription = "导入",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        hasOverlayPermission = hasOverlayPermission,
                        isMonitorRunning = isMonitorRunning,
                        selectedCategoryFilter = selectedCategoryFilter,
                        onSelectCategoryFilter = { selectedCategoryFilter = it },
                        onToggleTodo = { app.todoRepository.toggleTodoDone(it) },
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
            onSave = { text, catId, reminder, strongAlert, pinned ->
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
            },
            onDelete = editingTodo?.let { target ->
                {
                    app.todoRepository.deleteTodo(target.id)
                    showEditorSheet = false
                }
            },
        )
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
