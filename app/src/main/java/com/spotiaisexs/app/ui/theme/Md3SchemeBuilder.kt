package com.spotiaisexs.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Rebuilds the app's dark Material You scheme from a single source hue,
 * mirroring `_applyMaterialYouScheme()` / `_applyMonochromeScheme()` in the
 * legacy app.js (which itself hand-rolled dynamicDarkColorScheme()-style
 * tonal roles in CSS HSL space). This is intentionally NOT the same
 * algorithm as Android's real HCT-based dynamic color — using that here
 * would shift every color relationship in the app and count as a redesign.
 * This is a literal, faithful port so nothing has to be re-approved visually.
 */
object Md3SchemeBuilder {

    /** Chroma levels (CSS HSL %), match app.js constants exactly. */
    private const val CHROMA_PRIMARY = 30
    private const val CHROMA_SECONDARY = 14
    private const val CHROMA_TERTIARY = 22
    private const val CHROMA_NEUTRAL = 5
    private const val CHROMA_NEUTRAL_VARIANT = 8

    /** Extracts just the hue (0-360) from a hex color — the only component
     *  app.js's _hexToHsl() result that _applyMaterialYouScheme() actually uses. */
    fun hueOf(hex: String): Int {
        val clean = hex.removePrefix("#")
        if (clean.length < 6) return 0
        val rgb = clean.take(6).toIntOrNull(16) ?: return 0
        val r = ((rgb ushr 16) and 0xFF) / 255f
        val g = ((rgb ushr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max == min) return 0
        val d = max - min
        val h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } / 6f
        return (h * 360f).roundToInt().let { ((it % 360) + 360) % 360 }
    }

    /** Full dynamic dark scheme, seeded from [hex]'s hue. Matches _applyMaterialYouScheme(). */
    @Suppress("UNUSED_PARAMETER")
    fun buildScheme(hex: String, amoled: Boolean, liquidGlass: Boolean = false): ColorScheme =
        buildDarkScheme(hex, amoled, liquidGlass)

    /** Dark scheme, seeded from [hex]'s hue. */
    fun buildDarkScheme(hex: String, amoled: Boolean, liquidGlass: Boolean = false): ColorScheme {
        val h = hueOf(hex)
        val hT = (h + 60) % 360
        return build(
            primaryHue = h,
            secondaryHue = h,
            tertiaryHue = hT,
            neutralHue = h,
            amoled = amoled,
            primaryOverride = vividAccent(hex),
        )
    }

