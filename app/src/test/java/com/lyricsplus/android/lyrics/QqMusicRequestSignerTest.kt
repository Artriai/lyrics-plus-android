package com.lyricsplus.android.lyrics

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class QqMusicRequestSignerTest {
    // Expected values generated independently using the reference Python algorithm.
    @Test fun matchesReferenceSignaturesForAsciiAndUtf8Payloads() {
        val vectors = listOf(
            "" to "zzcf0e03e5gx4qeiq5cfgdyqwu7sdqfsb5fro3aa45053",
            "{}" to "zzcf8e26805gyafigxmxjehoe02mvsjjgtwzw6f1a05f9",
            """{"query":"スパークル - movie ver. RADWIMPS"}""" to
                "zzc423fcc70wm1qoon29hxc4rbztklh7gcaca3e7b56",
            """{"query":"Hotel California - 2013 Remaster Eagles"}""" to
                "zzc50f0c8cubftbrzvdpdz6mooqc77m6obc24c124ce60"
        )
        for ((payload, expected) in vectors) {
            assertEquals(expected, QqMusicRequestSigner.sign(payload))
        }
    }

    @Test fun signatureBindsExactWireBytesIncludingWhitespaceAndKeyOrder() {
        val payload = """{"title":"スパークル","page":1}"""
        val signature = QqMusicRequestSigner.sign(payload)
        assertEquals(signature, QqMusicRequestSigner.sign(payload))
        assertNotEquals(signature, QqMusicRequestSigner.sign("""{"page":1,"title":"スパークル"}"""))
        assertNotEquals(signature, QqMusicRequestSigner.sign("""{ "title":"スパークル","page":1}"""))
        assertNotEquals(signature, QqMusicRequestSigner.sign("""{"title":"スパークル","page":2}"""))
    }

    @Test fun localeCannotChangeProtocolSignature() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("zzcf8e26805gyafigxmxjehoe02mvsjjgtwzw6f1a05f9", QqMusicRequestSigner.sign("{}"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
