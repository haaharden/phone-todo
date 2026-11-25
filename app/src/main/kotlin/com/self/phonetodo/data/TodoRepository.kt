package com.self.phonetodo.data

import android.content.Context
import com.self.phonetodo.model.Category
import com.self.phonetodo.model.DATA_VERSION
import com.self.phonetodo.model.Reminder
import com.self.phonetodo.model.RepeatKind
import com.self.phonetodo.model.Todo
import com.self.phonetodo.model.TodoData
import com.self.phonetodo.reminder.ReminderCalculator
import com.self.phonetodo.sync.GithubSyncManager
import com.self.phonetodo.sync.SyncMerger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class TodoToggleResult(
    val previous: Todo,
    /** Non-null when checking a recurring todo advanced it to the next cycle instead of permanently finishing it. */
    val advancedNextAt: Long? = null,
)

class TodoRepository internal constructor(private val dataDir: File) {

    constructor(context: Context) : this(File(context.filesDir, "phone-todo-data"))

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val lock = Any()
    private val dataFile = File(dataDir.apply { mkdirs() }, "data.json")
    private val backupDir = File(dataDir, "backups").apply { mkdirs() }

    private val _recoveryNotice = MutableStateFlow<String?>(null)
    val recoveryNotice: StateFlow<String?> = _recoveryNotice.asStateFlow()

    private val _data = MutableStateFlow(loadInitial())
    val data: StateFlow<TodoData> = _data.asStateFlow()

    @Volatile
    var onDataMutated: ((TodoData, Boolean) -> Unit)? = null

    fun consumeRecoveryNotice() {
        _recoveryNotice.value = null
    }

    private fun parseTodoDataFile(file: File): TodoData {
        val raw = file.readText(Charsets.UTF_8)
        val parsed = json.decodeFromString<TodoData>(raw)
        // Backfill updatedAt if loaded from DeskTodo v2
        return parsed.copy(
            version = DATA_VERSION,
            categories = parsed.categories.map { c ->
                if (c.updatedAt == 0L) c.copy(updatedAt = System.currentTimeMillis()) else c
            },
            todos = parsed.todos.map { t ->
                if (t.updatedAt == 0L) t.copy(updatedAt = t.doneAt ?: t.createdAt) else t
            },
        )
    }

    private fun loadInitial(): TodoData {
        if (!dataFile.exists()) return TodoData(version = DATA_VERSION)

        val primary = runCatching { parseTodoDataFile(dataFile) }
        if (primary.isSuccess) {
            createRollingBackup()
            return primary.getOrThrow()
        }

        // Primary data.json is corrupted: preserve it and try recovering newest-to-oldest from rolling backups
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val corruptFile = File(dataDir, "data.json.corrupt_$stamp")
        runCatching {
            if (!dataFile.renameTo(corruptFile)) {
                dataFile.copyTo(corruptFile, overwrite = true)
                dataFile.delete()
            }
        }

        val backups = backupDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("data_") && it.name.endsWith(".json") }
            ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .orEmpty()

        for (backup in backups) {
            val recovered = runCatching { parseTodoDataFile(backup) }.getOrNull() ?: continue
            runCatching { writeAtomic(recovered) }
            val label = formatBackupLabel(backup)
            val count = recovered.activeTodos().size
            _recoveryNotice.value = "检测到本地数据损坏，已自动从备份（$label）恢复 $count 条待办"
            return recovered
        }

