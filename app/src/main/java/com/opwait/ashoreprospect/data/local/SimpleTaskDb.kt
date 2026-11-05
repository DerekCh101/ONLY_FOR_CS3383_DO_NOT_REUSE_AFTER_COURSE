package com.opwait.ashoreprospect.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant

class SimpleTaskDb(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "scheduler-cache.db",
    null,
    4,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE tasks (
                id TEXT PRIMARY KEY,
                title TEXT,
                duration REAL,
                state TEXT NOT NULL,
                placed_date TEXT,
                placed_time TEXT,
                deadline_date TEXT,
                deadline_time TEXT,
                starting_date TEXT,
                starting_time TEXT,
                lifecycle TEXT,
                workflow TEXT,
                placement_updated_at TEXT,
                last_updated_at TEXT,
                user_id TEXT,
                description TEXT,
                category TEXT,
                subcategory TEXT,
                description_done INTEGER,
                parent_id TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE blocked_times (
                id TEXT PRIMARY KEY,
                title TEXT,
                weekday INTEGER,
                specific_date TEXT,
                start_time TEXT,
                end_time TEXT,
                last_updated_at TEXT,
                is_exception INTEGER,
                user_id TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE sync_deletes (
                id TEXT PRIMARY KEY,
                deleted_at TEXT,
                title TEXT,
                user_id TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE sync_deletes_blocked (
                id TEXT PRIMARY KEY,
                deleted_at TEXT,
                title TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE sync_meta (
                key TEXT PRIMARY KEY,
                value TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE typewriter_cache (
                user_id TEXT PRIMARY KEY,
                content TEXT,
                last_updated_at TEXT
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            addColumn(db, "tasks", "description TEXT")
            addColumn(db, "tasks", "category TEXT")
            addColumn(db, "tasks", "subcategory TEXT")
            addColumn(db, "tasks", "description_done INTEGER")
        }
        if (oldVersion < 4) {
            addColumn(db, "tasks", "parent_id TEXT")
        }
    }

    private fun addColumn(db: SQLiteDatabase, table: String, spec: String) {
        runCatching { db.execSQL("ALTER TABLE $table ADD COLUMN $spec") }
    }

    fun getAllTasks(): List<TaskEntity> {
        val out = mutableListOf<TaskEntity>()
        readableDatabase.query("tasks", null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    TaskEntity(
                        id = c.str("id") ?: continue,
                        title = c.str("title").orEmpty(),
                        duration = c.dbl("duration"),
                        state = c.str("state") ?: "Flexible",
                        placedDate = c.str("placed_date"),
                        placedTime = c.str("placed_time"),
                        deadlineDate = c.str("deadline_date"),
                        deadlineTime = c.str("deadline_time"),
                        startingDate = c.str("starting_date"),
                        startingTime = c.str("starting_time"),
                        lifecycle = c.str("lifecycle") ?: "UNSCHEDULED",
                        workflow = c.str("workflow") ?: "UNFINISHED",
                        placementUpdatedAt = c.str("placement_updated_at"),
                        lastUpdatedAt = c.str("last_updated_at"),
                        userId = c.str("user_id"),
                        description = c.str("description").orEmpty(),
                        category = c.str("category").orEmpty(),
                        subcategory = c.str("subcategory").orEmpty(),
                        descriptionDone = c.intOrNull("description_done") == 1,
                        parentId = c.str("parent_id").orEmpty(),
                    ),
                )
            }
        }
        return out
    }

    fun getAllBlocked(): List<BlockedEntity> {
        val out = mutableListOf<BlockedEntity>()
        readableDatabase.query("blocked_times", null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    BlockedEntity(
                        id = c.str("id") ?: continue,
                        title = c.str("title") ?: "blocked",
                        weekday = c.intOrNull("weekday"),
                        specificDate = c.str("specific_date"),
                        startTime = c.str("start_time") ?: "06:00:00",
                        endTime = c.str("end_time") ?: "07:00:00",
                        lastUpdatedAt = c.str("last_updated_at"),
                        isException = c.intOrNull("is_exception") == 1,
                        userId = c.str("user_id"),
                    ),
                )
            }
        }
        return out
    }

    fun upsertTask(task: TaskEntity) {
        val cv = ContentValues().apply {
            put("id", task.id)
            put("title", task.title)
            put("duration", task.duration)
            put("state", task.state)
            put("placed_date", task.placedDate)
            put("placed_time", task.placedTime)
            put("deadline_date", task.deadlineDate)
            put("deadline_time", task.deadlineTime)
            put("starting_date", task.startingDate)
            put("starting_time", task.startingTime)
            put("lifecycle", task.lifecycle)
            put("workflow", task.workflow)
            put("placement_updated_at", task.placementUpdatedAt)
            put("last_updated_at", task.lastUpdatedAt)
            put("user_id", task.userId)
            put("description", task.description)
            put("category", task.category)
            put("subcategory", task.subcategory)
            put("description_done", if (task.descriptionDone) 1 else 0)
            put("parent_id", task.parentId)
        }
        writableDatabase.insertWithOnConflict("tasks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun upsertBlocked(row: BlockedEntity) {
        val cv = ContentValues().apply {
            put("id", row.id)
            put("title", row.title)
            if (row.weekday == null) putNull("weekday") else put("weekday", row.weekday)
            put("specific_date", row.specificDate)
            put("start_time", row.startTime)
            put("end_time", row.endTime)
            put("last_updated_at", row.lastUpdatedAt)
            put("is_exception", if (row.isException) 1 else 0)
            put("user_id", row.userId)
        }
        writableDatabase.insertWithOnConflict("blocked_times", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun upsertAllTasks(tasks: List<TaskEntity>) {
        writableDatabase.beginTransaction()
        try {
            tasks.forEach { upsertTask(it) }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun deleteAllUserData() {
        writableDatabase.execSQL("DELETE FROM tasks")
        writableDatabase.execSQL("DELETE FROM blocked_times")
        writableDatabase.execSQL("DELETE FROM sync_deletes")
        writableDatabase.execSQL("DELETE FROM sync_deletes_blocked")
        writableDatabase.execSQL("DELETE FROM typewriter_cache")
        writableDatabase.execSQL("DELETE FROM sync_meta")
    }

    fun deleteTaskById(id: String) {
        writableDatabase.delete("tasks", "id = ?", arrayOf(id))
    }

    fun deleteBlockedById(id: String) {
        writableDatabase.delete("blocked_times", "id = ?", arrayOf(id))
    }

    fun recordTaskDelete(id: String, title: String?, userId: String?) {
        val cv = ContentValues().apply {
            put("id", id)
            put("deleted_at", Instant.now().toString())
            put("title", title)
            put("user_id", userId)
        }
        writableDatabase.insertWithOnConflict("sync_deletes", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun recordBlockedDelete(id: String, title: String?) {
        val cv = ContentValues().apply {
            put("id", id)
            put("deleted_at", Instant.now().toString())
            put("title", title)
        }
        writableDatabase.insertWithOnConflict("sync_deletes_blocked", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun unrecordTaskDelete(id: String) {
        writableDatabase.delete("sync_deletes", "id = ?", arrayOf(id))
    }

    fun unrecordBlockedDelete(id: String) {
        writableDatabase.delete("sync_deletes_blocked", "id = ?", arrayOf(id))
    }

    fun isTaskDeleted(id: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM sync_deletes WHERE id = ? LIMIT 1", arrayOf(id)).use {
            return it.moveToFirst()
        }
    }

    fun isBlockedDeleted(id: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM sync_deletes_blocked WHERE id = ? LIMIT 1", arrayOf(id)).use {
            return it.moveToFirst()
        }
    }

    fun deletedTaskIds(): Set<String> = idSet("sync_deletes")
    fun deletedBlockedIds(): Set<String> = idSet("sync_deletes_blocked")

    private fun idSet(table: String): Set<String> {
        val out = mutableSetOf<String>()
        readableDatabase.query(table, arrayOf("id"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) c.getString(0)?.let { out.add(it) }
        }
        return out
    }

    fun getMeta(key: String): String? {
        readableDatabase.query("sync_meta", arrayOf("value"), "key = ?", arrayOf(key), null, null, null).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun setMeta(key: String, value: String) {
        val cv = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        writableDatabase.insertWithOnConflict("sync_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getTypewriter(userId: String): Pair<String, String?>? {
        readableDatabase.query(
            "typewriter_cache",
            arrayOf("content", "last_updated_at"),
            "user_id = ?",
            arrayOf(userId),
            null,
            null,
            null,
        ).use { c ->
            if (!c.moveToFirst()) return null
            return (c.getString(0) ?: "{}") to c.getString(1)
        }
    }

    fun saveTypewriter(userId: String, content: String, lastUpdatedAt: String?) {
        val cv = ContentValues().apply {
            put("user_id", userId)
            put("content", content)
            put("last_updated_at", lastUpdatedAt)
        }
        writableDatabase.insertWithOnConflict("typewriter_cache", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun Cursor.str(col: String): String? {
        val i = getColumnIndex(col)
        if (i < 0 || isNull(i)) return null
        return getString(i)
    }

    private fun Cursor.dbl(col: String): Double {
        val i = getColumnIndex(col)
        if (i < 0 || isNull(i)) return 0.0
        return getDouble(i)
    }

    private fun Cursor.intOrNull(col: String): Int? {
        val i = getColumnIndex(col)
        if (i < 0 || isNull(i)) return null
        return getInt(i)
    }
}

data class BlockedEntity(
    val id: String,
    val title: String,
    val weekday: Int?,
    val specificDate: String?,
    val startTime: String,
    val endTime: String,
    val lastUpdatedAt: String?,
    val isException: Boolean,
    val userId: String?,
)
