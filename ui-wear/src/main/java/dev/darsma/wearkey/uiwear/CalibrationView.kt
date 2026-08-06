package dev.darsma.wearkey.uiwear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.darsma.wearkey.imecore.touch.CalibrationSample
import dev.darsma.wearkey.imecore.touch.RoundDisplay
import dev.darsma.wearkey.imecore.touch.TouchCalibration
import dev.darsma.wearkey.imecore.touch.CalibrationFit
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Collects calibration taps for the §7.1 drift model.
 *
 * ## Why this screen exists
 *
 * The drift constants shipped with the touch model were plausible defaults, not measurements. The
 * on-device tests that "passed" did so with enough margin that they never actually constrained
 * those constants — and the effect being corrected is finger-pad geometry and wrist angle, which
 * synthetic ADB touches do not reproduce. Only a human tapping a real target can supply that data.
 *
 * ## Design
 *
 * A single dot appears; the user taps it; it moves. Nothing else is on screen, because anything
 * else would be something to look at other than the target — and where the eye goes, the finger
 * follows, which would corrupt the very measurement being taken.
 *
 * Targets are placed on a **golden-angle spiral** rather than a grid or a ring. Two reasons:
 * a ring at fixed radius leaves the exponent mathematically unconstrained (the fitter handles that
 * case, but the data would be uninformative), and a grid produces clusters at similar radii while
 * leaving others empty. The spiral spreads targets evenly across both radius and angle, which is
 * exactly what the two-parameter fit needs, and it never places two consecutive targets adjacent —
 * so the user cannot fall into a rhythm and stop aiming.
 *
 * Radii span 20%–95% of the display radius. Below 20% the radial direction is ill-conditioned and
 * the fitter discards the samples anyway; beyond 95% the target would sit under the bezel curve.
 *
 * The first tap is discarded deliberately: it is the one where the user is still working out what
 * the screen is asking of them.
 */
class CalibrationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    fun interface OnCompleteListener {
        /** [fit] is null when the collected taps did not support a trustworthy fit. */
        fun onComplete(fit: CalibrationFit?, samples: List<CalibrationSample>)
    }

    var onCompleteListener: OnCompleteListener? = null

    private val samples = mutableListOf<CalibrationSample>()
    private var targetIndex = 0
    private var targetX = 0f
    private var targetY = 0f
    private var lastTouchX = -1f
    private var lastTouchY = -1f
    private var showingHit = false

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A2A")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val hitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private val density: Float get() = resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        textPaint.textSize = 13f * density
        placeTarget()
    }

    /** Golden-angle spiral placement — see the class documentation for why. */
    private fun placeTarget() {
        if (width == 0 || height == 0) return
        val radius = min(width, height) / 2f
        val progress = targetIndex.toFloat() / (TOTAL_TARGETS - 1).coerceAtLeast(1)
        val distance = radius * (MIN_FRACTION + (MAX_FRACTION - MIN_FRACTION) * progress)
        val angle = targetIndex * GOLDEN_ANGLE_RAD
        targetX = width / 2f + distance * cos(angle)
        targetY = height / 2f + distance * sin(angle)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true

        lastTouchX = event.x
        lastTouchY = event.y
        showingHit = true

        // Discard the first tap: the user is still reading the screen, not aiming at it.
        if (targetIndex > 0) {
            samples.add(CalibrationSample(targetX, targetY, event.x, event.y))
        }

        targetIndex++
        if (targetIndex >= TOTAL_TARGETS) {
            finish()
        } else {
            // Brief pause so the user sees where they actually landed relative to the target —
            // this is the only feedback in the flow, and it is what makes the drift visible.
            postDelayed({
                showingHit = false
                placeTarget()
            }, HIT_FEEDBACK_MS)
        }
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun finish() {
        val radius = min(width, height) / 2f
        // In this Activity the view fills the round display, so the circle centre is the view
        // centre. That is NOT true inside the keyboard, where the view sits below the centre —
        // hence the geometry is passed explicitly rather than assumed anywhere.
        val display = RoundDisplay(width / 2f, height / 2f, radius)
        val fit = TouchCalibration.fit(samples, display)
        onCompleteListener?.onComplete(fit, samples.toList())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)

        val remaining = (TOTAL_TARGETS - targetIndex).coerceAtLeast(0)
        canvas.drawText(
            resources.getString(R.string.calibration_remaining, remaining),
            width / 2f,
            height * 0.16f,
            textPaint
        )

        // Faint ring at the target's radius: gives the eye a reference for where the dot sits
        // without drawing anything near the dot itself.
        val cx = width / 2f
        val cy = height / 2f
        val dx = targetX - cx
        val dy = targetY - cy
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        if (distance > 1f) canvas.drawCircle(cx, cy, distance, ringPaint)

        if (showingHit && lastTouchX >= 0f) {
            canvas.drawCircle(lastTouchX, lastTouchY, 4f * density, hitPaint)
        }
        canvas.drawCircle(targetX, targetY, TARGET_RADIUS_DP * density, targetPaint)
    }

    private companion object {
        /**
         * 25 targets: enough to over-satisfy the fitter's 8-sample minimum after the discarded
         * first tap and outlier rejection, while staying under about a minute of tapping. Longer
         * sessions produce worse data, not more of it — attention drifts and so does aim.
         */
        const val TOTAL_TARGETS = 25

        const val MIN_FRACTION = 0.20f
        const val MAX_FRACTION = 0.95f

        /** Golden angle in radians — consecutive targets never land adjacent. */
        const val GOLDEN_ANGLE_RAD = 2.39996f

        const val TARGET_RADIUS_DP = 7f
        const val HIT_FEEDBACK_MS = 260L
    }
}
