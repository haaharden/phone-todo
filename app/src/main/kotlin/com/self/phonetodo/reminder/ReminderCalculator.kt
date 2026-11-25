package com.self.phonetodo.reminder

import com.self.phonetodo.model.Reminder
import com.self.phonetodo.model.Repeat
import com.self.phonetodo.model.RepeatKind
import com.self.phonetodo.model.Todo
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Single source of truth for reminder time calculation, overdue/fired status,
 * today-vs-all filtering, and chronological task sorting (DeskTodo-compatible).
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

    private fun DayOfWeek.toDeskWeekday(): Int = if (this == DayOfWeek.SUNDAY) 0 else this.value

    private fun mondayOf(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())

    fun parseDate(dateStr: String): LocalDate = runCatching { LocalDate.parse(dateStr) }.getOrElse { LocalDate.now() }

    fun parseTime(timeStr: String): LocalTime {
        val parts = timeStr.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 9
        val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        return LocalTime.of(h, m)
    }

    fun wallToEpochMs(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()

    fun anchorEpochMs(rem: Reminder): Long =
        wallToEpochMs(parseDate(rem.date), parseTime(rem.time), safeZone(rem.timeZone))

    /**
     * Timestamp used for display and chronological ordering.
     * Fired reminders keep their fired/anchor timestamp so they NEVER jump to the bottom!
     */
    fun displayEpochMs(rem: Reminder): Long =
        rem.lastFiredAt ?: rem.nextAt ?: anchorEpochMs(rem)

    /** True once a reminder has fired or passed its scheduled time (renders red until checked off). */
    fun isFiredOrOverdue(rem: Reminder, now: Long = System.currentTimeMillis()): Boolean {
        if (rem.lastFiredAt != null) return true
        val next = rem.nextAt ?: return true
        return next <= now
    }

    /** Single source of truth for ordering pending todos. */
    fun sortPendingTodos(todos: List<Todo>): List<Todo> =
        todos.sortedWith(
            compareByDescending<Todo> { it.pinned }
                .thenBy { it.reminder?.let { r -> displayEpochMs(r) } ?: Long.MAX_VALUE }
                .thenByDescending { it.createdAt },
        )

    /**
     * True if a pending todo originated strictly before today (00:00 local time):
     * either its reminder timestamp was before today, or (if unscheduled) it was created before today.
     */
    fun isPastCarryover(todo: Todo, now: Long = System.currentTimeMillis()): Boolean {
        if (todo.done || todo.deletedAt != null) return false
        val localZone = ZoneId.systemDefault()
        val startOfTodayMs = Instant.ofEpochMilli(now)
            .atZone(localZone)
            .toLocalDate()
            .atStartOfDay(localZone)
            .toInstant()
            .toEpochMilli()

        val rem = todo.reminder
        return if (rem != null) {
            displayEpochMs(rem) < startOfTodayMs
        } else {
            todo.createdAt < startOfTodayMs
        }
    }

    /** True if a pending todo belongs specifically to Today (not past carryover, not strictly future). */
    fun isTodayOnlyTodo(todo: Todo, now: Long = System.currentTimeMillis()): Boolean {
        if (todo.done || todo.deletedAt != null) return false
        if (isPastCarryover(todo, now)) return false
        if (todo.pinned) return true

        val localZone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(localZone).toLocalDate()
        val endOfTodayMs = today.plusDays(1).atStartOfDay(localZone).toInstant().toEpochMilli() - 1L

        val rem = todo.reminder
        if (rem != null) {
            return isFiredOrOverdue(rem, now) || displayEpochMs(rem) <= endOfTodayMs
        }
        val createdDate = Instant.ofEpochMilli(todo.createdAt).atZone(localZone).toLocalDate()
        return createdDate == today
    }

    /** Total items shown in the focused "今日待办" tab (Past carryover + Today). */
    fun isTodayTodo(todo: Todo, now: Long = System.currentTimeMillis()): Boolean =
        isPastCarryover(todo, now) || isTodayOnlyTodo(todo, now)

    /** Concise red subtitle for unscheduled past carryover items (e.g. "昨天未完成", "3天前"). */
    fun pastUnscheduledLabel(todo: Todo, now: Long = System.currentTimeMillis()): String {
        val localZone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(localZone).toLocalDate()
        val createdDate = Instant.ofEpochMilli(todo.createdAt).atZone(localZone).toLocalDate()
        val days = ChronoUnit.DAYS.between(createdDate, today).coerceAtLeast(1)
        return when {
            days == 1L -> "昨天未完成"
            days <= 14L -> "${days}天前"
            else -> "${createdDate.monthValue}/${createdDate.dayOfMonth}"
        }
    }

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
                    val t = wallToEpochMs(anchor.plusDays(k * n), time, zone)
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
                for (offset in 0 until (7 * n + 14)) {
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
                    val t = wallToEpochMs(LocalDate.of(y, m, clampedDay), time, zone)
                    if (t > afterEpochMs) return t
                    k++
                }
                null
            }
        }
    }

    /**
     * Computes the next reminder timestamp when the user completes the CURRENT occurrence of a recurring todo:
     * - If the current occurrence already fired (lastFiredAt != null), advances past max(now, lastFiredAt).
     * - If checked off early before nextAt (lastFiredAt == null), advances past max(now, nextAt) so today's
     *   occurrence is actually completed and moves cleanly to the following cycle.
     */
    fun nextOccurrenceAfterComplete(rem: Reminder, nowMs: Long = System.currentTimeMillis()): Long? {
        if (rem.repeat.kind == RepeatKind.NONE) return null
        val pivot = if (rem.lastFiredAt != null) {
            maxOf(nowMs, rem.lastFiredAt)
        } else {
            maxOf(nowMs, rem.nextAt ?: 0L)
        }
        return nextOccurrence(rem, pivot)
    }

    fun formatIn(utcMs: Long, tz: String): String {
        val zone = safeZone(tz)
        val zdt = Instant.ofEpochMilli(utcMs).atZone(zone)
        val today = LocalDate.now(zone)
        val datePrefix = when {
            zdt.toLocalDate() == today -> "今天"
            zdt.toLocalDate() == today.plusDays(1) -> "明天"
            zdt.toLocalDate() == today.minusDays(1) -> "昨天"
            zdt.year != today.year -> "${zdt.year}/${zdt.monthValue}/${zdt.dayOfMonth} 周${WEEKDAY_NAMES[zdt.dayOfWeek.toDeskWeekday()]}"
            else -> "${zdt.monthValue}/${zdt.dayOfMonth} 周${WEEKDAY_NAMES[zdt.dayOfWeek.toDeskWeekday()]}"
        }
        return "$datePrefix %02d:%02d".format(zdt.hour, zdt.minute)
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
                "${if (n == 1) "每" else "每 $n "}周$names"
            }
            RepeatKind.MONTHLY -> if (n == 1) "每月" else "每 $n 个月"
            RepeatKind.YEARLY -> if (n == 1) "每年" else "每 $n 年"
        }
    }

    /** Always displays the actual scheduled/fired timestamp (highlighted red in UI when fired). */
    fun summary(rem: Reminder): String {
        val repeatText = if (rem.repeat.kind == RepeatKind.NONE) "" else "  ${describeRepeat(rem.repeat, rem.date)}"
        val ts = displayEpochMs(rem)
        val tzPrefix = if (rem.timeZone == systemTimeZone()) "" else "${tzLabel(rem.timeZone)} "
        return "$tzPrefix${formatIn(ts, rem.timeZone)}$repeatText"
    }

    fun todayIn(tz: String): String {
        val zdt = ZonedDateTime.now(safeZone(tz))
        return "%04d-%02d-%02d".format(zdt.year, zdt.monthValue, zdt.dayOfMonth)
    }

    fun buildReminder(
        date: LocalDate,
        hour: Int,
        minute: Int,
        tz: String,
        repeat: Repeat = Repeat(),
    ): Reminder {
        val dateStr = "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)
        val timeStr = "%02d:%02d".format(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        val base = Reminder(
            date = dateStr,
            time = timeStr,
            timeZone = tz,
            repeat = repeat,
            nextAt = null,
            lastFiredAt = null,
        )
        val next = nextOccurrence(base, System.currentTimeMillis() - 1)
        return base.copy(nextAt = next)
    }

    fun defaultNewReminderDateTime(tz: String): ZonedDateTime {
        val raw = ZonedDateTime.now(safeZone(tz)).plusMinutes(30).truncatedTo(ChronoUnit.MINUTES)
        val rem = raw.minute % 5
        return if (rem == 0) raw else raw.plusMinutes((5 - rem).toLong())
    }

    fun quickReminderAfterMinutes(minutes: Long, tz: String): Reminder {
        val target = ZonedDateTime.now(safeZone(tz)).plusMinutes(minutes).truncatedTo(ChronoUnit.MINUTES)
        return buildReminder(target.toLocalDate(), target.hour, target.minute, tz)
    }

    fun quickReminderTodayOrTomorrowAt(hour: Int, minute: Int, tz: String, forceTomorrow: Boolean = false): Reminder {
        val now = ZonedDateTime.now(safeZone(tz))
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (forceTomorrow || !target.isAfter(now)) target = target.plusDays(1)
        return buildReminder(target.toLocalDate(), target.hour, target.minute, tz)
    }
}
