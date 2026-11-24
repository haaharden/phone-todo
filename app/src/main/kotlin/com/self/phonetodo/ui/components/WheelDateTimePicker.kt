package com.self.phonetodo.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs

/**
 * Tactile 3-column precision wheel picker (Date / Hour / Minute) rendered inline in a single sheet.
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
    val dates = remember(today) { List(90) { today.plusDays(it.toLong()) } }
    val dateLabels = remember(dates, today) { dates.map { formatDateWheelLabel(it, today) } }
    val hourLabels = remember { List(24) { "%02d".format(it) } }
    val minuteLabels = remember { List(60) { "%02d".format(it) } }

    val dateIndex = remember(selectedDate, dates) {
        dates.indexOf(selectedDate).coerceIn(0, dates.lastIndex)
    }

    val itemHeight = 40.dp
    val wheelHeight = itemHeight * 3

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .height(wheelHeight + 8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
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
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex.coerceIn(0, items.lastIndex))
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    // Sync programmatic updates (e.g., quick preset chips) smoothly
    LaunchedEffect(selectedIndex) {
        val target = selectedIndex.coerceIn(0, items.lastIndex)
        if (!listState.isScrollInProgress && listState.firstVisibleItemIndex != target) {
            listState.animateScrollToItem(target)
        }
    }

    // Report settled snapped item
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
                if (closest != null && closest in items.indices) {
                    onSelectedIndexChanged(closest)
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

private fun formatDateWheelLabel(date: LocalDate, today: LocalDate): String {
    val wd = when (date.dayOfWeek) {
        DayOfWeek.MONDAY -> "周一"
        DayOfWeek.TUESDAY -> "周二"
        DayOfWeek.WEDNESDAY -> "周三"
        DayOfWeek.THURSDAY -> "周四"
        DayOfWeek.FRIDAY -> "周五"
        DayOfWeek.SATURDAY -> "周六"
        DayOfWeek.SUNDAY -> "周日"
    }
    return when (date) {
        today -> "今天 $wd"
        today.plusDays(1) -> "明天 $wd"
        today.plusDays(2) -> "后天 $wd"
        else -> "${date.monthValue}月${date.dayOfMonth}日 $wd"
    }
}
