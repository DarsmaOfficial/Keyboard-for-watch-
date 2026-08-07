package dev.darsma.wearkey.swipe

import kotlin.math.ln

/**
 * Resolves a whole word from per-tap key probability distributions (spec §7.2b).
 *
 * ## What this is for
 *
 * Ordinary typing commits a letter per tap: the highest-probability key wins immediately, and a tap
 * that landed between `g` and `h` is a coin flip resolved with no information. Spec §7.2b observes
 * that the information needed to settle it arrives *later* — once the word is known, `hello` and
 * `gello` are not equally plausible.
 *
 * So this defers commitment. Each tap contributes a probability distribution rather than a letter,
 * and the word is chosen at the end by combining spatial evidence with dictionary likelihood. That
 * is what allows typing without looking at the keys: the user aims approximately, and the
 * vocabulary resolves the ambiguity.
 *
 * ## Why it multiplies probabilities in log space
 *
 * The taps are treated as independent observations, so the probability of a candidate word is the
 * product of its per-tap probabilities. Multiplying thirty small floats underflows to zero, so the
 * work is done as a sum of logs — the same reason [dev.darsma.wearkey.imecore.touch.TouchModel]
 * uses log-sum-exp internally.
 *
 * ## Why a floor rather than rejection on a zero-probability key
 *
 * A candidate whose letter received *no* probability mass at some tap would score negative
 * infinity and be eliminated outright. That makes the model brittle: one badly misplaced tap would
 * discard the correct word entirely. [FLOOR] keeps such a candidate alive but heavily penalised,
 * so a single bad tap costs a word its lead without removing it from contention.
 */
class SpatialResolver(
    private val vocabulary: List<String>,
    private val frequencies: IntArray,
    private val frequencyWeight: Float = DEFAULT_FREQUENCY_WEIGHT
) {
    init {
        require(vocabulary.size == frequencies.size) {
            "vocabulary and frequencies must be the same length"
        }
    }

    /**
     * Ranks candidate words for a sequence of taps.
     *
     * [taps] holds one map per tap, from character to probability, as produced by the touch model's
     * `distribution()` after mapping key ids to characters. Only words of exactly [taps]`.size`
     * letters are considered — this mode does not attempt insertion or deletion correction, because
     * combining spatial ambiguity with edit-distance ambiguity produces candidate lists dominated
     * by words the user could not plausibly have aimed at.
     */
    fun resolve(taps: List<Map<Char, Float>>, limit: Int = 4): List<SwipeCandidate> {
        if (taps.isEmpty()) return emptyList()

        val results = ArrayList<SwipeCandidate>()
        for (i in vocabulary.indices) {
            val word = vocabulary[i]
            if (word.length != taps.size) continue

            var logProbability = 0f
            for (position in word.indices) {
                val p = taps[position][word[position]] ?: 0f
                logProbability += ln(if (p > FLOOR) p else FLOOR)
            }

            // Frequency enters logarithmically for the same reason as in SwipeRecognizer: it spans
            // orders of magnitude, and a linear term would let one very common word override clear
            // spatial evidence.
            val score = logProbability + frequencyWeight * ln((frequencies[i] + 1).toFloat())
            results.add(SwipeCandidate(word, score))
        }

        // Higher is better here, unlike the DTW path where lower distance wins.
        return results.sortedByDescending { it.score }.take(limit)
    }

    companion object {
        /**
         * Minimum probability credited to a letter that received none.
         *
         * Small enough to be a heavy penalty (about -11.5 in log space, versus roughly -0.1 for a
         * confident tap), large enough that a word survives one bad tap out of five.
         */
        const val FLOOR = 1e-5f

        /**
         * Weight of the frequency prior. Higher than the swipe recogniser's, because spatial
         * evidence from a few taps is weaker than a whole gesture path, so the vocabulary must
         * carry more of the decision.
         */
        const val DEFAULT_FREQUENCY_WEIGHT = 0.35f
    }
}
