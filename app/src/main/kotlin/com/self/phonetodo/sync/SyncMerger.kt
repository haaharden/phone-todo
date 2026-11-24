package com.self.phonetodo.sync

import com.self.phonetodo.model.Category
import com.self.phonetodo.model.DATA_VERSION
import com.self.phonetodo.model.Todo
import com.self.phonetodo.model.TodoData

/**
 * Deterministic per-item LWW + deletedAt tombstone merger.
 * Matches DeskTodo's v3 sync semantics so Phone ToDo and DeskTodo never overwrite each other.
 */
object SyncMerger {

    fun merge(local: TodoData, remote: TodoData): TodoData {
        val mergedCategories = mergeCategories(local.categories, remote.categories)
        val mergedTodos = mergeTodos(local.todos, remote.todos)
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
        // Keep newest created items near the top while preserving local order for ties
        return byId.values.toList()
    }
}
