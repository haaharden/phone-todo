package com.self.phonetodo.sync

import com.self.phonetodo.model.Category
import com.self.phonetodo.model.DATA_VERSION
import com.self.phonetodo.model.Todo
import com.self.phonetodo.model.TodoData

/**
 * Deterministic per-item LWW + deletedAt tombstone merger with 30-day tombstone pruning.
 * Matches DeskTodo's v3 sync semantics so Phone ToDo and DeskTodo stay cleanly in sync without file bloat.
 */
object SyncMerger {

    const val TOMBSTONE_TTL_MS = 30L * 86_400_000L

    fun merge(
        local: TodoData,
        remote: TodoData,
        nowMs: Long = System.currentTimeMillis(),
    ): TodoData {
        val cutoff = nowMs - TOMBSTONE_TTL_MS
        val mergedCategories = mergeCategories(local.categories, remote.categories)
            .filter { it.deletedAt == null || it.deletedAt >= cutoff }
        val mergedTodos = mergeTodos(local.todos, remote.todos)
            .filter { it.deletedAt == null || it.deletedAt >= cutoff }
        return TodoData(
            version = DATA_VERSION,
            categories = mergedCategories,
            inboxCollapsed = local.inboxCollapsed,
            todos = mergedTodos,
        )
    }

    private fun mergeCategories(local: List<Category>, remote: List<Category>): List<Category> {
        val byId = LinkedHashMap<String, Category>()
        for (c in local) byId[c.id] = c
        for (rc in remote) {
            val lc = byId[rc.id]
            if (lc == null) {
                byId[rc.id] = rc
            } else {
                val lTime = maxOf(lc.updatedAt, lc.deletedAt ?: 0L)
                val rTime = maxOf(rc.updatedAt, rc.deletedAt ?: 0L)
                byId[rc.id] = if (rTime > lTime) {
                    rc.copy(collapsed = lc.collapsed)
                } else {
                    lc
                }
            }
        }
        return byId.values.toList()
    }

    private fun mergeTodos(local: List<Todo>, remote: List<Todo>): List<Todo> {
        val byId = LinkedHashMap<String, Todo>()
        for (t in local) byId[t.id] = t
        for (rt in remote) {
            val lt = byId[rt.id]
            if (lt == null) {
                byId[rt.id] = rt
            } else {
                val lTime = maxOf(lt.updatedAt, lt.deletedAt ?: 0L, lt.doneAt ?: 0L, lt.createdAt)
                val rTime = maxOf(rt.updatedAt, rt.deletedAt ?: 0L, rt.doneAt ?: 0L, rt.createdAt)
                byId[rt.id] = if (rTime > lTime) rt else lt
            }
        }
        return byId.values.toList()
    }
}
