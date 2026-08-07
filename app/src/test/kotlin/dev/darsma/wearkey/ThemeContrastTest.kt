package dev.darsma.wearkey

import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Contrast and power properties of the keyboard themes (spec §8, §11 v0.3).
 *
 * These are pure colour arithmetic, so they run on the JVM without an Android context. The values
 * are duplicated from `KeyboardTheme` rather than imported because `:app` unit tests cannot load
 * `android.graphics.Color`, whose methods are stubs that throw outside a device.
 */
class ThemeContrastTest {

    private data class Theme(
        val id: String,
        val background: Int,
        val letterKey: Int,
        val label: Int,
        val accent: Int
    )

    private val themes = listOf(
        Theme("midnight", 0xFF000000.toInt(), 0xFF1C1C1E.toInt(), 0xFFFFFFFF.toInt(), 0xFF00E5FF.toInt()),
        Theme("high_contrast", 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFF00.toInt()),
        Theme("amber", 0xFF000000.toInt(), 0xFF1A1410.toInt(), 0xFFFFE0B2.toInt(), 0xFFFFB300.toInt())
    )

    /** Relative luminance per WCAG 2.1. */
    private fun luminance(color: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = channel((color shr 16) and 0xFF)
        val g = channel((color shr 8) and 0xFF)
        val b = channel(color and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /**
     * Every theme must keep a pure black background.
     *
     * Spec §8 requires `#000000` for a physical reason: on OLED a black pixel is a switched-off
     * sub-pixel, so the background costs no power. A theme that lightened it would light every
     * pixel for as long as the keyboard is open.
     */
    @Test
    fun `every theme keeps the AMOLED black background`() {
        for (theme in themes) {
            assertTrue(
                theme.background == 0xFF000000.toInt(),
                "${theme.id} must use #000000, not ${Integer.toHexString(theme.background)}"
            )
        }
    }

    /**
     * Labels must clear WCAG AA for large text (3:1) against their own key fill.
     *
     * Key labels are large and bold at watch size, so AA-large is the applicable threshold rather
     * than the 4.5:1 body-text figure.
     */
    @Test
    fun `key labels meet WCAG AA large against the key fill`() {
        for (theme in themes) {
            val ratio = contrast(theme.label, theme.letterKey)
            assertTrue(ratio >= 3.0, "${theme.id}: label/key contrast is %.2f, need 3.0".format(ratio))
        }
    }

    /** The high-contrast theme should be substantially better than the default, not merely different. */
    @Test
    fun `high contrast theme beats the default`() {
        val default = themes.first { it.id == "midnight" }
        val high = themes.first { it.id == "high_contrast" }
        val defaultRatio = contrast(default.label, default.letterKey)
        val highRatio = contrast(high.label, high.letterKey)
        assertTrue(
            highRatio > defaultRatio,
            "high contrast (%.2f) must exceed default (%.2f)".format(highRatio, defaultRatio)
        )
        assertTrue(highRatio >= 15.0, "high contrast should be near maximal, got %.2f".format(highRatio))
    }

    /** An accent used as a fill must still take a readable label, which is the background colour. */
    @Test
    fun `accent fill remains readable with an inverted label`() {
        for (theme in themes) {
            val ratio = contrast(theme.background, theme.accent)
            assertTrue(
                ratio >= 3.0,
                "${theme.id}: accent/background contrast is %.2f, need 3.0".format(ratio)
            )
        }
    }
}
