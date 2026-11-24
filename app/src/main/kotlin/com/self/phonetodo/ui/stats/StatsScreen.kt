package com.self.phonetodo.ui.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.self.phonetodo.data.UsageRepository
import com.self.phonetodo.model.AppSettings
import com.self.phonetodo.model.DailyTrendPoint
import com.self.phonetodo.model.HourlyUsage
import com.self.phonetodo.model.UsageOverview

@Composable
fun StatsScreen(
    overview: UsageOverview,
    settings: AppSettings,
    onRequestUsagePermission: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        // Precision Swiss Telemetry Row (hairline bounded, no chunky boxes)
        item {
            val unlockDelta = overview.todayUnlocks - overview.yesterdayUnlocks
            val deltaText = when {
                overview.yesterdayUnlocks == 0 -> "设定警戒 ${settings.dailyUnlockWarningThreshold} 次"
                unlockDelta > 0 -> "比昨日多 $unlockDelta 次"
                unlockDelta < 0 -> "比昨日少 ${-unlockDelta} 次"
                else -> "与昨日持平"
            }
            val warnUnlock = overview.todayUnlocks >= settings.dailyUnlockWarningThreshold
            val todayMin = (overview.todayScreenMillis / 60_000L).toInt()
            val warnTime = todayMin >= settings.dailyScreenMinutesWarningThreshold

            Column {
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${overview.todayUnlocks}",
                            style = MaterialTheme.typography.displayMedium,
                            color = if (warnUnlock) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "今日解锁次数",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = deltaText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = UsageRepository.formatDurationShort(overview.todayScreenMillis),
                            style = MaterialTheme.typography.displayMedium,
                            color = if (warnTime) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "今日亮屏总计",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "最长连续 ${UsageRepository.formatDurationShort(overview.longestSessionMillis)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        )
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        // 24h Distribution Sheet
        item {
            val peakHour = overview.hourlyBreakdown.maxByOrNull { it.minutes }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "24 小时用机节律",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (peakHour != null && peakHour.minutes >= 1f) {
                            Text(
                                text = "高峰 %02d:00".format(peakHour.hour),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    HourlyBarChart(hourly = overview.hourlyBreakdown)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        listOf("00", "06", "12", "18", "23").forEach { h ->
                            Text(
                                text = "$h:00",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // 7-day trend sheet
        if (overview.sevenDayTrend.isNotEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            text = "近 7 天解锁与时长",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        SevenDayTrendRow(points = overview.sevenDayTrend)
                    }
                }
            }
        }

        // Unified App Usage Sheet
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "今日应用使用排行",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 2.dp),
                )

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    when {
                        !overview.hasUsageStatsPermission -> {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = "需要「使用情况访问权限」",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "解锁次数与总时长已由本地服务精准记录；授权系统统计权限后可展示各 App 前台时长排行。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(
                                    onClick = onRequestUsagePermission,
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text("前往授权")
                                }
                            }
                        }

                        overview.topApps.isEmpty() -> {
                            Text(
                                text = "今日尚无超过 30 秒的应用使用记录",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(20.dp),
                            )
                        }

                        else -> {
                            val maxAppMs = overview.topApps.maxOf { it.foregroundMillis }.coerceAtLeast(1L)
                            Column(modifier = Modifier.fillMaxWidth()) {
                                overview.topApps.forEachIndexed { idx, app ->
                                    val frac = (app.foregroundMillis.toFloat() / maxAppMs.toFloat()).coerceIn(0.03f, 1f)
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 18.dp, vertical = 13.dp),
                                        verticalArrangement = Arrangement.spacedBy(7.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = app.appName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Text(
                                                text = UsageRepository.formatDurationShort(app.foregroundMillis),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(3.dp)
                                                .clip(RoundedCornerShape(1.5.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth(frac)
                                                    .height(3.dp)
                                                    .clip(RoundedCornerShape(1.5.dp))
                                                    .background(MaterialTheme.colorScheme.primary),
                                            )
                                        }
                                    }
                                    if (idx < overview.topApps.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 18.dp),
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
}

@Composable
private fun HourlyBarChart(hourly: List<HourlyUsage>) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val maxMin = (hourly.maxOfOrNull { it.minutes } ?: 1f).coerceAtLeast(10f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
    ) {
        val count = 24
        val gap = 4.dp.toPx()
        val barWidth = (size.width - gap * (count - 1)) / count
        val radius = CornerRadius(3.dp.toPx(), 3.dp.toPx())

        for (i in 0 until count) {
            val left = i * (barWidth + gap)
            drawRoundRect(
                color = track,
                topLeft = Offset(left, 0f),
                size = Size(barWidth, size.height),
                cornerRadius = radius,
            )
            val m = hourly.getOrNull(i)?.minutes ?: 0f
            if (m > 0f) {
                val h = ((m / maxMin) * size.height).coerceIn(5.dp.toPx(), size.height)
                drawRoundRect(
                    color = primary,
                    topLeft = Offset(left, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = radius,
                )
            }
        }
    }
}

@Composable
private fun SevenDayTrendRow(points: List<DailyTrendPoint>) {
    val maxMin = (points.maxOfOrNull { it.screenMinutes } ?: 1).coerceAtLeast(30)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        points.forEachIndexed { idx, p ->
            val isToday = idx == points.lastIndex
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = "${p.unlocks}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                )
                Box(
                    modifier = Modifier
                        .height(64.dp)
                        .width(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    val frac = (p.screenMinutes.toFloat() / maxMin.toFloat()).coerceIn(0f, 1f)
                    if (p.screenMinutes > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((64f * frac).coerceAtLeast(5f).dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (isToday) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                ),
                        )
                    }
                }
                Text(
                    text = p.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isToday) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
