package dev.darsma.wearkey.uiwear

import android.animation.ValueAnimator
import android.content.Context
import android.os.PowerManager

/** Central motion gate: system reduced-motion and battery saver are authoritative. */
internal object MotionPolicy {
    fun essentialAnimationEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    fun decorativeAnimationEnabled(context: Context): Boolean {
        if (!ValueAnimator.areAnimatorsEnabled()) return false
        return context.getSystemService(PowerManager::class.java)?.isPowerSaveMode != true
    }
}
