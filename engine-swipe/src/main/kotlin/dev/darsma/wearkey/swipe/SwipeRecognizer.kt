package dev.darsma.wearkey.swipe

import kotlin.math.ln

/**
 * Letter positions for one layout, in the key grid's pixel space.
 *
 * Only letters are included: swipe paths run over the letter block, and including space or
 * backspace would let a template route through a key no finger would cross mid-word.
 */
class KeyGeometry(
    /** Lowercase letters, index-aligned with [xs] and [ys]. */
    private val letters: CharArray,
    private val xs: FloatArray,
    private val ys: FloatArray
) {
    /** Index of [c], or -1 when the layout has no such key (e.g. a Latin letter in Cyrillic). */
    fun indexOf(c: Char): Int {
        for (i in letters.indices) if (letters[i] == c) return i
        return -1
    }

    fun x(i: Int): Float = xs[i]
    fun y(i: Int): Float = ys[i]

    companion object {
        fun of(letters: String, xs: FloatArray, ys: FloatArray): KeyGeometry =
            KeyGeometry(letters.toCharArray(), xs, ys)
    }
}

/** A ranked swipe candidate. */
data class SwipeCandidate(val word: String, val score: Float)

/**
 * Ranks vocabulary words against a swiped path (spec §7.3).
 *
 * ## Templates are built once per layout, not per gesture
 *
 * Every word's template depends only on the layout geometry, so they are precomputed when the
 * geometry is set and reused for every subsequent swipe. Rebuilding them per gesture would put
 * thousands of resamplings in the hot path and make the feature unusable on a watch.
 *
 * ## Why score combines shape with frequency
 *
 * Shape alone cannot separate words whose key paths nearly coincide — on QWERTY "to" and "yo"
 * differ by a few pixels of endpoint, and "hello" is a near-superset of "hell". The gesture
 * genuinely is ambiguous in those cases, so the tie must be broken by which word the user is more
 * likely to have meant. Spec §7.3 requires exactly this combination. Frequency enters as a log,
 * because it spans several orders of magnitude and a linear term would let one very common word
 * dominate every shape decision.
 */
class SwipeRecognizer(
    private val vocabulary: List<String>,
    private val frequencies: IntArray,
    /** Weight of the frequency prior relative to shape distance. */
    private val frequencyWeight: Float = DEFAULT_FREQUENCY_WEIGHT
) {
    private val dtw = Dtw()
    private var templates: Array<SwipePath?> = emptyArray()

    // Reusable scratch for template construction; sized to the longest word once.
    private var scratchX = FloatArray(0)
    private var scratchY = FloatArray(0)

    init {
        require(vocabulary.size == frequencies.size) {
            "vocabulary and frequencies must be the same length"
        }
    }

    /**
     * Precomputes one template per word for [geometry].
     *
     * Words containing a letter absent from the layout get a null template and are skipped at match
     * time. That is not an error: the English and Russian vocabularies are loaded together, and a
     * Cyrillic word simply has no path on a QWERTY grid.
     */
    fun setGeometry(geometry: KeyGeometry) {
        var longest = 0
        for (w in vocabulary) if (w.length > longest) longest = w.length
        if (scratchX.size < longest) {
            scratchX = FloatArray(longest)
            scratchY = FloatArray(longest)
        }

        templates = Array(vocabulary.size) { wi ->
            val word = vocabulary[wi]
            var n = 0
            var usable = true
            for (ci in word.indices) {
                val ki = geometry.indexOf(word[ci])
                if (ki < 0) {
                    usable = false
                    break
                }
                scratchX[n] = geometry.x(ki)
                scratchY[n] = geometry.y(ki)
                n++
            }
            if (usable && n > 0) SwipePath.fromKeyCentres(scratchX, scratchY, n) else null
        }
    }

    /**
     * Returns up to [limit] candidates for [path], best first.
     *
     * The scan keeps a running worst-accepted distance and feeds it to DTW as an early-abandon
     * ceiling, so most of the vocabulary is rejected after a few rows rather than fully aligned.
     */
    fun recognise(path: SwipePath, limit: Int = 4): List<SwipeCandidate> {
        if (templates.isEmpty()) return emptyList()

        val bestWords = arrayOfNulls<String>(limit)
        val bestScores = FloatArray(limit) { Float.MAX_VALUE }
        var accepted = 0

        for (i in templates.indices) {
            val template = templates[i] ?: continue

            // Only abandon early once the shortlist is full — before that every candidate is worth
            // a full evaluation, and an aggressive ceiling would discard the eventual winner.
            val ceiling = if (accepted == limit) bestScores[limit - 1] else Float.MAX_VALUE
            val shape = dtw.distance(path, template, ceiling)
            if (shape == Float.MAX_VALUE) continue

            val score = shape - frequencyWeight * ln((frequencies[i] + 1).toFloat())

            var slot = limit - 1
            if (score >= bestScores[slot]) continue
            while (slot > 0 && score < bestScores[slot - 1]) {
                bestScores[slot] = bestScores[slot - 1]
                bestWords[slot] = bestWords[slot - 1]
                slot--
            }
            bestScores[slot] = score
            bestWords[slot] = vocabulary[i]
            if (accepted < limit) accepted++
        }

        val out = ArrayList<SwipeCandidate>(accepted)
        for (i in 0 until limit) {
            val w = bestWords[i] ?: continue
            out.add(SwipeCandidate(w, bestScores[i]))
        }
        return out
    }

    companion object {
        /**
         * Chosen so frequency breaks ties between near-identical shapes without overriding a clear
         * shape difference. Typical DTW distances for a good match are ~0.1–1.0 and `ln(freq)`
         * spans ~0–9, so at 0.05 the prior is worth at most ~0.45 — decisive between neighbours,
         * negligible against a genuinely different path.
         */
        const val DEFAULT_FREQUENCY_WEIGHT = 0.05f
    }
}
