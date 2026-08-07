package dev.darsma.wearkey.swipe

/**
 * Dynamic Time Warping distance between two [SwipePath]s (spec §7.3).
 *
 * ## Why DTW rather than point-wise distance
 *
 * Two people swiping the same word produce the same *shape* traversed at different rates: one
 * lingers on the first letter, another cuts the corner at the third. Comparing point 7 to point 7
 * punishes that misalignment as if it were a different word. DTW instead finds the cheapest
 * monotonic alignment between the two sequences, so a path that is locally stretched or compressed
 * still matches — which is exactly the variation a real finger produces.
 *
 * ## Sakoe–Chiba band
 *
 * The alignment is constrained to a diagonal band of half-width [BAND]. This is both a speed and a
 * quality measure. Speed: it drops the cost from O(N²) to O(N·band). Quality: an unconstrained
 * warp can align the start of one path to the end of another, which lets genuinely different words
 * match by pathological stretching. Since both paths are resampled by arc length, corresponding
 * points are already near the diagonal, so a band that permits ±25% drift is generous.
 *
 * ## Allocation
 *
 * Spec §7.3 requires zero allocation in the drag hot path. The two cost rows are owned by the
 * [Dtw] instance and reused across calls, so a full vocabulary scan allocates nothing. This makes
 * instances stateful and therefore **not thread-safe** — one instance per recogniser, which is how
 * [SwipeRecognizer] holds it.
 */
class Dtw {

    private val previous = FloatArray(SwipePath.N)
    private val current = FloatArray(SwipePath.N)

    /**
     * Returns the DTW distance, or [Float.MAX_VALUE] if it exceeds [ceiling].
     *
     * The ceiling is an early-abandon: while scanning a vocabulary, most templates are obviously
     * wrong, and a row whose best cell already exceeds the best-so-far distance cannot recover —
     * DTW costs only ever accumulate. Bailing out there is what makes a full scan affordable on a
     * watch, and it is exact rather than heuristic: it never discards a path that would have won.
     */
    fun distance(a: SwipePath, b: SwipePath, ceiling: Float = Float.MAX_VALUE): Float {
        val n = SwipePath.N
        val pa = a.points
        val pb = b.points

        var prev = previous
        var cur = current

        // First row: only the "insert" direction is available, so costs accumulate along it.
        var running = 0f
        for (j in 0 until n) {
            running += cost(pa, 0, pb, j)
            prev[j] = if (j <= BAND) running else Float.MAX_VALUE
        }

        for (i in 1 until n) {
            val lo = if (i - BAND < 0) 0 else i - BAND
            val hi = if (i + BAND >= n) n - 1 else i + BAND

            // Cells outside the band must read as impassable, not as stale values from the row
            // before — otherwise the band leaks and the warp escapes it.
            if (lo > 0) cur[lo - 1] = Float.MAX_VALUE

            var rowBest = Float.MAX_VALUE
            for (j in lo..hi) {
                val diag = if (j > 0) prev[j - 1] else Float.MAX_VALUE
                val up = prev[j]
                val left = if (j > lo) cur[j - 1] else Float.MAX_VALUE

                var best = diag
                if (up < best) best = up
                if (left < best) best = left

                cur[j] = if (best == Float.MAX_VALUE) {
                    Float.MAX_VALUE
                } else {
                    best + cost(pa, i, pb, j)
                }
                if (cur[j] < rowBest) rowBest = cur[j]
            }

            // Early abandon — see the ceiling note above.
            if (rowBest > ceiling) return Float.MAX_VALUE

            val swap = prev
            prev = cur
            cur = swap
        }

        return prev[n - 1]
    }

    /** Squared Euclidean distance between point [i] of [a] and point [j] of [b]. */
    private fun cost(a: FloatArray, i: Int, b: FloatArray, j: Int): Float {
        val dx = a[i * 2] - b[j * 2]
        val dy = a[i * 2 + 1] - b[j * 2 + 1]
        return dx * dx + dy * dy
    }

    companion object {
        /** Sakoe–Chiba half-width: ±8 of 32 points, i.e. ±25% temporal drift. */
        const val BAND = 8
    }
}
