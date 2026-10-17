package com.lyricsplus.android.lyrics

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lyricsplus.android.data.LyricsLine
import org.json.JSONArray
import org.json.JSONObject

class LyricsCacheDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_AUTOMATIC_TABLE)
        db.execSQL(CREATE_SOURCE_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_LYRICS")
            onCreate(db)
            return
        }
        if (oldVersion < 3) {
            db.delete(TABLE_LYRICS, "$COLUMN_SOURCE IN (?, ?)", arrayOf("网易云音乐", "QQ音乐"))
        }
        if (oldVersion < 4) {
            db.delete(TABLE_LYRICS, "$COLUMN_SOURCE = ?", arrayOf("纯音乐"))
        }
        if (oldVersion < 5) {
            // Preserve old content without inventing a match score or a successful check.
            db.execSQL("ALTER TABLE $TABLE_LYRICS ADD COLUMN $COLUMN_SCORE INTEGER")
            db.execSQL("ALTER TABLE $TABLE_LYRICS ADD COLUMN $COLUMN_CHECKED_AT INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_LYRICS ADD COLUMN $COLUMN_CHECK_SUCCEEDED INTEGER NOT NULL DEFAULT 0")
            db.execSQL(CREATE_SOURCE_TABLE)
            db.execSQL(
                "INSERT INTO $TABLE_SOURCES ($COLUMN_KEY, $COLUMN_CONTENT, $COLUMN_SOURCE, $COLUMN_CACHED_AT, $COLUMN_SCORE) " +
                    "SELECT $COLUMN_KEY, $COLUMN_CONTENT, COALESCE($COLUMN_SOURCE, '本地缓存'), $COLUMN_CACHED_AT, $COLUMN_SCORE " +
                    "FROM $TABLE_LYRICS WHERE $COLUMN_KEY IS NOT NULL"
            )
        }
    }

    internal fun getLyrics(trackKey: String): LyricsCacheEntry? = readEntry(trackKey)

    internal fun getSourceLyrics(trackKey: String, source: String): LyricsCacheEntry? = readEntry(trackKey, source)

    private fun readEntry(trackKey: String, source: String? = null): LyricsCacheEntry? {
        val table = if (source == null) TABLE_LYRICS else TABLE_SOURCES
        val columns = if (source == null) {
            arrayOf(COLUMN_CONTENT, COLUMN_SOURCE, COLUMN_SCORE, COLUMN_CACHED_AT, COLUMN_CHECKED_AT, COLUMN_CHECK_SUCCEEDED)
        } else arrayOf(COLUMN_CONTENT, COLUMN_SOURCE, COLUMN_SCORE, COLUMN_CACHED_AT)
        return readableDatabase.query(
            table, columns,
            if (source == null) "$COLUMN_KEY = ?" else "$COLUMN_KEY = ? AND $COLUMN_SOURCE = ?",
            if (source == null) arrayOf(trackKey) else arrayOf(trackKey, source),
            null, null, null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val lyrics = runCatching { parseJson(cursor.getString(0)) }.getOrNull()
            if (lyrics.isNullOrEmpty()) return@use null
            val savedSource = if (cursor.isNull(1)) "本地缓存" else cursor.getString(1)
            if (savedSource == "纯音乐") return@use null
            val score = if (cursor.isNull(2)) null else cursor.getInt(2).takeIf { it in 0..100 }
            LyricsCacheEntry(
                CachedLyricsResult(lyrics, savedSource, score),
                cachedAt = cursor.getLong(3),
                checkedAt = if (source == null) cursor.getLong(4) else 0L,
                checkSucceeded = source == null && cursor.getInt(5) != 0
            )
        }
    }

    internal fun saveLyrics(trackKey: String, entry: LyricsCacheEntry) {
        val values = contentValues(trackKey, entry).apply {
            put(COLUMN_CHECKED_AT, entry.checkedAt)
            put(COLUMN_CHECK_SUCCEEDED, if (entry.checkSucceeded) 1 else 0)
        }
        writableDatabase.insertWithOnConflict(TABLE_LYRICS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    internal fun saveSourceLyrics(trackKey: String, entry: LyricsCacheEntry) {
        writableDatabase.insertWithOnConflict(
            TABLE_SOURCES, null, contentValues(trackKey, entry), SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun contentValues(trackKey: String, entry: LyricsCacheEntry) = ContentValues().apply {
        put(COLUMN_KEY, trackKey)
        put(COLUMN_CONTENT, toJson(entry.result.lyrics))
        put(COLUMN_SOURCE, entry.result.source)
        put(COLUMN_CACHED_AT, entry.cachedAt)
        entry.result.score?.let { put(COLUMN_SCORE, it) } ?: putNull(COLUMN_SCORE)
    }

    fun deleteLyrics(trackKey: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_LYRICS, "$COLUMN_KEY = ?", arrayOf(trackKey))
            db.delete(TABLE_SOURCES, "$COLUMN_KEY = ?", arrayOf(trackKey))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun hasLyrics(trackKey: String): Boolean = getLyrics(trackKey) != null

    private fun parseJson(jsonStr: String): List<LyricsLine> {
        val array = JSONArray(jsonStr)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            LyricsLine(
                startTimeMs = obj.getLong("startTimeMs"),
                text = obj.getString("text"),
                translation = obj.optString("translation", ""),
                reading = obj.optString("reading", "")
            )
        }
    }

    private fun toJson(lyrics: List<LyricsLine>): String {
        val array = JSONArray()
        lyrics.forEach { line ->
            array.put(
                JSONObject().apply {
                    put("startTimeMs", line.startTimeMs)
                    put("text", line.text)
                    put("translation", line.translation)
                    put("reading", line.reading)
                }
            )
        }
        return array.toString()
    }

    companion object {
        private const val DATABASE_NAME = "lyrics_cache.db"
        private const val DATABASE_VERSION = 5
        private const val TABLE_LYRICS = "cached_lyrics"
        private const val TABLE_SOURCES = "cached_lyrics_sources"
        private const val COLUMN_KEY = "track_key"
        private const val COLUMN_CONTENT = "content"
        private const val COLUMN_SOURCE = "source"
        private const val COLUMN_CACHED_AT = "cached_at"
        private const val COLUMN_SCORE = "match_score"
        private const val COLUMN_CHECKED_AT = "checked_at"
        private const val COLUMN_CHECK_SUCCEEDED = "check_succeeded"
        private const val CREATE_AUTOMATIC_TABLE = """
            CREATE TABLE $TABLE_LYRICS (
                $COLUMN_KEY TEXT PRIMARY KEY,
                $COLUMN_CONTENT TEXT,
                $COLUMN_SOURCE TEXT,
                $COLUMN_CACHED_AT INTEGER,
                $COLUMN_SCORE INTEGER,
                $COLUMN_CHECKED_AT INTEGER NOT NULL DEFAULT 0,
                $COLUMN_CHECK_SUCCEEDED INTEGER NOT NULL DEFAULT 0
            )
        """
        private const val CREATE_SOURCE_TABLE = """
            CREATE TABLE $TABLE_SOURCES (
                $COLUMN_KEY TEXT NOT NULL,
                $COLUMN_CONTENT TEXT,
                $COLUMN_SOURCE TEXT NOT NULL,
                $COLUMN_CACHED_AT INTEGER,
                $COLUMN_SCORE INTEGER,
                PRIMARY KEY ($COLUMN_KEY, $COLUMN_SOURCE)
            )
        """
    }
}
