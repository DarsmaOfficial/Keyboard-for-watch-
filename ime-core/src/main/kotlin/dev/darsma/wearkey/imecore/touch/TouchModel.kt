package dev.darsma.wearkey.imecore.touch

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A key's touch target — centroid and drawn size, in the key grid view's pixel space.
 *
 * [id] is the caller's index into its own key list; this module deliberately knows nothing about
 * key actions, labels or layouts.
 */
data class KeyTarget(
    val id: Int,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float
)

/** A touch point after correction, plus how far it was moved (useful for tests and telemetry-free debugging). */
data class CorrectedPoint(val x: Float, val y: Float, val shiftedBy: Float)

/**
 * Geometry of the round display expressed in the key grid view's coordinate space.
 *
 * [centerY] is routinely **negative**: the keyboard view occupies the lower part of the screen,
 * so the circle's centre sits above the view's own origin. Getting this sign wrong silently
 * inverts the radial correction, which is why it is passed explicitly rather than guessed.
 */
data class RoundDisplay(val centerX: Float, val centerY: Float, val radius: Float)

/**
 * Probabilistic key selection for a round display (spec §7.1).
 *
 * ## Why not rectangles
 *
 * Rectangular hit-testing treats a tap 1 px outside a key as belonging to no key at all, and a
 * tap 1 px inside as certain. Real touches are a distribution, not a point, and on a 233 dp round
 * display the keys nearest the bezel are clipped by the circle — so the geometric target is
 * smallest exactly where aiming is hardest. Per Fitts's law (`MT = a + b·log₂(2D/W)`) the
 * effective width `W` collapses at the perimeter and the error rate climbs.
 *
 * This model replaces boundary testing with a bivariate Gaussian per key and picks the most
 * likely key. Every tap resolves to *something* — which matters more than geometric purity,
 * because a keyboard that swallows a tap is worse than one that occasionally picks the neighbour.
 *
 * ## The scoring function, and why it is unnormalised
 *
 * Each key is modelled as a Gaussian centred on its centroid with per-axis spread proportional to
 * its drawn size (`σx = fx·w`, `σy = fy·h`). Keys are scored by squared Mahalanobis distance:
 *
 * ```
 * score(k) = -0.5 · ((dx/σx)² + (dy/σy)²)
 * ```
 *
 * The Gaussian normaliser `-log(2π σx σy)` is deliberately **omitted**, and that is a derivation
 * rather than an oversight. Including it penalises physically large keys: tapping just inside the
 * end of the spacebar would select the narrow key beside it, because the wide key's density is
 * spread thinner. The correct Bayesian statement is `P(k | t) ∝ P(t | k) · P(k)`, and the right
 * prior here is "the user aimed somewhere on this key", i.e. `P(k) ∝ area ∝ σx·σy`. That prior
 * exactly cancels the normaliser, leaving the unnormalised form above. So this *is* the posterior
 * under an area-proportional prior — verified against the real function row, where the spacebar is
 * roughly three times the width of its neighbours.
 *
 * ## Radial drift correction
 *
 * Spec §7.1 calls for an empirical offset **μ(x, y, r)** compensating "fat finger" centroid drift
 * toward the screen centre, scaled by distance from the radius. Implemented as: displace the
 * reported point *outward* along the radius by
 *
 * ```
 * |μ| = maxRadialDriftPx · (d / R)^driftExponent
 * ```
 *
 * so the correction vanishes at the centre and is strongest at the bezel. The exponent is >1
 * because the effect is negligible across the middle of the display and grows sharply where the
 * finger must roll onto the curved glass.
 *
 * These two constants are the *only* empirical values in this file, and they are honest defaults,
 * not measurements: they must be calibrated against real taps on the watch. [Config] exists so
 * that calibration changes one construction site rather than the algorithm.
 *
 * ## Extending edge keys to the physical display edge
 *
 * A tap beyond the outermost key of a row — on the glass curve where no key is drawn — is not
 * discarded. Because scoring is distance-based rather than containment-based, such a tap simply
 * resolves to the nearest key, which converts unusable clipped corner area into a valid target.
 * Correction is clamped to the display circle so a wild coordinate cannot be pushed to infinity.
 *
 * ## Hook for spatial prediction (§7.2b)
 *
 * [bestMatch] accepts an optional log-prior per key. Uniform today, but this is the seam where
 * eyes-free spatial prediction plugs in later: it needs exactly this per-key probability
 * distribution, deferring commitment until a whole word can be resolved against the dictionary.
 * Designing the seam now costs nothing; retrofitting it would mean rewriting the hot path.
 *
 * Pure Kotlin with no Android types, so all of this is unit-testable on the JVM.
 */
