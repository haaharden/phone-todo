package com.self.phonetodo.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.self.phonetodo.model.AppUsageItem
import com.self.phonetodo.model.DailyTrendPoint
import com.self.phonetodo.model.HourlyUsage
import com.self.phonetodo.model.UsageOverview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class UsageRepository(private val context: Context) {

    private val db = UsageDatabase(context)

    private val _overview = MutableStateFlow(UsageOverview())
    val overview: StateFlow<UsageOverview> = _overview.asStateFlow()

    fun recordUnlock(now: Long = System.currentTimeMillis()) {
        db.startSession(now, countedAsUnlock = true)
        refresh(now)
    }

    fun recordScreenOff(now: Long = System.currentTimeMillis()) {
        db.endActiveSession(now)
        refresh(now)
    }

    fun ensureActiveSessionIfInteractive(isInteractive: Boolean, now: Long = System.currentTimeMillis()) {
        if (isInteractive) {
            val todaySessions = db.getSessionsForDate(db.dateKeyOf(now))
            if (todaySessions.none { it.endAt == null }) {
                db.startSession(now, countedAsUnlock = false)
            }
        }
        refresh(now)
    }

    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun refresh(now: Long = System.currentTimeMillis()): UsageOverview {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val yesterday = today.minusDays(1)
        val sevenDaysAgo = today.minusDays(6)

        val allRecent = db.getSessionsSince(sevenDaysAgo)
        val byDate = allRecent.groupBy { it.dateKey }

        val todaySessions = byDate[today.toString()].orEmpty()
        val yesterdaySessions = byDate[yesterday.toString()].orEmpty()

        val todayUnlocks = todaySessions.count { it.countedAsUnlock }
        val todayMillis = todaySessions.sumOf { it.durationMs(now) }
        val longestSession = todaySessions.maxOfOrNull { it.durationMs(now) } ?: 0L

        val yesterdayUnlocks = yesterdaySessions.count { it.countedAsUnlock }
        val yesterdayMillis = yesterdaySessions.sumOf { it.durationMs(now) }

        // Hourly breakdown for today
        val hourlyMs = LongArray(24)
        val hourlyUnlocks = IntArray(24)
        val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()

        for (s in todaySessions) {
            val startZdt = Instant.ofEpochMilli(s.startAt).atZone(zone)
            if (s.countedAsUnlock && startZdt.toLocalDate() == today) {
                hourlyUnlocks[startZdt.hour]++
            }
            val segEnd = (s.endAt ?: now).coerceAtLeast(s.startAt)
            var cur = s.startAt.coerceAtLeast(todayStartMs)
            while (cur < segEnd) {
                val h = Instant.ofEpochMilli(cur).atZone(zone).hour
                val hourEnd = today.atTime(h, 0).plusHours(1).atZone(zone).toInstant().toEpochMilli()
                val sliceEnd = minOf(segEnd, hourEnd)
                hourlyMs[h] += (sliceEnd - cur).coerceAtLeast(0L)
                cur = sliceEnd
            }
        }

        val hourly = (0..23).map { h ->
            HourlyUsage(
                hour = h,
                minutes = hourlyMs[h] / 60_000f,
                unlocks = hourlyUnlocks[h],
            )
        }

        val trend = (0..6).map { offset ->
            val d = sevenDaysAgo.plusDays(offset.toLong())
            val key = d.toString()
            val list = byDate[key].orEmpty()
            DailyTrendPoint(
                dateKey = key,
                label = "${d.monthValue}/${d.dayOfMonth}",
                unlocks = list.count { it.countedAsUnlock },
                screenMinutes = (list.sumOf { it.durationMs(now) } / 60_000L).toInt(),
            )
        }

        val hasPerm = hasUsageStatsPermission()
        val topApps = if (hasPerm) queryTodayAppUsage(todayStartMs, now) else emptyList()

        // If local service was just installed today, supplement screen time with UsageStats total when local is 0
        val effectiveTodayMillis = if (todayMillis == 0L && topApps.isNotEmpty()) {
            topApps.sumOf { it.foregroundMillis }
        } else {
            todayMillis
        }

        val result = UsageOverview(
            todayUnlocks = todayUnlocks,
            todayScreenMillis = effectiveTodayMillis,
            longestSessionMillis = longestSession,
            yesterdayUnlocks = yesterdayUnlocks,
            yesterdayScreenMillis = yesterdayMillis,
            hourlyBreakdown = hourly,
            sevenDayTrend = trend,
            topApps = topApps,
            hasUsageStatsPermission = hasPerm,
        )
        _overview.value = result
        return result
    }

    private fun queryTodayAppUsage(startMs: Long, endMs: Long): List<AppUsageItem> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyList()
        val durations = mutableMapOf<String, Long>()
        val activeStart = mutableMapOf<String, Long>()

        runCatching {
            val events = usm.queryEvents(startMs, endMs)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        activeStart[pkg] = event.timeStamp
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED,
                    -> {
                        val started = activeStart.remove(pkg)
                        if (started != null && event.timeStamp > started) {
                            durations[pkg] = (durations[pkg] ?: 0L) + (event.timeStamp - started)
                        }
                    }
                }
            }
            // Close currently foregrounded app up to now
            for ((pkg, started) in activeStart) {
                if (endMs > started) {
                    durations[pkg] = (durations[pkg] ?: 0L) + (endMs - started)
                }
            }
        }

        val ignoredPackages = setOf(
            "com.miui.home",
            "com.android.systemui",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher",
            "com.android.launcher3",
            "com.miui.securitycenter",
        )

        val pm = context.packageManager
        return durations.entries
            .filter { (pkg, ms) -> ms >= 30_000L && pkg !in ignoredPackages }
            .sortedByDescending { it.value }
            .take(15)
            .map { (pkg, ms) ->
                val label = runCatching {
                    val info = pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                    pm.getApplicationLabel(info).toString()
                }.getOrElse {
                    runCatching {
                        @Suppress("DEPRECATION")
                        val info = pm.getApplicationInfo(pkg, 0)
                        pm.getApplicationLabel(info).toString()
                    }.getOrElse { pkg.substringAfterLast('.') }
                }
                AppUsageItem(
                    packageName = pkg,
                    appName = label,
                    foregroundMillis = ms,
                )
            }
    }

    companion object {
        fun formatDurationShort(millis: Long): String {
            val totalMinutes = (millis / 60_000L).coerceAtLeast(0L)
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return when {
                hours > 0 -> "${hours}h ${minutes}m"
                totalMinutes > 0 -> "${minutes}m"
                millis >= 1_000L -> "${millis / 1_000L}s"
                else -> "0m"
            }
        }
    }
}
