package com.self.phonetodo.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
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

    private data class SystemDayStats(
        val unlocks: Int = 0,
        val screenMillis: Long = 0L,
        val longestSessionMillis: Long = 0L,
        val hourlyMs: LongArray = LongArray(24),
        val hourlyUnlocks: IntArray = IntArray(24),
        val topApps: List<AppUsageItem> = emptyList(),
    )

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

    @Suppress("DEPRECATION")
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

        val localUnlocks = todaySessions.count { it.countedAsUnlock }
        val localMillis = todaySessions.sumOf { it.durationMs(now) }
        val localLongest = todaySessions.maxOfOrNull { it.durationMs(now) } ?: 0L

        val yesterdayUnlocks = yesterdaySessions.count { it.countedAsUnlock }
        val yesterdayMillis = yesterdaySessions.sumOf { it.durationMs(now) }

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

        val hasPerm = hasUsageStatsPermission()
        val sys = if (hasPerm) queryTodaySystemStats(today, todayStartMs, now, zone) else SystemDayStats()

        // Reconcile local SQLite sessions with OS kernel UsageEvents so stats stay accurate
        // even if HyperOS temporarily reclaims the silent background listener service.
        val effectiveTodayUnlocks = maxOf(localUnlocks, sys.unlocks)
        val rawScreenMs = maxOf(localMillis, sys.screenMillis)
        val effectiveTodayMillis = if (rawScreenMs == 0L && sys.topApps.isNotEmpty()) {
            sys.topApps.sumOf { it.foregroundMillis }
        } else {
            rawScreenMs
        }
        val effectiveLongest = maxOf(localLongest, sys.longestSessionMillis)

        val hourly = (0..23).map { h ->
            HourlyUsage(
                hour = h,
                minutes = maxOf(hourlyMs[h], sys.hourlyMs[h]) / 60_000f,
                unlocks = maxOf(hourlyUnlocks[h], sys.hourlyUnlocks[h]),
            )
        }

        val trend = (0..6).map { offset ->
            val d = sevenDaysAgo.plusDays(offset.toLong())
            val key = d.toString()
            if (d == today) {
                DailyTrendPoint(
                    dateKey = key,
                    label = "${d.monthValue}/${d.dayOfMonth}",
                    unlocks = effectiveTodayUnlocks,
                    screenMinutes = (effectiveTodayMillis / 60_000L).toInt(),
                )
            } else {
                val list = byDate[key].orEmpty()
                DailyTrendPoint(
                    dateKey = key,
                    label = "${d.monthValue}/${d.dayOfMonth}",
                    unlocks = list.count { it.countedAsUnlock },
                    screenMinutes = (list.sumOf { it.durationMs(now) } / 60_000L).toInt(),
                )
            }
        }

        val result = UsageOverview(
            todayUnlocks = effectiveTodayUnlocks,
            todayScreenMillis = effectiveTodayMillis,
            longestSessionMillis = effectiveLongest,
            yesterdayUnlocks = yesterdayUnlocks,
            yesterdayScreenMillis = yesterdayMillis,
            hourlyBreakdown = hourly,
            sevenDayTrend = trend,
            topApps = sys.topApps,
            hasUsageStatsPermission = hasPerm,
        )
        _overview.value = result
        return result
    }

    private fun queryTodaySystemStats(
        today: LocalDate,
        startMs: Long,
        endMs: Long,
        zone: ZoneId,
    ): SystemDayStats {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return SystemDayStats()
        val durations = mutableMapOf<String, Long>()
        val activeStart = mutableMapOf<String, Long>()
        val hourlyMs = LongArray(24)
        val hourlyUnlocks = IntArray(24)

        var sysUnlocks = 0
        var lastUnlockMs = 0L
        var screenOnStart: Long? = null
        var totalScreenMs = 0L
        var longestMs = 0L

        fun accumulateScreenSegment(segStart: Long, segEnd: Long) {
            if (segEnd <= segStart) return
            val clampedEnd = minOf(segEnd, segStart + 8L * 3_600_000L)
            val dur = clampedEnd - segStart
            totalScreenMs += dur
            if (dur > longestMs) longestMs = dur
            var cur = maxOf(segStart, startMs)
            while (cur < clampedEnd) {
                val h = Instant.ofEpochMilli(cur).atZone(zone).hour
                val hourEnd = today.atTime(h, 0).plusHours(1).atZone(zone).toInstant().toEpochMilli()
                val sliceEnd = minOf(clampedEnd, hourEnd)
                hourlyMs[h] += (sliceEnd - cur).coerceAtLeast(0L)
                cur = sliceEnd
            }
        }

        runCatching {
            val events = usm.queryEvents(startMs, endMs)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val ts = event.timeStamp
                when (event.eventType) {
                    UsageEvents.Event.KEYGUARD_HIDDEN -> {
                        if (ts - lastUnlockMs >= 1_500L) {
                            sysUnlocks++
                            lastUnlockMs = ts
                            val h = Instant.ofEpochMilli(ts).atZone(zone).hour
                            hourlyUnlocks[h]++
                        }
                        if (screenOnStart == null) screenOnStart = ts
                    }

                    UsageEvents.Event.SCREEN_INTERACTIVE -> {
                        if (screenOnStart == null) screenOnStart = ts
                    }

                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        val s = screenOnStart
                        if (s != null) {
                            accumulateScreenSegment(s, ts)
                            screenOnStart = null
                        }
                    }

                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        event.packageName?.let { activeStart[it] = ts }
                    }

                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED,
                    -> {
                        val pkg = event.packageName
                        if (pkg != null) {
                            val started = activeStart.remove(pkg)
                            if (started != null && ts > started) {
                                durations[pkg] = (durations[pkg] ?: 0L) + (ts - started)
                            }
                        }
                    }
                }
            }

            // Close active screen-on session up to now if screen is currently awake
            val openStart = screenOnStart
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (openStart != null && pm?.isInteractive == true) {
                accumulateScreenSegment(openStart, endMs)
            }

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
        val topApps = durations.entries
            .filter { (pkg, ms) -> ms >= 30_000L && pkg !in ignoredPackages }
            .sortedByDescending { it.value }
            .take(15)
            .map { (pkg, ms) ->
                val label = runCatching {
                    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getApplicationInfo(pkg, 0)
                    }
                    pm.getApplicationLabel(info).toString()
                }.getOrElse { pkg.substringAfterLast('.') }
                AppUsageItem(
                    packageName = pkg,
                    appName = label,
                    foregroundMillis = ms,
                )
            }

        return SystemDayStats(
            unlocks = sysUnlocks,
            screenMillis = totalScreenMs,
            longestSessionMillis = longestMs,
            hourlyMs = hourlyMs,
            hourlyUnlocks = hourlyUnlocks,
            topApps = topApps,
        )
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
