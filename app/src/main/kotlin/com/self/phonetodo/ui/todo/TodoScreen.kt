package com.self.phonetodo.ui.todo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.self.phonetodo.data.UsageRepository
import com.self.phonetodo.model.AppSettings
import com.self.phonetodo.model.Category
import com.self.phonetodo.model.Todo
import com.self.phonetodo.model.TodoData
import com.self.phonetodo.model.UsageOverview
import com.self.phonetodo.reminder.ReminderCalculator
import kotlinx.coroutines.launch

@Composable
fun TodoScreen(
    todoData: TodoData,
    usageOverview: UsageOverview,
    settings: AppSettings,
    hasOverlayPermission: Boolean,
    isMonitorRunning: Boolean,
    isTopScreen: Boolean,
    selectedCategoryFilter: String?,
    onSelectCategoryFilter: (String?) -> Unit,
    onToggleTodo: (String) -> Unit,
    onEditTodo: (Todo) -> Unit,
    onAddCategory: (String) -> Unit,
    onToggleShowDone: () -> Unit,
    onOpenStats: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRestartMonitor: () -> Unit,
) {
    var showNewCatDialog by remember { mutableStateOf(false) }
    var newCatName by remember { mutableStateOf("") }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val scope = rememberCoroutineScope()

    BackHandler(enabled = isTopScreen && !showNewCatDialog && pagerState.currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    val activeCategories = remember(todoData) { todoData.activeCategories() }
    val categoryNameMap = remember(activeCategories) { activeCategories.associate { it.id to it.name } }
    val activeTodos = remember(todoData) { todoData.activeTodos() }
    val allPending = remember(activeTodos) { ReminderCalculator.sortPendingTodos(activeTodos.filter { !it.done }) }
    val pastCarryover = remember(allPending) { allPending.filter { ReminderCalculator.isPastCarryover(it) } }
    val todayOnly = remember(allPending) { allPending.filter { ReminderCalculator.isTodayOnlyTodo(it) } }
    val todayTotalCount = pastCarryover.size + todayOnly.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Watch-Dial Telemetry Strip
        WatchDialTelemetryStrip(
            overview = usageOverview,
            settings = settings,
            pendingCount = allPending.size,
            onClick = onOpenStats,
        )

        // 2. Quiet actionable permission nudge only when enabled & needed
        val needOverlay = settings.keepAliveServiceEnabled && settings.unlockBannerEnabled && !hasOverlayPermission
        val needMonitor = settings.keepAliveServiceEnabled && !isMonitorRunning
        if (needOverlay || needMonitor) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.11f),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (needOverlay) "未开启悬浮窗，解锁顶部胶囊已降级为通知" else "常驻监听服务未运行",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { if (needOverlay) onRequestOverlayPermission() else onRestartMonitor() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = if (needOverlay) "去开启" else "启动",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        // 3. Swipeable Scope Switcher: 今日待办 <-> 全部待办
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScopeTabHeader(
                title = "今日待办",
                count = todayTotalCount,
                selected = pagerState.currentPage == 0,
                onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
            )
            ScopeTabHeader(
                title = "全部待办",
                count = allPending.size,
                selected = pagerState.currentPage == 1,
                onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
            )
        }

        // 4. Native HorizontalPager (swipe left/right smoothly between Today and All)
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            pageSpacing = 16.dp,
            verticalAlignment = Alignment.Top,
        ) { page ->
            if (page == 0) {
                TodayTodoPage(
                    pastCarryover = pastCarryover,
                    todayOnly = todayOnly,
                    allPendingCount = allPending.size,
                    categoryNameMap = categoryNameMap,
                    onToggleTodo = onToggleTodo,
                    onEditTodo = onEditTodo,
                    onSwitchToAll = { scope.launch { pagerState.animateScrollToPage(1) } },
                )
            } else {
                AllTodoPage(
                    activeTodos = activeTodos,
                    activeCategories = activeCategories,
                    categoryNameMap = categoryNameMap,
                    selectedCategoryFilter = selectedCategoryFilter,
                    showDone = settings.showDone,
                    onSelectCategoryFilter = onSelectCategoryFilter,
                    onOpenNewCategory = { showNewCatDialog = true },
                    onToggleTodo = onToggleTodo,
                    onEditTodo = onEditTodo,
                    onToggleShowDone = onToggleShowDone,
                )
            }
        }
    }

    if (showNewCatDialog) {
        AlertDialog(
            onDismissRequest = { showNewCatDialog = false },
            title = { Text("新建分类") },
            text = {
                OutlinedTextField(
                    value = newCatName,
                    onValueChange = { newCatName = it },
                    placeholder = { Text("输入分类名称") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newCatName.isNotBlank()) {
                            onAddCategory(newCatName.trim())
                            newCatName = ""
                            showNewCatDialog = false
                        }
                    },
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showNewCatDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TodayTodoPage(
    pastCarryover: List<Todo>,
    todayOnly: List<Todo>,
    allPendingCount: Int,
    categoryNameMap: Map<String, String>,
    onToggleTodo: (String) -> Unit,
    onEditTodo: (Todo) -> Unit,
    onSwitchToAll: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Zone 1: Past Carryover (items from before today that remain unchecked)
        if (pastCarryover.isNotEmpty()) {
            item(key = "past_carryover_zone") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = "往日遗留",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${pastCarryover.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.78f),
                        )
                    }

                    TodoPaperSheet(
                        todos = pastCarryover,
                        categoryNameMap = categoryNameMap,
                        showCategoryName = true,
                        emptyTitle = "",
                        emptySubtitle = "",
                        emptyActionLabel = null,
                        onEmptyAction = {},
                        onToggleTodo = onToggleTodo,
                        onEditTodo = onEditTodo,
                        isCarryoverZone = true,
                    )
                }
            }
        }

        // Zone 2: Today
        item(key = "today_zone") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pastCarryover.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = "今日",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${todayOnly.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }
                }

                TodoPaperSheet(
                    todos = todayOnly,
                    categoryNameMap = categoryNameMap,
                    showCategoryName = true,
                    emptyTitle = if (pastCarryover.isNotEmpty()) "今日暂无新增到期事项" else "今日待办已清空",
                    emptySubtitle = when {
                        pastCarryover.isNotEmpty() -> "先清掉上方的往日遗留，或轻触右下角加号记下今天的计划。"
                        allPendingCount > 0 -> "左滑可查看全部 $allPendingCount 项待办，或轻触右下角加号记录新事项。"
                        else -> "轻触右下角加号记下一件事，或点右上角从 Super Productivity 批量迁移。"
                    },
                    emptyActionLabel = if (todayOnly.isEmpty() && pastCarryover.isEmpty() && allPendingCount > 0) "查看全部待办" else null,
                    onEmptyAction = onSwitchToAll,
                    onToggleTodo = onToggleTodo,
                    onEditTodo = onEditTodo,
                )
            }
        }
    }
}

