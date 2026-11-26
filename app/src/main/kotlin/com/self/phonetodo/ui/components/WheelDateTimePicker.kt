package com.self.phonetodo.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Tactile dual-mode precision wheel picker rendered inline in a single sheet:
 * - Compact mode (default): [Date (90-day window) | Hour : Minute] for rapid near-term selection
 * - Expanded Y/M/D mode (tap the date header pill): [Year | Month | Day] for instant far-future jumps
 *
 * Uses SnapPosition.Start aligned with vertical itemHeight contentPadding so item offset == 0
 * maps pixel-for-pixel to the center focus band, and reports live per-column updates without
 * cross-column races.
 */
@Composable
fun WheelDateTimePicker(
    selectedDate: LocalDate,
    selectedHour: Int,
    selectedMinute: Int,
    onDateChanged: (LocalDate) -> Unit,
    onHourChanged: (Int) -> Unit,
    onMinuteChanged: (Int) -> Unit,
    onInteract: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val today = remember { LocalDate.now() }
    var expandYearMonthDay by remember { mutableStateOf(false) }

    // Anchor quick-mode 90-day window ONLY when entering quick mode or when selectedDate leaves current window
    // (never shift windowStart while the user is actively scrolling inside the current 90-day window!).
    var windowStart by remember(today) {
        mutableStateOf(computeQuickWindowStart(selectedDate, today))
    }
    LaunchedEffect(selectedDate, expandYearMonthDay) {
        if (!expandYearMonthDay) {
            val diffFromWindow = ChronoUnit.DAYS.between(windowStart, selectedDate)
            if (diffFromWindow < 0 || diffFromWindow >= 90) {
                windowStart = computeQuickWindowStart(selectedDate, today)
            }
        }
    }

    val dates = remember(windowStart) { List(90) { windowStart.plusDays(it.toLong()) } }
    val dateLabels = remember(dates, today) { dates.map { formatDateWheelLabel(it, today) } }
    val dateIndex = remember(selectedDate, dates) {
        dates.indexOf(selectedDate).coerceIn(0, dates.lastIndex)
    }

    val hourLabels = remember { List(24) { "%02d".format(it) } }
    val minuteLabels = remember { List(60) { "%02d".format(it) } }

    // Year / Month / Day wheel ranges
    val minYear = remember(today, selectedDate.year) { minOf(today.year, selectedDate.year) }
    val years = remember(minYear, today) { (minYear..(today.year + 10)).toList() }
    val yearLabels = remember(years) { years.map { "${it}年" } }
    val monthLabels = remember { (1..12).map { "${it}月" } }
    val daysInSelectedMonth = remember(selectedDate.year, selectedDate.monthValue) {
        YearMonth.of(selectedDate.year, selectedDate.monthValue).lengthOfMonth()
    }
    val dayLabels = remember(daysInSelectedMonth) { (1..daysInSelectedMonth).map { "${it}日" } }

    val itemHeight = 40.dp
    val wheelHeight = itemHeight * 3

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Precision mode switcher strip: tap date to expand/collapse Year-Month-Day wheels
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onInteract()
                        expandYearMonthDay = !expandYearMonthDay
                    }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatFullDateHeader(selectedDate),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (expandYearMonthDay) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (expandYearMonthDay) "调时间 %02d:%02d".format(selectedHour, selectedMinute) else "选年月日",
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = if (expandYearMonthDay) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        imageVector = if (expandYearMonthDay) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = if (expandYearMonthDay) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            AnimatedContent(
                targetState = expandYearMonthDay,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "wheel_mode_morph",
            ) { isYmdMode ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(wheelHeight + 8.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Precision center focus band
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(itemHeight)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                RoundedCornerShape(11.dp),
                            ),
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.align(Alignment.TopCenter),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        HorizontalDivider(
                            modifier = Modifier.align(Alignment.BottomCenter),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }

                    if (!isYmdMode) {
                        // Quick near-term wheel: [Date | Hour : Minute]
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            WheelColumn(
                                items = dateLabels,
                                selectedIndex = dateIndex,
                                onInteract = onInteract,
                                onSelectedIndexChanged = { idx ->
                                    val nextDate = dates.getOrNull(idx) ?: return@WheelColumn
                                    if (nextDate != selectedDate) {
                                        onDateChanged(nextDate)
                                    }
                                },
                                modifier = Modifier.weight(1.9f),
                            )

                            WheelColumn(
                                items = hourLabels,
                                selectedIndex = selectedHour.coerceIn(0, 23),
                                onInteract = onInteract,
                                onSelectedIndexChanged = { h ->
                                    if (h != selectedHour) {
                                        onHourChanged(h)
                                    }
                                },
                                circular = true,
                                modifier = Modifier.weight(0.85f),
                            )

                            Text(
                                text = ":",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )

                            WheelColumn(
                                items = minuteLabels,
                                selectedIndex = selectedMinute.coerceIn(0, 59),
                                onInteract = onInteract,
                                onSelectedIndexChanged = { m ->
                                    if (m != selectedMinute) {
                                        onMinuteChanged(m)
                                    }
                                },
                                circular = true,
                                modifier = Modifier.weight(0.85f),
                            )
                        }
                    } else {
                        // Expanded far-future wheel: [Year | Month | Day]
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            WheelColumn(
                                items = yearLabels,
                                selectedIndex = years.indexOf(selectedDate.year).coerceIn(0, years.lastIndex),
                                onInteract = onInteract,
                                onSelectedIndexChanged = { idx ->
                                    val y = years.getOrNull(idx) ?: return@WheelColumn
                                    val maxD = YearMonth.of(y, selectedDate.monthValue).lengthOfMonth()
                                    val next = LocalDate.of(y, selectedDate.monthValue, selectedDate.dayOfMonth.coerceAtMost(maxD))
                                    if (next != selectedDate) {
                                        onDateChanged(next)
                                    }
                                },
                                modifier = Modifier.weight(1.25f),
                            )

                            WheelColumn(
                                items = monthLabels,
                                selectedIndex = (selectedDate.monthValue - 1).coerceIn(0, 11),
                                onInteract = onInteract,
                                onSelectedIndexChanged = { idx ->
                                    val m = (idx + 1).coerceIn(1, 12)
                                    val maxD = YearMonth.of(selectedDate.year, m).lengthOfMonth()
                                    val next = LocalDate.of(selectedDate.year, m, selectedDate.dayOfMonth.coerceAtMost(maxD))
                                    if (next != selectedDate) {
                                        onDateChanged(next)
                                    }
                                },
                                modifier = Modifier.weight(0.95f),
                            )

                            WheelColumn(
                                items = dayLabels,
                                selectedIndex = (selectedDate.dayOfMonth - 1).coerceIn(0, dayLabels.lastIndex),
                                onInteract = onInteract,
                                onSelectedIndexChanged = { idx ->
                                    val d = (idx + 1).coerceIn(1, daysInSelectedMonth)
                                    val next = LocalDate.of(selectedDate.year, selectedDate.monthValue, d)
                                    if (next != selectedDate) {
                                        onDateChanged(next)
                                    }
                                },
                                modifier = Modifier.weight(0.95f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WheelColumn(
    items: List<String>,
    selectedIndex: Int,
    onInteract: () -> Unit,
    onSelectedIndexChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    circular: Boolean = false,
) {
    val itemHeight = 40.dp
    val isCircular = circular && items.size > 1
    val totalCount = if (isCircular) items.size * CIRCULAR_LOOP_REPEATS else items.size
    val safeInitialLogical = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val safeInitialVirtual = if (isCircular) {
        circularInitialIndex(safeInitialLogical, items.size)
    } else {
        safeInitialLogical
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = safeInitialVirtual)
    // SnapPosition.Start aligns the snapped item's top to offset == 0 (exact start of the center band after itemHeight top padding)
    val flingBehavior = rememberSnapFlingBehavior(
        lazyListState = listState,
        snapPosition = SnapPosition.Start,
    )
    val latestOnSelected by rememberUpdatedState(onSelectedIndexChanged)
    val latestOnInteract by rememberUpdatedState(onInteract)
    val latestSelectedIndex by rememberUpdatedState(selectedIndex)
    val latestItemCount by rememberUpdatedState(items.size)
    val latestIsCircular by rememberUpdatedState(isCircular)

    var isProgrammaticScroll by remember { mutableStateOf(false) }
    var lastReportedFromScroll by remember { mutableIntStateOf(safeInitialLogical) }

    // Dismiss keyboard the instant the user starts dragging a wheel
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling && !isProgrammaticScroll) {
                    latestOnInteract()
                }
            }
    }

    // Because vertical contentPadding == itemHeight and height == 3 * itemHeight,
    // the item sitting in the center focus band ALWAYS has item.offset == 0.
    val centeredVirtualIndex by remember {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            visible.minByOrNull { abs(it.offset) }?.index ?: listState.firstVisibleItemIndex
        }
    }

    // Live emission as the user drags or flings the wheel (never dropped, always accurate even on immediate Save tap)
    LaunchedEffect(listState) {
        snapshotFlow { centeredVirtualIndex }
            .distinctUntilChanged()
            .collect { virtualIdx ->
                val logicalIdx = virtualToLogicalIndex(virtualIdx, latestItemCount, latestIsCircular)
                if (!isProgrammaticScroll && logicalIdx in 0 until latestItemCount && logicalIdx != latestSelectedIndex) {
                    lastReportedFromScroll = logicalIdx
                    latestOnSelected(logicalIdx)
                }
            }
    }

    // Sync ONLY external programmatic updates (presets, tapping an adjacent row, or month length clamping),
    // never fighting an active user drag/fling.
    LaunchedEffect(selectedIndex, items.size, isCircular) {
        val targetLogical = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val isExternalUpdate = targetLogical != lastReportedFromScroll
        val currentCenteredLogical = virtualToLogicalIndex(centeredVirtualIndex, items.size, isCircular)
        if (isExternalUpdate && currentCenteredLogical != targetLogical) {
            val targetVirtual = if (isCircular) {
                nearestCircularTargetIndex(
                    currentVirtualIndex = centeredVirtualIndex,
                    targetLogicalIndex = targetLogical,
                    itemCount = items.size,
                    totalVirtualCount = totalCount,
                )
            } else {
                targetLogical
            }
            isProgrammaticScroll = true
            lastReportedFromScroll = targetLogical
            try {
                listState.animateScrollToItem(targetVirtual)
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    LazyColumn(
        state = listState,
        flingBehavior = flingBehavior,
        contentPadding = PaddingValues(vertical = itemHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.height(itemHeight * 3),
    ) {
        items(totalCount) { virtualIndex ->
            val logicalIndex = virtualToLogicalIndex(virtualIndex, items.size, isCircular)
            val isSelected = virtualIndex == centeredVirtualIndex
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        latestOnInteract()
                        if (logicalIndex != latestSelectedIndex) {
                            latestOnSelected(logicalIndex)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = items[logicalIndex],
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = if (isSelected) 16.sp else 14.sp,
                        fontFeatureSettings = "tnum",
                    ),
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.alpha(if (isSelected) 1f else 0.45f),
                )
            }
        }
    }
}

internal const val CIRCULAR_LOOP_REPEATS = 1000

internal fun circularInitialIndex(
    selectedIndex: Int,
    itemCount: Int,
    repeats: Int = CIRCULAR_LOOP_REPEATS,
): Int {
    if (itemCount <= 0) return 0
    val normalized = selectedIndex.mod(itemCount)
    return (repeats / 2) * itemCount + normalized
}

internal fun virtualToLogicalIndex(
    virtualIndex: Int,
    itemCount: Int,
    circular: Boolean,
): Int {
    if (itemCount <= 0) return 0
    return if (circular) {
        virtualIndex.mod(itemCount)
    } else {
        virtualIndex.coerceIn(0, itemCount - 1)
    }
}

internal fun nearestCircularTargetIndex(
    currentVirtualIndex: Int,
    targetLogicalIndex: Int,
    itemCount: Int,
    totalVirtualCount: Int,
    repeats: Int = CIRCULAR_LOOP_REPEATS,
): Int {
    if (itemCount <= 0 || totalVirtualCount <= 0) return 0
    val currentLogical = currentVirtualIndex.mod(itemCount)
    val normalizedTarget = targetLogicalIndex.mod(itemCount)
    var delta = normalizedTarget - currentLogical
    val half = itemCount / 2
    if (delta > half) delta -= itemCount
    if (delta < -half) delta += itemCount
    val candidate = currentVirtualIndex + delta
    return if (candidate in 0 until totalVirtualCount) {
        candidate
    } else {
        circularInitialIndex(normalizedTarget, itemCount, repeats)
    }
}

private fun computeQuickWindowStart(selectedDate: LocalDate, today: LocalDate): LocalDate {
    val diff = ChronoUnit.DAYS.between(today, selectedDate)
    return when {
        diff < 0 -> selectedDate
        diff <= 89 -> today
        else -> maxOf(today, selectedDate.minusDays(30))
    }
}

private fun weekdayCn(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}

private fun formatFullDateHeader(date: LocalDate): String =
    "${date.year}年${date.monthValue}月${date.dayOfMonth}日 ${weekdayCn(date.dayOfWeek)}"

private fun formatDateWheelLabel(date: LocalDate, today: LocalDate): String {
    val wd = weekdayCn(date.dayOfWeek)
    return when {
        date == today -> "今天 $wd"
        date == today.plusDays(1) -> "明天 $wd"
        date == today.plusDays(2) -> "后天 $wd"
        date.year != today.year -> "${date.year % 100}/${date.monthValue}/${date.dayOfMonth} $wd"
        else -> "${date.monthValue}月${date.dayOfMonth}日 $wd"
    }
}
