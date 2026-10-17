package com.lyricsplus.android.lyrics

import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

// zzc protocol reference: L-1124/QQMusicApi (GPLv3).
// See THIRD_PARTY_NOTICES.md for source and license details.
internal object QqMusicRequestSigner {
    private val prefixPositions = intArrayOf(23, 14, 6, 36, 16, 7, 19)
    private val suffixPositions = intArrayOf(16, 1, 32, 12, 19, 27, 8, 5)
    private val xorMask = intArrayOf(
        89, 39, 179, 150, 218, 82, 58, 252, 177, 52,
        186, 123, 120, 64, 242, 133, 143, 161, 121, 179
    )

    /** Sign the exact UTF-8 JSON string that will be sent, without reserializing it. */
    fun sign(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) }
        val prefix = prefixPositions.map { hex[it] }.joinToString("")
        val suffix = suffixPositions.map { hex[it] }.joinToString("")
        val scrambled = ByteArray(digest.size) { index ->
            (digest[index].toInt() xor xorMask[index]).toByte()
        }
        val middle = Base64.getEncoder().encodeToString(scrambled)
            .filterNot { it == '/' || it == '+' || it == '=' }
        return "zzc$prefix$middle$suffix".lowercase(Locale.ROOT)
    }
}
