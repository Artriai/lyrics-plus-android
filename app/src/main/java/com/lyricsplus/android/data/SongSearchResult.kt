package com.lyricsplus.android.data

data class SongSearchResult(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Long,
    val source: String,
    val hasSyncedLyrics: Boolean = true
)
