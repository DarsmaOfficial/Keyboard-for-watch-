package dev.darsma.wearkey

import dev.darsma.wearkey.dict.SpellEngine
import dev.darsma.wearkey.swipe.KeyGeometry
import dev.darsma.wearkey.swipe.SwipePath
import dev.darsma.wearkey.swipe.SwipeRecognizer
import dev.darsma.wearkey.uiwear.KeyGridView

/**
 * Owns glide-typing recognition for the IME (spec §7.3).
 *
 * Kept out of [WearKeyImeService] because template building is expensive and must happen on layout
 * and language changes, not on every gesture — a policy that is easy to get wrong when it is
 * tangled with input-connection code.
 *
 * ## Vocabulary cap
 *
 * Templates cost real memory: one [SwipePath] is 32 points × 2 floats = 256 bytes, so the full 10k
 * vocabulary would be ~2.6 MB of heap on top of the mapped index. Against the §3 budget of 12 MB
 * with two languages loaded, that is affordable only for the words users actually swipe. Capping at
 * [MAX_TEMPLATES] keeps the most frequent words — the index is frequency-ordered, so a prefix is
 * the best possible slice — for ~0.8 MB.
 *
 * Rare words remain typeable by tapping. That is the correct trade: glide typing is a speed
 * optimisation for common words, and a rare word's path is ambiguous anyway.
 */
class SwipeController(private val spellEngine: SpellEngine) {

    private var recognizer: SwipeRecognizer? = null
    private var geometrySignature: String? = null

    /** True when a gesture can currently be recognised. */
    val isReady: Boolean
        get() = recognizer != null

    /**
     * Rebuilds templates for [grid]'s current layout, if anything relevant changed.
     *
     * The signature guards against needless rebuilds: `onStartInputView` fires for every field, and
     * rebuilding thousands of templates each time would add a visible stall to opening a text box.
     */
    fun refresh(grid: KeyGridView) {
        if (!spellEngine.isReady) {
            recognizer = null
            geometrySignature = null
            return
        }

        val geometry = grid.letterGeometry() ?: return
        val (letters, xs, ys) = geometry

        // Layout identity plus grid size: either changing invalidates every template.
        val signature = "$letters@${grid.width}x${grid.height}"
        if (signature == geometrySignature && recognizer != null) return

        val (words, freqs) = spellEngine.vocabularySnapshot(MAX_TEMPLATES)
        if (words.isEmpty()) {
            recognizer = null
            geometrySignature = null
            return
        }

        recognizer = SwipeRecognizer(words, freqs).also {
            it.setGeometry(KeyGeometry.of(letters, xs, ys))
        }
        geometrySignature = signature
    }

    /** Drops templates, e.g. when the dictionary unloads on a language switch. */
    fun clear() {
        recognizer = null
        geometrySignature = null
    }

    /**
     * Recognises a raw trace, returning candidates best-first, or empty when unrecognisable.
     *
     * [xs] and [ys] are the grid's reusable buffers, so this must not retain them — [SwipePath]
     * copies what it needs during resampling.
     */
    fun recognise(xs: FloatArray, ys: FloatArray, count: Int, density: Float): List<String> {
        val r = recognizer ?: return emptyList()
        val path = SwipePath.fromSamples(xs, ys, count, minLength = MIN_SWIPE_DP * density)
            ?: return emptyList()
        return r.recognise(path).map { it.word }
    }

    companion object {
        /**
         * How many of the most frequent words get glide templates. 3000 covers the overwhelming
         * majority of swiped input while costing ~0.8 MB of heap.
         */
        const val MAX_TEMPLATES = 3000

        /**
         * Minimum travel for a trace to be treated as a word rather than a stray drag. Slightly
         * above the grid's own swipe threshold so a gesture that barely crosses it still has enough
         * shape to match.
         */
        const val MIN_SWIPE_DP = 28f
    }
}
