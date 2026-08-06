package dev.darsma.wearkey

import android.content.Context
import android.content.SharedPreferences

/**
 * Non-sensitive preferences (haptic intensity, layout prefs).
 *
 * These live in **device-protected storage** (spec §6: keep layout/UI prefs in DE, clipboard in
 * CE). DE storage is readable before first unlock, which is exactly what we want for something
 * as harmless as haptic intensity — and it means the keyboard's feel is correct the very first
 * time it is shown after a reboot, before any credential is entered.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val deContext = runCatching { context.createDeviceProtectedStorageContext() }
            .getOrDefault(context)
        deContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 0f = off, up to 1f = full. Read by KeyGridView's HapticFeedback. */
    var hapticIntensity: Float
        get() = prefs.getFloat(KEY_HAPTIC_INTENSITY, 1f)
        set(value) = prefs.edit().putFloat(KEY_HAPTIC_INTENSITY, value.coerceIn(0f, 1f)).apply()

    fun markClipboardClearRequested() {
        prefs.edit().putLong(KEY_LAST_CLEAR, System.currentTimeMillis()).apply()
    }

    /** True once the user has run touch calibration and accepted the result (spec §7.1). */
    val hasTouchCalibration: Boolean
        get() = prefs.contains(KEY_DRIFT_PX)

    /**
     * Fitted radial drift magnitude in pixels, or null when uncalibrated.
     *
     * Null rather than a default, so the caller decides what "uncalibrated" means. Returning a
     * default here would make a calibrated-to-zero device indistinguishable from an uncalibrated
     * one, and zero is a legitimate fit — some people simply do not drift.
     */
    val touchDriftPx: Float?
        get() = if (prefs.contains(KEY_DRIFT_PX)) prefs.getFloat(KEY_DRIFT_PX, 0f) else null

    /** Fitted growth exponent of the drift correction, or null when uncalibrated. */
    val touchDriftExponent: Float?
        get() = if (prefs.contains(KEY_DRIFT_EXPONENT)) prefs.getFloat(KEY_DRIFT_EXPONENT, 2f) else null

    fun saveTouchCalibration(driftPx: Float, exponent: Float) {
        prefs.edit()
            .putFloat(KEY_DRIFT_PX, driftPx)
            .putFloat(KEY_DRIFT_EXPONENT, exponent)
            .apply()
    }

    /** Restores the shipped defaults. Always reachable — calibration must never be a trap. */
    fun clearTouchCalibration() {
        prefs.edit().remove(KEY_DRIFT_PX).remove(KEY_DRIFT_EXPONENT).apply()
    }

    companion object {
        private const val PREFS_NAME = "wearkey_settings"
        private const val KEY_HAPTIC_INTENSITY = "haptic_intensity"
        private const val KEY_LAST_CLEAR = "last_clear_ms"
        private const val KEY_DRIFT_PX = "touch_drift_px"
        private const val KEY_DRIFT_EXPONENT = "touch_drift_exponent"
    }
}
