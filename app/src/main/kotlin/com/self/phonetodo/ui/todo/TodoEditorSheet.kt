package com.self.phonetodo.ui.todo

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.self.phonetodo.model.Category
import com.self.phonetodo.model.Reminder
import com.self.phonetodo.model.Repeat
import com.self.phonetodo.model.RepeatKind
import com.self.phonetodo.model.Todo
import com.self.phonetodo.reminder.ReminderCalculator
import com.self.phonetodo.ui.components.WheelDateTimePicker

/**
 * Single-screen bottom sheet for creating/editing a todo.
 * - Timing reminder enabled by default for new todos (zero switch friction).
 * - Tapping anywhere outside the text input (or scrolling/spinning wheels) hides the soft keyboard automatically.
 * - Dismissing outside / swiping down / pressing Back auto-saves non-blank changes (explicit "取消" discards).
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
        autoSaved: Boolean,
    ) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Populated inside ModalBottomSheet content so focus/keyboard calls target the Dialog's own window & FocusOwner.
    val dismissKeyboardRef = remember { mutableStateOf<() -> Unit>({}) }

    val defaultZdt = remember(defaultTimeZone) {
        ReminderCalculator.defaultNewReminderDateTime(defaultTimeZone)
    }

    val initialText = remember(editingTodo) { editingTodo?.text.orEmpty() }
    val initialCatId = remember(editingTodo, defaultCategoryId) { editingTodo?.categoryId ?: defaultCategoryId }
    val initialReminderEnabled = remember(editingTodo) { editingTodo == null || editingTodo.reminder != null }
    val initialDate = remember(editingTodo) {
        editingTodo?.reminder?.let { ReminderCalculator.parseDate(it.date) } ?: defaultZdt.toLocalDate()
    }
    val initialTime = remember(editingTodo) {
        editingTodo?.reminder?.let { ReminderCalculator.parseTime(it.time) } ?: defaultZdt.toLocalTime()
    }
    val initialRepeat = remember(editingTodo) { editingTodo?.reminder?.repeat ?: Repeat() }
    val initialStrongAlert = remember(editingTodo) { editingTodo?.strongAlert ?: false }
    val initialPinned = remember(editingTodo) { editingTodo?.pinned ?: false }

    var text by remember(editingTodo) { mutableStateOf(initialText) }
    var categoryId by remember(editingTodo, defaultCategoryId) { mutableStateOf(initialCatId) }
    var reminderEnabled by remember(editingTodo) { mutableStateOf(initialReminderEnabled) }
    var pickedDate by remember(editingTodo) { mutableStateOf(initialDate) }
    var pickedHour by remember(editingTodo) { mutableIntStateOf(initialTime.hour) }
    var pickedMinute by remember(editingTodo) { mutableIntStateOf(initialTime.minute) }
    var repeat by remember(editingTodo) { mutableStateOf(initialRepeat) }
    var strongAlert by remember(editingTodo) { mutableStateOf(initialStrongAlert) }
    var pinned by remember(editingTodo) { mutableStateOf(initialPinned) }
    var explicitDiscard by remember(editingTodo) { mutableStateOf(false) }

    val reminderFieldsChanged = reminderEnabled != initialReminderEnabled ||
        (
            reminderEnabled && (
                pickedDate != initialDate ||
                    pickedHour != initialTime.hour ||
                    pickedMinute != initialTime.minute ||
                    repeat != initialRepeat
                )
            )

    val hasChanges = text.trim() != initialText.trim() ||
        categoryId != initialCatId ||
        reminderFieldsChanged ||
        strongAlert != initialStrongAlert ||
        pinned != initialPinned

    fun buildFinalReminder(): Reminder? {
        if (!reminderEnabled) return null
        // Preserve existing reminder verbatim (including lastFiredAt) when user only edited title/category/flags
        if (editingTodo?.reminder != null && !reminderFieldsChanged) {
            return editingTodo.reminder
        }
        val tz = editingTodo?.reminder?.timeZone ?: defaultTimeZone
        return ReminderCalculator.buildReminder(pickedDate, pickedHour, pickedMinute, tz, repeat)
    }

    fun applyQuickPreset(rem: Reminder) {
        dismissKeyboardRef.value()
        reminderEnabled = true
        pickedDate = ReminderCalculator.parseDate(rem.date)
        val t = ReminderCalculator.parseTime(rem.time)
        pickedHour = t.hour
        pickedMinute = t.minute
    }

    ModalBottomSheet(
        onDismissRequest = {
            dismissKeyboardRef.value()
            val trimmed = text.trim()
            if (!explicitDiscard && trimmed.isNotEmpty() && (editingTodo == null || hasChanges)) {
                onSave(trimmed, categoryId, buildFinalReminder(), strongAlert, pinned, true)
            } else {
                onDismiss()
            }
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        // Bottom nav inset only when IME is closed; never let sheet stretch into the top status bar / erase the outside scrim
        contentWindowInsets = {
            WindowInsets.navigationBars.exclude(WindowInsets.ime).only(WindowInsetsSides.Bottom)
        },
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { dismissKeyboardRef.value() },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                BottomSheetDefaults.DragHandle()
            }
        },
    ) {
        // Read FocusManager / SoftwareKeyboardController / View INSIDE ModalBottomSheet so they belong to the Dialog window
        val sheetFocusManager = LocalFocusManager.current
        val sheetKeyboardController = LocalSoftwareKeyboardController.current
        val sheetView = LocalView.current

        fun hideKeyboard() {
            sheetFocusManager.clearFocus(force = true)
            sheetKeyboardController?.hide()
            val imm = sheetView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(sheetView.windowToken, 0)
        }
        SideEffect {
            dismissKeyboardRef.value = ::hideKeyboard
        }

        // Keep the sheet anchored below a constant, generous dimmed scrim band at the top even while the IME rises,
        // so the top edge stays calm (never shooting into the status bar) and the user can always tap outside to auto-save.
        val density = LocalDensity.current
        val fallbackScreenHeightDp = LocalConfiguration.current.screenHeightDp.dp
        val measuredWindowHeightDp = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
        val windowHeightDp = if (measuredWindowHeightDp > 0.dp) measuredWindowHeightDp else fallbackScreenHeightDp
        val statusBarTopDp = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
        val navBarBottomDp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
        val imeBottomDp = with(density) { WindowInsets.ime.getBottom(this).toDp() }
        val bottomInsetDp = maxOf(imeBottomDp, navBarBottomDp)
        val availableAboveBottomDp = (windowHeightDp - bottomInsetDp).coerceAtLeast(320.dp)
        val topScrimReserveDp = statusBarTopDp + 120.dp
        val maxContentHeightDp = (availableAboveBottomDp - topScrimReserveDp - 36.dp).coerceAtLeast(220.dp)

        var sheetRootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        var textFieldBoundsInWindow by remember { mutableStateOf<Rect?>(null) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxContentHeightDp)
                .onGloballyPositioned { coords -> sheetRootCoords = coords }
                .pointerInput(Unit) {
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        val rootCoords = sheetRootCoords
                        val tfBounds = textFieldBoundsInWindow
                        val tappedInsideInput = if (rootCoords != null && rootCoords.isAttached && tfBounds != null) {
                            tfBounds.contains(rootCoords.localToWindow(down.position))
                        } else {
                            false
                        }
                        if (tappedInsideInput) {
                            return@awaitEachGesture
                        }

                        var movedBeyondSlop = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (!movedBeyondSlop) {
                                    hideKeyboard()
                                }
                                break
                            }
                            if (!movedBeyondSlop && (change.position - down.position).getDistance() > touchSlop) {
                                movedBeyondSlop = true
                                hideKeyboard()
                            }
                        }
                    }
                }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
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
                TextButton(
                    onClick = {
                        explicitDiscard = true
                        hideKeyboard()
                        onDismiss()
                    },
                ) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onDelete != null) {
                    TextButton(
                        onClick = {
                            explicitDiscard = true
                            hideKeyboard()
                            onDelete()
                        },
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Content input
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("写下要做的事…") },
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        textFieldBoundsInWindow = coords.boundsInWindow()
                    },
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
                        onClick = {
                            hideKeyboard()
                            categoryId = null
                        },
                        label = { Text("收集箱") },
                    )
                    categories.forEach { cat ->
                        FilterChip(
                            selected = categoryId == cat.id,
                            onClick = {
                                hideKeyboard()
                                categoryId = cat.id
                            },
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
                    onClick = {
                        hideKeyboard()
                        reminderEnabled = !reminderEnabled
                    },
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
                        onInteract = ::hideKeyboard,
                    )

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
                                onClick = {
                                    hideKeyboard()
                                    repeat = repeat.copy(kind = kind)
                                },
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
                                            hideKeyboard()
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
                    onClick = {
                        hideKeyboard()
                        strongAlert = !strongAlert
                    },
                    label = { Text("闹钟级强提醒") },
                )
                FilterChip(
                    selected = pinned,
                    onClick = {
                        hideKeyboard()
                        pinned = !pinned
                    },
                    label = { Text("置顶") },
                )
            }

            Spacer(Modifier.height(2.dp))

            Button(
                onClick = {
                    hideKeyboard()
                    onSave(text.trim(), categoryId, buildFinalReminder(), strongAlert, pinned, false)
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
