package com.usetracker.screentime.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One continuous stretch of the screen being on and unlocked. Times are epoch millis. */
data class Session(val start: Long, val end: Long) {
    val duration: Long get() = end - start
}

/** A stretch of time attributed to one app on screen (or, in [SessionStore.ACTIVE], to a signal). */
data class AppSpan(val pkg: String, val start: Long, val end: Long) {
    val duration: Long get() = end - start
}

/** Stores everything in a tiny SQLite database (a few hundred KB per year). */
class SessionStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "screentime.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        // start is the primary key so re-processing the same events can never double count.
        db.execSQL("CREATE TABLE sessions (start INTEGER PRIMARY KEY, end INTEGER NOT NULL)")
        for (table in listOf(APPS, ACTIVE)) createSpanTable(db, table)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Databases from before 1.5 also have an unused "listening" table; it's left alone.
        if (oldVersion < 2) createSpanTable(db, APPS)
        if (oldVersion < 3) createSpanTable(db, ACTIVE)
    }

    /** For [ACTIVE], pkg holds the kind of signal instead of an app: held or touch. */
    private fun createSpanTable(db: SQLiteDatabase, table: String) {
        db.execSQL(
            "CREATE TABLE $table (start INTEGER NOT NULL, end INTEGER NOT NULL, " +
                "pkg TEXT NOT NULL, PRIMARY KEY (start, pkg))"
        )
    }

    fun insertSessions(sessions: List<Session>) = transaction { db ->
        for (s in sessions) {
            // Skip anything overlapping a different stored session, so a re-scan can't double count.
            if (overlaps(db, "sessions", s.start, s.end, pkg = null)) continue
            val values = ContentValues().apply {
                put("start", s.start)
                put("end", s.end)
            }
            db.insertWithOnConflict("sessions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** [perApp]: only reject overlaps with the same app (or, in [ACTIVE], the same signal). */
    fun insertSpans(table: String, spans: List<AppSpan>, perApp: Boolean) = transaction { db ->
        for (s in spans) {
            if (overlaps(db, table, s.start, s.end, pkg = if (perApp) s.pkg else null)) continue
            val values = ContentValues().apply {
                put("start", s.start)
                put("end", s.end)
                put("pkg", s.pkg)
            }
            db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun sessions(): List<Session> =
        readableDatabase.rawQuery("SELECT start, end FROM sessions ORDER BY start", null).use { c ->
            buildList { while (c.moveToNext()) add(Session(c.getLong(0), c.getLong(1))) }
        }

    fun spans(table: String): List<AppSpan> =
        readableDatabase.rawQuery("SELECT pkg, start, end FROM $table ORDER BY start", null).use { c ->
            buildList { while (c.moveToNext()) add(AppSpan(c.getString(0), c.getLong(1), c.getLong(2))) }
        }

    private fun overlaps(db: SQLiteDatabase, table: String, start: Long, end: Long, pkg: String?): Boolean {
        val pkgClause = if (pkg != null) " AND pkg = ?" else ""
        val args = listOfNotNull(start.toString(), end.toString(), start.toString(), pkg).toTypedArray()
        return db.rawQuery(
            "SELECT 1 FROM $table WHERE start != ? AND start < ? AND end > ?$pkgClause LIMIT 1", args
        ).use { it.moveToFirst() }
    }

    private fun transaction(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val APPS = "app_usage"
        const val ACTIVE = "active"

        @Volatile private var instance: SessionStore? = null

        fun get(context: Context): SessionStore =
            instance ?: synchronized(this) {
                instance ?: SessionStore(context.applicationContext).also { instance = it }
            }
    }
}