        _recoveryNotice.value = "检测到本地数据损坏且无可用备份，坏文件已保留为 ${corruptFile.name}"
        return TodoData(version = DATA_VERSION)
    }

    private fun formatBackupLabel(backup: File): String {
        val rawTs = backup.name.removePrefix("data_").removeSuffix(".json")
        return runCatching {
            val parsed = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(rawTs)
            if (parsed != null) {
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(parsed)
            } else {
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(backup.lastModified()))
            }
        }.getOrElse {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(backup.lastModified()))
        }
    }

    private fun createRollingBackup() {
        runCatching {
            if (!dataFile.exists()) return
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            dataFile.copyTo(File(backupDir, "data_$ts.json"), overwrite = true)
            val files = backupDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("data_") && it.name.endsWith(".json") }
                ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
                ?: return
            files.drop(10).forEach { it.delete() }
        }
    }

    private fun writeAtomic(normalized: TodoData) {
        val tmp = File(dataDir, "data.json.tmp")
        tmp.writeText(json.encodeToString(TodoData.serializer(), normalized), Charsets.UTF_8)
        if (!tmp.renameTo(dataFile)) {
            dataFile.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmp.delete()
        }
    }

    /**
     * Atomically transforms & persists TodoData under [lock], then invokes [onDataMutated]
     * strictly AFTER releasing [lock] to prevent AB-BA deadlocks with ReminderScheduler.
     */
    fun updateData(triggerSync: Boolean = true, transform: (TodoData) -> TodoData): TodoData {
        val normalized = synchronized(lock) {
            val updated = transform(_data.value).copy(version = DATA_VERSION)
            writeAtomic(updated)
            _data.value = updated
            updated
        }
        onDataMutated?.invoke(normalized, triggerSync)
        return normalized
    }

    /**
     * Atomically merges remote/sync data against the LATEST local snapshot under [lock],
     * preventing lost updates when the user adds or edits todos while a sync HTTP call is in flight.
     */
    fun mergeFromSync(incoming: TodoData, nowMs: Long = System.currentTimeMillis()): TodoData {
        var changed = false
        val merged = synchronized(lock) {
            val current = _data.value
            val result = SyncMerger.merge(current, incoming, nowMs).copy(version = DATA_VERSION)
            if (!GithubSyncManager.isSemanticallyEqual(current, result)) {
                writeAtomic(result)
                _data.value = result
                changed = true
            }
            result
        }
        if (changed) {
            onDataMutated?.invoke(merged, false)
        }
        return merged
    }

    fun addTodo(
        text: String,
        categoryId: String? = null,
        reminder: Reminder? = null,
        strongAlert: Boolean = false,
        pinned: Boolean = false,
    ): Todo? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val now = System.currentTimeMillis()
        val resolvedReminder = reminder?.let { rem ->
            val next = rem.nextAt ?: ReminderCalculator.nextOccurrence(rem, now - 1)
            rem.copy(nextAt = next)
        }
        val todo = Todo(
            id = UUID.randomUUID().toString(),
            text = trimmed,
            done = false,
            createdAt = now,
            doneAt = null,
            categoryId = categoryId,
            reminder = resolvedReminder,
            updatedAt = now,
            deletedAt = null,
            strongAlert = strongAlert,
            pinned = pinned,
        )
        updateData { current ->
            current.copy(todos = listOf(todo) + current.todos)
        }
        return todo
    }

    fun updateTodo(todo: Todo) {
        val now = System.currentTimeMillis()
        updateData { current ->
            current.copy(
                todos = current.todos.map { existing ->
                    if (existing.id == todo.id) todo.copy(updatedAt = now) else existing
                },
            )
        }
    }

    /**
     * Completes or unchecks a todo:
     * - For an active recurring todo (`!done && repeat != NONE`), completes the CURRENT cycle and advances
     *   `nextAt` to the following occurrence while clearing `lastFiredAt` (keeping the habit alive!).
     * - For one-off todos (or unchecking a done todo), flips `done` normally.
     */
    fun toggleTodoDone(todoId: String, now: Long = System.currentTimeMillis()): TodoToggleResult? {
        var result: TodoToggleResult? = null
        val mutated = synchronized(lock) {
            val current = _data.value
            val target = current.activeTodos().firstOrNull { it.id == todoId } ?: return null
            val rem = target.reminder
            val isRecurringAdvance = !target.done && rem != null && rem.repeat.kind != RepeatKind.NONE
            val advancedNext = if (isRecurringAdvance) {
                ReminderCalculator.nextOccurrenceAfterComplete(rem!!, now)
            } else {
                null
            }

            val updatedTodos = current.todos.map { todo ->
                if (todo.id != todoId) return@map todo
                if (isRecurringAdvance && advancedNext != null) {
                    todo.copy(
                        done = false,
                        doneAt = null,
                        reminder = rem!!.copy(nextAt = advancedNext, lastFiredAt = null),
                        updatedAt = now,
                    )
                } else {
                    val nextDone = !todo.done
                    val updatedReminder = todo.reminder?.let { r ->
                        when {
                            nextDone -> r.copy(lastFiredAt = null)
                            r.nextAt == null && r.repeat.kind != RepeatKind.NONE ->
                                r.copy(nextAt = ReminderCalculator.nextOccurrence(r, now))
                            else -> r
                        }
                    }
                    todo.copy(
                        done = nextDone,
                        doneAt = if (nextDone) now else null,
                        reminder = updatedReminder,
                        updatedAt = now,
                    )
                }
            }
            val nextData = current.copy(version = DATA_VERSION, todos = updatedTodos)
            writeAtomic(nextData)
            _data.value = nextData
            result = TodoToggleResult(previous = target, advancedNextAt = advancedNext)
            nextData
        }
        onDataMutated?.invoke(mutated, true)
        return result
    }

    /** Permanently marks a todo (including recurring todos) as done, ending future repeats. */
    fun finishTodoPermanently(todoId: String, now: Long = System.currentTimeMillis()): Todo? {
        var previous: Todo? = null
        val mutated = synchronized(lock) {
            val current = _data.value
            val target = current.activeTodos().firstOrNull { it.id == todoId } ?: return null
            previous = target
            val nextData = current.copy(
                version = DATA_VERSION,
                todos = current.todos.map { todo ->
                    if (todo.id != todoId) return@map todo
                    todo.copy(
                        done = true,
                        doneAt = now,
                        reminder = todo.reminder?.copy(lastFiredAt = null),
                        updatedAt = now,
                    )
                },
            )
            writeAtomic(nextData)
            _data.value = nextData
            nextData
        }
        onDataMutated?.invoke(mutated, true)
        return previous
    }

    fun deleteTodo(todoId: String): Todo? {
        var previous: Todo? = null
        val now = System.currentTimeMillis()
        val mutated = synchronized(lock) {
            val current = _data.value
            val target = current.activeTodos().firstOrNull { it.id == todoId } ?: return null
            previous = target
            val nextData = current.copy(
                version = DATA_VERSION,
                todos = current.todos.map { todo ->
                    if (todo.id == todoId) todo.copy(deletedAt = now, updatedAt = now) else todo
                },
            )
            writeAtomic(nextData)
            _data.value = nextData
            nextData
        }
        onDataMutated?.invoke(mutated, true)
        return previous
    }

    /** Lossless restore for 3.5-second Undo (completion, recurring advance, or deletion). */
    fun restoreTodo(snapshot: Todo) {
        val now = System.currentTimeMillis()
        updateData { current ->
            val exists = current.todos.any { it.id == snapshot.id }
            val restored = snapshot.copy(deletedAt = null, updatedAt = now)
            current.copy(
                todos = if (exists) {
                    current.todos.map { if (it.id == snapshot.id) restored else it }
                } else {
                    listOf(restored) + current.todos
                },
            )
        }
    }

    fun advanceReminderAfterFired(todoId: String, firedAt: Long) {
        val now = System.currentTimeMillis()
        updateData { current ->
            current.copy(
                todos = current.todos.map { todo ->
                    if (todo.id != todoId) return@map todo
                    val rem = todo.reminder ?: return@map todo
                    val after = maxOf(firedAt, now)
                    val next = ReminderCalculator.nextOccurrence(rem, after)
                    todo.copy(
                        // Keep lastFiredAt so the todo stays right in place with a red timestamp until checked off
                        reminder = rem.copy(nextAt = next, lastFiredAt = firedAt),
                        updatedAt = now,
                    )
                },
            )
        }
    }

    fun snoozeReminder(todoId: String, minutes: Long) {
        val now = System.currentTimeMillis()
        val snoozedAt = now + minutes * 60_000L
        updateData { current ->
            current.copy(
                todos = current.todos.map { todo ->
                    if (todo.id != todoId) return@map todo
                    val rem = todo.reminder ?: ReminderCalculator.quickReminderAfterMinutes(
                        minutes,
                        ReminderCalculator.systemTimeZone(),
                    )
                    todo.copy(
                        reminder = rem.copy(nextAt = snoozedAt, lastFiredAt = null),
                        updatedAt = now,
                    )
                },
            )
        }
    }

    fun addCategory(name: String): Category? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val now = System.currentTimeMillis()
        val cat = Category(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            collapsed = false,
            updatedAt = now,
            deletedAt = null,
        )
        updateData { current ->
            current.copy(categories = current.categories + cat)
        }
        return cat
    }

    fun renameCategory(categoryId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        val now = System.currentTimeMillis()
        updateData { current ->
            current.copy(
                categories = current.categories.map { c ->
                    if (c.id == categoryId) c.copy(name = trimmed, updatedAt = now) else c
                },
            )
        }
    }

    fun toggleCategoryCollapsed(categoryId: String?) {
        val now = System.currentTimeMillis()
        updateData(triggerSync = false) { current ->
            if (categoryId == null) {
                current.copy(inboxCollapsed = !current.inboxCollapsed)
            } else {
                current.copy(
                    categories = current.categories.map { c ->
                        if (c.id == categoryId) c.copy(collapsed = !c.collapsed, updatedAt = now) else c
                    },
                )
            }
        }
    }

    fun deleteCategory(categoryId: String) {
        val now = System.currentTimeMillis()
        updateData { current ->
            current.copy(
                categories = current.categories.map { c ->
                    if (c.id == categoryId) c.copy(deletedAt = now, updatedAt = now) else c
                },
                todos = current.todos.map { t ->
                    if (t.categoryId == categoryId) t.copy(categoryId = null, updatedAt = now) else t
                },
            )
        }
    }

    fun bulkImport(newCategories: List<Category>, newTodos: List<Todo>): Pair<Int, Int> {
        var addedCats = 0
        var addedTodos = 0
        val now = System.currentTimeMillis()
        updateData { current ->
            val existingCatIds = current.categories.map { it.id }.toMutableSet()
            val existingCatByName = current.activeCategories().associateBy { it.name.lowercase() }
            val catIdRemap = mutableMapOf<String, String>()
            val catsToAdd = mutableListOf<Category>()

            for (cat in newCategories) {
                if (cat.id in existingCatIds) continue
                val sameName = existingCatByName[cat.name.lowercase()]
                if (sameName != null) {
                    catIdRemap[cat.id] = sameName.id
                } else {
                    catsToAdd += cat.copy(updatedAt = now, deletedAt = null)
                    existingCatIds += cat.id
                    addedCats++
                }
            }

            val existingTodoIds = current.todos.map { it.id }.toSet()
            val todosToAdd = newTodos.filter { it.id !in existingTodoIds }.map { t ->
                val remappedCat = t.categoryId?.let { catIdRemap[it] ?: it }
                t.copy(
                    categoryId = remappedCat,
                    updatedAt = now,
                    deletedAt = null,
                )
            }
            addedTodos = todosToAdd.size

            current.copy(
                categories = current.categories + catsToAdd,
                todos = todosToAdd + current.todos,
            )
        }
        return addedCats to addedTodos
    }

    fun topBannerTodos(maxCount: Int): List<Todo> {
        val active = _data.value.activeTodos().filter { !it.done }
        return ReminderCalculator.sortPendingTodos(active).take(maxCount.coerceAtLeast(1))
    }

    fun pendingCount(): Int = _data.value.activeTodos().count { !it.done }

    fun exportJson(): String = json.encodeToString(TodoData.serializer(), _data.value)
}
