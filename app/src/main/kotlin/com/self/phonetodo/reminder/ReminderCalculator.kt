package com.self.phonetodo.reminder

import com.self.phonetodo.model.Reminder
import com.self.phonetodo.model.Repeat
import com.self.phonetodo.model.RepeatKind
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Exact Kotlin port of DeskTodo's reminder.ts (IANA timeZone + DST + calendar recurrence).
 */
object ReminderCalculator {

    data class TimeZoneOption(val id: String, val label: String)

    val TIME_ZONES: List<TimeZoneOption> = listOf(
        TimeZoneOption("Asia/Shanghai", "北京时间"),
        TimeZoneOption("America/Los_Angeles", "美西时间"),
        TimeZoneOption("America/Denver", "美山地时间"),
        TimeZoneOption("America/Chicago", "美中时间"),
        TimeZoneOption("America/New_York", "美东时间"),
        TimeZoneOption("Europe/London", "伦敦时间"),
        TimeZoneOption("Europe/Berlin", "柏林时间"),
        TimeZoneOption("Asia/Tokyo", "东京时间"),
        TimeZoneOption("Asia/Singapore", "新加坡时间"),
        TimeZoneOption("UTC", "UTC"),
    )

    private val WEEKDAY_NAMES = arrayOf("日", "一", "二", "三", "四", "五", "六")

    fun systemTimeZone(): String = ZoneId.systemDefault().id

    fun tzLabel(tz: String): String = TIME_ZONES.firstOrNull { it.id == tz }?.label ?: tz

    fun safeZone(tz: String): ZoneId = runCatching { ZoneId.of(tz) }.getOrElse { ZoneId.of("Asia/Shanghai") }

    /** Convert java.time DayOfWeek to DeskTodo convention: 0 = Sun, 1 = Mon ... 6 = Sat */
    private fun DayOfWeek.toDeskWeekday(): Int = if (this == DayOfWeek.SUNDAY) 0 else this.value

    private fun mondayOf(date: LocalDate): LocalDate {
        val offsetFromMonday = (date.dayOfWeek.value - 1).toLong()
        return date.minusDays(offsetFromMonday)
    }

    private fun parseDate(dateStr: String): LocalDate =
        runCatching { LocalDate.parse(dateStr) }.getOrElse { LocalDate.now() }

    private fun parseTime(timeStr: String): LocalTime {
        val parts = timeStr.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 9
        val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        return LocalTime.of(h, m)
    }