    /**
     * Redesign: the accent is used as-is, vivid and functional (Spotify's
     * single-accent rule), instead of a pastel tone of its hue. Seeds that
     * would vanish on black (dark artwork colors) or blind (near-white) are
     * pulled into a readable lightness band; greys stay grey.
     */
    internal fun vividAccent(hex: String): Color? {
        val clean = hex.removePrefix("#")
        if (clean.length < 6) return null
        val rgb = clean.take(6).toIntOrNull(16) ?: return null
        val r = ((rgb ushr 16) and 0xFF) / 255f
        val g = ((rgb ushr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        val d = max - min
        val sat = if (d == 0f) 0f else d / (1f - abs(2f * l - 1f))
        if (sat < 0.08f) return null // grey seed: keep the tonal scheme
        val satPct = (sat.coerceAtLeast(0.5f) * 100f).roundToInt().coerceAtMost(100)
        val lightPct = (l.coerceIn(0.55f, 0.72f) * 100f).roundToInt()
        return hsl(hueOf(hex), satPct, lightPct)
    }

    /** Full dynamic light scheme, seeded from [hex]'s hue. */
    @Suppress("UNUSED_PARAMETER")
    fun buildLightScheme(hex: String, liquidGlass: Boolean = false): ColorScheme {
        val h = hueOf(hex)
        val hT = (h + 60) % 360
        return buildLight(
            primaryHue = h,
            secondaryHue = h,
            tertiaryHue = hT,
            neutralHue = h,
            monochrome = false,
        )
    }

    /** Pure grayscale dark scheme (all chroma = 0). Matches _applyMonochromeScheme(). */
    @Suppress("UNUSED_PARAMETER")
    fun buildMonochromeScheme(amoled: Boolean, liquidGlass: Boolean = false): ColorScheme =
        buildMonochromeDarkScheme(amoled, liquidGlass)

    /** Pure grayscale dark scheme. */
    fun buildMonochromeDarkScheme(amoled: Boolean, liquidGlass: Boolean = false): ColorScheme = build(
        primaryHue = 0,
        secondaryHue = 0,
        tertiaryHue = 0,
        neutralHue = 0,
        amoled = amoled,
        monochrome = true,
    )

    /** Pure grayscale light scheme (all chroma = 0). */
    @Suppress("UNUSED_PARAMETER")
    fun buildMonochromeLightScheme(liquidGlass: Boolean = false): ColorScheme = buildLight(
        primaryHue = 0,
        secondaryHue = 0,
        tertiaryHue = 0,
        neutralHue = 0,
        monochrome = true,
    )

    private fun build(
        primaryHue: Int,
        secondaryHue: Int,
        tertiaryHue: Int,
        neutralHue: Int,
        amoled: Boolean,
        monochrome: Boolean = false,
        primaryOverride: Color? = null,
    ): ColorScheme {
        val cP = if (monochrome) 0 else CHROMA_PRIMARY
        val cS = if (monochrome) 0 else CHROMA_SECONDARY
        val cT = if (monochrome) 0 else CHROMA_TERTIARY
        // Redesign: on pure black the surfaces are neutral greys, never tinted
        // by the accent — color comes from artwork and the accent only.
        val cN = if (monochrome || amoled) 0 else CHROMA_NEUTRAL
        val cNV = if (monochrome || amoled) 0 else CHROMA_NEUTRAL_VARIANT

        // Elevation ladder on black: ~#121212 / #1C1C1C / #292929 (lighter = higher).
        val bgL = if (amoled) 0 else 6
        val sc1L = if (amoled) 7 else 11
        val sc2L = if (amoled) 11 else 16
        val sc3L = if (amoled) 16 else 20
        val onSurfaceL = if (amoled) 96 else 90
        val onSurfaceVariantL = if (amoled) 70 else 80
        val primaryCol = primaryOverride ?: hsl(primaryHue, cP, 82)
        val onPrimaryCol = if (primaryOverride != null) {
            if (primaryOverride.luminance() > 0.35f) Color(0xFF1A0E08) else Color.White
        } else {
            hsl(primaryHue, cP, 16)
        }

        val backgroundCol = if (amoled) Color.Black else hsl(neutralHue, cN, bgL)
        val surfaceCol = if (amoled) Color.Black else hsl(neutralHue, cN, bgL)
        val surfaceLowCol = if (amoled) Color.Black else hsl(neutralHue, cN, bgL)
        val surfaceLowestCol = if (amoled) Color.Black else hsl(neutralHue, cN, (bgL - 2).coerceAtLeast(0))

        val scheme = darkColorScheme(
            primary = primaryCol,
            onPrimary = onPrimaryCol,
            primaryContainer = hsl(primaryHue, cP, if (amoled) 24 else 30),
            onPrimaryContainer = hsl(primaryHue, (cP - 5).coerceAtLeast(0), 90),

            secondary = hsl(secondaryHue, cS, 80),
            onSecondary = hsl(secondaryHue, cS, 16),
            secondaryContainer = hsl(secondaryHue, cS, if (amoled) 22 else 28),
            onSecondaryContainer = hsl(secondaryHue, (cS - 4).coerceAtLeast(0), 90),

            tertiary = hsl(tertiaryHue, cT, 80),
            onTertiary = hsl(tertiaryHue, cT, 16),
            tertiaryContainer = hsl(tertiaryHue, cT, if (amoled) 22 else 28),
            onTertiaryContainer = hsl(tertiaryHue, (cT - 6).coerceAtLeast(0), 90),

            error = hsl(0, if (monochrome) 45 else 45, 80),
            errorContainer = hsl(0, if (monochrome) 30 else 30, 25),
            onErrorContainer = hsl(0, if (monochrome) 25 else 25, 88),
            onError = hsl(0, 45, 16),

            background = backgroundCol,
            onBackground = hsl(neutralHue, cNV, onSurfaceL),
            surface = surfaceCol,
            onSurface = hsl(neutralHue, cNV, onSurfaceL),
            surfaceContainer = hsl(neutralHue, cN, sc1L),
            surfaceContainerHigh = hsl(neutralHue, cN, sc2L),
            surfaceContainerHighest = hsl(neutralHue, cN, sc3L),
            surfaceContainerLow = surfaceLowCol,
            surfaceContainerLowest = surfaceLowestCol,
            surfaceVariant = hsl(neutralHue, cNV, sc2L + 4),
            onSurfaceVariant = hsl(neutralHue, cNV, onSurfaceVariantL),

            outline = hsl(neutralHue, cNV, 60),
            outlineVariant = hsl(neutralHue, cNV, 28),

            inverseSurface = hsl(neutralHue, cNV, 90),
            inverseOnSurface = hsl(neutralHue, cNV, 20),
            inversePrimary = hsl(primaryHue, cP, 40),
            scrim = Color.Black,
        )

        return scheme
    }

    private fun buildLight(
        primaryHue: Int,
        secondaryHue: Int,
        tertiaryHue: Int,
        neutralHue: Int,
        monochrome: Boolean = false,
    ): ColorScheme {
        val cP = if (monochrome) 0 else CHROMA_PRIMARY + 15
        val cS = if (monochrome) 0 else CHROMA_SECONDARY + 8
        val cT = if (monochrome) 0 else CHROMA_TERTIARY + 12
        val cN = if (monochrome) 0 else 4
        val cNV = if (monochrome) 0 else 6

        return androidx.compose.material3.lightColorScheme(
            primary = hsl(primaryHue, cP, 40),
            onPrimary = Color.White,
            primaryContainer = hsl(primaryHue, cP, 90),
            onPrimaryContainer = hsl(primaryHue, cP, 10),

            secondary = hsl(secondaryHue, cS, 40),
            onSecondary = Color.White,
            secondaryContainer = hsl(secondaryHue, cS, 90),
            onSecondaryContainer = hsl(secondaryHue, cS, 10),

            tertiary = hsl(tertiaryHue, cT, 40),
            onTertiary = Color.White,
            tertiaryContainer = hsl(tertiaryHue, cT, 90),
            onTertiaryContainer = hsl(tertiaryHue, cT, 10),

            error = hsl(0, 70, 40),
            errorContainer = hsl(0, 70, 90),
            onErrorContainer = hsl(0, 70, 12),
            onError = Color.White,

            background = hsl(neutralHue, cN, 98),
            onBackground = hsl(neutralHue, cNV, 10),
            surface = hsl(neutralHue, cN, 98),
            onSurface = hsl(neutralHue, cNV, 10),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = hsl(neutralHue, cN, 96),
            surfaceContainer = hsl(neutralHue, cN, 94),
            surfaceContainerHigh = hsl(neutralHue, cN, 92),
            surfaceContainerHighest = hsl(neutralHue, cN, 90),
            surfaceVariant = hsl(neutralHue, cNV, 90),
            onSurfaceVariant = hsl(neutralHue, cNV, 30),

            outline = hsl(neutralHue, cNV, 50),
            outlineVariant = hsl(neutralHue, cNV, 80),

            inverseSurface = hsl(neutralHue, cNV, 20),
            inverseOnSurface = hsl(neutralHue, cNV, 95),
            inversePrimary = hsl(primaryHue, cP, 80),
            scrim = Color.Black,
        )
    }

    /**
     * HSL(h in 0-360, s in 0-100, l in 0-100) -> Compose Color.
     * Standard conversion — must match browsers' CSS hsl() exactly so every
     * token lines up with the values app.css previously rendered.
     */
    private fun hsl(h: Int, s: Int, l: Int): Color {
        val hf = ((h % 360) + 360) % 360 / 360f
        val sf = (s.coerceIn(0, 100)) / 100f
        val lf = (l.coerceIn(0, 100)) / 100f

        if (sf == 0f) return Color(lf, lf, lf)

        val q = if (lf < 0.5f) lf * (1 + sf) else lf + sf - lf * sf
        val p = 2 * lf - q

        fun hueToRgb(t: Float): Float {
            var tt = t
            if (tt < 0f) tt += 1f
            if (tt > 1f) tt -= 1f
            return when {
                tt < 1f / 6f -> p + (q - p) * 6f * tt
                tt < 1f / 2f -> q
                tt < 2f / 3f -> p + (q - p) * (2f / 3f - tt) * 6f
                else -> p
            }
        }

        val r = hueToRgb(hf + 1f / 3f)
        val g = hueToRgb(hf)
        val b = hueToRgb(hf - 1f / 3f)
        return Color(r, g, b)
    }
}

/** Kept for readability at call sites that only care about hue distance. */
internal fun hueDelta(a: Int, b: Int): Int = abs(a - b).let { minOf(it, 360 - it) }
