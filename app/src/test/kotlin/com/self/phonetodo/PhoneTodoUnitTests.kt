package com.self.phonetodo

import com.self.phonetodo.importer.SuperProductivityImporter
import com.self.phonetodo.model.Category
import com.self.phonetodo.model.Reminder
import com.self.phonetodo.model.Repeat
import com.self.phonetodo.model.RepeatKind
import com.self.phonetodo.model.Todo
import com.self.phonetodo.model.TodoData
import com.self.phonetodo.reminder.ReminderCalculator
import com.self.phonetodo.sync.SyncMerger
import com.self.phonetodo.update.AppUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class PhoneTodoUnitTests {

    @Test
    fun reminderCalculator_monthlyClampsMonthEnd() {
        val rem = Reminder(
            date = "2026-01-31",
            time = "09:00",
            timeZone = "Asia/Shanghai",
            repeat = Repeat(RepeatKind.MONTHLY, interval = 1),
        )
        val after = ZonedDateTime.parse("2026-01-31T09:00:00+08:00").toInstant().toEpochMilli()
        val next = ReminderCalculator.nextOccurrence(rem, after)
        assertNotNull(next)
        val expected = ZonedDateTime.parse("2026-02-28T09:00:00+08:00").toInstant().toEpochMilli()
        assertEquals(expected, next)
    }

    @Test
    fun syncMerger_resolvesLwwAndTombstoneDeletion() {
        val local = TodoData(
            version = 3,
            categories = listOf(Category("c1", "工作", updatedAt = 100L)),
            todos = listOf(
                Todo("t1", "本地旧版", done = false, createdAt = 10L, updatedAt = 100L),
                Todo("t2", "本地已删", done = false, createdAt = 10L, updatedAt = 300L, deletedAt = 300L),
            ),
        )
        val remote = TodoData(
            version = 3,
            categories = listOf(Category("c1", "工作与项目", updatedAt = 200L)),
            todos = listOf(
                Todo("t1", "云端新版", done = true, createdAt = 10L, doneAt = 250L, updatedAt = 250L),
                Todo("t2", "云端未删", done = false, createdAt = 10L, updatedAt = 150L),
                Todo("t3", "电脑新建", done = false, createdAt = 50L, updatedAt = 200L),
            ),
        )

        val merged = SyncMerger.merge(local, remote)
        assertEquals("工作与项目", merged.categories.first { it.id == "c1" }.name)
        assertEquals("云端新版", merged.todos.first { it.id == "t1" }.text)
        assertTrue(merged.todos.first { it.id == "t1" }.done)
        assertNotNull(merged.todos.first { it.id == "t2" }.deletedAt)
        assertEquals(2, merged.activeTodos().size)
        assertNull(merged.activeTodos().firstOrNull { it.id == "t2" })
    }

    @Test
    fun superProductivityImporter_parsesCompleteJsonBackupAndDeduplicates() {
        val sampleJson = """
            {
              "project": {
                "ids": ["p1"],
                "entities": {
                  "p1": { "id": "p1", "title": "研发工作" }
                }
              },
              "task": {
                "ids": ["t1", "t2"],
                "entities": {
                  "t1": { "id": "t1", "title": "提交周报", "isDone": false, "created": 1730000000000, "projectId": "p1", "priority": 3 },
                  "t2": { "id": "t2", "title": "子任务检查", "parentId": "t1", "isDone": false, "created": 1730000005000, "projectId": "p1" }
                }
              },
              "archiveYoung": {
                "task": {
                  "ids": ["a1"],
                  "entities": {
                    "a1": { "id": "a1", "title": "上周已完成", "isDone": true, "created": 1729000000000, "doneOn": 1729001000000 }
                  }
                }
              },
              "taskRepeatCfg": {
                "ids": ["r1"],
                "entities": {
                  "r1": { "id": "r1", "title": "每日站会", "isPaused": false, "repeatCycle": "WEEKLY", "repeatEvery": 1, "monday": true, "wednesday": true, "startTime": "10:00", "projectId": "p1" }
                }
              }
            }
        """.trimIndent()

        val preview = SuperProductivityImporter.parse(
            rawContent = sampleJson,
            existingTodoIds = setOf("sp_task_t1"),
            defaultTimeZone = "Asia/Shanghai",
        ).getOrThrow()

        assertEquals(1, preview.categories.size)
        assertEquals("研发工作", preview.categories.first().name)
        assertEquals(1, preview.activeTodos.size)
        assertEquals("[提交周报] 子任务检查", preview.activeTodos.first().text)
        assertEquals(1, preview.archivedTodos.size)
        assertEquals(1, preview.repeatTodos.size)
        assertEquals(1, preview.skippedDuplicateCount)
    }

    @Test
    fun appUpdater_comparesSemverCorrectly() {
        assertTrue(AppUpdater.isRemoteVersionNewer("1.0.0", "1.1.0"))
        assertTrue(AppUpdater.isRemoteVersionNewer("1.1.0", "v1.1.1"))
        assertTrue(AppUpdater.isRemoteVersionNewer("1.1.9", "1.2.0"))
        assertFalse(AppUpdater.isRemoteVersionNewer("1.1.0", "1.1.0"))
        assertFalse(AppUpdater.isRemoteVersionNewer("1.2.0", "1.1.5"))
    }

    @Test
    fun firedReminder_staysInPlaceAndFlagsRedUntilCompleted() {
        val t10 = ZonedDateTime.parse("2026-11-24T10:00:00+08:00").toInstant().toEpochMilli()
        val t15 = ZonedDateTime.parse("2026-11-24T15:00:00+08:00").toInstant().toEpochMilli()
        val now = ZonedDateTime.parse("2026-11-24T12:00:00+08:00").toInstant().toEpochMilli()

        val firedTodo = Todo(
            id = "fired",
            text = "十点开会",
            reminder = Reminder("2026-11-24", "10:00", "Asia/Shanghai", Repeat(), nextAt = null, lastFiredAt = t10),
        )
        val futureTodo = Todo(
            id = "future",
            text = "三点交稿",
            reminder = Reminder("2026-11-24", "15:00", "Asia/Shanghai", Repeat(), nextAt = t15),
        )
        val unscheduled = Todo(id = "none", text = "买咖啡", reminder = null)

        val sorted = ReminderCalculator.sortPendingTodos(listOf(unscheduled, futureTodo, firedTodo))
        assertEquals(listOf("fired", "future", "none"), sorted.map { it.id })
        assertTrue(ReminderCalculator.isFiredOrOverdue(firedTodo.reminder!!, now))
        assertFalse(ReminderCalculator.isFiredOrOverdue(futureTodo.reminder!!, now))
        assertTrue(ReminderCalculator.isTodayTodo(firedTodo, now))
        assertTrue(ReminderCalculator.isTodayTodo(futureTodo, now))
    }
}
