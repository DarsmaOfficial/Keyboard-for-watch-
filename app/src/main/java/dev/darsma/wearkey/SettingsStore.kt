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

    companion object {
        private const val PREFS_NAME = "wearkey_settings"
        private const val KEY_HAPTIC_INTENSITY = "haptic_intensity"
        private const val KEY_LAST_CLEAR = "last_clear_ms"
    }
}
