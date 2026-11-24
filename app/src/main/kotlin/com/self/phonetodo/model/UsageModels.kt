package com.self.phonetodo.model

data class UsageSession(
    val id: Long = 0L,
    /** "YYYY-MM-DD" in local timezone */
    val dateKey: String,
    val startAt: Long,
    val endAt: Long?,
    /** True if started from an actual unlock (USER_PRESENT) */
    val countedAsUnlock: Boolean = true,
) {
    fun durationMs(now: Long = System.currentTimeMillis()): Long {
        val end = endAt ?: now
        return (end - startAt).coerceAtLeast(0L)
    }
}

data class HourlyUsage(
    val hour: Int, // 0..23
    val minutes: Float,
    val unlocks: Int,
)

data class DailyTrendPoint(
    val dateKey: String,
    val label: String, // e.g. "11/24"
    val unlocks: Int,
    val screenMinutes: Int,
)

data class AppUsageItem(
    val packageName: String,
    val appName: String,
    val foregroundMillis: Long,
)

data class UsageOverview(
    val todayUnlocks: Int = 0,
    val todayScreenMillis: Long = 0L,
    val longestSessionMillis: Long = 0L,
    val yesterdayUnlocks: Int = 0,
    val yesterdayScreenMillis: Long = 0L,
    val hourlyBreakdown: List<HourlyUsage> = List(24) { HourlyUsage(it, 0f, 0) },
    val sevenDayTrend: List<DailyTrendPoint> = emptyList(),
    val topApps: List<AppUsageItem> = emptyList(),
    val hasUsageStatsPermission: Boolean = false,
)
