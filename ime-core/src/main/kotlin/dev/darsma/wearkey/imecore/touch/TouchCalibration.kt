package dev.darsma.wearkey.imecore.touch

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * One recorded calibration tap: where the user was asked to aim, and where they actually touched.
 *
 * Coordinates are in the key grid view's pixel space, the same space [TouchModel] works in.
 */
data class CalibrationSample(
    val targetX: Float,
    val targetY: Float,
    val touchX: Float,
    val touchY: Float
)

/**
 * Result of fitting the radial drift model to recorded taps.
 *
 * @property maxRadialDriftPx fitted correction magnitude at the display rim
 * @property driftExponent fitted growth rate of the correction with radial distance
 * @property sampleCount how many samples survived filtering
 * @property meanAbsErrorBefore mean radial aiming error before correction, in pixels
 * @property meanAbsErrorAfter the same after applying the fitted correction
 */
data class CalibrationFit(
    val maxRadialDriftPx: Float,
    val driftExponent: Float,
    val sampleCount: Int,
    val meanAbsErrorBefore: Float,
    val meanAbsErrorAfter: Float
) {
    /** True when the fit actually reduced aiming error — the only reason to adopt it. */
    val isImprovement: Boolean get() = meanAbsErrorAfter < meanAbsErrorBefore

    /** Percentage reduction in mean aiming error; negative if the fit made things worse. */
    val improvementPercent: Float
        get() = if (meanAbsErrorBefore <= 0f) 0f
        else (meanAbsErrorBefore - meanAbsErrorAfter) / meanAbsErrorBefore * 100f
}

/**
 * Fits the empirical drift vector **μ(x, y, r)** of spec §7.1 from real taps.
 *
 * ## What is being measured
 *
 * When a user aims at a key near the bezel, the touch the digitiser reports lands systematically
 * *inward* — toward the screen centre — because the finger pad contacts the glass before the
 * fingertip reaches the intended point, and because the wrist rolls the finger as it approaches
 * the display edge. [TouchModel] corrects for this by displacing the reported point back outward,
 * with a magnitude that vanishes at the centre and grows toward the rim:
 *
 * ```
 * |μ| = maxRadialDriftPx · (d / R)^driftExponent
 * ```
 *
 * This class recovers those two constants from data rather than assuming them. That distinction
 * matters: the values shipped before calibration were plausible guesses, and the on-device tests
 * that "passed" did so with enough margin that they never constrained the constants at all.
 *
 * ## Why the fit is done in log space
 *
 * Writing `e` for the observed inward error along the radius and `u = d / R` for normalised radial
 * distance, the model is `e = A · u^k`. Taking logarithms linearises it:
 *
 * ```
 * ln(e) = ln(A) + k · ln(u)
 * ```
 *
 * so ordinary least squares over `(ln u, ln e)` recovers both parameters directly, with no
 * iterative optimiser and no dependency. On a watch that matters — this runs on the device.
 *
 * The trade-off is honest and worth stating: fitting in log space minimises *relative* rather than
 * absolute error, which weights the small-error samples near the display centre more heavily than
 * a linear fit would. For this model that is the desired behaviour, because those samples are
 * exactly the ones that pin the exponent.
 *
 * ## Robustness
 *
 * A calibration session is short and a user will mis-tap occasionally. Guards, in order:
 *
 * - Samples whose radial error is not strictly positive contribute no information about *outward*
 *   drift and are dropped (`ln` of a non-positive number is undefined anyway).
 * - Samples nearer the centre than [MIN_NORMALISED_RADIUS] are dropped: near the centre the radial
 *   direction is ill-conditioned, so a tiny error yields a huge `ln u` leverage.
 * - Gross outliers beyond [OUTLIER_SIGMA] standard deviations of radial error are removed once,
 *   which handles the "tapped the wrong key entirely" case without discarding honest sloppiness.
 * - The fitted parameters are clamped to sane ranges, so a pathological session cannot produce a
 *   keyboard that is unusable.
 * - The caller is told the before/after error, and [CalibrationFit.isImprovement] exists so a fit
 *   that makes aiming *worse* can be rejected rather than adopted out of politeness to the maths.
 */
object TouchCalibration {

    /** Minimum samples for a fit worth trusting. Below this, keep the existing configuration. */
    const val MIN_SAMPLES = 8

    /** Samples closer to the centre than this fraction of the radius are ill-conditioned. */
    private const val MIN_NORMALISED_RADIUS = 0.15f

