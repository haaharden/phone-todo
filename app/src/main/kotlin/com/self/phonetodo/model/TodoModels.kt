package com.self.phonetodo.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val DATA_VERSION = 3

@Serializable
enum class RepeatKind {
    @SerialName("none") NONE,
    @SerialName("daily") DAILY,
    @SerialName("weekly") WEEKLY,
    @SerialName("monthly") MONTHLY,
    @SerialName("yearly") YEARLY,
}

@Serializable
data class Repeat(
    val kind: RepeatKind = RepeatKind.NONE,
    val interval: Int = 1,
    /** 0 = Sunday, 1 = Monday ... 6 = Saturday (matches DeskTodo exactly) */
    val weekdays: List<Int> = emptyList(),
)

@Serializable
data class Reminder(
    /** "YYYY-MM-DD" in [timeZone] */
    val date: String,
    /** "HH:MM" in [timeZone] */
    val time: String,
    /** IANA timezone ID, e.g. "Asia/Shanghai" */
    val timeZone: String = "Asia/Shanghai",
    val repeat: Repeat = Repeat(),
    /** Next trigger time in Unix epoch millis; null if completed/none */
    val nextAt: Long? = null,
)

@Serializable
data class Category(
    val id: String,
    val name: String,
    val collapsed: Boolean = false,
    val updatedAt: Long = 0L,
    val deletedAt: Long? = null,
)

@Serializable
data class Todo(
    val id: String,
    val text: String,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val doneAt: Long? = null,
    val categoryId: String? = null,
    val reminder: Reminder? = null,
    val updatedAt: Long = createdAt,
    val deletedAt: Long? = null,
    /** Mobile strong full-screen alarm toggle; preserved harmlessly by DeskTodo */
    val strongAlert: Boolean = false,
    /** Pin to top of unlock banner & list */
    val pinned: Boolean = false,
)

@Serializable
data class TodoData(
    val version: Int = DATA_VERSION,
    val categories: List<Category> = emptyList(),
    val inboxCollapsed: Boolean = false,
    val todos: List<Todo> = emptyList(),
) {
    fun activeCategories(): List<Category> = categories.filter { it.deletedAt == null }
    fun activeTodos(): List<Todo> = todos.filter { it.deletedAt == null }
}