class TouchModel(
    /** Display geometry this model was built for; exposed so callers can rebuild with new tuning. */
    val display: RoundDisplay,
    private val config: Config = Config()
) {

    /**
     * Tunable parameters. Defaults are starting points for on-device calibration, not results.
     *
     * @property sigmaXFraction horizontal spread as a fraction of key width
     * @property sigmaYFraction vertical spread as a fraction of key height; smaller than the
     *   horizontal fraction on purpose, so a sloppy tap slides sideways within a row far more
     *   readily than it jumps to the row above or below — row jumps produce the most confusing
     *   errors because the wrong letter comes from a visually distant key
     * @property maxRadialDriftPx outward correction applied at the very edge of the display
     * @property driftExponent how sharply the correction grows with radial distance
     */
    data class Config(
        val sigmaXFraction: Float = 0.55f,
        val sigmaYFraction: Float = 0.42f,
        val maxRadialDriftPx: Float = 6f,
        val driftExponent: Float = 2.0f
    ) {
        init {
            require(sigmaXFraction > 0f && sigmaYFraction > 0f) { "sigma fractions must be positive" }
            require(maxRadialDriftPx >= 0f) { "drift cannot be negative" }
            require(driftExponent > 0f) { "drift exponent must be positive" }
        }
    }

    /**
     * Applies the radial drift correction to a reported touch point.
     *
     * Exposed separately from [bestMatch] so the correction can be tested — and inspected — without
     * involving any key layout.
     */
    fun correct(x: Float, y: Float): CorrectedPoint {
        val dx = x - display.centerX
        val dy = y - display.centerY
        val distance = sqrt(dx * dx + dy * dy)

        // At the exact centre the radial direction is undefined; there is also nothing to correct.
        if (distance < EPSILON || display.radius < EPSILON) return CorrectedPoint(x, y, 0f)

        val normalised = (distance / display.radius).coerceIn(0f, 1f)
        val magnitude = config.maxRadialDriftPx * normalised.pow(config.driftExponent)

        // Never push a point outside the physical display: a correction that lands on the bezel
        // describes a touch that cannot exist.
        val maxOutward = (display.radius - distance).coerceAtLeast(0f)
        val applied = minOf(magnitude, maxOutward)
        if (applied < EPSILON) return CorrectedPoint(x, y, 0f)

        val ux = dx / distance
        val uy = dy / distance
        return CorrectedPoint(x + ux * applied, y + uy * applied, applied)
    }

    /**
     * Returns the most likely key for a touch, or null when [targets] is empty.
     *
     * @param logPrior optional additive log-prior per key id — the §7.2b seam. Must return 0f for
     *   a uniform prior; values are added to the log-likelihood, so a prior of `ln(2)` makes a key
     *   twice as likely a priori.
     */
    fun bestMatch(
        x: Float,
        y: Float,
        targets: List<KeyTarget>,
        logPrior: ((Int) -> Float)? = null
    ): KeyTarget? {
        if (targets.isEmpty()) return null

        val point = correct(x, y)
        var best: KeyTarget? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for (target in targets) {
            var score = logLikelihood(point.x, point.y, target)
            if (logPrior != null) score += logPrior(target.id)
            if (score > bestScore) {
                bestScore = score
                best = target
            }
        }
        return best
    }

    /**
     * Unnormalised log-likelihood that [target] was intended, given an already-corrected point.
     * See the class documentation for why the Gaussian normaliser is absent.
     */
    fun logLikelihood(correctedX: Float, correctedY: Float, target: KeyTarget): Float {
        val sigmaX = (target.width * config.sigmaXFraction).coerceAtLeast(MIN_SIGMA)
        val sigmaY = (target.height * config.sigmaYFraction).coerceAtLeast(MIN_SIGMA)
        val nx = (correctedX - target.centerX) / sigmaX
        val ny = (correctedY - target.centerY) / sigmaY
        return -0.5f * (nx * nx + ny * ny)
    }

    /**
     * Full posterior over keys, normalised to sum to 1.
     *
     * Not used by plain tap input, which only needs the argmax, but it is the input spatial
     * prediction (§7.2b) will consume, and it makes the model's behaviour inspectable in tests.
     */
    fun distribution(
        x: Float,
        y: Float,
        targets: List<KeyTarget>,
        logPrior: ((Int) -> Float)? = null
    ): Map<Int, Float> {
        if (targets.isEmpty()) return emptyMap()
        val point = correct(x, y)

        val scores = targets.associate { target ->
            var s = logLikelihood(point.x, point.y, target)
            if (logPrior != null) s += logPrior(target.id)
            target.id to s
        }
        // Subtract the maximum before exponentiating — standard log-sum-exp guard against
        // underflow, which matters because scores are unbounded below.
        val max = scores.values.max()
        val weights = scores.mapValues { (_, s) -> kotlin.math.exp((s - max).toDouble()).toFloat() }
        val total = weights.values.sum()
        return if (total < EPSILON) weights else weights.mapValues { (_, w) -> w / total }
    }

    private companion object {
        const val EPSILON = 1e-4f

        /** Guards against a degenerate zero-size key producing a division by zero. */
        const val MIN_SIGMA = 0.5f
    }
}

/** True when [a] and [b] agree to within [tolerance]; used by tests and geometry assertions. */
internal fun nearlyEquals(a: Float, b: Float, tolerance: Float = 1e-3f): Boolean =
    abs(a - b) <= tolerance