    /** Radial errors beyond this many standard deviations are treated as mis-taps. */
    private const val OUTLIER_SIGMA = 2.5f

    private const val MIN_DRIFT_PX = 0f
    private const val MAX_DRIFT_PX = 24f
    private const val MIN_EXPONENT = 0.5f
    private const val MAX_EXPONENT = 5f

    /**
     * Fits [CalibrationFit] from [samples] taken on the given [display].
     *
     * Returns null when there is not enough usable data — the caller should then keep the current
     * configuration rather than adopting a noisy fit.
     */
    fun fit(samples: List<CalibrationSample>, display: RoundDisplay): CalibrationFit? {
        if (samples.size < MIN_SAMPLES || display.radius <= 0f) return null

        // Project each sample's aiming error onto the radial direction of its target. A positive
        // value means the touch landed inward of the target — the effect being corrected.
        data class Point(val normalisedRadius: Float, val inwardError: Float)

        val points = samples.mapNotNull { s ->
            val dx = s.targetX - display.centerX
            val dy = s.targetY - display.centerY
            val distance = sqrt(dx * dx + dy * dy)
            if (distance < 1e-3f) return@mapNotNull null

            val u = distance / display.radius
            if (u < MIN_NORMALISED_RADIUS) return@mapNotNull null

            // Unit vector pointing outward from the display centre through the target.
            val ux = dx / distance
            val uy = dy / distance
            // Component of (target - touch) along that direction: positive when the touch fell short.
            val inward = (s.targetX - s.touchX) * ux + (s.targetY - s.touchY) * uy
            Point(u, inward)
        }
        if (points.size < MIN_SAMPLES) return null

        // Remove gross mis-taps once, using the spread of the radial errors themselves.
        val mean = points.map { it.inwardError }.average().toFloat()
        val variance = points.map { (it.inwardError - mean).let { d -> d * d } }.average().toFloat()
        val sigma = sqrt(variance)
        val kept = if (sigma > 1e-3f) {
            points.filter { abs(it.inwardError - mean) <= OUTLIER_SIGMA * sigma }
        } else {
            points
        }
        if (kept.size < MIN_SAMPLES) return null

        val errorBefore = kept.map { abs(it.inwardError) }.average().toFloat()

        // Only samples with genuine inward drift inform the power law.
        val fittable = kept.filter { it.inwardError > MIN_FITTABLE_ERROR_PX }
        if (fittable.size < MIN_SAMPLES) {
            // No systematic inward drift worth correcting. That is a legitimate outcome, not a
            // failure: report a zero correction so the caller can adopt it knowingly.
            return CalibrationFit(
                maxRadialDriftPx = 0f,
                driftExponent = 2f,
                sampleCount = kept.size,
                meanAbsErrorBefore = errorBefore,
                meanAbsErrorAfter = errorBefore
            )
        }

        // Least squares on ln(e) = ln(A) + k·ln(u).
        val xs = fittable.map { ln(it.normalisedRadius.toDouble()) }
        val ys = fittable.map { ln(it.inwardError.toDouble()) }
        val xMean = xs.average()
        val yMean = ys.average()

        var sxy = 0.0
        var sxx = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - xMean
            sxy += dx * (ys[i] - yMean)
            sxx += dx * dx
        }

        // Degenerate case: every sample sits at the same radius, so the exponent is unconstrained.
        // Keep the default exponent and solve only for the magnitude.
        val exponent = if (sxx < 1e-9) 2.0 else (sxy / sxx)
        val logA = yMean - exponent * xMean
        val amplitudeAtRim = kotlin.math.exp(logA)

        val fittedDrift = amplitudeAtRim.toFloat().coerceIn(MIN_DRIFT_PX, MAX_DRIFT_PX)
        val fittedExponent = exponent.toFloat().coerceIn(MIN_EXPONENT, MAX_EXPONENT)

        // Report the residual error the fitted correction would leave behind, so the caller can
        // verify the fit actually helps before adopting it.
        val errorAfter = kept.map { p ->
            val applied = fittedDrift * p.normalisedRadius.pow(fittedExponent)
            abs(p.inwardError - applied)
        }.average().toFloat()

        return CalibrationFit(
            maxRadialDriftPx = fittedDrift,
            driftExponent = fittedExponent,
            sampleCount = kept.size,
            meanAbsErrorBefore = errorBefore,
            meanAbsErrorAfter = errorAfter
        )
    }

    /** Below this, an "error" is indistinguishable from digitiser noise. */
    private const val MIN_FITTABLE_ERROR_PX = 0.5f
}
