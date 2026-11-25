package com.self.phonetodo.data

import android.content.Context
import com.self.phonetodo.model.Category
import com.self.phonetodo.model.DATA_VERSION
import com.self.phonetodo.model.Reminder
import com.self.phonetodo.model.RepeatKind
import com.self.phonetodo.model.Todo
import com.self.phonetodo.model.TodoData
import com.self.phonetodo.reminder.ReminderCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class TodoRepository(private val context: Context) {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val lock = Any()
    private val dataDir = File(context.filesDir, "phone-todo-data").apply { mkdirs() }
    private val dataFile = File(dataDir, "data.json")
    private val backupDir = File(dataDir, "backups").apply { mkdirs() }

    private val _data = MutableStateFlow(loadInitial())
    val data: StateFlow<TodoData> = _data.asStateFlow()

    @Volatile
    var onDataMutated: ((TodoData, Boolean) -> Unit)? = null

    init {
        createRollingBackup()
    }

    private fun loadInitial(): TodoData {
        if (!dataFile.exists()) return TodoData(version = DATA_VERSION)
        return runCatching {
            val raw = dataFile.readText(Charsets.UTF_8)
            val parsed = json.decodeFromString<TodoData>(raw)
            // Backfill updatedAt if loaded from DeskTodo v2
            parsed.copy(
                version = DATA_VERSION,
                categories = parsed.categories.map { c ->
                    if (c.updatedAt == 0L) c.copy(updatedAt = System.currentTimeMillis()) else c
                },
                todos = parsed.todos.map { t ->
                    if (t.updatedAt == 0L) t.copy(updatedAt = t.doneAt ?: t.createdAt) else t
                },
            )
        }.getOrElse { TodoData(version = DATA_VERSION) }
    }

    private fun createRollingBackup() {
        runCatching {
            if (!dataFile.exists()) return
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            dataFile.copyTo(File(backupDir, "data_$ts.json"), overwrite = true)
            val files = backupDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            files.drop(10).forEach { it.delete() }
        }
    }

    private fun saveLocked(next: TodoData, triggerSync: Boolean) {
        val normalized = next.copy(version = DATA_VERSION)
        val tmp = File(dataDir, "data.json.tmp")
        tmp.writeText(json.encodeToString(TodoData.serializer(), normalized), Charsets.UTF_8)
        if (!tmp.renameTo(dataFile)) {
            dataFile.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmp.delete()
        }
        _data.value = normalized
        onDataMutated?.invoke(normalized, triggerSync)
    }

    fun updateData(triggerSync: Boolean = true, transform: (TodoData) -> TodoData): TodoData {
        synchronized(lock) {
            val updated = transform(_data.value)
            saveLocked(updated, triggerSync)
            return updated
        }
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

    fun toggleTodoDone(todoId: String): Todo? {
        val previous = _data.value.activeTodos().firstOrNull { it.id == todoId } ?: return null
        val now = System.currentTimeMillis()
        updateData { current ->
            current.copy(
                todos = current.todos.map { todo ->
                    if (todo.id != todoId) return@map todo
                    val nextDone = !todo.done
                    val updatedReminder = todo.reminder?.let { rem ->
                        when {
                            nextDone -> rem.copy(lastFiredAt = null)
                            rem.nextAt == null && rem.repeat.kind != RepeatKind.NONE ->
                                rem.copy(nextAt = ReminderCalculator.nextOccurrence(rem, now))
                            else -> rem
                        }
                    }
                    todo.copy(
                        done = nextDone,
                        doneAt = if (nextDone) now else null,
                        reminder = updatedReminder,
                        updatedAt = now,
                    )
                },
            )
        }
        return previous
    }

    fun deleteTodo(todoId: String): Todo? {
        val previous = _data.value.activeTodos().firstOrNull { it.id == todoId } ?: return null
        val now = System.currentTimeMillis()
        updateData { current ->
            current.copy(
                todos = current.todos.map { todo ->
                    if (todo.id == todoId) todo.copy(deletedAt = now, updatedAt = now) else todo
                },
            )
        }
        return previous
    }

    /** Lossless restore for 3-second Undo (completion or deletion). */
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

    fun replaceFromSync(merged: TodoData) {
        updateData(triggerSync = false) { merged }
    }

    fun topBannerTodos(maxCount: Int): List<Todo> {
        val active = _data.value.activeTodos().filter { !it.done }
        return ReminderCalculator.sortPendingTodos(active).take(maxCount.coerceAtLeast(1))
    }

    fun pendingCount(): Int = _data.value.activeTodos().count { !it.done }

    fun exportJson(): String = json.encodeToString(TodoData.serializer(), _data.value)
}
