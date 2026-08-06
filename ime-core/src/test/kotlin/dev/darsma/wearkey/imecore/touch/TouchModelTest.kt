package dev.darsma.wearkey.imecore.touch

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the round-display touch model (spec §7.1).
 *
 * Geometry mirrors the real OnePlus Watch 2: 466 × 466 px, radius 233 px. The keyboard view
 * occupies the lower ~66% of the screen, so in the view's own coordinate space the circle centre
 * is above the origin — hence the negative centreY, exactly as KeyGridView computes it.
 */
class TouchModelTest {

    private val display = RoundDisplay(centerX = 233f, centerY = -84f, radius = 233f)
    private val model = TouchModel(display)

    /** A realistic letter row: 10 keys, 38 px wide, 44 px tall. */
    private fun letterRow(y: Float): List<KeyTarget> =
        (0 until 10).map { i ->
            KeyTarget(id = i, centerX = 27f + i * 41.5f, centerY = y, width = 38f, height = 44f)
        }

    @Test
    fun `tap on a key centre selects that key`() {
        val row = letterRow(160f)
        row.forEach { key ->
            val hit = model.bestMatch(key.centerX, key.centerY, row)
            assertEquals(key.id, hit?.id, "tap at the centre of key ${key.id} should select it")
        }
    }

    @Test
    fun `tap in the gap between two keys picks the nearer one`() {
        val row = letterRow(160f)
        val left = row[3]
        val right = row[4]
        val gapX = (left.centerX + right.centerX) / 2f

        // Just left of the midpoint belongs to the left key, just right to the right key.
        assertEquals(left.id, model.bestMatch(gapX - 2f, 160f, row)?.id)
        assertEquals(right.id, model.bestMatch(gapX + 2f, 160f, row)?.id)
    }

    @Test
    fun `tap beyond the outermost key still resolves to it`() {
        val row = letterRow(160f)
        val last = row.last()
        // 30 px past the final key centre — on the glass curve, where nothing is drawn. A
        // rectangular hit test would swallow this; spec §7.1 requires it to reach the edge key.
        val hit = model.bestMatch(last.centerX + 30f, 160f, row)
        assertEquals(last.id, hit?.id)
    }

    @Test
    fun `a tap never resolves to nothing when keys exist`() {
        val row = letterRow(160f)
        // Deliberately absurd coordinates, including outside the display entirely.
        val wild = listOf(-500f to -500f, 900f to 900f, 0f to 0f, 466f to 466f)
        wild.forEach { (x, y) ->
            assertNotNull(model.bestMatch(x, y, row), "tap at ($x, $y) must resolve to some key")
        }
    }

    @Test
    fun `empty target list yields null rather than throwing`() {
        assertNull(model.bestMatch(100f, 100f, emptyList()))
        assertTrue(model.distribution(100f, 100f, emptyList()).isEmpty())
    }

    /**
     * The load-bearing test for omitting the Gaussian normaliser.
     *
     * The real function row has a spacebar roughly three times the width of the key beside it. A
     * normalised bivariate Gaussian would score the *narrow* key higher for a tap just inside the
     * spacebar's end, because a wide key spreads its density thinner. That would make the most
     * frequently used key on the keyboard the hardest to hit near its edges.
     */
    @Test
    fun `wide key wins near its own edge against a narrow neighbour`() {
        val space = KeyTarget(id = 0, centerX = 229f, centerY = 421f, width = 110f, height = 40f)
        val clipboard = KeyTarget(id = 1, centerX = 305f, centerY = 421f, width = 36f, height = 40f)
        val keys = listOf(space, clipboard)

        // 12 px inside the spacebar's right edge (spacebar spans 174..284).
        val hit = model.bestMatch(272f, 421f, keys)
        assertEquals(space.id, hit?.id, "a tap inside the spacebar must not be stolen by a narrow neighbour")
    }

