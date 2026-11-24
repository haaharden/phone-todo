package com.self.phonetodo.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.self.phonetodo.model.UsageSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class UsageDatabase(context: Context) : SQLiteOpenHelper(context, "phone_todo_usage.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE usage_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date_key TEXT NOT NULL,
                start_at INTEGER NOT NULL,
                end_at INTEGER,
                counted_as_unlock INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_usage_date_key ON usage_sessions(date_key)")
        db.execSQL("CREATE INDEX idx_usage_open ON usage_sessions(end_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun dateKeyOf(epochMs: Long): String {
        val d = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
        return d.toString()
    }

    /**
     * Records a phone unlock / active session start.
     * Closes any stale open session first so durations never double-count.
     */
    fun startSession(now: Long = System.currentTimeMillis(), countedAsUnlock: Boolean = true): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // If there is already an open session very recently (< 1500ms), avoid duplicate unlock count
            db.rawQuery(
                "SELECT id, start_at FROM usage_sessions WHERE end_at IS NULL ORDER BY id DESC LIMIT 1",
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val existingStart = cursor.getLong(1)
                    if (now - existingStart < 1_500L) {
                        db.setTransactionSuccessful()
                        return cursor.getLong(0)
                    }
                }
            }
            closeOpenSessionsLocked(db, now)

            val cv = ContentValues().apply {
                put("date_key", dateKeyOf(now))
                put("start_at", now)
                putNull("end_at")
                put("counted_as_unlock", if (countedAsUnlock) 1 else 0)
            }
            val id = db.insert("usage_sessions", null, cv)
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Closes active session(s) when screen turns off, splitting cleanly across midnight boundaries.
     */
    fun endActiveSession(now: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            closeOpenSessionsLocked(db, now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun closeOpenSessionsLocked(db: SQLiteDatabase, endTime: Long) {
        val zone = ZoneId.systemDefault()
        val openList = mutableListOf<UsageSession>()
        db.rawQuery(
            "SELECT id, date_key, start_at, counted_as_unlock FROM usage_sessions WHERE end_at IS NULL",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                openList += UsageSession(
                    id = cursor.getLong(0),
                    dateKey = cursor.getString(1),
                    startAt = cursor.getLong(2),
                    endAt = null,
                    countedAsUnlock = cursor.getInt(3) == 1,
                )
            }
        }

        for (session in openList) {
            // Clamp runaway unclosed sessions (e.g., force-kill/reboot) to max 8 hours
            val safeEnd = endTime.coerceIn(session.startAt, session.startAt + 8L * 3_600_000L)
            val startDate = Instant.ofEpochMilli(session.startAt).atZone(zone).toLocalDate()
            val endDate = Instant.ofEpochMilli(safeEnd).atZone(zone).toLocalDate()

            if (startDate == endDate) {
                val cv = ContentValues().apply { put("end_at", safeEnd) }
                db.update("usage_sessions", cv, "id = ?", arrayOf(session.id.toString()))
            } else {
                // Split cleanly across midnight so each day gets exact duration
                var curDate = startDate
                var first = true
                while (!curDate.isAfter(endDate)) {
                    val segStart = if (first) session.startAt else curDate.atStartOfDay(zone).toInstant().toEpochMilli()
                    val nextMidnight = curDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val segEnd = if (curDate == endDate) safeEnd else nextMidnight
                    if (first) {
                        val cv = ContentValues().apply { put("end_at", segEnd) }
                        db.update("usage_sessions", cv, "id = ?", arrayOf(session.id.toString()))
                    } else {
                        val cv = ContentValues().apply {
                            put("date_key", curDate.toString())
                            put("start_at", segStart)
                            put("end_at", segEnd)
                            put("counted_as_unlock", 0) // continuation from midnight, not a new unlock
                        }
                        db.insert("usage_sessions", null, cv)
                    }
                    first = false
                    curDate = curDate.plusDays(1)
                }
            }
        }
    }

    fun getSessionsForDate(dateKey: String): List<UsageSession> {
        val result = mutableListOf<UsageSession>()
        readableDatabase.rawQuery(
            "SELECT id, date_key, start_at, end_at, counted_as_unlock FROM usage_sessions WHERE date_key = ? ORDER BY start_at ASC",
            arrayOf(dateKey),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val endAt = if (cursor.isNull(3)) null else cursor.getLong(3)
                result += UsageSession(
                    id = cursor.getLong(0),
                    dateKey = cursor.getString(1),
                    startAt = cursor.getLong(2),
                    endAt = endAt,
                    countedAsUnlock = cursor.getInt(4) == 1,
                )
            }
        }
        return result
    }

    fun getSessionsSince(startDate: LocalDate): List<UsageSession> {
        val result = mutableListOf<UsageSession>()
        readableDatabase.rawQuery(
            "SELECT id, date_key, start_at, end_at, counted_as_unlock FROM usage_sessions WHERE date_key >= ? ORDER BY start_at ASC",
            arrayOf(startDate.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val endAt = if (cursor.isNull(3)) null else cursor.getLong(3)
                result += UsageSession(
                    id = cursor.getLong(0),
                    dateKey = cursor.getString(1),
                    startAt = cursor.getLong(2),
                    endAt = endAt,
                    countedAsUnlock = cursor.getInt(4) == 1,
                )
            }
        }
        return result
    }
}
