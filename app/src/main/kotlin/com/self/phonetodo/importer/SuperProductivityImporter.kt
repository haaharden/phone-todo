package com.self.phonetodo.importer

import com.self.phonetodo.model.Category
import com.self.phonetodo.model.Reminder
import com.self.phonetodo.model.Repeat
import com.self.phonetodo.model.RepeatKind
import com.self.phonetodo.model.Todo
import com.self.phonetodo.reminder.ReminderCalculator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class ImportPreview(
    val sourceType: String,
    val categories: List<Category>,
    val activeTodos: List<Todo>,
    val archivedTodos: List<Todo>,
    val repeatTodos: List<Todo>,
    val skippedDuplicateCount: Int,
    val warnings: List<String> = emptyList(),
)

/**
 * Parses Super Productivity (github.com/super-productivity/super-productivity) exports:
 * 1. Complete JSON backup (AppDataComplete: project, tag, task, archiveYoung, archiveOld, taskArchive, taskRepeatCfg, reminders)
 * 2. Worklog CSV / plain-text log export
 */
object SuperProductivityImporter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(
        rawContent: String,
        existingTodoIds: Set<String>,
        defaultTimeZone: String = "Asia/Shanghai",
    ): Result<ImportPreview> = runCatching {
        val trimmed = rawContent.trim()
        require(trimmed.isNotEmpty()) { "导入内容为空" }

        if (trimmed.startsWith("{")) {
            parseCompleteJsonBackup(trimmed, existingTodoIds, defaultTimeZone)
        } else {
            parseWorklogTextOrCsv(trimmed, existingTodoIds)
        }
    }

    private fun parseCompleteJsonBackup(
        rawJson: String,
        existingTodoIds: Set<String>,
        defaultTimeZone: String,
    ): ImportPreview {
        val root = json.parseToJsonElement(rawJson).jsonObject
        // Support both direct AppDataComplete and wrapped { data: AppDataComplete }
        val payload = root["data"]?.asObjOrNull() ?: root
        val warnings = mutableListOf<String>()

        // 1. Parse Projects -> Categories
        val projectMap = mutableMapOf<String, Category>()
        val projectEntities = payload["project"]?.asObjOrNull()?.get("entities")?.asObjOrNull()
        projectEntities?.forEach { (id, el) ->
            val obj = el.asObjOrNull() ?: return@forEach
            val title = obj.str("title")?.trim().orEmpty()
            if (title.isNotEmpty() && id != "INBOX_PROJECT") {
                projectMap[id] = Category(
                    id = "sp_proj_$id",
                    name = title,
                    collapsed = false,
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }

        // Also parse Tags for fallback labeling when task has no project
        val tagNameById = mutableMapOf<String, String>()
        payload["tag"]?.asObjOrNull()?.get("entities")?.asObjOrNull()?.forEach { (id, el) ->
            val obj = el.asObjOrNull() ?: return@forEach
            val title = obj.str("title")?.trim().orEmpty()
            if (title.isNotEmpty() && id != "TODAY") {
                tagNameById[id] = title
            }
        }

        // 2. Parse Reminders map (reminderId -> remindAt epochMs)
        val reminderByTaskId = mutableMapOf<String, Long>()
        val reminderById = mutableMapOf<String, Long>()
        payload["reminders"]?.asArrOrNull()?.forEach { el ->
            val obj = el.asObjOrNull() ?: return@forEach
            val rid = obj.str("id")
            val relatedId = obj.str("relatedId")
            val remindAt = obj.lng("remindAt")
            if (remindAt != null) {
                if (rid != null) reminderById[rid] = remindAt
                if (relatedId != null) reminderByTaskId[relatedId] = remindAt
            }
        }

        val zone = ReminderCalculator.safeZone(defaultTimeZone)

        fun extractTasks(entities: JsonObject?, archived: Boolean): List<Todo> {
            if (entities == null) return emptyList()
            val titleById = mutableMapOf<String, String>()
            entities.forEach { (id, el) ->
                el.asObjOrNull()?.str("title")?.let { titleById[id] = it }
            }

            val out = mutableListOf<Todo>()
            entities.forEach { (id, el) ->
                val obj = el.asObjOrNull() ?: return@forEach
                val rawTitle = obj.str("title")?.trim().orEmpty()
                if (rawTitle.isEmpty()) return@forEach

                val parentId = obj.str("parentId")
                val parentTitle = parentId?.let { titleById[it] }
                val finalText = if (!parentTitle.isNullOrBlank()) {
                    "[$parentTitle] $rawTitle"
                } else {
                    rawTitle
                }

                val isDone = obj.bool("isDone") ?: archived
                val created = obj.lng("created") ?: System.currentTimeMillis()
                val doneOn = obj.lng("doneOn") ?: if (isDone) created else null
                val rawProjId = obj.str("projectId")
                val categoryId = rawProjId?.let { projectMap[it]?.id }

                // Resolve reminder from dueWithTime > reminders[] > deadlineWithTime > dueDay
                val remindMs = obj.lng("dueWithTime")
                    ?: obj.str("reminderId")?.let { reminderById[it] }
                    ?: reminderByTaskId[id]
                    ?: obj.lng("deadlineWithTime")
                val dueDay = obj.str("dueDay") ?: obj.str("deadlineDay")

                val reminder: Reminder? = when {
                    remindMs != null -> {
                        val zdt = Instant.ofEpochMilli(remindMs).atZone(zone)
                        val dateStr = "%04d-%02d-%02d".format(zdt.year, zdt.monthValue, zdt.dayOfMonth)
                        val timeStr = "%02d:%02d".format(zdt.hour, zdt.minute)
                        Reminder(
                            date = dateStr,
                            time = timeStr,
                            timeZone = defaultTimeZone,
                            repeat = Repeat(),
                            nextAt = if (!isDone && remindMs > System.currentTimeMillis()) remindMs else null,
                        )
                    }
                    !dueDay.isNullOrBlank() && dueDay.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> {
                        val rem = Reminder(
                            date = dueDay,
                            time = "09:00",
                            timeZone = defaultTimeZone,
                            repeat = Repeat(),
                            nextAt = null,
                        )
                        val next = if (!isDone) ReminderCalculator.nextOccurrence(rem, System.currentTimeMillis()) else null
                        rem.copy(nextAt = next)
                    }
                    else -> null
                }

                val priorityVal = obj["priority"]?.jsonPrimitive?.contentOrNull
                val isHighPriority = priorityVal == "3" || priorityVal.equals("high", ignoreCase = true)

                out += Todo(
                    id = "sp_task_$id",
                    text = finalText,
                    done = isDone,
                    createdAt = created,
                    doneAt = doneOn,
                    categoryId = categoryId,
                    reminder = reminder,
                    updatedAt = doneOn ?: created,
                    strongAlert = isHighPriority && reminder?.nextAt != null,
                    pinned = isHighPriority && !isDone,
                )
            }
            return out
        }

        // 3. Active tasks
        val activeEntities = payload["task"]?.asObjOrNull()?.get("entities")?.asObjOrNull()
        val activeTodosAll = extractTasks(activeEntities, archived = false)

        // 4. Archived tasks (new split archiveYoung + archiveOld, plus legacy taskArchive)
        val youngEntities = payload["archiveYoung"]?.asObjOrNull()
            ?.get("task")?.asObjOrNull()
            ?.get("entities")?.asObjOrNull()
        val oldEntities = payload["archiveOld"]?.asObjOrNull()
            ?.get("task")?.asObjOrNull()
            ?.get("entities")?.asObjOrNull()
        val legacyArchiveEntities = payload["taskArchive"]?.asObjOrNull()
            ?.get("entities")?.asObjOrNull()

        val archivedTodosAll = (
            extractTasks(youngEntities, archived = true) +
                extractTasks(oldEntities, archived = true) +
                extractTasks(legacyArchiveEntities, archived = true)
            ).distinctBy { it.id }

        // 5. Repeat task configurations (taskRepeatCfg.entities)
        val repeatEntities = payload["taskRepeatCfg"]?.asObjOrNull()?.get("entities")?.asObjOrNull()
        val repeatTodosAll = mutableListOf<Todo>()
        repeatEntities?.forEach { (id, el) ->
            val obj = el.asObjOrNull() ?: return@forEach
            val isPaused = obj.bool("isPaused") ?: false
            if (isPaused) return@forEach
            val title = obj.str("title")?.trim().orEmpty()
            if (title.isEmpty()) return@forEach

            val cycle = obj.str("repeatCycle")?.uppercase() ?: "DAILY"
            val every = (obj.int("repeatEvery") ?: 1).coerceAtLeast(1)
            val startTime = obj.str("startTime")?.takeIf { it.matches(Regex("\\d{2}:\\d{2}")) } ?: "09:00"
            val startDate = obj.str("startDate")?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                ?: ReminderCalculator.todayIn(defaultTimeZone)

            val repeat = when (cycle) {
                "DAILY" -> Repeat(RepeatKind.DAILY, every)
                "WEEKLY" -> {
                    val days = mutableListOf<Int>()
                    if (obj.bool("sunday") == true) days += 0
                    if (obj.bool("monday") == true) days += 1
                    if (obj.bool("tuesday") == true) days += 2
                    if (obj.bool("wednesday") == true) days += 3
                    if (obj.bool("thursday") == true) days += 4
                    if (obj.bool("friday") == true) days += 5
                    if (obj.bool("saturday") == true) days += 6
                    Repeat(RepeatKind.WEEKLY, every, days)
                }
                "MONTHLY" -> Repeat(RepeatKind.MONTHLY, every)
                "YEARLY" -> Repeat(RepeatKind.YEARLY, every)
                else -> Repeat(RepeatKind.DAILY, every)
            }

            val baseRem = Reminder(
                date = startDate,
                time = startTime,
                timeZone = defaultTimeZone,
                repeat = repeat,
                nextAt = null,
            )
            val nextAt = ReminderCalculator.nextOccurrence(baseRem, System.currentTimeMillis())
            val projId = obj.str("projectId")?.let { projectMap[it]?.id }

            repeatTodosAll += Todo(
                id = "sp_repeat_$id",
                text = title,
                done = false,
                createdAt = System.currentTimeMillis(),
                categoryId = projId,
                reminder = baseRem.copy(nextAt = nextAt),
                updatedAt = System.currentTimeMillis(),
            )
        }

        val allBeforeDedup = activeTodosAll.size + archivedTodosAll.size + repeatTodosAll.size
        val filteredActive = activeTodosAll.filter { it.id !in existingTodoIds }
        val filteredArchived = archivedTodosAll.filter { it.id !in existingTodoIds }
        val filteredRepeat = repeatTodosAll.filter { it.id !in existingTodoIds }
        val skipped = allBeforeDedup - (filteredActive.size + filteredArchived.size + filteredRepeat.size)

        if (allBeforeDedup == 0) {
            warnings += "未检测到 Super Productivity 的任务字段，请确认导出的是完整 JSON 备份文件。"
        }

        return ImportPreview(
            sourceType = "Super Productivity 完整 JSON 备份",
            categories = projectMap.values.toList(),
            activeTodos = filteredActive,
            archivedTodos = filteredArchived,
            repeatTodos = filteredRepeat,
            skippedDuplicateCount = skipped,
            warnings = warnings,
        )
    }

    private fun parseWorklogTextOrCsv(
        rawText: String,
        existingTodoIds: Set<String>,
    ): ImportPreview {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val todos = mutableListOf<Todo>()
        val now = System.currentTimeMillis()
        val isCsv = lines.firstOrNull()?.contains(",") == true && lines.size > 1
        val dataLines = if (isCsv && (lines[0].contains("title", true) || lines[0].contains("date", true))) {
            lines.drop(1)
        } else {
            lines
        }

        dataLines.forEachIndexed { idx, line ->
            val cleaned = if (isCsv) {
                // Take the longest non-date/non-numeric CSV column as task title
                line.split(",").map { it.trim().trim('"') }
                    .filter { it.isNotEmpty() && !it.matches(Regex("[\\d:./\\-\\s]+")) }
                    .maxByOrNull { it.length }
                    .orEmpty()
            } else {
                line.removePrefix("- ").removePrefix("* ").removePrefix("[x] ").removePrefix("[ ] ").trim()
            }

            if (cleaned.isNotEmpty()) {
                val stableId = "sp_log_${cleaned.hashCode().toUInt()}"
                if (stableId !in existingTodoIds) {
                    val isDone = line.startsWith("[x]", ignoreCase = true) || isCsv
                    todos += Todo(
                        id = stableId,
                        text = cleaned,
                        done = isDone,
                        createdAt = now - idx * 1000L,
                        doneAt = if (isDone) now - idx * 1000L else null,
                        updatedAt = now,
                    )
                }
            }
        }

        return ImportPreview(
            sourceType = if (isCsv) "Super Productivity Worklog CSV" else "纯文本 / Markdown 清单",
            categories = emptyList(),
            activeTodos = todos.filter { !it.done },
            archivedTodos = todos.filter { it.done },
            repeatTodos = emptyList(),
            skippedDuplicateCount = 0,
        )
    }

    private fun JsonElement.asObjOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()
    private fun JsonElement.asArrOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()
    private fun JsonObject.str(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    private fun JsonObject.lng(key: String): Long? = this[key]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
    private fun JsonObject.int(key: String): Int? = this[key]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
    private fun JsonObject.bool(key: String): Boolean? = this[key]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
}
