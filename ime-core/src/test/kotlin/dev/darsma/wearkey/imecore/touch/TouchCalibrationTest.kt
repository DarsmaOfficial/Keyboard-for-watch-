package dev.darsma.wearkey.imecore.touch

import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for fitting the empirical drift vector μ(x, y, r) of spec §7.1 from recorded taps.
 *
 * The central technique here is a round trip: synthesise samples that contain a *known* drift,
 * fit them, and check the fit recovers the constants that generated them. A fitter that cannot
 * recover its own model has no business being trusted with real data.
 */
class TouchCalibrationTest {

    private val display = RoundDisplay(centerX = 233f, centerY = -84f, radius = 233f)

    /**
     * Builds samples whose touches fall inward of their targets by exactly
     * `drift · (d/R)^exponent`, optionally with noise.
     */
    private fun synthesise(
        drift: Float,
        exponent: Float,
        count: Int = 40,
        noisePx: Float = 0f,
        seed: Int = 7
    ): List<CalibrationSample> {
        val random = Random(seed)
        return (0 until count).map { i ->
            // Spread targets across radii and angles, staying inside the display.
            val fraction = 0.2f + 0.75f * (i.toFloat() / (count - 1))
            val angle = i * 2.39996f // golden angle, so targets do not cluster
            val distance = fraction * display.radius
            val tx = display.centerX + distance * kotlin.math.cos(angle)
            val ty = display.centerY + distance * kotlin.math.sin(angle)

            val magnitude = drift * fraction.pow(exponent)
            val ux = (tx - display.centerX) / distance
            val uy = (ty - display.centerY) / distance

            val nx = if (noisePx > 0f) (random.nextFloat() - 0.5f) * 2f * noisePx else 0f
            val ny = if (noisePx > 0f) (random.nextFloat() - 0.5f) * 2f * noisePx else 0f

            // Touch lands inward: subtract the outward unit vector scaled by the drift.
            CalibrationSample(
                targetX = tx,
                targetY = ty,
                touchX = tx - ux * magnitude + nx,
                touchY = ty - uy * magnitude + ny
            )
        }
    }

    @Test
    fun `recovers the constants that generated clean samples`() {
        val fit = TouchCalibration.fit(synthesise(drift = 9f, exponent = 2.4f), display)
        assertNotNull(fit)
        assertTrue(
            abs(fit.maxRadialDriftPx - 9f) < 0.5f,
            "expected drift near 9, got ${fit.maxRadialDriftPx}"
        )
        assertTrue(
            abs(fit.driftExponent - 2.4f) < 0.15f,
            "expected exponent near 2.4, got ${fit.driftExponent}"
        )
    }

    @Test
    fun `recovers approximately correct constants despite noise`() {
        val fit = TouchCalibration.fit(synthesise(drift = 7f, exponent = 2f, noisePx = 1.5f), display)
        assertNotNull(fit)
        assertTrue(abs(fit.maxRadialDriftPx - 7f) < 2.5f, "drift was ${fit.maxRadialDriftPx}")
        assertTrue(abs(fit.driftExponent - 2f) < 0.8f, "exponent was ${fit.driftExponent}")
        assertTrue(fit.isImprovement, "a fit on drifted data should reduce error")
    }

    @Test
    fun `reports a genuine reduction in aiming error`() {
        val fit = TouchCalibration.fit(synthesise(drift = 8f, exponent = 2f), display)
        assertNotNull(fit)
        assertTrue(fit.meanAbsErrorBefore > 1f, "synthetic data should start with real error")
        assertTrue(
            fit.meanAbsErrorAfter < fit.meanAbsErrorBefore * 0.25f,
            "correction should remove most of the error: ${fit.meanAbsErrorBefore} -> ${fit.meanAbsErrorAfter}"
        )
        assertTrue(fit.improvementPercent > 70f, "improvement was ${fit.improvementPercent}%")
    }

    @Test
    fun `too few samples yields null rather than a confident guess`() {
        val few = synthesise(drift = 8f, exponent = 2f, count = TouchCalibration.MIN_SAMPLES - 1)
        assertNull(TouchCalibration.fit(few, display))
        assertNull(TouchCalibration.fit(emptyList(), display))
    }

    @Test
    fun `samples with no systematic drift produce a zero correction`() {
        val fit = TouchCalibration.fit(synthesise(drift = 0f, exponent = 2f), display)
        assertNotNull(fit)
        assertEquals(0f, fit.maxRadialDriftPx, "no drift in the data means no correction")
    }

    @Test
    fun `a single gross mis-tap does not wreck the fit`() {
        val clean = synthesise(drift = 8f, exponent = 2f).toMutableList()
        // Simulate hitting a key three rows away.
        clean[5] = clean[5].copy(touchX = clean[5].touchX + 120f, touchY = clean[5].touchY + 90f)

        val fit = TouchCalibration.fit(clean, display)
        assertNotNull(fit)
        assertTrue(
            abs(fit.maxRadialDriftPx - 8f) < 3f,
            "outlier rejection should keep the fit near 8, got ${fit.maxRadialDriftPx}"
        )
    }

    @Test
    fun `fitted parameters are clamped to usable ranges`() {
        // Absurd drift, far larger than any real finger offset.
        val extreme = synthesise(drift = 200f, exponent = 2f)
        val fit = TouchCalibration.fit(extreme, display)
        assertNotNull(fit)
        assertTrue(fit.maxRadialDriftPx <= 24f, "drift must be clamped, got ${fit.maxRadialDriftPx}")
        assertTrue(fit.driftExponent in 0.5f..5f, "exponent must be clamped, got ${fit.driftExponent}")
    }

    @Test
    fun `samples clustered at one radius still yield a usable magnitude`() {
        // All targets at the same distance: the exponent is mathematically unconstrained.
        val samples = (0 until 20).map { i ->
            val angle = i * 0.314f
            val distance = 0.8f * display.radius
            val tx = display.centerX + distance * kotlin.math.cos(angle)
            val ty = display.centerY + distance * kotlin.math.sin(angle)
            val ux = (tx - display.centerX) / distance
            val uy = (ty - display.centerY) / distance
            val magnitude = 5f
            CalibrationSample(tx, ty, tx - ux * magnitude, ty - uy * magnitude)
        }
        val fit = TouchCalibration.fit(samples, display)
        assertNotNull(fit)
        assertTrue(fit.driftExponent.isFinite(), "exponent must stay finite")
        assertTrue(fit.maxRadialDriftPx > 0f, "a real offset should still be detected")
    }

    @Test
    fun `a degenerate display is rejected`() {
        val samples = synthesise(drift = 8f, exponent = 2f)
        assertNull(TouchCalibration.fit(samples, RoundDisplay(233f, -84f, 0f)))
    }

    @Test
    fun `the fitted config feeds straight back into the touch model`() {
        val fit = TouchCalibration.fit(synthesise(drift = 9f, exponent = 2.2f), display)
        assertNotNull(fit)

        // The whole point of the exercise: the fit must be usable as a TouchModel.Config.
        val config = TouchModel.Config(
            maxRadialDriftPx = fit.maxRadialDriftPx,
            driftExponent = fit.driftExponent
        )
        val model = TouchModel(display, config)
        val corrected = model.correct(display.centerX + 200f, display.centerY)
        assertTrue(corrected.shiftedBy > 0f, "a calibrated model should still correct near the rim")
    }
}
