package com.self.phonetodo.ui.todo

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.self.phonetodo.data.UsageRepository
import com.self.phonetodo.model.AppSettings
import com.self.phonetodo.model.Todo
import com.self.phonetodo.model.TodoData
import com.self.phonetodo.model.UsageOverview
import com.self.phonetodo.reminder.ReminderCalculator

@Composable
fun TodoScreen(
    todoData: TodoData,
    usageOverview: UsageOverview,
    settings: AppSettings,
    hasOverlayPermission: Boolean,
    isMonitorRunning: Boolean,
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

    val activeCategories = remember(todoData) { todoData.activeCategories() }
    val categoryNameMap = remember(activeCategories) { activeCategories.associate { it.id to it.name } }
    val activeTodos = remember(todoData) { todoData.activeTodos() }
    val allPendingCount = remember(activeTodos) { activeTodos.count { !it.done } }

    val filteredTodos = remember(activeTodos, selectedCategoryFilter) {
        when (selectedCategoryFilter) {
            null -> activeTodos
            "__inbox__" -> activeTodos.filter { it.categoryId == null }
            else -> activeTodos.filter { it.categoryId == selectedCategoryFilter }
        }
    }

    val pendingTodos = remember(filteredTodos) {
        filteredTodos.filter { !it.done }.sortedWith(
            compareByDescending<Todo> { it.pinned }
                .thenBy { it.reminder?.nextAt ?: Long.MAX_VALUE }
                .thenByDescending { it.createdAt },
        )
    }
    val doneTodos = remember(filteredTodos) {
        filteredTodos.filter { it.done }.sortedByDescending { it.doneAt ?: it.updatedAt }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Signature Watch-Dial Telemetry Strip (no bulky SaaS card box)
        item(key = "telemetry_dial") {
            WatchDialTelemetryStrip(
                overview = usageOverview,
                settings = settings,
                pendingCount = allPendingCount,
                onClick = onOpenStats,
            )
        }

        // Quiet actionable permission nudge only when needed
        if (!hasOverlayPermission || !isMonitorRunning) {
            item(key = "setup_nudge") {
                val needOverlay = !hasOverlayPermission
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.11f),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (needOverlay) {
                                "尚未开启悬浮窗，解锁时无法弹出顶部横幅"
                            } else {
                                "常驻监听服务未运行，解锁计数已暂停"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { if (needOverlay) onRequestOverlayPermission() else onRestartMonitor() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
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
        }

        // Quiet minimalist category strip (no heavy outlined chips)
        item(key = "category_strip") {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    CategoryTabItem(
                        label = "全部",
                        count = allPendingCount,
                        selected = selectedCategoryFilter == null,
                        onClick = { onSelectCategoryFilter(null) },
                    )
                }
                item {
                    val inboxCount = activeTodos.count { !it.done && it.categoryId == null }
                    CategoryTabItem(
                        label = "收集箱",
                        count = inboxCount,
                        selected = selectedCategoryFilter == "__inbox__",
                        onClick = { onSelectCategoryFilter("__inbox__") },
                    )
                }
                items(activeCategories, key = { it.id }) { cat ->
                    val count = activeTodos.count { !it.done && it.categoryId == cat.id }
                    CategoryTabItem(
                        label = cat.name,
                        count = count,
                        selected = selectedCategoryFilter == cat.id,
                        onClick = { onSelectCategoryFilter(cat.id) },
                    )
                }
                item {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showNewCatDialog = true }
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = "新建分类",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = "分类",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Unified Porcelain Paper Sheet for pending tasks
        item(key = "pending_sheet") {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (pendingTodos.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 38.dp),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "眼下没有待办事项",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "轻触右下角加号记下一件事，或点右上角从 Super Productivity 批量迁移。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        pendingTodos.forEachIndexed { index, todo ->
                            TodoPaperRow(
                                todo = todo,
                                categoryName = if (selectedCategoryFilter == null) {
                                    todo.categoryId?.let { categoryNameMap[it] }
                                } else {
                                    null
                                },
                                onToggle = { onToggleTodo(todo.id) },
                                onClick = { onEditTodo(todo) },
                            )
                            if (index < pendingTodos.lastIndex) {
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

        // Quiet collapsible completed sheet
        if (doneTodos.isNotEmpty()) {
            item(key = "done_sheet") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onToggleShowDone() }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "已完成  ${doneTodos.size}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (settings.showDone) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    if (settings.showDone) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                val shownDone = doneTodos.take(60)
                                shownDone.forEachIndexed { index, todo ->
                                    TodoPaperRow(
                                        todo = todo,
                                        categoryName = null,
                                        onToggle = { onToggleTodo(todo.id) },
                                        onClick = { onEditTodo(todo) },
                                    )
                                    if (index < shownDone.lastIndex) {
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
                .padding(vertical = 16.dp),
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
                Text(
                    text = "今日解锁",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(modifier = Modifier.weight(1.25f)) {
                Text(
                    text = UsageRepository.formatDurationShort(overview.todayScreenMillis),
                    style = MaterialTheme.typography.displayMedium,
                    color = if (warnTime) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "屏幕使用",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                Text(
                    text = "待完成",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Razor-thin 2dp dial progress hairline (replaces heavy cards)
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
private fun CategoryTabItem(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (count > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .width(if (selected) 18.dp else 0.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun TodoPaperRow(
    todo: Todo,
    categoryName: String?,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Tactile precision checkbox ring
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
                            color = if (todo.pinned) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
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

            if (todo.reminder != null || !categoryName.isNullOrBlank()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    todo.reminder?.let { rem ->
                        val isStrong = todo.strongAlert && !todo.done
                        val tint = if (isStrong) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isStrong) Icons.Rounded.NotificationsActive else Icons.Rounded.Alarm,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = ReminderCalculator.summary(rem),
                                style = MaterialTheme.typography.labelMedium,
                                color = tint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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
