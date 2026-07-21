package com.lyricsplus.android.lyrics

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lyricsplus.android.data.LyricsLine
import org.json.JSONArray
import org.json.JSONObject

data class CachedLyricsResult(
    val lyrics: List<LyricsLine>,
    val source: String,
    val score: Int = 100
)

class LyricsCacheDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_LYRICS (
                $COLUMN_KEY TEXT PRIMARY KEY,
                $COLUMN_CONTENT TEXT,
                $COLUMN_SOURCE TEXT,
                $COLUMN_CACHED_AT INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_LYRICS")
            onCreate(db)
            return
        }
        if (oldVersion < 3) {
            db.delete(
                TABLE_LYRICS,
                "$COLUMN_SOURCE IN (?, ?)",
                arrayOf("网易云音乐", "QQ音乐")
            )
        }
        if (oldVersion < 4) {
            db.delete(
                TABLE_LYRICS,
                "$COLUMN_SOURCE = ?",
                arrayOf("纯音乐")
            )
        }
    }

    fun getLyrics(trackKey: String): CachedLyricsResult? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_LYRICS,
            arrayOf(COLUMN_CONTENT, COLUMN_SOURCE),
            "$COLUMN_KEY = ?",
            arrayOf(trackKey),
            null, null, null
        )

        return cursor.use {
            if (it.moveToFirst()) {
                val jsonStr = it.getString(0)
                val source = if (!it.isNull(1)) it.getString(1) else "本地缓存"
                val lyrics = runCatching { parseJson(jsonStr) }.getOrNull()
                if (lyrics != null) {
                    CachedLyricsResult(lyrics, source)
                } else {
                    null
                }
            } else {
                null
            }
        }
    }

    fun saveLyrics(trackKey: String, lyrics: List<LyricsLine>, source: String) {
        val db = writableDatabase
        val jsonStr = toJson(lyrics)
        
        val values = ContentValues().apply {
            put(COLUMN_KEY, trackKey)
            put(COLUMN_CONTENT, jsonStr)
            put(COLUMN_SOURCE, source)
            put(COLUMN_CACHED_AT, System.currentTimeMillis())
        }

        db.insertWithOnConflict(
            TABLE_LYRICS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun deleteLyrics(trackKey: String) {
        val db = writableDatabase
        db.delete(TABLE_LYRICS, "$COLUMN_KEY = ?", arrayOf(trackKey))
    }

    fun hasLyrics(trackKey: String): Boolean {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_LYRICS,
            arrayOf(COLUMN_KEY),
            "$COLUMN_KEY = ?",
            arrayOf(trackKey),
            null, null, null
        )
        return cursor.use { it.moveToFirst() }
    }

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
        private const val DATABASE_VERSION = 4
        private const val TABLE_LYRICS = "cached_lyrics"
        private const val COLUMN_KEY = "track_key"
        private const val COLUMN_CONTENT = "content"
        private const val COLUMN_SOURCE = "source"
        private const val COLUMN_CACHED_AT = "cached_at"
    }
}
