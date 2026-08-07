package dev.darsma.wearkey.swipe

import kotlin.math.sqrt

/**
 * A swipe gesture reduced to a fixed-length, scale-normalised curve (spec §7.3).
 *
 * ## Why resampling is not optional
 *
 * Raw `MotionEvent` streams are useless for shape comparison as they arrive. Their point count
 * depends on how fast the finger moved and how often the digitiser sampled — the same word swiped
 * quickly and slowly produces sequences of wildly different length, and the slow one carries dense
 * clusters wherever the finger hesitated. Comparing those directly measures typing speed as much as
 * shape.
 *
 * Resampling to a fixed [N] equidistant points removes both problems at once: every gesture and
 * every template become the same length, and *arc length* rather than *time* decides where points
 * land, so hesitation stops being a signal. Spec §7.3 fixes N = 32.
 *
 * ## Why normalise into the unit square
 *
 * Templates are built from key centres in view pixels, but the view's size is not fixed — the key
 * grid resizes, and the same word must match on any grid. Mapping both gesture and template through
 * the same normalisation makes the comparison independent of screen size and of where on the grid
 * the word happens to sit.
 *
 * The normalisation deliberately preserves aspect ratio and centres the shape. Scaling x and y
 * independently to fill the square would make a horizontal swipe ("aw") and a diagonal one
 * indistinguishable, because both would be stretched into the same box.
 */
class SwipePath private constructor(
    /** Interleaved x,y pairs: `[x0, y0, x1, y1, ...]`, length `2 * N`. */
    val points: FloatArray
) {
    companion object {
        /** Resampled point count, fixed by spec §7.3. */
        const val N = 32

        /**
         * Builds a normalised path from raw touch samples.
         *
         * [xs] and [ys] are read up to [count] entries, which lets the caller keep one oversized
         * reusable buffer rather than allocating per gesture (spec §7.3: zero allocation in the drag
         * hot path).
         *
         * Returns `null` for gestures too short to carry shape information — a stationary press or
         * a two-sample flick is a tap, not a swipe, and forcing it through resampling would invent
         * a shape out of digitiser noise.
         */
        fun fromSamples(xs: FloatArray, ys: FloatArray, count: Int, minLength: Float): SwipePath? {
            if (count < 2) return null

            // Cumulative arc length, so resampling can walk it by distance rather than by index.
            var total = 0f
            for (i in 1 until count) {
                total += dist(xs[i - 1], ys[i - 1], xs[i], ys[i])
            }
            if (total < minLength) return null

            val out = FloatArray(N * 2)
            resampleInto(xs, ys, count, total, out)
            normaliseInPlace(out)
            return SwipePath(out)
        }

        /**
         * Builds a template path directly from an ordered list of key centres.
         *
         * The straight segments between consecutive centres stand in for the finger's path. That is
         * an idealisation — a real finger curves between keys — but it is the right one: DTW is
         * tolerant of local timing and shape deviation, and any smoother model would encode a guess
         * about how *this* user rounds corners, which is precisely what the matcher should be
         * learning from the gesture rather than assuming.
         *
         * Consecutive duplicate centres are collapsed. A word like "aa" traces no path between its
         * two letters, and leaving the duplicate in would spend resampled points standing still,
         * distorting the shape.
         */
        fun fromKeyCentres(cxs: FloatArray, cys: FloatArray, count: Int): SwipePath? {
            if (count == 0) return null

            // Collapse repeats in place into a scratch pair of arrays.
            val xs = FloatArray(count)
            val ys = FloatArray(count)
            var n = 0
            for (i in 0 until count) {
                if (n == 0 || cxs[i] != xs[n - 1] || cys[i] != ys[n - 1]) {
                    xs[n] = cxs[i]
                    ys[n] = cys[i]
                    n++
                }
            }

            // A single-key word ("i", "a") has no extent. Represent it as a degenerate path at the
            // origin so it still compares meaningfully against a near-stationary gesture.
            if (n == 1) return SwipePath(FloatArray(N * 2))

            var total = 0f
            for (i in 1 until n) total += dist(xs[i - 1], ys[i - 1], xs[i], ys[i])
            if (total <= 0f) return SwipePath(FloatArray(N * 2))

            val out = FloatArray(N * 2)
            resampleInto(xs, ys, n, total, out)
            normaliseInPlace(out)
            return SwipePath(out)
        }

        /** Walks the polyline at fixed arc-length intervals, writing N points into [out]. */
        private fun resampleInto(
            xs: FloatArray,
            ys: FloatArray,
            count: Int,
            total: Float,
            out: FloatArray
        ) {
            val step = total / (N - 1)
            out[0] = xs[0]
            out[1] = ys[0]

            var written = 1
            var segment = 1
            var walked = 0f          // arc length consumed up to the start of `segment`
            var target = step

            while (written < N - 1 && segment < count) {
                val segLen = dist(xs[segment - 1], ys[segment - 1], xs[segment], ys[segment])
                if (segLen <= 0f) {
                    segment++
                    continue
                }
                if (walked + segLen >= target) {
                    val t = (target - walked) / segLen
                    out[written * 2] = xs[segment - 1] + t * (xs[segment] - xs[segment - 1])
                    out[written * 2 + 1] = ys[segment - 1] + t * (ys[segment] - ys[segment - 1])
                    written++
                    target += step
                } else {
                    walked += segLen
                    segment++
                }
            }

            // Pin the last point to the true endpoint. Accumulated float error in the walk above can
            // leave the final sample short, and the endpoint is the most diagnostic point of all —
            // it is what separates "hell" from "hello".
            while (written < N) {
                out[written * 2] = xs[count - 1]
                out[written * 2 + 1] = ys[count - 1]
                written++
            }
        }

        /** Centres the shape and scales it by its largest extent, preserving aspect ratio. */
        private fun normaliseInPlace(p: FloatArray) {
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (i in 0 until N) {
                val x = p[i * 2]
                val y = p[i * 2 + 1]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }

            val spanX = maxX - minX
            val spanY = maxY - minY
            val span = if (spanX > spanY) spanX else spanY

            // A path with no extent (single key, or a jitter-only gesture) normalises to all zeros
            // rather than dividing by ~0 and exploding into noise.
            if (span <= 1e-6f) {
                java.util.Arrays.fill(p, 0f)
                return
            }

            val cx = (minX + maxX) * 0.5f
            val cy = (minY + maxY) * 0.5f
            val inv = 1f / span
            for (i in 0 until N) {
                p[i * 2] = (p[i * 2] - cx) * inv
                p[i * 2 + 1] = (p[i * 2 + 1] - cy) * inv
            }
        }

        private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax
            val dy = by - ay
            return sqrt(dx * dx + dy * dy)
        }
    }
}
