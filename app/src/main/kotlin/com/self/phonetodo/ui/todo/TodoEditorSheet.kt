package com.self.phonetodo.ui.todo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.self.phonetodo.ui.components.WheelDateTimePicker
import java.time.LocalDate

/**
 * Single-screen bottom sheet for creating/editing a todo.
 * Timing reminder is enabled by default for new todos (zero switch friction), with an inline
 * dual-mode precision wheel ([Date | Hour:Min] + expandable [Year | Month | Day]).
 */
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
    val defaultZdt = remember(defaultTimeZone) {
        ReminderCalculator.defaultNewReminderDateTime(defaultTimeZone)
    }

    var text by remember(editingTodo) { mutableStateOf(editingTodo?.text.orEmpty()) }
    var categoryId by remember(editingTodo, defaultCategoryId) {
        mutableStateOf(editingTodo?.categoryId ?: defaultCategoryId)
    }
    // New todos default straight to timed reminders without needing to toggle a switch
    var reminderEnabled by remember(editingTodo) {
        mutableStateOf(editingTodo == null || editingTodo.reminder != null)
    }

    val initialDate = remember(editingTodo) {
        editingTodo?.reminder?.let { ReminderCalculator.parseDate(it.date) } ?: defaultZdt.toLocalDate()
    }
    val initialTime = remember(editingTodo) {
        editingTodo?.reminder?.let { ReminderCalculator.parseTime(it.time) } ?: defaultZdt.toLocalTime()
    }

    var pickedDate by remember(editingTodo) { mutableStateOf(initialDate) }
    var pickedHour by remember(editingTodo) { mutableIntStateOf(initialTime.hour) }
    var pickedMinute by remember(editingTodo) { mutableIntStateOf(initialTime.minute) }
    var repeat by remember(editingTodo) { mutableStateOf(editingTodo?.reminder?.repeat ?: Repeat()) }

    var strongAlert by remember(editingTodo) { mutableStateOf(editingTodo?.strongAlert ?: false) }
    var pinned by remember(editingTodo) { mutableStateOf(editingTodo?.pinned ?: false) }

    fun applyQuickPreset(rem: Reminder) {
        reminderEnabled = true
        pickedDate = ReminderCalculator.parseDate(rem.date)
        val t = ReminderCalculator.parseTime(rem.time)
        pickedHour = t.hour
        pickedMinute = t.minute
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
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
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (editingTodo == null) "新建待办" else "编辑待办",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Content input
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("写下要做的事…") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                minLines = 2,
                maxLines = 4,
            )

            // Categories
            if (categories.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = categoryId == null,
                        onClick = { categoryId = null },
                        label = { Text("收集箱") },
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

            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            // Timing reminder header (default ON, zero switch; quiet text button to clear or re-enable)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (reminderEnabled) "定时提醒" else "未设定时提醒",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (reminderEnabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (reminderEnabled) {
                        val preview = ReminderCalculator.buildReminder(
                            pickedDate,
                            pickedHour,
                            pickedMinute,
                            editingTodo?.reminder?.timeZone ?: defaultTimeZone,
                            repeat,
                        )
                        Text(
                            text = ReminderCalculator.summary(preview),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                TextButton(
                    onClick = { reminderEnabled = !reminderEnabled },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (reminderEnabled) "不提醒" else "+ 设定时",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (reminderEnabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }

            // Inline dual-mode wheel + quick presets + repeat
            AnimatedVisibility(visible = reminderEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Quick one-tap presets that smoothly spin the wheels
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilterChip(
                            selected = false,
                            onClick = { applyQuickPreset(ReminderCalculator.quickReminderAfterMinutes(10, defaultTimeZone)) },
                            label = { Text("+10分") },
                        )
                        FilterChip(
                            selected = false,
                            onClick = { applyQuickPreset(ReminderCalculator.quickReminderAfterMinutes(60, defaultTimeZone)) },
                            label = { Text("+1小时") },
                        )
                        FilterChip(
                            selected = false,
                            onClick = { applyQuickPreset(ReminderCalculator.quickReminderTodayOrTomorrowAt(20, 0, defaultTimeZone)) },
                            label = { Text("今晚20:00") },
                        )
                        FilterChip(
                            selected = false,
                            onClick = {
                                applyQuickPreset(
                                    ReminderCalculator.quickReminderTodayOrTomorrowAt(9, 0, defaultTimeZone, forceTomorrow = true),
                                )
                            },
                            label = { Text("明早09:00") },
                        )
                    }

                    WheelDateTimePicker(
                        selectedDate = pickedDate,
                        selectedHour = pickedHour,
                        selectedMinute = pickedMinute,
                        onDateChanged = { pickedDate = it },
                        onHourChanged = { pickedHour = it },
                        onMinuteChanged = { pickedMinute = it },
                    )

                    // Compact inline repeat picker
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(
                            RepeatKind.NONE to "不重复",
                            RepeatKind.DAILY to "每天",
                            RepeatKind.WEEKLY to "每周",
                            RepeatKind.MONTHLY to "每月",
                            RepeatKind.YEARLY to "每年",
                        ).forEach { (kind, label) ->
                            FilterChip(
                                selected = repeat.kind == kind,
                                onClick = { repeat = repeat.copy(kind = kind) },
                                label = { Text(label) },
                            )
                        }
                    }

                    if (repeat.kind == RepeatKind.WEEKLY) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 0 to "日")
                                .forEach { (dayInt, dayLabel) ->
                                    val selected = dayInt in repeat.weekdays
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            val nextDays = if (selected) repeat.weekdays - dayInt else repeat.weekdays + dayInt
                                            repeat = repeat.copy(weekdays = nextDays)
                                        },
                                        label = { Text("周$dayLabel") },
                                    )
                                }
                        }
                    }
                }
            }

            // Compact inline options row (Strong alert & Pin)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = strongAlert,
                    onClick = { strongAlert = !strongAlert },
                    label = { Text("闹钟级强提醒") },
                )
                FilterChip(
                    selected = pinned,
                    onClick = { pinned = !pinned },
                    label = { Text("置顶") },
                )
            }

            Spacer(Modifier.height(2.dp))

            Button(
                onClick = {
                    val tz = editingTodo?.reminder?.timeZone ?: defaultTimeZone
                    val finalRem = if (reminderEnabled) {
                        ReminderCalculator.buildReminder(pickedDate, pickedHour, pickedMinute, tz, repeat)
                    } else {
                        null
                    }
                    onSave(text, categoryId, finalRem, strongAlert, pinned)
                },
                enabled = text.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(if (editingTodo == null) "添加待办" else "保存", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
