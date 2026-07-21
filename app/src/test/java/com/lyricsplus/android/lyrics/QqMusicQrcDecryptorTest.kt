package com.lyricsplus.android.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class QqMusicQrcDecryptorTest {
    @Test
    fun decryptsKnownQrcVector() {
        val encrypted =
            "BB1DB48DC814CA52383B3DCFA8782DC30FDF64D4906EEE3FEF1B6C1C5B9177CE" +
                "4F77779990D47D39"

        assertEquals(
            "[0,1000]测(0,500)试(500,500)\n",
            QqMusicQrcDecryptor.decrypt(encrypted)
        )
    }

    @Test
    fun rejectsInvalidHexadecimalPayload() {
        assertThrows(IllegalArgumentException::class.java) {
            QqMusicQrcDecryptor.decrypt("not-hex")
        }
    }

    @Test
    fun extractsTimedContentFromQrcXml() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <QrcInfos>
              <LyricInfo LyricCount="1">
                <Lyric_1 LyricType="1" LyricContent="[0,1000]A&amp;B(0,1000)&#10;"/>
              </LyricInfo>
            </QrcInfos>
        """.trimIndent()

        assertEquals("[0,1000]A&B(0,1000)\n", QrcPayloadParser.extract(xml))
    }

    @Test
    fun leavesNonXmlLyricsUntouched() {
        val lrc = "[00:01.00]Test"
        assertEquals(lrc, QrcPayloadParser.extract(lrc))
    }
}
