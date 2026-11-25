package com.self.phonetodo

import com.self.phonetodo.data.TodoRepository
import com.self.phonetodo.importer.TodoDataPort
import com.self.phonetodo.model.Category
import com.self.phonetodo.model.Reminder
import com.self.phonetodo.model.Repeat
import com.self.phonetodo.model.RepeatKind
import com.self.phonetodo.model.Todo
import com.self.phonetodo.model.TodoData
import com.self.phonetodo.reminder.ReminderCalculator
import com.self.phonetodo.sync.GithubSyncManager
import com.self.phonetodo.sync.SyncMerger
import com.self.phonetodo.update.AppUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
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

        val merged = SyncMerger.merge(local, remote, nowMs = 1_000L)
        assertEquals("工作与项目", merged.categories.first { it.id == "c1" }.name)
        assertEquals("云端新版", merged.todos.first { it.id == "t1" }.text)
        assertTrue(merged.todos.first { it.id == "t1" }.done)
        assertNotNull(merged.todos.first { it.id == "t2" }.deletedAt)
        assertEquals(2, merged.activeTodos().size)
        assertNull(merged.activeTodos().firstOrNull { it.id == "t2" })

        // Tombstones older than 30 days are pruned cleanly
        val mergedAfter31Days = SyncMerger.merge(
            local,
            remote,
            nowMs = 300L + SyncMerger.TOMBSTONE_TTL_MS + 86_400_000L,
        )
        assertNull(mergedAfter31Days.todos.firstOrNull { it.id == "t2" })
    }

    @Test
    fun todoDataPort_roundTripsStandardJsonAndCsvAndSupportsLegacyBackups() {
        val original = TodoData(
            version = 3,
            categories = listOf(Category("c1", "研发工作", updatedAt = 100L)),
            todos = listOf(
                Todo(
                    id = "t1",
                    text = "提交周报, 含 \"复盘\"",
                    done = false,
                    createdAt = 1730000000000L,
                    categoryId = "c1",
                    reminder = Reminder("2026-11-25", "09:30", "Asia/Shanghai", Repeat(RepeatKind.DAILY, 1)),
                    pinned = true,
                    strongAlert = true,
                ),
                Todo(
                    id = "t2",
                    text = "已完成项",
                    done = true,
                    createdAt = 1730000005000L,
                    doneAt = 1730000010000L,
                ),
            ),
        )

        // 1. Standard JSON export -> import round-trip
        val jsonStr = TodoDataPort.exportJson(original)
        val jsonPreview = TodoDataPort.parse(jsonStr, emptySet(), "Asia/Shanghai").getOrThrow()
        assertEquals("标准 JSON 备份", jsonPreview.sourceType)
        assertEquals(1, jsonPreview.categories.size)
        assertEquals(1, jsonPreview.repeatTodos.size)
        assertEquals(1, jsonPreview.archivedTodos.size)
        assertEquals("提交周报, 含 \"复盘\"", jsonPreview.repeatTodos.first().text)

        // 2. Standard CSV export -> import round-trip (preserves quotes/commas/category/reminder)
        val csvStr = TodoDataPort.exportCsv(original)
        val csvPreview = TodoDataPort.parse(csvStr, emptySet(), "Asia/Shanghai").getOrThrow()
        assertEquals("标准 CSV 表格", csvPreview.sourceType)
        assertEquals(1, csvPreview.categories.size)
        assertEquals("研发工作", csvPreview.categories.first().name)
        assertEquals(1, csvPreview.repeatTodos.size)
        assertEquals("提交周报, 含 \"复盘\"", csvPreview.repeatTodos.first().text)
        assertTrue(csvPreview.repeatTodos.first().pinned)
        assertTrue(csvPreview.repeatTodos.first().strongAlert)
        assertEquals(1, csvPreview.archivedTodos.size)

        // 3. Silent compatibility with entity-store JSON backup
        val sampleJson = """
            {
              "project": { "ids": ["p1"], "entities": { "p1": { "id": "p1", "title": "研发工作" } } },
              "task": {
                "ids": ["t1", "t2"],
                "entities": {
                  "t1": { "id": "t1", "title": "提交周报", "isDone": false, "created": 1730000000000, "projectId": "p1" },
                  "t2": { "id": "t2", "title": "子任务检查", "parentId": "t1", "isDone": false, "created": 1730000005000, "projectId": "p1" }
                }
              },
              "archiveYoung": { "task": { "ids": ["a1"], "entities": { "a1": { "id": "a1", "title": "上周已完成", "isDone": true, "created": 1729000000000, "doneOn": 1729001000000 } } } },
              "taskRepeatCfg": { "ids": ["r1"], "entities": { "r1": { "id": "r1", "title": "每日站会", "isPaused": false, "repeatCycle": "WEEKLY", "repeatEvery": 1, "monday": true, "startTime": "10:00", "projectId": "p1" } } }
            }
        """.trimIndent()
        val legacyPreview = TodoDataPort.parse(sampleJson, setOf("sp_task_t1"), "Asia/Shanghai").getOrThrow()
        assertEquals("任务备份 JSON", legacyPreview.sourceType)
        assertEquals(1, legacyPreview.activeTodos.size)
        assertEquals("[提交周报] 子任务检查", legacyPreview.activeTodos.first().text)
        assertEquals(1, legacyPreview.archivedTodos.size)
        assertEquals(1, legacyPreview.repeatTodos.size)
        assertEquals(1, legacyPreview.skippedDuplicateCount)
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
        assertFalse(ReminderCalculator.isPastCarryover(firedTodo, now))
    }

    @Test
    fun pastCarryover_separatesYesterdayItemsFromToday() {
        val now = ZonedDateTime.parse("2026-11-24T12:00:00+08:00").toInstant().toEpochMilli()
        val yesterdayRemMs = ZonedDateTime.parse("2026-11-23T18:00:00+08:00").toInstant().toEpochMilli()
        val threeDaysAgoMs = ZonedDateTime.parse("2026-11-21T10:00:00+08:00").toInstant().toEpochMilli()
        val tomorrowRemMs = ZonedDateTime.parse("2026-11-25T09:00:00+08:00").toInstant().toEpochMilli()

        val pastWithRem = Todo(
            id = "p1",
            text = "昨天报销",
            createdAt = yesterdayRemMs,
            reminder = Reminder("2026-11-23", "18:00", "Asia/Shanghai", Repeat(), nextAt = null, lastFiredAt = yesterdayRemMs),
        )
        val pastNoRem = Todo(
            id = "p2",
            text = "三天前未设提醒",
            createdAt = threeDaysAgoMs,
            reminder = null,
        )
        val tomorrowTodo = Todo(
            id = "f1",
            text = "明天交周报",
            createdAt = now,
            reminder = Reminder("2026-11-25", "09:00", "Asia/Shanghai", Repeat(), nextAt = tomorrowRemMs),
        )

        assertTrue(ReminderCalculator.isPastCarryover(pastWithRem, now))
        assertTrue(ReminderCalculator.isPastCarryover(pastNoRem, now))
        assertFalse(ReminderCalculator.isTodayOnlyTodo(pastWithRem, now))
        assertFalse(ReminderCalculator.isTodayOnlyTodo(pastNoRem, now))
        assertTrue(ReminderCalculator.isTodayTodo(pastWithRem, now))
        assertTrue(ReminderCalculator.isTodayTodo(pastNoRem, now))
        assertEquals("3天前", ReminderCalculator.pastUnscheduledLabel(pastNoRem, now))
        assertFalse(ReminderCalculator.isTodayTodo(tomorrowTodo, now))
    }

    @Test
    fun githubSyncManager_normalizesRepoAndChecksEquality() {
        assertEquals("haaharden/todo-sync", GithubSyncManager.normalizeRepo("haaharden/todo-sync"))
        assertEquals("haaharden/todo-sync", GithubSyncManager.normalizeRepo("https://github.com/haaharden/todo-sync.git"))
        assertEquals("haaharden/todo-sync", GithubSyncManager.normalizeRepo("  github.com/haaharden/todo-sync/  "))
        assertNull(GithubSyncManager.normalizeRepo("invalid-repo"))
        assertNull(GithubSyncManager.normalizeRepo("a/b/c"))

        val a = TodoData(
            version = 3,
            categories = listOf(Category("c1", "工作", collapsed = true, updatedAt = 100L)),
            inboxCollapsed = true,
            todos = listOf(Todo("t1", "买咖啡", createdAt = 10L, updatedAt = 100L)),
        )
        val b = a.copy(
            categories = listOf(Category("c1", "工作", collapsed = false, updatedAt = 100L)),
            inboxCollapsed = false,
        )
        assertTrue(GithubSyncManager.isSemanticallyEqual(a, b))
    }

    @Test
    fun reminderCalculator_defaultTimeRoundsToFiveMinAndFormatsFutureYear() {
        val dt = ReminderCalculator.defaultNewReminderDateTime("Asia/Shanghai")
        assertEquals(0, dt.minute % 5)
        assertEquals(0, dt.second)

        val futureYearMs = ZonedDateTime.parse("2030-06-15T09:30:00+08:00").toInstant().toEpochMilli()
        val formatted = ReminderCalculator.formatIn(futureYearMs, "Asia/Shanghai")
        assertTrue("Expected year prefix in '$formatted'", formatted.startsWith("2030/6/15 "))
        assertTrue(formatted.endsWith("09:30"))
    }

    @Test
    fun githubSync_preservesConcurrentLocalEditDuringUpload() {
        val tempDir = Files.createTempDirectory("phonetodo_sync_race").toFile()
        try {
            val todoRepo = TodoRepository(tempDir)
            todoRepo.addTodo("已有待办")
            val remoteInitial = TodoData(
                version = 3,
                todos = listOf(Todo("remote_1", "桌面新建", createdAt = 100L, updatedAt = 200L)),
            )

            var remoteState: TodoData? = remoteInitial
            var remoteSha = "sha_1"
            var putCalls = 0
            var injectedDuringUpload = false

            val transport = object : GithubSyncManager.SyncTransport {
                override fun verifyPrivateRepo(repo: String, token: String) = Unit

                override fun fetchRemoteFile(repo: String, token: String): GithubSyncManager.RemoteFile {
                    return GithubSyncManager.RemoteFile(sha = remoteSha, data = remoteState)
                }

                override fun putRemoteFile(repo: String, token: String, content: String, sha: String?) {
                    putCalls++
                    if (!injectedDuringUpload) {
                        injectedDuringUpload = true
                        // Simulate user creating a brand-new todo while the HTTP upload is in flight!
                        todoRepo.addTodo("上传期间新增的待办")
                    }
                    remoteState = todoRepo.json.decodeFromString(TodoData.serializer(), content)
                    remoteSha = "sha_${putCalls + 1}"
                }
            }

            val uploaded = GithubSyncManager.runSyncLoop(
                repo = "haaharden/todo-sync",
                token = "tok",
                todoRepository = todoRepo,
                transport = transport,
            )

            assertTrue(uploaded)
            assertEquals(2, putCalls)
            val localTexts = todoRepo.data.value.activeTodos().map { it.text }.toSet()
            val remoteTexts = remoteState!!.activeTodos().map { it.text }.toSet()
            assertTrue("Local must keep concurrent edit", "上传期间新增的待办" in localTexts)
            assertTrue("Local must receive remote todo", "桌面新建" in localTexts)
            assertTrue("Remote must also receive concurrent edit on follow-up pass", "上传期间新增的待办" in remoteTexts)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun githubSync_rejectsPublicRepositoryBeforeUpload() {
        assertEquals(true, GithubSyncManager.parseRepoIsPrivate("""{"id":1,"private":true}"""))
        assertEquals(false, GithubSyncManager.parseRepoIsPrivate("""{"id":1,"private":false}"""))
        assertNull(GithubSyncManager.parseRepoIsPrivate("""{"id":1}"""))

        val tempDir = Files.createTempDirectory("phonetodo_public_guard").toFile()
        try {
            val repo = TodoRepository(tempDir)
            repo.addTodo("私密待办")
            var putAttempted = false

            val transport = object : GithubSyncManager.SyncTransport {
                override fun verifyPrivateRepo(repo: String, token: String) {
                    error("安全拦截：仓库不是私有仓库")
                }

                override fun fetchRemoteFile(repo: String, token: String): GithubSyncManager.RemoteFile? = null

                override fun putRemoteFile(repo: String, token: String, content: String, sha: String?) {
                    putAttempted = true
                }
            }

            try {
                GithubSyncManager.runSyncLoop("haaharden/public-repo", "tok", repo, transport)
                fail("Expected public repo upload to be rejected")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty().contains("私有仓库"))
            }
            assertFalse("Must never call putRemoteFile when repo is not private", putAttempted)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun todoRepository_recoversFromCorruptedPrimaryUsingLatestBackup() {
        val tempDir = Files.createTempDirectory("phonetodo_corrupt_recover").toFile()
        try {
            val repo1 = TodoRepository(tempDir)
            repo1.addTodo("重要待办不可丢")

            // Write a valid backup explicitly and corrupt primary data.json
            val backupDir = File(tempDir, "backups").apply { mkdirs() }
            val primary = File(tempDir, "data.json")
            primary.copyTo(File(backupDir, "data_20261125_130000.json"), overwrite = true)
            primary.writeText("{ truncated corrupted json", Charsets.UTF_8)

            val repo2 = TodoRepository(tempDir)
            val restoredTexts = repo2.data.value.activeTodos().map { it.text }
            assertEquals(listOf("重要待办不可丢"), restoredTexts)
            assertNotNull(repo2.recoveryNotice.value)
            assertTrue(repo2.recoveryNotice.value!!.contains("已自动从备份"))
            val corruptPreserved = tempDir.listFiles()?.any { it.name.startsWith("data.json.corrupt_") } == true
            assertTrue("Corrupted original file must be preserved", corruptPreserved)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun todoRepository_recurringCompleteOnceAdvancesAndFinishPermanentlyEnds() {
        val tempDir = Files.createTempDirectory("phonetodo_recurring").toFile()
        try {
            val repo = TodoRepository(tempDir)
            val today9am = ZonedDateTime.parse("2026-11-25T09:00:00+08:00").toInstant().toEpochMilli()
            val tomorrow9am = ZonedDateTime.parse("2026-11-26T09:00:00+08:00").toInstant().toEpochMilli()
            val dayAfter9am = ZonedDateTime.parse("2026-11-27T09:00:00+08:00").toInstant().toEpochMilli()
            val earlyNow = ZonedDateTime.parse("2026-11-25T08:30:00+08:00").toInstant().toEpochMilli()

            val created = repo.addTodo(
                text = "每天吃药",
                reminder = Reminder(
                    date = "2026-11-25",
                    time = "09:00",
                    timeZone = "Asia/Shanghai",
                    repeat = Repeat(RepeatKind.DAILY, interval = 1),
                    nextAt = today9am,
                    lastFiredAt = null,
                ),
            )!!

            // 1. Complete early before 09:00 -> advances cleanly to tomorrow 09:00 and stays active
            val resEarly = repo.toggleTodoDone(created.id, now = earlyNow)!!
            assertEquals(tomorrow9am, resEarly.advancedNextAt)
            val afterEarly = repo.data.value.activeTodos().first { it.id == created.id }
            assertFalse(afterEarly.done)
            assertEquals(tomorrow9am, afterEarly.reminder?.nextAt)
            assertNull(afterEarly.reminder?.lastFiredAt)

            // 2. Undo restores exact previous state
            repo.restoreTodo(resEarly.previous)
            val afterUndo = repo.data.value.activeTodos().first { it.id == created.id }
            assertEquals(today9am, afterUndo.reminder?.nextAt)

            // 3. Simulate fired reminder at tomorrow 09:00, then check off at 09:15 -> advances to dayAfter 09:00
            repo.advanceReminderAfterFired(created.id, tomorrow9am)
            val afterFired = repo.data.value.activeTodos().first { it.id == created.id }
            assertEquals(tomorrow9am, afterFired.reminder?.lastFiredAt)
            val resAfterFire = repo.toggleTodoDone(
                created.id,
                now = ZonedDateTime.parse("2026-11-26T09:15:00+08:00").toInstant().toEpochMilli(),
            )!!
            assertEquals(dayAfter9am, resAfterFire.advancedNextAt)
            assertNull(repo.data.value.activeTodos().first { it.id == created.id }.reminder?.lastFiredAt)

            // 4. Finish permanently marks done = true
            repo.finishTodoPermanently(created.id)
            assertTrue(repo.data.value.activeTodos().first { it.id == created.id }.done)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
