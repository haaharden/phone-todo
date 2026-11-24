package com.self.phonetodo.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import kotlinx.coroutines.flow.filter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Tactile dual-mode precision wheel picker rendered inline in a single sheet:
 * - Compact mode (default): [Date (90-day window) | Hour : Minute] for rapid near-term selection
 * - Expanded Y/M/D mode (tap the date header pill): [Year | Month | Day] for instant far-future jumps
 */
@Composable
fun WheelDateTimePicker(
    selectedDate: LocalDate,
    selectedHour: Int,
    selectedMinute: Int,
    onDateTimeChanged: (LocalDate, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = remember { LocalDate.now() }
    var expandYearMonthDay by remember { mutableStateOf(false) }

    // Smart 90-day quick window: starts at today for near dates, or centers around far-future dates
    val windowStart = remember(selectedDate, today) {
        val diff = ChronoUnit.DAYS.between(today, selectedDate)
        if (diff in 0..89) today else maxOf(today, selectedDate.minusDays(30))
    }
    val dates = remember(windowStart) { List(90) { windowStart.plusDays(it.toLong()) } }
    val dateLabels = remember(dates, today) { dates.map { formatDateWheelLabel(it, today) } }
    val dateIndex = remember(selectedDate, dates) {
        dates.indexOf(selectedDate).coerceIn(0, dates.lastIndex)
    }

    val hourLabels = remember { List(24) { "%02d".format(it) } }
    val minuteLabels = remember { List(60) { "%02d".format(it) } }

    // Year / Month / Day wheel ranges (today.year .. today.year + 10)
    val minYear = remember(today) { minOf(today.year, selectedDate.year) }
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
                    .clickable { expandYearMonthDay = !expandYearMonthDay }
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
                                onSelectedIndexChanged = { idx ->
                                    val nextDate = dates.getOrNull(idx) ?: today
                                    if (nextDate != selectedDate) {
                                        onDateTimeChanged(nextDate, selectedHour, selectedMinute)
                                    }
                                },
                                modifier = Modifier.weight(1.9f),
                            )

                            WheelColumn(
                                items = hourLabels,
                                selectedIndex = selectedHour.coerceIn(0, 23),
                                onSelectedIndexChanged = { h ->
                                    if (h != selectedHour) {
                                        onDateTimeChanged(selectedDate, h, selectedMinute)
                                    }
                                },
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
                                onSelectedIndexChanged = { m ->
                                    if (m != selectedMinute) {
                                        onDateTimeChanged(selectedDate, selectedHour, m)
                                    }
                                },
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
                                onSelectedIndexChanged = { idx ->
                                    val y = years.getOrNull(idx) ?: selectedDate.year
                                    val maxD = YearMonth.of(y, selectedDate.monthValue).lengthOfMonth()
                                    val next = LocalDate.of(y, selectedDate.monthValue, selectedDate.dayOfMonth.coerceAtMost(maxD))
                                    if (next != selectedDate) {
                                        onDateTimeChanged(next, selectedHour, selectedMinute)
                                    }
                                },
                                modifier = Modifier.weight(1.25f),
                            )

                            WheelColumn(
                                items = monthLabels,
                                selectedIndex = (selectedDate.monthValue - 1).coerceIn(0, 11),
                                onSelectedIndexChanged = { idx ->
                                    val m = (idx + 1).coerceIn(1, 12)
                                    val maxD = YearMonth.of(selectedDate.year, m).lengthOfMonth()
                                    val next = LocalDate.of(selectedDate.year, m, selectedDate.dayOfMonth.coerceAtMost(maxD))
                                    if (next != selectedDate) {
                                        onDateTimeChanged(next, selectedHour, selectedMinute)
                                    }
                                },
                                modifier = Modifier.weight(0.95f),
                            )

                            WheelColumn(
                                items = dayLabels,
                                selectedIndex = (selectedDate.dayOfMonth - 1).coerceIn(0, dayLabels.lastIndex),
                                onSelectedIndexChanged = { idx ->
                                    val d = (idx + 1).coerceIn(1, daysInSelectedMonth)
                                    val next = LocalDate.of(selectedDate.year, selectedDate.monthValue, d)
                                    if (next != selectedDate) {
                                        onDateTimeChanged(next, selectedHour, selectedMinute)
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
    onSelectedIndexChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val itemHeight = 40.dp
    val safeInitial = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = safeInitial)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val latestOnSelected by rememberUpdatedState(onSelectedIndexChanged)
    val latestItemCount by rememberUpdatedState(items.size)

    // Sync programmatic updates (e.g., quick preset chips, month length change, mode switch) smoothly
    LaunchedEffect(selectedIndex, items.size) {
        val target = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (!listState.isScrollInProgress && listState.firstVisibleItemIndex != target) {
            listState.animateScrollToItem(target)
        }
    }

    // Report settled snapped item without stale closure captures
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .distinctUntilChanged()
            .collect {
                val layoutInfo = listState.layoutInfo
                val centerY = layoutInfo.viewportStartOffset + (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset) / 2
                val closest = layoutInfo.visibleItemsInfo.minByOrNull { item ->
                    abs((item.offset + item.size / 2) - centerY)
                }?.index
                if (closest != null && closest in 0 until latestItemCount) {
                    latestOnSelected(closest)
                }
            }
    }

    val centeredIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val centerY = info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs((it.offset + it.size / 2) - centerY) }?.index
                ?: listState.firstVisibleItemIndex
        }
    }

    LazyColumn(
        state = listState,
        flingBehavior = flingBehavior,
        contentPadding = PaddingValues(vertical = itemHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.height(itemHeight * 3),
    ) {
        items(items.size) { index ->
            val distance = abs(index - centeredIndex)
            val isSelected = distance == 0
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = items[index],
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
