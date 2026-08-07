package dev.darsma.wearkey

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Settings screen (spec §3.2 / §11.5). A plain View-based Activity — Compose is allowed for
 * settings per spec §8.0, but this screen is trivial and pulling the Compose runtime in just for
 * it would add megabytes for no benefit.
 *
 * Wear-friendly: a vertical scroll of large, full-width rows, black background, generous top and
 * bottom padding so the first and last rows clear the round bezel.
 */
class SettingsActivity : Activity() {

    private lateinit var hapticStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = SettingsStore(this)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Round-display safe area: keep the first/last rows off the bezel curve.
            setPadding(dp(16), dp(56), dp(16), dp(56))
        }
        scroll.addView(column)

        column.addView(title(getString(R.string.settings_title), dp(8)))

        // Haptic intensity — cycles Off / Low / Medium / High (spec §8.1 requires a full-off
        // option and a user-facing intensity control).
        hapticStatus = TextView(this)
        column.addView(
            row(getString(R.string.settings_haptics), hapticStatus) {
                prefs.hapticIntensity = when (prefs.hapticIntensity) {
                    0f -> 0.33f
                    0.33f -> 0.66f
                    0.66f -> 1f
                    else -> 0f
                }
                updateHapticLabel(prefs)
            }
        )
        updateHapticLabel(prefs)

        // Clear all learned data (spec §11.5 mandatory one-tap action).
        column.addView(
            row(getString(R.string.settings_clear_data), null) {
                EncryptedClipboardPersistence(this).clear()
                SettingsStore(this).markClipboardClearRequested()
                // Emoji recents are usage history too. "Clear data" that left them behind would
                // be a false promise, and the user has no other way to reach them.
                EmojiRecentsStore(this).clear()
                toast(getString(R.string.settings_cleared))
            }
        )

        // Touch calibration (spec §7.1). The drift model's constants are shipped as estimates;
        // this is how they become measurements for a particular wrist and finger.
        column.addView(
            row(getString(R.string.settings_calibration), null) {
                startActivity(Intent(this, CalibrationActivity::class.java))
            }
        )

        // Frame-time measurement (spec §14). Developer-facing, but it lives here rather than behind
        // a hidden gesture: the gate has to be re-checked after any rendering change, and a
        // measurement that is awkward to take is a measurement that stops being taken.
        column.addView(
            row(getString(R.string.settings_frame_stats), null) {
                startActivity(Intent(this, FrameStatsActivity::class.java))
            }
        )

        // Open source licenses — REQUIRED to be viewable offline in-app (spec §3.2). BSD
        // redistribution terms are not satisfied by a link, and a link would break the
        // no-network rule anyway.
        column.addView(
            row(getString(R.string.settings_tutorial), null) {
                startActivity(Intent(this, TutorialActivity::class.java))
            }
        )

        column.addView(
            row(getString(R.string.settings_language_packs), null) {
                startActivity(Intent(this, LanguagePacksActivity::class.java))
            }
        )

        column.addView(
            row(getString(R.string.settings_licenses), null) {
                startActivity(Intent(this, LicensesActivity::class.java))
            }
        )

        column.addView(
            caption(getString(R.string.settings_privacy_note), dp(16))
        )

        setContentView(scroll)

        // Spec §11.5: the tutorial fires on first launch. Doing it from here rather than from the
        // IME is deliberate — an InputMethodService that starts an Activity would steal focus from
        // the field the user is trying to fill in.
        TutorialActivity.launchIfFirstRun(this)
    }

    private fun updateHapticLabel(prefs: SettingsStore) {
        hapticStatus.text = when (prefs.hapticIntensity) {
            0f -> getString(R.string.settings_haptics_off)
            0.33f -> getString(R.string.settings_haptics_low)
            0.66f -> getString(R.string.settings_haptics_medium)
            else -> getString(R.string.settings_haptics_high)
        }
    }

    private fun title(text: String, bottomPad: Int): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, bottomPad)
        }

    private fun caption(text: String, topPad: Int): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, topPad, 0, 0)
        }

    /** A tappable row: a title line, an optional value line, and a click action. */
    private fun row(label: String, valueView: TextView?, onClick: () -> Unit): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#1C1C1E"))
                cornerRadius = dp(16).toFloat()
            }
            background = bg
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            layoutParams = lp
            isClickable = true
            isFocusable = true

            addView(TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 15f
            })
            if (valueView != null) {
                valueView.setTextColor(Color.parseColor("#00E5FF"))
                valueView.textSize = 13f
                addView(valueView)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }
}
