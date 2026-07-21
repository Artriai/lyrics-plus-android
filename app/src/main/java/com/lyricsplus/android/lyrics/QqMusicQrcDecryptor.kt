package com.lyricsplus.android.lyrics

import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

// The DES routines in this file are adapted from WXRIW/QQMusicDecoder.
// See THIRD_PARTY_NOTICES.md for source and license details.

/**
 * Decrypts the hexadecimal lyric payload returned by QQ Music's
 * music.musichallSong.PlayLyricInfo.GetPlayLyricInfo endpoint.
 *
 * QQ Music uses a 3DES-compatible cipher with a historical error in S-box 4,
 * so the platform DESede implementation cannot decode these payloads.
 */
object QqMusicQrcDecryptor {
    private val key = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.US_ASCII)

    fun decrypt(encryptedHex: String): String {
        require(encryptedHex.isNotBlank()) { "QRC payload is empty" }

        val encrypted = encryptedHex.hexToByteArray()
        require(encrypted.size % 8 == 0) { "Invalid QRC payload length" }

        val compressed = QqMusicBuggyTripleDes.decrypt(encrypted, key)
        return InflaterInputStream(ByteArrayInputStream(compressed))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }

    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0) { "Invalid hexadecimal QRC payload" }

        return ByteArray(length / 2) { index ->
            val offset = index * 2
            val high = Character.digit(this[offset], 16)
            val low = Character.digit(this[offset + 1], 16)
            require(high >= 0 && low >= 0) { "Invalid hexadecimal QRC payload" }
            ((high shl 4) or low).toByte()
        }
    }
}

/**
 * Minimal DES/3DES implementation matching the historical DES variant used by
 * QQ Music. Its S-box tables contain two values that differ from standard DES,
 * so a platform DESede implementation is not compatible.
 */
private object QqMusicBuggyTripleDes {
    private val roundShifts = intArrayOf(
        1, 1, 2, 2, 2, 2, 2, 2,
        1, 2, 2, 2, 2, 2, 2, 1
    )