    @Test
    fun `vertical spread is tighter than horizontal so rows do not swap`() {
        val upper = KeyTarget(id = 0, centerX = 233f, centerY = 120f, width = 38f, height = 44f)
        val lower = KeyTarget(id = 1, centerX = 233f, centerY = 164f, width = 38f, height = 44f)
        val neighbour = KeyTarget(id = 2, centerX = 274f, centerY = 120f, width = 38f, height = 44f)
        val keys = listOf(upper, lower, neighbour)

        // A tap displaced equally right and down from `upper`: the horizontal neighbour should win,
        // because sliding within a row is a far less confusing error than jumping rows.
        val hit = model.bestMatch(upper.centerX + 22f, upper.centerY + 22f, keys)
        assertEquals(neighbour.id, hit?.id)
    }

    @Test
    fun `radial correction is zero at the display centre and grows outward`() {
        val atCentre = model.correct(display.centerX, display.centerY)
        assertTrue(atCentre.shiftedBy < 1e-3f, "no correction at the centre, got ${atCentre.shiftedBy}")

        val near = model.correct(display.centerX + 40f, display.centerY)
        val far = model.correct(display.centerX + 180f, display.centerY)
        assertTrue(far.shiftedBy > near.shiftedBy, "correction must grow with radial distance")
    }

    @Test
    fun `radial correction pushes outward along the radius`() {
        // Directly right of centre: correction must increase x and leave y alone.
        val p = model.correct(display.centerX + 150f, display.centerY)
        assertTrue(p.x > display.centerX + 150f, "should be displaced further from centre")
        assertTrue(abs(p.y - display.centerY) < 1e-3f, "no vertical component on a horizontal radius")
    }

    @Test
    fun `correction never pushes a point outside the display circle`() {
        // A point already at the rim: any outward push would leave the physical screen.
        val p = model.correct(display.centerX + display.radius, display.centerY)
        val dx = p.x - display.centerX
        val dy = p.y - display.centerY
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        assertTrue(distance <= display.radius + 1e-3f, "corrected point escaped the display: $distance")
    }

    @Test
    fun `distribution sums to one and peaks at the tapped key`() {
        val row = letterRow(160f)
        val target = row[6]
        val dist = model.distribution(target.centerX, target.centerY, row)

        val total = dist.values.sum()
        assertTrue(abs(total - 1f) < 1e-3f, "posterior must sum to 1, got $total")

        val peak = dist.maxByOrNull { it.value }
        assertEquals(target.id, peak?.key)
    }

    @Test
    fun `a strong prior can override a marginal spatial preference`() {
        val row = letterRow(160f)
        val left = row[3]
        val right = row[4]
        // Just inside the right key, so spatially `right` is (marginally) preferred.
        val x = (left.centerX + right.centerX) / 2f + 1f
        assertEquals(right.id, model.bestMatch(x, 160f, row))

        // With a strong prior favouring the left key, the decision flips. This is the seam
        // §7.2b spatial prediction will use to fold in dictionary likelihood.
        val hit = model.bestMatch(x, 160f, row, logPrior = { id -> if (id == left.id) 5f else 0f })
        assertEquals(left.id, hit?.id)
    }

    @Test
    fun `degenerate zero-size key does not divide by zero`() {
        val degenerate = listOf(KeyTarget(id = 0, centerX = 100f, centerY = 100f, width = 0f, height = 0f))
        val hit = model.bestMatch(100f, 100f, degenerate)
        assertEquals(0, hit?.id)
        val score = model.logLikelihood(140f, 100f, degenerate.first())
        assertTrue(score.isFinite(), "score must stay finite for a zero-size key, got $score")
    }

    @Test
    fun `invalid configuration is rejected at construction`() {
        val bad: List<() -> TouchModel.Config> = listOf(
            { TouchModel.Config(sigmaXFraction = 0f) },
            { TouchModel.Config(sigmaYFraction = -1f) },
            { TouchModel.Config(maxRadialDriftPx = -1f) },
            { TouchModel.Config(driftExponent = 0f) }
        )
        bad.forEach { build ->
            try {
                build()
                throw AssertionError("expected an IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
                // correct
            }
        }
    }
}
