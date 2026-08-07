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

    /** True once the first-run tutorial has been completed or skipped (spec §11.5). */
    fun hasSeenTutorial(): Boolean = prefs.getBoolean(KEY_TUTORIAL_SEEN, false)

    fun markTutorialSeen() {
        prefs.edit().putBoolean(KEY_TUTORIAL_SEEN, true).apply()
    }

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

    // --- Pending calibration -------------------------------------------------------------
    //
    // A completed-but-unaccepted fit. Stored separately from the applied values on purpose: it
    // must survive the activity dying without ever changing how a tap resolves. A calibration
    // session costs 25 deliberate taps, and holding that only in memory loses it to a screen
    // timeout or a reclaimed process — which is precisely what happened on the first real run.

    val pendingDriftPx: Float?
        get() = if (prefs.contains(KEY_PENDING_DRIFT_PX)) prefs.getFloat(KEY_PENDING_DRIFT_PX, 0f) else null

    val pendingDriftExponent: Float?
        get() = if (prefs.contains(KEY_PENDING_EXPONENT)) prefs.getFloat(KEY_PENDING_EXPONENT, 2f) else null

    /** Improvement the pending fit claimed, so the offer can be described without recomputing. */
    val pendingImprovementPercent: Float
        get() = prefs.getFloat(KEY_PENDING_IMPROVEMENT, 0f)

    fun savePendingCalibration(driftPx: Float, exponent: Float, improvementPercent: Float) {
        prefs.edit()
            .putFloat(KEY_PENDING_DRIFT_PX, driftPx)
            .putFloat(KEY_PENDING_EXPONENT, exponent)
            .putFloat(KEY_PENDING_IMPROVEMENT, improvementPercent)
            .apply()
    }

    fun clearPendingCalibration() {
        prefs.edit()
            .remove(KEY_PENDING_DRIFT_PX)
            .remove(KEY_PENDING_EXPONENT)
            .remove(KEY_PENDING_IMPROVEMENT)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "wearkey_settings"
        private const val KEY_HAPTIC_INTENSITY = "haptic_intensity"
        private const val KEY_LAST_CLEAR = "last_clear_ms"
        private const val KEY_TUTORIAL_SEEN = "tutorial_seen"
        private const val KEY_DRIFT_PX = "touch_drift_px"
        private const val KEY_DRIFT_EXPONENT = "touch_drift_exponent"
        private const val KEY_PENDING_DRIFT_PX = "pending_drift_px"
        private const val KEY_PENDING_EXPONENT = "pending_drift_exponent"
        private const val KEY_PENDING_IMPROVEMENT = "pending_improvement"
    }
}
