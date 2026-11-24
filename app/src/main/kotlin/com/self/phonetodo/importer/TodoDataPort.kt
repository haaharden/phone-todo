package com.self.phonetodo.importer

import com.self.phonetodo.model.Category
import com.self.phonetodo.model.DATA_VERSION
import com.self.phonetodo.model.Reminder
import com.self.phonetodo.model.Repeat
import com.self.phonetodo.model.RepeatKind
import com.self.phonetodo.model.Todo
import com.self.phonetodo.model.TodoData
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
import java.time.format.DateTimeFormatter

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
 * Universal todo import/export codec supporting standard open formats:
 * - Export: Standard JSON backup (`TodoData` v3) & RFC 4180 CSV (UTF-8 with BOM for Excel)
 * - Import: Standard JSON backup, generic JSON task array, standard CSV table,
 *   Markdown / plain-text checklist, plus silent compatibility for common entity-store JSON backups.
 */
object TodoDataPort {

    private val prettyJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        isLenient = true
    }

    private val isoDateTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // -------------------------------------------------------------------------
    // Export
    // -------------------------------------------------------------------------

    /** Exports active (non-deleted) categories and todos as lossless standard JSON. */
    fun exportJson(data: TodoData): String {
        val clean = TodoData(
            version = DATA_VERSION,
            categories = data.activeCategories(),
            inboxCollapsed = data.inboxCollapsed,
            todos = data.activeTodos(),
        )
        return prettyJson.encodeToString(TodoData.serializer(), clean)
    }

    /** Exports active (non-deleted) todos as standard RFC 4180 CSV with UTF-8 BOM. */
    fun exportCsv(data: TodoData): String {
        val catById = data.activeCategories().associate { it.id to it.name }
        val zone = ZoneId.systemDefault()
        val sb = StringBuilder()
        sb.append('\uFEFF') // UTF-8 BOM so Excel opens Chinese cleanly
        sb.appendLine("title,done,category,reminder_date,reminder_time,repeat,pinned,strong_alert,created_at")

        for (t in data.activeTodos()) {
            val catName = t.categoryId?.let { catById[it] }.orEmpty()
            val remDate = t.reminder?.date.orEmpty()
            val remTime = t.reminder?.time.orEmpty()
            val repeatStr = t.reminder?.repeat?.kind?.name?.lowercase() ?: "none"
            val createdStr = Instant.ofEpochMilli(t.createdAt).atZone(zone).format(isoDateTimeFmt)

            sb.append(csvEscape(t.text)).append(',')
            sb.append(if (t.done) "true" else "false").append(',')
            sb.append(csvEscape(catName)).append(',')
            sb.append(csvEscape(remDate)).append(',')
            sb.append(csvEscape(remTime)).append(',')
            sb.append(csvEscape(repeatStr)).append(',')
            sb.append(if (t.pinned) "true" else "false").append(',')
            sb.append(if (t.strongAlert) "true" else "false").append(',')
            sb.appendLine(csvEscape(createdStr))
        }
        return sb.toString()
    }

    private fun csvEscape(raw: String): String {
        val needsQuotes = raw.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (!needsQuotes) raw else "\"${raw.replace("\"", "\"\"")}\""
    }

    // -------------------------------------------------------------------------
    // Import (auto-detect universal JSON / CSV / Markdown / plain text)
    // -------------------------------------------------------------------------

    fun parse(
        rawContent: String,
        existingTodoIds: Set<String>,
        defaultTimeZone: String = "Asia/Shanghai",
    ): Result<ImportPreview> = runCatching {
        val trimmed = rawContent.removePrefix("\uFEFF").trim()
        require(trimmed.isNotEmpty()) { "导入内容为空" }

        when {
            trimmed.startsWith("{") -> parseJsonObject(trimmed, existingTodoIds, defaultTimeZone)
            trimmed.startsWith("[") -> parseGenericJsonArray(trimmed, existingTodoIds, defaultTimeZone)
            else -> parseCsvOrPlainText(trimmed, existingTodoIds, defaultTimeZone)
        }
    }

    private fun parseJsonObject(
        rawJson: String,
        existingTodoIds: Set<String>,
        defaultTimeZone: String,
    ): ImportPreview {
        val root = prettyJson.parseToJsonElement(rawJson).jsonObject
        val payload = root["data"]?.asObjOrNull() ?: root

        // 1. Standard TodoData JSON ({ "todos": [...], "categories": [...] })
        if (payload["todos"] is JsonArray) {
            return parseStandardTodoDataJson(payload.toString(), existingTodoIds)
        }

        // 2. Entity-store task backup JSON (project / task / archiveYoung / archiveOld / taskRepeatCfg)
        if (payload.containsKey("task") || payload.containsKey("taskRepeatCfg") || payload.containsKey("archiveYoung")) {
            return parseEntityStoreJson(payload, existingTodoIds, defaultTimeZone)
        }

        // 3. Generic object wrapping a tasks/items array
        val listEl = payload["tasks"]?.asArrOrNull() ?: payload["items"]?.asArrOrNull()
        if (listEl != null) {
            return parseGenericJsonArray(listEl.toString(), existingTodoIds, defaultTimeZone)
        }

        return ImportPreview(
            sourceType = "JSON 文件",
            categories = emptyList(),
            activeTodos = emptyList(),
            archivedTodos = emptyList(),
            repeatTodos = emptyList(),
            skippedDuplicateCount = 0,
            warnings = listOf("未识别到待办字段，请确认文件为标准 JSON 待办备份、CSV 表格或文本清单。"),
        )
    }

    private fun parseStandardTodoDataJson(
        rawJson: String,
        existingTodoIds: Set<String>,
    ): ImportPreview {
        val parsed = prettyJson.decodeFromString<TodoData>(rawJson)
        val cats = parsed.activeCategories()
        val allTodos = parsed.activeTodos()
        val fresh = allTodos.filter { it.id !in existingTodoIds }
        val skipped = allTodos.size - fresh.size

        val repeatTodos = fresh.filter { !it.done && it.reminder != null && it.reminder.repeat.kind != RepeatKind.NONE }
        val repeatIds = repeatTodos.map { it.id }.toSet()
        val activeTodos = fresh.filter { !it.done && it.id !in repeatIds }
        val archivedTodos = fresh.filter { it.done }

        return ImportPreview(
            sourceType = "标准 JSON 备份",
            categories = cats,
            activeTodos = activeTodos,
            archivedTodos = archivedTodos,
            repeatTodos = repeatTodos,
            skippedDuplicateCount = skipped,
        )
    }

    private fun parseGenericJsonArray(
        rawJson: String,
        existingTodoIds: Set<String>,
        defaultTimeZone: String,
    ): ImportPreview {
        val arr = prettyJson.parseToJsonElement(rawJson).jsonArray
        val now = System.currentTimeMillis()
        val catByName = linkedMapOf<String, Category>()
        val todos = mutableListOf<Todo>()
        var skipped = 0

        arr.forEachIndexed { idx, el ->
            val obj = el.asObjOrNull() ?: return@forEachIndexed
            val title = (obj.str("text") ?: obj.str("title") ?: obj.str("name"))?.trim().orEmpty()
            if (title.isEmpty()) return@forEachIndexed

            val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: "imp_json_${title.hashCode().toUInt()}_$idx"
            if (id in existingTodoIds) {
                skipped++
                return@forEachIndexed
            }

            val done = obj.bool("done") ?: obj.bool("completed") ?: obj.bool("isDone") ?: false
            val catName = (obj.str("category") ?: obj.str("project"))?.trim().orEmpty()
            val catId = if (catName.isNotEmpty()) {
                catByName.getOrPut(catName) {
                    Category(id = "imp_cat_${catName.hashCode().toUInt()}", name = catName, updatedAt = now)
                }.id
            } else {
                null
            }

            val remDate = obj.str("reminder_date") ?: obj.str("date")
            val remTime = obj.str("reminder_time") ?: obj.str("time")
            val repeatKind = parseRepeatKind(obj.str("repeat"))
            val reminder = buildImportedReminder(remDate, remTime, repeatKind, defaultTimeZone, done)

            todos += Todo(
                id = id,
                text = title,
                done = done,
                createdAt = obj.lng("createdAt") ?: (now - idx * 1000L),
                doneAt = if (done) now else null,
                categoryId = catId,
                reminder = reminder,
                updatedAt = now,
                pinned = obj.bool("pinned") ?: false,
                strongAlert = obj.bool("strongAlert") ?: obj.bool("strong_alert") ?: false,
            )
        }

        val repeatTodos = todos.filter { !it.done && it.reminder != null && it.reminder.repeat.kind != RepeatKind.NONE }
        val repeatIds = repeatTodos.map { it.id }.toSet()
        return ImportPreview(
            sourceType = "通用 JSON 列表",
            categories = catByName.values.toList(),
            activeTodos = todos.filter { !it.done && it.id !in repeatIds },
            archivedTodos = todos.filter { it.done },
            repeatTodos = repeatTodos,
            skippedDuplicateCount = skipped,
        )
    }

    private fun parseEntityStoreJson(
        payload: JsonObject,
        existingTodoIds: Set<String>,
        defaultTimeZone: String,
    ): ImportPreview {
        val warnings = mutableListOf<String>()
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

                val parentTitle = obj.str("parentId")?.let { titleById[it] }
                val finalText = if (!parentTitle.isNullOrBlank()) "[$parentTitle] $rawTitle" else rawTitle

                val isDone = obj.bool("isDone") ?: archived
                val created = obj.lng("created") ?: System.currentTimeMillis()
                val doneOn = obj.lng("doneOn") ?: if (isDone) created else null
                val categoryId = obj.str("projectId")?.let { projectMap[it]?.id }

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
                        val rem = Reminder(date = dueDay, time = "09:00", timeZone = defaultTimeZone, repeat = Repeat())
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

        val activeTodosAll = extractTasks(payload["task"]?.asObjOrNull()?.get("entities")?.asObjOrNull(), false)
        val youngEntities = payload["archiveYoung"]?.asObjOrNull()?.get("task")?.asObjOrNull()?.get("entities")?.asObjOrNull()
        val oldEntities = payload["archiveOld"]?.asObjOrNull()?.get("task")?.asObjOrNull()?.get("entities")?.asObjOrNull()
        val legacyArchive = payload["taskArchive"]?.asObjOrNull()?.get("entities")?.asObjOrNull()
        val archivedTodosAll = (
            extractTasks(youngEntities, true) +
                extractTasks(oldEntities, true) +
                extractTasks(legacyArchive, true)
            ).distinctBy { it.id }

        val repeatTodosAll = mutableListOf<Todo>()
        payload["taskRepeatCfg"]?.asObjOrNull()?.get("entities")?.asObjOrNull()?.forEach { (id, el) ->
            val obj = el.asObjOrNull() ?: return@forEach
            if (obj.bool("isPaused") == true) return@forEach
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

            val baseRem = Reminder(date = startDate, time = startTime, timeZone = defaultTimeZone, repeat = repeat)
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
            warnings += "未检测到可导入的任务条目，请确认 JSON 备份文件有效。"
        }

        return ImportPreview(
            sourceType = "任务备份 JSON",
            categories = projectMap.values.toList(),
            activeTodos = filteredActive,
            archivedTodos = filteredArchived,
            repeatTodos = filteredRepeat,
            skippedDuplicateCount = skipped,
            warnings = warnings,
        )
    }

    private fun parseCsvOrPlainText(
        rawText: String,
        existingTodoIds: Set<String>,
        defaultTimeZone: String,
    ): ImportPreview {
        val rows = parseCsvRows(rawText)
        val firstRow = rows.firstOrNull().orEmpty()
        val looksLikeCsv = rows.size >= 2 && firstRow.size >= 2

        if (looksLikeCsv) {
            val headerNorm = firstRow.map { it.trim().lowercase() }
            val titleIdx = headerNorm.indexOfFirst { it in setOf("title", "text", "task", "name", "内容", "待办", "标题") }
            if (titleIdx >= 0) {
                val doneIdx = headerNorm.indexOfFirst { it in setOf("done", "completed", "isdone", "status", "完成", "状态") }
                val catIdx = headerNorm.indexOfFirst { it in setOf("category", "project", "分类", "分组") }
                val dateIdx = headerNorm.indexOfFirst { it in setOf("reminder_date", "date", "due_date", "日期", "提醒日期") }
                val timeIdx = headerNorm.indexOfFirst { it in setOf("reminder_time", "time", "时间", "提醒时间") }
                val repeatIdx = headerNorm.indexOfFirst { it in setOf("repeat", "重复") }
                val pinIdx = headerNorm.indexOfFirst { it in setOf("pinned", "pin", "置顶") }
                val strongIdx = headerNorm.indexOfFirst { it in setOf("strong_alert", "strongalert", "强提醒") }

                val now = System.currentTimeMillis()
                val catByName = linkedMapOf<String, Category>()
                val todos = mutableListOf<Todo>()
                var skipped = 0

                rows.drop(1).forEachIndexed { idx, cols ->
                    val title = cols.getOrNull(titleIdx)?.trim().orEmpty()
                    if (title.isEmpty()) return@forEachIndexed

                    val stableId = "imp_csv_${title.hashCode().toUInt()}_${cols.getOrNull(dateIdx).orEmpty()}"
                    if (stableId in existingTodoIds) {
                        skipped++
                        return@forEachIndexed
                    }

                    val doneRaw = cols.getOrNull(doneIdx)?.trim()?.lowercase().orEmpty()
                    val isDone = doneRaw in setOf("true", "1", "yes", "done", "已完成", "是")

                    val catName = cols.getOrNull(catIdx)?.trim().orEmpty()
                    val catId = if (catName.isNotEmpty()) {
                        catByName.getOrPut(catName) {
                            Category(id = "imp_cat_${catName.hashCode().toUInt()}", name = catName, updatedAt = now)
                        }.id
                    } else {
                        null
                    }

                    val remDate = cols.getOrNull(dateIdx)?.trim()
                    val remTime = cols.getOrNull(timeIdx)?.trim()
                    val repeatKind = parseRepeatKind(cols.getOrNull(repeatIdx))
                    val reminder = buildImportedReminder(remDate, remTime, repeatKind, defaultTimeZone, isDone)
                    val pinned = cols.getOrNull(pinIdx)?.trim()?.lowercase() in setOf("true", "1", "yes", "是")
                    val strong = cols.getOrNull(strongIdx)?.trim()?.lowercase() in setOf("true", "1", "yes", "是")

                    todos += Todo(
                        id = stableId,
                        text = title,
                        done = isDone,
                        createdAt = now - idx * 1000L,
                        doneAt = if (isDone) now - idx * 1000L else null,
                        categoryId = catId,
                        reminder = reminder,
                        updatedAt = now,
                        pinned = pinned,
                        strongAlert = strong,
                    )
                }

                val repeatTodos = todos.filter { !it.done && it.reminder != null && it.reminder.repeat.kind != RepeatKind.NONE }
                val repeatIds = repeatTodos.map { it.id }.toSet()
                return ImportPreview(
                    sourceType = "标准 CSV 表格",
                    categories = catByName.values.toList(),
                    activeTodos = todos.filter { !it.done && it.id !in repeatIds },
                    archivedTodos = todos.filter { it.done },
                    repeatTodos = repeatTodos,
                    skippedDuplicateCount = skipped,
                )
            }
        }

        // Generic CSV log or Markdown / plain-text checklist
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val isGenericCsv = lines.size > 1 && lines.first().contains(",")
        val dataLines = if (isGenericCsv && (lines[0].contains("title", true) || lines[0].contains("date", true))) {
            lines.drop(1)
        } else {
            lines
        }

        val now = System.currentTimeMillis()
        val todos = mutableListOf<Todo>()
        var skipped = 0

        dataLines.forEachIndexed { idx, line ->
            val cleaned = if (isGenericCsv) {
                parseCsvRows(line).firstOrNull().orEmpty()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.matches(Regex("[\\d:./\\-\\s]+")) }
                    .maxByOrNull { it.length }
                    .orEmpty()
            } else {
                line.removePrefix("- ").removePrefix("* ").removePrefix("[x] ").removePrefix("[X] ").removePrefix("[ ] ").trim()
            }

            if (cleaned.isNotEmpty()) {
                val stableId = "imp_txt_${cleaned.hashCode().toUInt()}"
                if (stableId in existingTodoIds) {
                    skipped++
                } else {
                    val isDone = line.startsWith("- [x]", true) || line.startsWith("[x]", true) || isGenericCsv
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
            sourceType = if (isGenericCsv) "通用 CSV 日志" else "纯文本 / Markdown 清单",
            categories = emptyList(),
            activeTodos = todos.filter { !it.done },
            archivedTodos = todos.filter { it.done },
            repeatTodos = emptyList(),
            skippedDuplicateCount = skipped,
        )
    }

    private fun parseRepeatKind(raw: String?): RepeatKind = when (raw?.trim()?.lowercase()) {
        "daily", "每天" -> RepeatKind.DAILY
        "weekly", "每周" -> RepeatKind.WEEKLY
        "monthly", "每月" -> RepeatKind.MONTHLY
        "yearly", "每年" -> RepeatKind.YEARLY
        else -> RepeatKind.NONE
    }

    private fun buildImportedReminder(
        dateStr: String?,
        timeStr: String?,
        repeatKind: RepeatKind,
        defaultTimeZone: String,
        isDone: Boolean,
    ): Reminder? {
        val d = dateStr?.trim()?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: return null
        val t = timeStr?.trim()?.takeIf { it.matches(Regex("\\d{2}:\\d{2}")) } ?: "09:00"
        val base = Reminder(
            date = d,
            time = t,
            timeZone = defaultTimeZone,
            repeat = Repeat(kind = repeatKind, interval = 1),
            nextAt = null,
        )
        val next = if (!isDone) ReminderCalculator.nextOccurrence(base, System.currentTimeMillis() - 1) else null
        return base.copy(nextAt = next)
    }

    /** Proper RFC 4180 CSV parser supporting quoted commas, escaped quotes, and multiline values. */
    private fun parseCsvRows(input: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < input.length && input[i + 1] == '"') {
                            field.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(c)
                    }
                }
                c == '"' -> inQuotes = true
                c == ',' -> {
                    currentRow += field.toString()
                    field.setLength(0)
                }
                c == '\r' -> Unit
                c == '\n' -> {
                    currentRow += field.toString()
                    field.setLength(0)
                    if (currentRow.any { it.isNotBlank() }) rows += currentRow.toList()
                    currentRow.clear()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow += field.toString()
            if (currentRow.any { it.isNotBlank() }) rows += currentRow.toList()
        }
        return rows
    }

    private fun JsonElement.asObjOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()
    private fun JsonElement.asArrOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()
    private fun JsonObject.str(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    private fun JsonObject.lng(key: String): Long? = this[key]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
    private fun JsonObject.int(key: String): Int? = this[key]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
    private fun JsonObject.bool(key: String): Boolean? = this[key]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
}
