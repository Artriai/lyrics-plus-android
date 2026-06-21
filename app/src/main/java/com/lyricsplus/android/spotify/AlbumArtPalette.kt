package com.lyricsplus.android.spotify

import android.graphics.Bitmap
import android.graphics.Color
import androidx.collection.LruCache
import androidx.palette.graphics.Palette

data class AlbumArtPalette(
    val start: String,
    val end: String,
    val accent: String
)

object AlbumArtPaletteExtractor {
    // Cache palette results by bitmap identity to avoid re-extracting
    // when the same album art is used (e.g. switching lyrics sources).
    private val paletteCache = LruCache<Int, List<AlbumArtPalette>>(16)

    fun fromBitmap(bitmap: Bitmap?): AlbumArtPalette? {
        return extractTemplates(bitmap).firstOrNull()
    }

    fun extractTemplates(bitmap: Bitmap?): List<AlbumArtPalette> {
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return emptyList()

        val key = System.identityHashCode(bitmap)
        paletteCache.get(key)?.let { return it }

        val palette = try {
            Palette.from(bitmap).generate()
        } catch (e: Exception) {
            return emptyList()
        }

        val vibrant = palette.vibrantSwatch
        val darkVibrant = palette.darkVibrantSwatch
        val lightVibrant = palette.lightVibrantSwatch
        val muted = palette.mutedSwatch
        val darkMuted = palette.darkMutedSwatch
        val lightMuted = palette.lightMutedSwatch
        val dominant = palette.dominantSwatch

        // Fallbacks hierarchy
        val dominantRgb = dominant?.rgb ?: vibrant?.rgb ?: Color.parseColor("#181A19")
        val vibrantRgb = vibrant?.rgb ?: dominantRgb
        val darkVibrantRgb = darkVibrant?.rgb ?: darkMuted?.rgb ?: dominantRgb
        val lightVibrantRgb = lightVibrant?.rgb ?: vibrantRgb
        val mutedRgb = muted?.rgb ?: vibrantRgb
        val darkMutedRgb = darkMuted?.rgb ?: darkVibrantRgb
        val lightMutedRgb = lightMuted?.rgb ?: mutedRgb

        // Create the 5 different visual styles
        // Template 1: Vibrant Blend (默认流光 - High energy, colorful)
        val vibrantStart = vibrantRgb.toAdjustedHex(saturation = 1.10f, value = 0.65f)
        val vibrantEnd = darkMutedRgb.toAdjustedHex(saturation = 0.80f, value = 0.24f)
        val vibrantAccent = lightVibrantRgb.toAdjustedHex(saturation = 1.20f, value = 0.75f)
        val vibrantTemplate = AlbumArtPalette(vibrantStart, vibrantEnd, vibrantAccent)

        // Template 2: Deep & Chill (深邃夜色 - Low brightness, nocturnal mood)
        val deepStart = darkVibrantRgb.toAdjustedHex(saturation = 0.90f, value = 0.35f)
        val deepEnd = darkMutedRgb.toAdjustedHex(saturation = 0.80f, value = 0.16f)
        val deepAccent = mutedRgb.toAdjustedHex(saturation = 1.00f, value = 0.50f)
        val deepTemplate = AlbumArtPalette(deepStart, deepEnd, deepAccent)

        // Template 3: Soft Muted (柔和浅雅 - Morandi pastels, subtle)
        val softStart = mutedRgb.toAdjustedHex(saturation = 0.80f, value = 0.50f)
        val softEnd = darkMutedRgb.toAdjustedHex(saturation = 0.80f, value = 0.22f)
        val softAccent = lightMutedRgb.toAdjustedHex(saturation = 0.90f, value = 0.60f)
        val softTemplate = AlbumArtPalette(softStart, softEnd, softAccent)

        // Template 4: Neon Contrast (电音霓虹 - Bold dynamic mix)
        val neonStart = lightVibrantRgb.toAdjustedHex(saturation = 1.20f, value = 0.68f)
        val neonEnd = darkVibrantRgb.toAdjustedHex(saturation = 1.00f, value = 0.22f)
        val neonAccent = vibrantRgb.toAdjustedHex(saturation = 1.30f, value = 0.78f)
        val neonTemplate = AlbumArtPalette(neonStart, neonEnd, neonAccent)

        // Template 5: Dominant Mono (主导单色 - Unified monochrome feel)
        val monoStart = dominantRgb.toAdjustedHex(saturation = 1.10f, value = 0.55f)
        val monoEnd = dominantRgb.toAdjustedHex(saturation = 0.80f, value = 0.18f)
        val monoAccent = dominantRgb.toAdjustedHex(saturation = 1.20f, value = 0.78f, hueShift = 30f)
        val monoTemplate = AlbumArtPalette(monoStart, monoEnd, monoAccent)

        val result = listOf(
            vibrantTemplate,
            deepTemplate,
            softTemplate,
            neonTemplate,
            monoTemplate
        )
        paletteCache.put(key, result)
        return result
    }

    private fun Int.toAdjustedHex(saturation: Float, value: Float, hueShift: Float = 0f): String {
        val hsv = FloatArray(3)
        Color.colorToHSV(this, hsv)
        hsv[0] = (hsv[0] + hueShift + 360f) % 360f
        hsv[1] = (hsv[1] * saturation).coerceIn(0.32f, 0.92f)
        hsv[2] = (hsv[2] * value).coerceIn(0.16f, 0.86f)
        val color = Color.HSVToColor(hsv)
        return "#%02X%02X%02X".format(Color.red(color), Color.green(color), Color.blue(color))
    }
}