    private val keyPermutationC = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17,
        9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35
    )

    private val keyPermutationD = intArrayOf(
        62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21,
        13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3
    )

    private val keyCompression = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9,
        22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1,
        40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 47,
        43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31
    )

    private val sBoxes = arrayOf(
        intArrayOf(
            14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
            0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
            4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
            15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13
        ),
        intArrayOf(
            15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
            3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
            0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
            13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9
        ),
        intArrayOf(
            10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
            13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
            13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
            1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12
        ),
        intArrayOf(
            7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
            13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
            10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
            3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14
        ),
        intArrayOf(
            2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
            14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
            4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
            11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3
        ),
        intArrayOf(
            12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
            10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
            9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
            4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13
        ),
        intArrayOf(
            4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
            13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
            1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
            6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12
        ),
        intArrayOf(
            13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
            1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
            7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
            2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11
        )
    )

    fun decrypt(input: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 24) { "QRC key must contain 24 bytes" }
        require(input.size % 8 == 0) { "QRC ciphertext must align to an 8-byte block" }

        // Triple-DES decryption order: D(K3), E(K2), D(K1).
        val schedules = arrayOf(
            keySchedule(key.copyOfRange(16, 24), decrypt = true),
            keySchedule(key.copyOfRange(8, 16), decrypt = false),
            keySchedule(key.copyOfRange(0, 8), decrypt = true)
        )
        val output = ByteArray(input.size)

        for (offset in input.indices step 8) {
            var block = input.copyOfRange(offset, offset + 8)
            block = cryptBlock(block, schedules[0])
            block = cryptBlock(block, schedules[1])
            block = cryptBlock(block, schedules[2])
            block.copyInto(output, destinationOffset = offset)
        }

        return output
    }

    private fun bitNumber(input: ByteArray, bit: Int, outputBit: Int): Int {
        val index = bit / 32 * 4 + 3 - bit % 32 / 8
        val value = input[index].toInt() and 0xff
        return ((value ushr (7 - bit % 8)) and 1) shl outputBit
    }

    private fun bitNumberRight(input: Int, bit: Int, outputBit: Int): Int =
        ((input ushr (31 - bit)) and 1) shl outputBit

    private fun bitNumberLeft(input: Int, bit: Int, outputBit: Int): Int =
        ((input shl bit) and Int.MIN_VALUE) ushr outputBit

    private fun sBoxBit(value: Int): Int =
        (value and 0x20) or ((value and 0x1f) ushr 1) or ((value and 1) shl 4)

    private fun keySchedule(key: ByteArray, decrypt: Boolean): Array<ByteArray> {
        val schedule = Array(16) { ByteArray(6) }
        var left = 0
        var right = 0

        for (index in 0 until 28) {
            left = left or bitNumber(key, keyPermutationC[index], 31 - index)
            right = right or bitNumber(key, keyPermutationD[index], 31 - index)
        }

        repeat(16) { round ->
            val shift = roundShifts[round]
            left = ((left shl shift) or (left ushr (28 - shift))) and 0xfffffff0.toInt()
            right = ((right shl shift) or (right ushr (28 - shift))) and 0xfffffff0.toInt()
            val targetRound = if (decrypt) 15 - round else round

            for (index in 0 until 24) {
                val byteIndex = index / 8
                schedule[targetRound][byteIndex] =
                    (schedule[targetRound][byteIndex].toInt() or
                        bitNumberRight(left, keyCompression[index], 7 - index % 8)).toByte()
            }
            for (index in 24 until 48) {
                val byteIndex = index / 8
                schedule[targetRound][byteIndex] =
                    (schedule[targetRound][byteIndex].toInt() or
                        bitNumberRight(right, keyCompression[index] - 27, 7 - index % 8)).toByte()
            }
        }

        return schedule
    }

    private fun initialPermutation(input: ByteArray): IntArray = intArrayOf(
        bitNumber(input, 57, 31) or bitNumber(input, 49, 30) or bitNumber(input, 41, 29) or
            bitNumber(input, 33, 28) or bitNumber(input, 25, 27) or bitNumber(input, 17, 26) or
            bitNumber(input, 9, 25) or bitNumber(input, 1, 24) or bitNumber(input, 59, 23) or
            bitNumber(input, 51, 22) or bitNumber(input, 43, 21) or bitNumber(input, 35, 20) or
            bitNumber(input, 27, 19) or bitNumber(input, 19, 18) or bitNumber(input, 11, 17) or
            bitNumber(input, 3, 16) or bitNumber(input, 61, 15) or bitNumber(input, 53, 14) or
            bitNumber(input, 45, 13) or bitNumber(input, 37, 12) or bitNumber(input, 29, 11) or
            bitNumber(input, 21, 10) or bitNumber(input, 13, 9) or bitNumber(input, 5, 8) or
            bitNumber(input, 63, 7) or bitNumber(input, 55, 6) or bitNumber(input, 47, 5) or
            bitNumber(input, 39, 4) or bitNumber(input, 31, 3) or bitNumber(input, 23, 2) or
            bitNumber(input, 15, 1) or bitNumber(input, 7, 0),
        bitNumber(input, 56, 31) or bitNumber(input, 48, 30) or bitNumber(input, 40, 29) or
            bitNumber(input, 32, 28) or bitNumber(input, 24, 27) or bitNumber(input, 16, 26) or
            bitNumber(input, 8, 25) or bitNumber(input, 0, 24) or bitNumber(input, 58, 23) or
            bitNumber(input, 50, 22) or bitNumber(input, 42, 21) or bitNumber(input, 34, 20) or
            bitNumber(input, 26, 19) or bitNumber(input, 18, 18) or bitNumber(input, 10, 17) or
            bitNumber(input, 2, 16) or bitNumber(input, 60, 15) or bitNumber(input, 52, 14) or
            bitNumber(input, 44, 13) or bitNumber(input, 36, 12) or bitNumber(input, 28, 11) or
            bitNumber(input, 20, 10) or bitNumber(input, 12, 9) or bitNumber(input, 4, 8) or
            bitNumber(input, 62, 7) or bitNumber(input, 54, 6) or bitNumber(input, 46, 5) or
            bitNumber(input, 38, 4) or bitNumber(input, 30, 3) or bitNumber(input, 22, 2) or
            bitNumber(input, 14, 1) or bitNumber(input, 6, 0)
    )

    private fun finalPermutation(state: IntArray): ByteArray {
        val output = ByteArray(8)

        output[3] = (bitNumberRight(state[1], 7, 7) or bitNumberRight(state[0], 7, 6) or
            bitNumberRight(state[1], 15, 5) or bitNumberRight(state[0], 15, 4) or
            bitNumberRight(state[1], 23, 3) or bitNumberRight(state[0], 23, 2) or
            bitNumberRight(state[1], 31, 1) or bitNumberRight(state[0], 31, 0)).toByte()
        output[2] = (bitNumberRight(state[1], 6, 7) or bitNumberRight(state[0], 6, 6) or
            bitNumberRight(state[1], 14, 5) or bitNumberRight(state[0], 14, 4) or
            bitNumberRight(state[1], 22, 3) or bitNumberRight(state[0], 22, 2) or
            bitNumberRight(state[1], 30, 1) or bitNumberRight(state[0], 30, 0)).toByte()
        output[1] = (bitNumberRight(state[1], 5, 7) or bitNumberRight(state[0], 5, 6) or
            bitNumberRight(state[1], 13, 5) or bitNumberRight(state[0], 13, 4) or
            bitNumberRight(state[1], 21, 3) or bitNumberRight(state[0], 21, 2) or
            bitNumberRight(state[1], 29, 1) or bitNumberRight(state[0], 29, 0)).toByte()
        output[0] = (bitNumberRight(state[1], 4, 7) or bitNumberRight(state[0], 4, 6) or
            bitNumberRight(state[1], 12, 5) or bitNumberRight(state[0], 12, 4) or
            bitNumberRight(state[1], 20, 3) or bitNumberRight(state[0], 20, 2) or
            bitNumberRight(state[1], 28, 1) or bitNumberRight(state[0], 28, 0)).toByte()
        output[7] = (bitNumberRight(state[1], 3, 7) or bitNumberRight(state[0], 3, 6) or
            bitNumberRight(state[1], 11, 5) or bitNumberRight(state[0], 11, 4) or
            bitNumberRight(state[1], 19, 3) or bitNumberRight(state[0], 19, 2) or
            bitNumberRight(state[1], 27, 1) or bitNumberRight(state[0], 27, 0)).toByte()
        output[6] = (bitNumberRight(state[1], 2, 7) or bitNumberRight(state[0], 2, 6) or
            bitNumberRight(state[1], 10, 5) or bitNumberRight(state[0], 10, 4) or
            bitNumberRight(state[1], 18, 3) or bitNumberRight(state[0], 18, 2) or
            bitNumberRight(state[1], 26, 1) or bitNumberRight(state[0], 26, 0)).toByte()
        output[5] = (bitNumberRight(state[1], 1, 7) or bitNumberRight(state[0], 1, 6) or
            bitNumberRight(state[1], 9, 5) or bitNumberRight(state[0], 9, 4) or
            bitNumberRight(state[1], 17, 3) or bitNumberRight(state[0], 17, 2) or
            bitNumberRight(state[1], 25, 1) or bitNumberRight(state[0], 25, 0)).toByte()
        output[4] = (bitNumberRight(state[1], 0, 7) or bitNumberRight(state[0], 0, 6) or
            bitNumberRight(state[1], 8, 5) or bitNumberRight(state[0], 8, 4) or
            bitNumberRight(state[1], 16, 3) or bitNumberRight(state[0], 16, 2) or
            bitNumberRight(state[1], 24, 1) or bitNumberRight(state[0], 24, 0)).toByte()

        return output
    }

    private fun roundFunction(input: Int, key: ByteArray): Int {
        val expanded = ByteArray(6)
        val first = bitNumberLeft(input, 31, 0) or ((input and 0xf0000000.toInt()) ushr 1) or
            bitNumberLeft(input, 4, 5) or bitNumberLeft(input, 3, 6) or
            ((input and 0x0f000000) ushr 3) or bitNumberLeft(input, 8, 11) or
            bitNumberLeft(input, 7, 12) or ((input and 0x00f00000) ushr 5) or
            bitNumberLeft(input, 12, 17) or bitNumberLeft(input, 11, 18) or
            ((input and 0x000f0000) ushr 7) or bitNumberLeft(input, 16, 23)
        val second = bitNumberLeft(input, 15, 0) or ((input and 0x0000f000) shl 15) or
            bitNumberLeft(input, 20, 5) or bitNumberLeft(input, 19, 6) or
            ((input and 0x00000f00) shl 13) or bitNumberLeft(input, 24, 11) or
            bitNumberLeft(input, 23, 12) or ((input and 0x000000f0) shl 11) or
            bitNumberLeft(input, 28, 17) or bitNumberLeft(input, 27, 18) or
            ((input and 0x0000000f) shl 9) or bitNumberLeft(input, 0, 23)

        expanded[0] = (first ushr 24).toByte()
        expanded[1] = (first ushr 16).toByte()
        expanded[2] = (first ushr 8).toByte()
        expanded[3] = (second ushr 24).toByte()
        expanded[4] = (second ushr 16).toByte()
        expanded[5] = (second ushr 8).toByte()

        for (index in expanded.indices) {
            expanded[index] = (expanded[index].toInt() xor key[index].toInt()).toByte()
        }

        val values = IntArray(6) { expanded[it].toInt() and 0xff }
        var state =
            (sBoxes[0][sBoxBit(values[0] ushr 2)] shl 28) or
                (sBoxes[1][sBoxBit(((values[0] and 3) shl 4) or (values[1] ushr 4))] shl 24) or
                (sBoxes[2][sBoxBit(((values[1] and 15) shl 2) or (values[2] ushr 6))] shl 20) or
                (sBoxes[3][sBoxBit(values[2] and 63)] shl 16) or
                (sBoxes[4][sBoxBit(values[3] ushr 2)] shl 12) or
                (sBoxes[5][sBoxBit(((values[3] and 3) shl 4) or (values[4] ushr 4))] shl 8) or
                (sBoxes[6][sBoxBit(((values[4] and 15) shl 2) or (values[5] ushr 6))] shl 4) or
                sBoxes[7][sBoxBit(values[5] and 63)]

        state = bitNumberLeft(state, 15, 0) or bitNumberLeft(state, 6, 1) or
            bitNumberLeft(state, 19, 2) or bitNumberLeft(state, 20, 3) or
            bitNumberLeft(state, 28, 4) or bitNumberLeft(state, 11, 5) or
            bitNumberLeft(state, 27, 6) or bitNumberLeft(state, 16, 7) or
            bitNumberLeft(state, 0, 8) or bitNumberLeft(state, 14, 9) or
            bitNumberLeft(state, 22, 10) or bitNumberLeft(state, 25, 11) or
            bitNumberLeft(state, 4, 12) or bitNumberLeft(state, 17, 13) or
            bitNumberLeft(state, 30, 14) or bitNumberLeft(state, 9, 15) or
            bitNumberLeft(state, 1, 16) or bitNumberLeft(state, 7, 17) or
            bitNumberLeft(state, 23, 18) or bitNumberLeft(state, 13, 19) or
            bitNumberLeft(state, 31, 20) or bitNumberLeft(state, 26, 21) or
            bitNumberLeft(state, 2, 22) or bitNumberLeft(state, 8, 23) or
            bitNumberLeft(state, 18, 24) or bitNumberLeft(state, 12, 25) or
            bitNumberLeft(state, 29, 26) or bitNumberLeft(state, 5, 27) or
            bitNumberLeft(state, 21, 28) or bitNumberLeft(state, 10, 29) or
            bitNumberLeft(state, 3, 30) or bitNumberLeft(state, 24, 31)
        return state
    }

    private fun cryptBlock(input: ByteArray, schedule: Array<ByteArray>): ByteArray {
        val state = initialPermutation(input)

        for (round in 0 until 15) {
            val previousRight = state[1]
            state[1] = roundFunction(state[1], schedule[round]) xor state[0]
            state[0] = previousRight
        }
        state[0] = roundFunction(state[1], schedule[15]) xor state[0]

        return finalPermutation(state)
    }
}
