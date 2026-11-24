package com.self.phonetodo.ui.todo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.self.phonetodo.model.Category
import com.self.phonetodo.model.Reminder
import com.self.phonetodo.model.Repeat
import com.self.phonetodo.model.RepeatKind
import com.self.phonetodo.model.Todo
import com.self.phonetodo.reminder.ReminderCalculator
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TodoEditorSheet(
    editingTodo: Todo?,
    categories: List<Category>,
    defaultCategoryId: String?,
    defaultTimeZone: String,
    onDismiss: () -> Unit,
    onSave: (
        text: String,
        categoryId: String?,
        reminder: Reminder?,
        strongAlert: Boolean,
        pinned: Boolean,
    ) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember(editingTodo) { mutableStateOf(editingTodo?.text.orEmpty()) }
    var categoryId by remember(editingTodo, defaultCategoryId) {
        mutableStateOf(editingTodo?.categoryId ?: defaultCategoryId)
    }
    var reminder by remember(editingTodo) { mutableStateOf(editingTodo?.reminder) }
    var strongAlert by remember(editingTodo) { mutableStateOf(editingTodo?.strongAlert ?: false) }
    var pinned by remember(editingTodo) { mutableStateOf(editingTodo?.pinned ?: false) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (editingTodo == null) "新建待办" else "编辑待办",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("准备做什么？") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                minLines = 2,
                maxLines = 4,
            )

            // Category picker
            if (categories.isNotEmpty()) {
                Text(
                    text = "分类",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = categoryId == null,
                        onClick = { categoryId = null },
                        label = { Text("未分类") },
                    )
                    categories.forEach { cat ->
                        FilterChip(
                            selected = categoryId == cat.id,
                            onClick = { categoryId = cat.id },
                            label = { Text(cat.name) },
                        )
                    }
                }
            }

            // Quick reminders
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Alarm,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "提醒时间",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (reminder != null) {
                    IconButton(onClick = { reminder = null }) {
                        Icon(Icons.Rounded.Close, contentDescription = "清除提醒")
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = false,
                    onClick = { reminder = ReminderCalculator.quickReminderAfterMinutes(10, defaultTimeZone) },
                    label = { Text("10分钟后") },
                )
                FilterChip(
                    selected = false,
                    onClick = { reminder = ReminderCalculator.quickReminderAfterMinutes(60, defaultTimeZone) },
                    label = { Text("1小时后") },
                )
                FilterChip(
                    selected = false,
                    onClick = { reminder = ReminderCalculator.quickReminderTodayOrTomorrowAt(20, 0, defaultTimeZone) },
                    label = { Text("今晚 20:00") },
                )
                FilterChip(
                    selected = false,
                    onClick = {
                        reminder = ReminderCalculator.quickReminderTodayOrTomorrowAt(
                            9,
                            0,
                            defaultTimeZone,
                            forceTomorrow = true,
                        )
                    },
                    label = { Text("明早 09:00") },
                )
                FilterChip(
                    selected = false,
                    onClick = {
                        if (reminder == null) {
                            reminder = ReminderCalculator.quickReminderAfterMinutes(30, defaultTimeZone)
                        }
                        showDatePicker = true
                    },
                    leadingIcon = { Icon(Icons.Rounded.CalendarToday, contentDescription = null) },
                    label = { Text("自定义日期/时间") },
                )
            }

            reminder?.let { rem ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { showDatePicker = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(rem.date)
                        }
                        OutlinedButton(
                            onClick = { showTimePicker = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(rem.time)
                        }
                    }

                    // Repeat options
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Repeat, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text("重复", style = MaterialTheme.typography.labelLarge)
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val options = listOf(
                            RepeatKind.NONE to "不重复",
                            RepeatKind.DAILY to "每天",
                            RepeatKind.WEEKLY to "每周",
                            RepeatKind.MONTHLY to "每月",
                            RepeatKind.YEARLY to "每年",
                        )
                        options.forEach { (kind, label) ->
                            FilterChip(
                                selected = rem.repeat.kind == kind,
                                onClick = {
                                    val updated = rem.copy(repeat = rem.repeat.copy(kind = kind))
                                    val next = ReminderCalculator.nextOccurrence(updated, System.currentTimeMillis() - 1)
                                    reminder = updated.copy(nextAt = next)
                                },
                                label = { Text(label) },
                            )
                        }
                    }

                    if (rem.repeat.kind == RepeatKind.WEEKLY) {
                        val weekdays = listOf(1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 0 to "日")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            weekdays.forEach { (dayInt, dayLabel) ->
                                val selected = dayInt in rem.repeat.weekdays
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        val nextDays = if (selected) {
                                            rem.repeat.weekdays - dayInt
                                        } else {
                                            rem.repeat.weekdays + dayInt
                                        }
                                        val updated = rem.copy(repeat = rem.repeat.copy(weekdays = nextDays))
                                        val next = ReminderCalculator.nextOccurrence(updated, System.currentTimeMillis() - 1)
                                        reminder = updated.copy(nextAt = next)
                                    },
                                    label = { Text("周$dayLabel") },
                                )
                            }
                        }
                    }

                    Text(
                        text = "下次触发：${ReminderCalculator.summary(rem)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("闹钟级强提醒", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        "到点时锁屏亮屏弹窗并响铃，绝不错过",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = strongAlert, onCheckedChange = { strongAlert = it })
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("置顶到解锁顶部横幅", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        "每次解锁手机时优先展示在顶部横幅",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = pinned, onCheckedChange = { pinned = it })
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = {
                    val finalRem = reminder?.let { rem ->
                        val next = ReminderCalculator.nextOccurrence(rem, System.currentTimeMillis() - 1)
                        rem.copy(nextAt = next)
                    }
                    onSave(text, categoryId, finalRem, strongAlert, pinned)
                },
                enabled = text.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(if (editingTodo == null) "添加待办" else "保存修改", fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            val d = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            val dateStr = "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
                            val base = reminder ?: ReminderCalculator.quickReminderAfterMinutes(30, defaultTimeZone)
                            val updated = base.copy(date = dateStr)
                            val next = ReminderCalculator.nextOccurrence(updated, System.currentTimeMillis() - 1)
                            reminder = updated.copy(nextAt = next)
                        }
                        showDatePicker = false
                        showTimePicker = true
                    },
                ) { Text("下一步：选时间") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (showTimePicker) {
        val currentParts = (reminder?.time ?: "09:00").split(":")
        val tpState = rememberTimePickerState(
            initialHour = currentParts.getOrNull(0)?.toIntOrNull() ?: 9,
            initialMinute = currentParts.getOrNull(1)?.toIntOrNull() ?: 0,
            is24Hour = true,
        )
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val timeStr = "%02d:%02d".format(tpState.hour, tpState.minute)
                        val base = reminder ?: ReminderCalculator.quickReminderAfterMinutes(30, defaultTimeZone)
                        val updated = base.copy(time = timeStr)
                        val next = ReminderCalculator.nextOccurrence(updated, System.currentTimeMillis() - 1)
                        reminder = updated.copy(nextAt = next)
                        showTimePicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("取消") }
            },
            title = { Text("选择提醒时间") },
            text = { TimePicker(state = tpState) },
        )
    }
}
