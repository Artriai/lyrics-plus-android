package com.lyricsplus.android.lyrics

/** A song key alone cannot distinguish A → B → A or repeated source switches. */
internal class LyricsRequestGeneration {
    private var value = 0L
    fun next(): Long = ++value
    fun isCurrent(request: Long): Boolean = value == request
}
