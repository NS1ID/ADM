package com.adm.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.Executors

/**
 * Persistence for downloads, history, bookmarks, tabs, userscripts and extensions.
 *
 * Plain [SQLiteOpenHelper] is used on purpose: it needs no annotation processor,
 * so the project builds offline with the on-device toolchain. Writes are funnelled
 * through a single background executor to avoid main-thread I/O.
 */
class AppDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, "adm.db", null, SCHEMA_VERSION) {

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "adm-db") }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE downloads (
                id TEXT PRIMARY KEY,
                url TEXT NOT NULL,
                filename TEXT NOT NULL,
                path TEXT NOT NULL,
                mime TEXT,
                total INTEGER NOT NULL DEFAULT 0,
                downloaded INTEGER NOT NULL DEFAULT 0,
                state TEXT NOT NULL DEFAULT 'queued',
                connections INTEGER NOT NULL DEFAULT 8,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                error TEXT,
                referer TEXT,
                user_agent TEXT,
                category TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL,
                title TEXT,
                visited_at INTEGER NOT NULL,
                visits INTEGER NOT NULL DEFAULT 1,
                incognito INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL,
                title TEXT,
                folder TEXT,
                favicon TEXT,
                created_at INTEGER NOT NULL,
                position INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE tabs (
                id TEXT PRIMARY KEY,
                url TEXT,
                title TEXT,
                incognito INTEGER NOT NULL DEFAULT 0,
                position INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE userscripts (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                namespace TEXT,
                version TEXT,
                description TEXT,
                matches TEXT,
                source TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                run_at TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE extensions (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                version TEXT,
                author TEXT,
                description TEXT,
                manifest TEXT,
                source TEXT,
                enabled INTEGER NOT NULL DEFAULT 1,
                installed_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_history_time ON history(visited_at DESC)")
        db.execSQL("CREATE INDEX idx_downloads_state ON downloads(state)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive migrations only; destructive fallback keeps dev builds usable.
        if (oldVersion < 2) {
            runCatching { db.execSQL("ALTER TABLE downloads ADD COLUMN category TEXT") }
        }
        if (oldVersion < 3) {
            runCatching { db.execSQL("ALTER TABLE tabs ADD COLUMN incognito INTEGER NOT NULL DEFAULT 0") }
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    fun runAsync(block: (SQLiteDatabase) -> Unit) {
        io.execute { runCatching { block(writableDatabase) } }
    }

    /** History for `url`, bumping the visit counter when it already exists. */
    fun recordVisit(url: String, title: String?, incognito: Boolean) {
        if (incognito) return
        runAsync { db ->
            val now = System.currentTimeMillis()
            val cursor = db.rawQuery("SELECT id FROM history WHERE url = ? LIMIT 1", arrayOf(url))
            val existing = cursor.use { if (it.moveToFirst()) it.getLong(0) else -1L }
            if (existing >= 0) {
                db.execSQL("UPDATE history SET visited_at = ?, visits = visits + 1, title = ? WHERE id = ?",
                    arrayOf<Any?>(now, title ?: url, existing))
            } else {
                val values = ContentValues().apply {
                    put("url", url)
                    put("title", title ?: url)
                    put("visited_at", now)
                    put("incognito", 0)
                }
                db.insert("history", null, values)
            }
        }
    }

    fun clearHistory() = runAsync { it.delete("history", null, null) }

    companion object {
        private const val SCHEMA_VERSION = 3

        @Volatile
        private var instance: AppDatabase? = null

        fun init(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) instance = AppDatabase(context.applicationContext)
                }
            }
        }

        fun get(): AppDatabase = instance
            ?: error("AppDatabase.init() was not called from AdmApplication")
    }
}
