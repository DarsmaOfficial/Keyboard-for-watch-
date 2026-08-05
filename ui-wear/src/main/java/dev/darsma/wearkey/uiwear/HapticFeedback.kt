package dev.darsma.wearkey.uiwear

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptic feedback for key presses (spec §8.1).
 *
 * The target hardware (`aw-haptic-hv`) reports `capabilities = [AMPLITUDE_CONTROL]` and
 * `hapticChannelMaxAmplitude = 0.0` — meaning it supports amplitude but has **no** primitive or
 * composition support. So `VibrationEffect.createOneShot(duration, amplitude)` is the only
 * usable API here; `createPredefined` and `startComposition` would silently degrade.
 *
 * The durations and amplitudes below are the spec's map verbatim. They are short on purpose: a
 * keyboard vibrates on every keystroke, so anything longer reads as sluggish and costs battery.
 */
class HapticFeedback(context: Context) {

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()

    private val hasAmplitudeControl: Boolean = vibrator?.hasAmplitudeControl() ?: false

    /**
     * User-facing intensity, 0f..1f. 0 disables haptics entirely (spec §8.1 requires a full-off
     * option). Scales amplitude, never duration — shortening the pulse makes it feel broken
     * rather than gentle.
     */
    var intensity: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    enum class Feedback(val durationMs: Long, val amplitude: Int) {
        /** Alphanumeric key tap — crisp, low-latency confirmation. */
        KEY_TAP(8L, 120),
        /** Spacebar or layer shift — soft transition. */
        SPACE_OR_LAYER(10L, 60),
        /** Enter / primary action — firm execution. */
        ENTER(12L, 180),
        /** Backspace / delete — high-priority warning. */
        BACKSPACE(16L, 255),
        /** Caret scrub step — a light tick per character. */
        CARET_STEP(4L, 40)
    }

    fun perform(feedback: Feedback) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (intensity <= 0f) return

        val amplitude = (feedback.amplitude * intensity)
            .toInt()
            .coerceIn(1, 255)

        runCatching {
            val effect = if (hasAmplitudeControl) {
                VibrationEffect.createOneShot(feedback.durationMs, amplitude)
            } else {
                // No amplitude control: fall back to a plain pulse of the same duration rather
                // than dropping feedback entirely.
                VibrationEffect.createOneShot(feedback.durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            v.vibrate(effect)
        }
    }

    fun cancel() {
        runCatching { vibrator?.cancel() }
    }
}