@Composable
private fun AllTodoPage(
    activeTodos: List<Todo>,
    activeCategories: List<Category>,
    categoryNameMap: Map<String, String>,
    selectedCategoryFilter: String?,
    showDone: Boolean,
    onSelectCategoryFilter: (String?) -> Unit,
    onOpenNewCategory: () -> Unit,
    onToggleTodo: (String) -> Unit,
    onEditTodo: (Todo) -> Unit,
    onToggleShowDone: () -> Unit,
) {
    val filtered = remember(activeTodos, selectedCategoryFilter) {
        when (selectedCategoryFilter) {
            null -> activeTodos
            "__inbox__" -> activeTodos.filter { it.categoryId == null }
            else -> activeTodos.filter { it.categoryId == selectedCategoryFilter }
        }
    }
    val pending = remember(filtered) { ReminderCalculator.sortPendingTodos(filtered.filter { !it.done }) }
    val done = remember(filtered) { filtered.filter { it.done }.sortedByDescending { it.doneAt ?: it.updatedAt } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Category Filter Row
        item(key = "cat_strip") {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    CategoryPill(
                        label = "全部",
                        selected = selectedCategoryFilter == null,
                        onClick = { onSelectCategoryFilter(null) },
                    )
                }
                item {
                    CategoryPill(
                        label = "收集箱",
                        selected = selectedCategoryFilter == "__inbox__",
                        onClick = { onSelectCategoryFilter("__inbox__") },
                    )
                }
                items(activeCategories, key = { it.id }) { cat ->
                    CategoryPill(
                        label = cat.name,
                        selected = selectedCategoryFilter == cat.id,
                        onClick = { onSelectCategoryFilter(cat.id) },
                    )
                }
                item {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onOpenNewCategory)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = "新建分类",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            "分类",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Pending sheet
        item(key = "all_sheet") {
            TodoPaperSheet(
                todos = pending,
                categoryNameMap = categoryNameMap,
                showCategoryName = selectedCategoryFilter == null,
                emptyTitle = "暂无未完成待办",
                emptySubtitle = "轻触右下角加号添加新待办。",
                emptyActionLabel = null,
                onEmptyAction = {},
                onToggleTodo = onToggleTodo,
                onEditTodo = onEditTodo,
            )
        }

        // Collapsible completed section
        if (done.isNotEmpty()) {
            item(key = "done_section") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onToggleShowDone)
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "已完成  ${done.size}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (showDone) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    if (showDone) {
                        TodoPaperSheet(
                            todos = done.take(60),
                            categoryNameMap = categoryNameMap,
                            showCategoryName = false,
                            emptyTitle = "",
                            emptySubtitle = "",
                            emptyActionLabel = null,
                            onEmptyAction = {},
                            onToggleTodo = onToggleTodo,
                            onEditTodo = onEditTodo,
                            dimSurface = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoPaperSheet(
    todos: List<Todo>,
    categoryNameMap: Map<String, String>,
    showCategoryName: Boolean,
    emptyTitle: String,
    emptySubtitle: String,
    emptyActionLabel: String?,
    onEmptyAction: () -> Unit,
    onToggleTodo: (String) -> Unit,
    onEditTodo: (Todo) -> Unit,
    dimSurface: Boolean = false,
    isCarryoverZone: Boolean = false,
) {
    val errorTint = MaterialTheme.colorScheme.error
    val containerColor = when {
        dimSurface -> MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
        else -> MaterialTheme.colorScheme.surface
    }
    val borderColor = if (isCarryoverZone) {
        errorTint.copy(alpha = 0.24f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        border = BorderStroke(0.5.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isCarryoverZone) {
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .drawWithContent {
                            // Subtle warm-rose paper wash + crisp 2.5dp left carmine rail
                            drawRect(color = errorTint.copy(alpha = 0.038f))
                            drawContent()
                            drawRect(
                                color = errorTint.copy(alpha = 0.78f),
                                topLeft = Offset.Zero,
                                size = Size(2.5.dp.toPx(), size.height),
                            )
                        }
                } else {
                    Modifier
                },
            ),
    ) {
        if (todos.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 34.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(emptyTitle, style = MaterialTheme.typography.titleMedium)
                Text(
                    emptySubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (emptyActionLabel != null) {
                    TextButton(
                        onClick = onEmptyAction,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(emptyActionLabel, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                todos.forEachIndexed { index, todo ->
                    TodoPaperRow(
                        todo = todo,
                        categoryName = if (showCategoryName) todo.categoryId?.let { categoryNameMap[it] } else null,
                        onToggle = { onToggleTodo(todo.id) },
                        onClick = { onEditTodo(todo) },
                    )
                    if (index < todos.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeTabHeader(
    title: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        horizontalAlignment = Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .width(if (selected) 24.dp else 0.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun CategoryPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WatchDialTelemetryStrip(
    overview: UsageOverview,
    settings: AppSettings,
    pendingCount: Int,
    onClick: () -> Unit,
) {
    val threshold = settings.dailyUnlockWarningThreshold.coerceAtLeast(1)
    val unlockRatio = (overview.todayUnlocks.toFloat() / threshold.toFloat()).coerceIn(0f, 1f)
    val warnUnlock = overview.todayUnlocks >= threshold
    val warnTime = (overview.todayScreenMillis / 60_000L) >= settings.dailyScreenMinutesWarningThreshold
    val accent = if (warnUnlock || warnTime) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${overview.todayUnlocks}",
                    style = MaterialTheme.typography.displayMedium,
                    color = if (warnUnlock) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(2.dp))
                Text("今日解锁", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Column(modifier = Modifier.weight(1.25f)) {
                Text(
                    text = UsageRepository.formatDurationShort(overview.todayScreenMillis),
                    style = MaterialTheme.typography.displayMedium,
                    color = if (warnTime) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(2.dp))
                Text("屏幕使用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Column(
                modifier = Modifier.weight(0.75f),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = "$pendingCount",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(2.dp))
                Text("待完成", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
        ) {
            if (unlockRatio > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(unlockRatio.coerceAtLeast(0.03f))
                        .height(2.dp)
                        .background(accent),
                )
            }
        }
    }
}

@Composable
private fun TodoPaperRow(
    todo: Todo,
    categoryName: String?,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val reminder = todo.reminder
    val isPast = !todo.done && ReminderCalculator.isPastCarryover(todo)
    val isFiredRed = !todo.done && (isPast || (reminder != null && ReminderCalculator.isFiredOrOverdue(reminder)))
    val pastUnscheduledText = if (isPast && reminder == null) ReminderCalculator.pastUnscheduledLabel(todo) else null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(22.dp)
                .clip(CircleShape)
                .then(
                    if (todo.done) {
                        Modifier.background(MaterialTheme.colorScheme.primary)
                    } else {
                        Modifier.border(
                            width = 1.6.dp,
                            color = when {
                                isFiredRed -> MaterialTheme.colorScheme.error
                                todo.pinned -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.outline
                            },
                            shape = CircleShape,
                        )
                    },
                )
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            if (todo.done) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "已完成",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (todo.pinned && !todo.done) {
                    Box(
                        modifier = Modifier
                            .padding(end = 7.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondary),
                    )
                }
                Text(
                    text = todo.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (todo.done) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (todo.done) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (reminder != null || pastUnscheduledText != null || !categoryName.isNullOrBlank()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (reminder != null) {
                        val tint = when {
                            todo.done -> MaterialTheme.colorScheme.onSurfaceVariant
                            isFiredRed -> MaterialTheme.colorScheme.error
                            todo.strongAlert -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.primary
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (todo.strongAlert && !todo.done) {
                                    Icons.Rounded.NotificationsActive
                                } else {
                                    Icons.Rounded.Alarm
                                },
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = ReminderCalculator.summary(reminder),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isFiredRed) FontWeight.SemiBold else FontWeight.Medium,
                                color = tint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else if (pastUnscheduledText != null) {
                        Text(
                            text = pastUnscheduledText,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                        )
                    }

                    if (!categoryName.isNullOrBlank()) {
                        Text(
                            text = categoryName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
