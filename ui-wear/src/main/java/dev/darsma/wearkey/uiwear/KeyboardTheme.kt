package dev.darsma.wearkey.uiwear

import android.graphics.Color

/**
 * Colour scheme for the keyboard surface (spec §8, §11 v0.3).
 *
 * ## Why every theme keeps a pure black background
 *
 * Spec §8 requires `#000000`, and the reason is physical rather than aesthetic: on the W5's OLED
 * panel a black pixel is a switched-off sub-pixel, so the background costs no power at all. A
 * "dark grey" theme would light every pixel on screen for the entire time the keyboard is open,
 * which on a watch battery is a real regression. Themes therefore vary the *keys*, not the canvas.
 *
 * ## Why colours are values here rather than Android resources
 *
 * A `res/values-night` colour set would be resolved by the framework at inflation time, but this
 * keyboard draws on a `Canvas` and picks colours per key and per state (pressed, shift-active,
 * function vs letter). Those decisions cannot be expressed as a static resource lookup, and
 * splitting them between XML and code would mean two places to look when a colour is wrong.
 */
data class KeyboardTheme(
    val id: String,
    val background: Int,
    val letterKey: Int,
    val functionKey: Int,
    val pressedKey: Int,
    val accent: Int,
    val label: Int,
    /** Outline width in dp; 0 disables outlines. Only high contrast uses them. */
    val keyStrokeDp: Float = 0f,
    val keyStroke: Int = Color.TRANSPARENT
) {
    companion object {

        /** The default. AMOLED black canvas with near-black keys — quiet, and cheap to display. */
        val MIDNIGHT = KeyboardTheme(
            id = "midnight",
            background = Color.BLACK,
            letterKey = Color.parseColor("#1C1C1E"),
            functionKey = Color.parseColor("#3A3A3C"),
            pressedKey = Color.parseColor("#4A4A4E"),
            accent = Color.parseColor("#00E5FF"),
            label = Color.WHITE
        )

        /**
         * Maximum legibility for low vision or bright sunlight (spec §8: "high-contrast option").
         *
         * Keys stay black and gain a white outline instead of a light fill. That is deliberate and
         * is the opposite of what "high contrast" usually implies: filling every key white would
         * produce a large static bright region, which §8 explicitly warns against, and on OLED it
         * both burns power and risks image retention. An outline gives the same edge definition —
         * which is what actually separates one key from the next — at almost no lit area.
         */
        val HIGH_CONTRAST = KeyboardTheme(
            id = "high_contrast",
            background = Color.BLACK,
            letterKey = Color.BLACK,
            functionKey = Color.parseColor("#101010"),
            pressedKey = Color.WHITE,
            accent = Color.parseColor("#FFFF00"),
            label = Color.WHITE,
            keyStrokeDp = 1.5f,
            keyStroke = Color.WHITE
        )

        /**
         * Warmer, lower-blue palette for night use.
         *
         * Amber rather than cyan: long-wavelength light is the least disruptive to dark adaptation,
         * so a glance at the watch at 3am does not cost several minutes of night vision.
         */
        val AMBER = KeyboardTheme(
            id = "amber",
            background = Color.BLACK,
            letterKey = Color.parseColor("#1A1410"),
            functionKey = Color.parseColor("#332619"),
            pressedKey = Color.parseColor("#4A3823"),
            accent = Color.parseColor("#FFB300"),
            label = Color.parseColor("#FFE0B2")
        )

        val ALL = listOf(MIDNIGHT, HIGH_CONTRAST, AMBER)

        /** Looks up by id, falling back to the default rather than throwing on unknown input. */
        fun byId(id: String?): KeyboardTheme = ALL.firstOrNull { it.id == id } ?: MIDNIGHT
    }
}
