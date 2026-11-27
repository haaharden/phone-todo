package com.self.phonetodo.ui.todo

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.self.phonetodo.model.Category
import com.self.phonetodo.model.Reminder
import com.self.phonetodo.model.Repeat
import com.self.phonetodo.model.RepeatKind
import com.self.phonetodo.model.Todo
import com.self.phonetodo.reminder.ReminderCalculator
import com.self.phonetodo.ui.components.WheelDateTimePicker
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Single-screen in-window overlay bottom sheet for creating/editing a todo.
 *
 * Rendered directly inside MainActivity's edge-to-edge root Box (instead of Material3's
 * ModalBottomSheet Dialog which hardcodes outer imePadding() + SOFT_INPUT_ADJUST_RESIZE):
 * - Zero vertical jump or snap-back when the soft keyboard opens or closes (the text box
 *   sits near the top of the sheet, well above the keyboard, so the sheet stays rock-steady
 *   and the top dimmed scrim always remains wide open and tappable).
 * - FocusManager, SoftwareKeyboardController, and InputMethodManager all share MainActivity's
 *   window, so tapping or dragging anywhere outside the text input dismisses the keyboard instantly.
 * - Tapping outside, swiping down, or pressing Back auto-saves non-blank edits; "取消" discards.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    onFinishPermanently: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val rootView = LocalView.current

    fun hideKeyboard() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        val imm = rootView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(rootView.windowToken, 0)
    }

    val defaultZdt = remember(defaultTimeZone) {
        ReminderCalculator.defaultNewReminderDateTime(defaultTimeZone)
    }

    val initialText = remember(editingTodo) { editingTodo?.text.orEmpty() }
    val initialCatId = remember(editingTodo, defaultCategoryId) { editingTodo?.categoryId ?: defaultCategoryId }
    val initialReminderEnabled = remember(editingTodo) { editingTodo == null || editingTodo.reminder != null }
    val initialDate = remember(editingTodo) {
        editingTodo?.reminder?.let { rem ->
            Instant.ofEpochMilli(ReminderCalculator.displayEpochMs(rem))
                .atZone(ReminderCalculator.safeZone(rem.timeZone))
                .toLocalDate()
        } ?: defaultZdt.toLocalDate()
    }
    val initialTime = remember(editingTodo) {
        editingTodo?.reminder?.let { rem ->
            Instant.ofEpochMilli(ReminderCalculator.displayEpochMs(rem))
                .atZone(ReminderCalculator.safeZone(rem.timeZone))
                .toLocalTime()
        } ?: defaultZdt.toLocalTime()
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
    var customRepeatOpen by remember(editingTodo) {
        mutableStateOf(ReminderCalculator.isCustomRepeat(initialRepeat))
    }
    var strongAlert by remember(editingTodo) { mutableStateOf(initialStrongAlert) }
    var pinned by remember(editingTodo) { mutableStateOf(initialPinned) }

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
        hideKeyboard()
        reminderEnabled = true
        pickedDate = ReminderCalculator.parseDate(rem.date)
        val t = ReminderCalculator.parseTime(rem.time)
        pickedHour = t.hour
        pickedMinute = t.minute
    }

    // Smooth entry/exit and interactive downward drag
    val enterProgress = remember { Animatable(0f) }
    val dragOffsetPx = remember { Animatable(0f) }
    var sheetHeightPx by remember { mutableFloatStateOf(with(density) { 640.dp.toPx() }) }
    var isClosing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        enterProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 230, easing = FastOutSlowInEasing),
        )
    }

    fun closeWithAnimation(afterClose: () -> Unit) {
        if (isClosing) return
        isClosing = true
        hideKeyboard()
        scope.launch {
            enterProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
            )
            afterClose()
        }
    }

    fun dismissAutoSave() {
        val trimmed = text.trim()
        val shouldAutoSave = trimmed.isNotEmpty() && (editingTodo == null || hasChanges)
        val finalRem = if (shouldAutoSave) buildFinalReminder() else null
        closeWithAnimation {
            if (shouldAutoSave) {
                onSave(trimmed, categoryId, finalRem, strongAlert, pinned, true)
            } else {
                onDismiss()
            }
        }
    }

    // Back gesture closes keyboard first (handled natively by Android IME); next Back auto-saves & closes sheet
    BackHandler(enabled = !isClosing) {
        dismissAutoSave()
    }

    var sheetRootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var textFieldBoundsInWindow by remember { mutableStateOf<Rect?>(null) }
    val dismissThresholdPx = with(density) { 110.dp.toPx() }

    val handleDragState = rememberDraggableState { delta ->
        if (!isClosing) {
            scope.launch {
                dragOffsetPx.snapTo((dragOffsetPx.value + delta).coerceAtLeast(0f))
            }
        }
    }

    val scrimAlpha = (0.44f * enterProgress.value * (1f - (dragOffsetPx.value / sheetHeightPx.coerceAtLeast(1f)).coerceIn(0f, 1f)))
        .coerceIn(0f, 0.44f)

    Box(modifier = Modifier.fillMaxSize()) {
        // Full-screen dimmed backdrop scrim — always exposed at top and tappable to auto-save & close
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { dismissAutoSave() })
                },
        )

        // Bottom sheet container — strictly anchored at bottom with NO imePadding, so keyboard open/close = 0px jump
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .statusBarsPadding()
                .padding(top = 84.dp)
                .fillMaxWidth()
                .onSizeChanged { size ->
                    if (size.height > 0) {
                        sheetHeightPx = size.height.toFloat()
                    }
                }
                .graphicsLayer {
                    translationY = (1f - enterProgress.value) * sheetHeightPx + dragOffsetPx.value
                }
                .onGloballyPositioned { coords -> sheetRootCoords = coords }
                // Non-consuming Initial-pass observer: any tap or drag outside the text input dismisses the keyboard
                // without ever swallowing button/chip/wheel clicks.
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
                // Absorb unhandled taps on empty sheet whitespace so they never pass through to the scrim behind it
                .pointerInput(Unit) {
                    detectTapGestures { /* absorbed inside sheet */ }
                },
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 16.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Drag handle + swipe-down-to-dismiss header zone
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .draggable(
                            state = handleDragState,
                            orientation = Orientation.Vertical,
                            onDragStarted = { hideKeyboard() },
                            onDragStopped = { velocity ->
                                if (dragOffsetPx.value > dismissThresholdPx || velocity > 900f) {
                                    dismissAutoSave()
                                } else {
                                    scope.launch {
                                        dragOffsetPx.animateTo(
                                            targetValue = 0f,
                                            animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                                        )
                                    }
                                }
                            },
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)),
                        )
                    }

                    // Header row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 12.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (editingTodo == null) "新建待办" else "编辑待办",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { closeWithAnimation(onDismiss) },
                        ) {
                            Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (onDelete != null) {
                            TextButton(
                                onClick = { closeWithAnimation(onDelete) },
                            ) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                // Scrollable editor form
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .navigationBarsPadding()
                        .padding(top = 4.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
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
                                        selected = !customRepeatOpen && repeat.kind == kind,
                                        onClick = {
                                            hideKeyboard()
                                            customRepeatOpen = false
                                            repeat = Repeat(kind = kind, interval = 1, weekdays = emptyList())
                                        },
                                        label = { Text(label) },
                                    )
                                }
                                FilterChip(
                                    selected = customRepeatOpen,
                                    onClick = {
                                        hideKeyboard()
                                        customRepeatOpen = true
                                        val anchorDay = ReminderCalculator.toDeskWeekday(pickedDate.dayOfWeek)
                                        repeat = when {
                                            repeat.kind == RepeatKind.NONE -> Repeat(
                                                kind = RepeatKind.WEEKLY,
                                                interval = 1,
                                                weekdays = listOf(anchorDay),
                                            )
                                            repeat.kind == RepeatKind.WEEKLY && repeat.weekdays.isEmpty() -> repeat.copy(
                                                weekdays = listOf(anchorDay),
                                            )
                                            else -> repeat
                                        }
                                    },
                                    label = { Text("自定义") },
                                )
                            }

                            AnimatedVisibility(visible = customRepeatOpen) {
                                CustomRepeatPanel(
                                    repeat = repeat,
                                    anchorDate = "%04d-%02d-%02d".format(
                                        pickedDate.year,
                                        pickedDate.monthValue,
                                        pickedDate.dayOfMonth,
                                    ),
                                    anchorWeekday = ReminderCalculator.toDeskWeekday(pickedDate.dayOfWeek),
                                    onRepeatChanged = { next ->
                                        hideKeyboard()
                                        repeat = next
                                    },
                                )
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

                    val showEndRepeat = onFinishPermanently != null &&
                        editingTodo != null &&
                        !editingTodo.done &&
                        editingTodo.reminder?.repeat?.kind != null &&
                        editingTodo.reminder.repeat.kind != RepeatKind.NONE

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (showEndRepeat) {
                            OutlinedButton(
                                onClick = { closeWithAnimation { onFinishPermanently?.invoke() } },
                                enabled = !isClosing,
                                modifier = Modifier.height(50.dp),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text("结束重复")
                            }
                        }
                        Button(
                            onClick = {
                                val trimmed = text.trim()
                                val finalRem = buildFinalReminder()
                                closeWithAnimation {
                                    onSave(trimmed, categoryId, finalRem, strongAlert, pinned, false)
                                }
                            },
                            enabled = text.isNotBlank() && !isClosing,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(if (editingTodo == null) "添加待办" else "保存", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomRepeatPanel(
    repeat: Repeat,
    anchorDate: String,
    anchorWeekday: Int,
    onRepeatChanged: (Repeat) -> Unit,
) {
    val effectiveKind = if (repeat.kind == RepeatKind.NONE) RepeatKind.WEEKLY else repeat.kind
    val interval = repeat.interval.coerceIn(1, 99)
    val selectedDays = remember(repeat.weekdays, anchorWeekday, effectiveKind) {
        if (effectiveKind == RepeatKind.WEEKLY) {
            if (repeat.weekdays.isNotEmpty()) {
                ReminderCalculator.normalizeWeekdays(repeat.weekdays)
            } else {
                listOf(anchorWeekday)
            }
        } else {
            emptyList()
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Row 1: Interval stepper ("每 [ - ] N [ + ]") + Unit segmented selector ("天 | 周 | 月 | 年")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "每",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                        .padding(horizontal = 3.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = interval > 1) {
                                onRepeatChanged(
                                    Repeat(
                                        kind = effectiveKind,
                                        interval = (interval - 1).coerceAtLeast(1),
                                        weekdays = if (effectiveKind == RepeatKind.WEEKLY) selectedDays else emptyList(),
                                    ),
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Remove,
                            contentDescription = "减少间隔",
                            tint = if (interval > 1) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            },
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    Text(
                        text = interval.toString(),
                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 26.dp),
                    )

                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = interval < 99) {
                                onRepeatChanged(
                                    Repeat(
                                        kind = effectiveKind,
                                        interval = (interval + 1).coerceAtMost(99),
                                        weekdays = if (effectiveKind == RepeatKind.WEEKLY) selectedDays else emptyList(),
                                    ),
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "增加间隔",
                            tint = if (interval < 99) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            },
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(
                        RepeatKind.DAILY to "天",
                        RepeatKind.WEEKLY to "周",
                        RepeatKind.MONTHLY to "月",
                        RepeatKind.YEARLY to "年",
                    ).forEach { (unitKind, unitLabel) ->
                        val active = effectiveKind == unitKind
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                )
                                .clickable {
                                    val nextDays = if (unitKind == RepeatKind.WEEKLY) {
                                        if (repeat.weekdays.isNotEmpty()) {
                                            ReminderCalculator.normalizeWeekdays(repeat.weekdays)
                                        } else {
                                            listOf(anchorWeekday)
                                        }
                                    } else {
                                        emptyList()
                                    }
                                    onRepeatChanged(
                                        Repeat(
                                            kind = unitKind,
                                            interval = interval,
                                            weekdays = nextDays,
                                        ),
                                    )
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = unitLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }

            // Row 2 & 3 (Weekly): 7-day equal-width toggle row + quick weekday presets
            if (effectiveKind == RepeatKind.WEEKLY) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        1 to "一",
                        2 to "二",
                        3 to "三",
                        4 to "四",
                        5 to "五",
                        6 to "六",
                        0 to "日",
                    ).forEach { (dayInt, dayLabel) ->
                        val selected = dayInt in selectedDays
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                            },
                            border = if (selected) {
                                null
                            } else {
                                BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                            },
                            onClick = {
                                val nextDays = ReminderCalculator.toggleWeekday(selectedDays, dayInt)
                                onRepeatChanged(
                                    Repeat(
                                        kind = RepeatKind.WEEKLY,
                                        interval = interval,
                                        weekdays = nextDays,
                                    ),
                                )
                            },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = dayLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }

                // Quick presets for the most common custom weekday schedules
                val presets = listOf(
                    "工作日" to listOf(1, 2, 3, 4, 5),
                    "周一至周六" to listOf(1, 2, 3, 4, 5, 6),
                    "周末" to listOf(6, 0),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    presets.forEach { (label, days) ->
                        val active = selectedDays == days
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (active) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                            },
                            border = if (active) {
                                null
                            } else {
                                BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                            },
                            onClick = {
                                onRepeatChanged(
                                    Repeat(
                                        kind = RepeatKind.WEEKLY,
                                        interval = interval,
                                        weekdays = days,
                                    ),
                                )
                            },
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }

            // Live concise rule caption
            val effectiveRepeat = Repeat(
                kind = effectiveKind,
                interval = interval,
                weekdays = if (effectiveKind == RepeatKind.WEEKLY) selectedDays else emptyList(),
            )
            Text(
                text = "重复规则：${ReminderCalculator.describeRepeat(effectiveRepeat, anchorDate)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