    private fun wallToEpochMs(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()

    /**
     * Computes next occurrence strictly after [afterEpochMs] (in Unix ms).
     * Returns null if there is no future occurrence (e.g. one-time reminder already elapsed).
     */
    fun nextOccurrence(rem: Reminder, afterEpochMs: Long): Long? {
        val zone = safeZone(rem.timeZone)
        val anchor = parseDate(rem.date)
        val time = parseTime(rem.time)
        val n = rem.repeat.interval.coerceAtLeast(1)

        val afterZdt = Instant.ofEpochMilli(afterEpochMs).atZone(zone)
        val afterDate = afterZdt.toLocalDate()
        val startDate = if (anchor.isAfter(afterDate.minusDays(1))) anchor else afterDate.minusDays(1)

        return when (rem.repeat.kind) {
            RepeatKind.NONE -> {
                val t = wallToEpochMs(anchor, time, zone)
                if (t > afterEpochMs) t else null
            }

            RepeatKind.DAILY -> {
                val diffDays = ChronoUnit.DAYS.between(anchor, startDate).coerceAtLeast(0)
                var k = diffDays / n
                repeat(10) {
                    val candidate = anchor.plusDays(k * n)
                    val t = wallToEpochMs(candidate, time, zone)
                    if (t > afterEpochMs) return t
                    k++
                }
                null
            }

            RepeatKind.WEEKLY -> {
                val days = if (rem.repeat.weekdays.isNotEmpty()) {
                    rem.repeat.weekdays.toSet()
                } else {
                    setOf(anchor.dayOfWeek.toDeskWeekday())
                }
                val anchorMonday = mondayOf(anchor)
                val maxDays = 7 * n + 14
                for (offset in 0 until maxDays) {
                    val candidate = startDate.plusDays(offset.toLong())
                    val weekIndex = ChronoUnit.WEEKS.between(anchorMonday, mondayOf(candidate))
                    if (weekIndex < 0 || weekIndex % n != 0L) continue
                    if (candidate.dayOfWeek.toDeskWeekday() !in days) continue
                    val t = wallToEpochMs(candidate, time, zone)
                    if (t > afterEpochMs) return t
                }
                null
            }

            RepeatKind.MONTHLY, RepeatKind.YEARLY -> {
                val stepMonths = if (rem.repeat.kind == RepeatKind.MONTHLY) n else n * 12
                val anchorIdx = anchor.year * 12 + (anchor.monthValue - 1)
                val afterIdx = afterDate.year * 12 + (afterDate.monthValue - 1)
                var k = ((afterIdx - 1 - anchorIdx) / stepMonths).coerceAtLeast(0)
                repeat(6) {
                    val idx = anchorIdx + k * stepMonths
                    val y = idx / 12
                    val m = (idx % 12) + 1
                    val clampedDay = anchor.dayOfMonth.coerceAtMost(YearMonth.of(y, m).lengthOfMonth())
                    val candidate = LocalDate.of(y, m, clampedDay)
                    val t = wallToEpochMs(candidate, time, zone)
                    if (t > afterEpochMs) return t
                    k++
                }
                null
            }
        }
    }

    fun formatIn(utcMs: Long, tz: String): String {
        val zone = safeZone(tz)
        val zdt = Instant.ofEpochMilli(utcMs).atZone(zone)
        val wd = WEEKDAY_NAMES[zdt.dayOfWeek.toDeskWeekday()]
        return "%d/%d 周%s %02d:%02d".format(
            zdt.monthValue,
            zdt.dayOfMonth,
            wd,
            zdt.hour,
            zdt.minute,
        )
    }

    fun describeRepeat(repeat: Repeat, anchorDate: String): String {
        val n = repeat.interval.coerceAtLeast(1)
        return when (repeat.kind) {
            RepeatKind.NONE -> "不重复"
            RepeatKind.DAILY -> if (n == 1) "每天" else "每 $n 天"
            RepeatKind.WEEKLY -> {
                val days = if (repeat.weekdays.isNotEmpty()) {
                    repeat.weekdays
                } else {
                    listOf(parseDate(anchorDate).dayOfWeek.toDeskWeekday())
                }
                val sorted = days.distinct().sortedBy { (it + 6) % 7 }
                val names = sorted.joinToString("、") { "周${WEEKDAY_NAMES[it.coerceIn(0, 6)]}" }
                "${if (n == 1) "每" else "每 $n "}周的$names"
            }
            RepeatKind.MONTHLY -> if (n == 1) "每月" else "每 $n 个月"
            RepeatKind.YEARLY -> if (n == 1) "每年" else "每 $n 年"
        }
    }

    fun summary(rem: Reminder): String {
        val repeatText = if (rem.repeat.kind == RepeatKind.NONE) "" else " · ${describeRepeat(rem.repeat, rem.date)}"
        val next = rem.nextAt ?: return if (rem.repeat.kind == RepeatKind.NONE) "已提醒" else "已结束$repeatText"
        val sys = systemTimeZone()
        val tzPrefix = if (rem.timeZone == sys) "" else "${tzLabel(rem.timeZone)} "
        return "$tzPrefix${formatIn(next, rem.timeZone)}$repeatText"
    }

    fun todayIn(tz: String): String {
        val zdt = ZonedDateTime.now(safeZone(tz))
        return "%04d-%02d-%02d".format(zdt.year, zdt.monthValue, zdt.dayOfMonth)
    }

    fun quickReminderAfterMinutes(minutes: Long, tz: String): Reminder {
        val zone = safeZone(tz)
        val target = ZonedDateTime.now(zone).plusMinutes(minutes).truncatedTo(ChronoUnit.MINUTES)
        val date = "%04d-%02d-%02d".format(target.year, target.monthValue, target.dayOfMonth)
        val time = "%02d:%02d".format(target.hour, target.minute)
        return Reminder(
            date = date,
            time = time,
            timeZone = tz,
            repeat = Repeat(),
            nextAt = target.toInstant().toEpochMilli(),
        )
    }

    fun quickReminderTodayOrTomorrowAt(hour: Int, minute: Int, tz: String, forceTomorrow: Boolean = false): Reminder {
        val zone = safeZone(tz)
        val now = ZonedDateTime.now(zone)
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (forceTomorrow || !target.isAfter(now)) {
            target = target.plusDays(1)
        }
        val date = "%04d-%02d-%02d".format(target.year, target.monthValue, target.dayOfMonth)
        val time = "%02d:%02d".format(target.hour, target.minute)
        return Reminder(
            date = date,
            time = time,
            timeZone = tz,
            repeat = Repeat(),
            nextAt = target.toInstant().toEpochMilli(),
        )
    }
}
