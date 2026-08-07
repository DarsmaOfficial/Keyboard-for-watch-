package dev.darsma.wearkey.dict

import java.nio.ByteBuffer

/**
 * Spell-check / autocorrect engine (spec §7.2).
 *
 * Backed by [WordIndex]: a packed binary index that the Android layer memory-maps, so a resident
 * dictionary costs essentially no Java heap. The history behind that choice matters, because the
 * obvious library was tried first and measured:
 *
 * | Configuration | Dalvik heap on the watch |
 * |---|---|
 * | SymSpellKt, 30 000 words | 39.8 MB |
 * | SymSpellKt, 10 000 words | 15.5 MB |
 * | this mapped index, 10 000 words | ~0 (file-backed pages) |
 *
 * The gate in spec §14 is 8 MB, and §4.2 pre-authorised exactly this fallback: benchmark
 * SymSpellKt's real retained heap, and if it does not fit, write a flat index with a genuine mmap
 * path. SymSpellKt's cost is structural — `Map<Long, ArrayList<String>>` for the delete table
 * means three Java objects per delete variant, and 10 000 words generate 68 625 variants — so no
 * amount of tuning would have brought it inside the budget.
 *
 * `maxEditDistance` is fixed at 1 and is not tunable (spec §4.2). Distance 2 multiplies the
 * variant count by roughly four and was rejected outright.
 *
 * Everything here is pure Kotlin/JVM with no Android imports, so it is unit-testable on the host.
 */
class SpellEngine {

    private var index: WordIndex? = null

    /** True once an index is loaded and lookups will return something useful. */
    val isReady: Boolean
        get() = index != null

    /** Number of words currently resident, for diagnostics and tests. */
    val wordCount: Int
        get() = index?.size ?: 0

    /**
     * Loads a packed index built by `tools/build_index.py`.
     *
     * Calling this again swaps languages: the previous index is dropped, so exactly one stays
     * resident (spec §4.2). An unrecognised or truncated buffer leaves the engine unloaded rather
     * than throwing — the keyboard must degrade to layout-only typing, never die (spec §11).
     */
    fun load(buffer: ByteBuffer) {
        index = WordIndex.from(buffer)
    }

    /** Drops the index. Mapped pages become reclaimable as soon as the buffer is released. */
    fun unload() {
        index = null
    }

    /**
     * Snapshot of the vocabulary and its frequencies, for building glide-typing templates (§7.3).
     *
     * [limit] caps how many words are taken. Because the index is ordered by descending frequency,
     * taking a prefix keeps the *most common* words rather than an arbitrary slice — so a cap
     * trades recall for memory in the least damaging way available.
     *
     * This is intentionally a copy rather than a live view. The recogniser holds it for the
     * lifetime of a layout, and a view over a buffer that [unload] can invalidate would turn a
     * language switch into a crash.
     */
    fun vocabularySnapshot(limit: Int): Pair<List<String>, IntArray> {
        val idx = index ?: return emptyList<String>() to IntArray(0)
        val n = minOf(limit, idx.size)
        val words = ArrayList<String>(n)
        val freqs = IntArray(n)
        for (i in 0 until n) {
            words.add(idx.wordAtIndex(i))
            freqs[i] = idx.frequencyAt(i)
        }
        return words to freqs
    }

    /**
     * Correction candidates for [word], best first. Empty when no index is loaded.
     *
     * Candidates arrive pre-ranked by corpus frequency because the index stores words in
     * descending frequency order. This is what fixes the original on-device defect: with every
     * word weighted equally, "helo" offered "halo / held / helm" and never "hello".
     */
    fun suggest(word: String): List<String> {
        if (word.isBlank()) return emptyList()
        val idx = index ?: return emptyList()
        return runCatching {
            idx.lookup(word.lowercase(), MAX_SUGGESTIONS)
        }.getOrDefault(emptyList())
    }

    /**
     * The single best correction, or null when the word is already fine or nothing is close
     * enough. Deliberately conservative: "correcting" a word the user actually meant is worse than
     * leaving a typo alone, especially on a watch where undoing costs several taps.
     *
     * Note the keyboard never applies this automatically — the candidate row offers words and the
     * user taps one. This exists for callers that want a single answer.
     */
    fun bestCorrection(word: String): String? {
        if (word.isBlank()) return null
        if (word.any { !it.isLetter() }) return null // leave mixed alphanumerics alone
        val idx = index ?: return null
        val lower = word.lowercase()
        if (idx.contains(lower)) return null // already a dictionary word
        return suggest(lower).firstOrNull()
    }

    /** True when [word] is in the resident dictionary. */
    fun isKnown(word: String): Boolean {
        val idx = index ?: return false
        return idx.contains(word.lowercase())
    }

    companion object {
        /**
         * How many candidates the keyboard offers at once.
         *
         * Four rather than three, decided from on-device behaviour: typing "helo" produces
         * help / held / hero / hello / helm / halo, all at edit distance 1 and correctly ordered
         * by real frequency — but at three chips the word the user almost certainly meant fell
         * just off the end. A 466 px display fits four legibly, and the candidates are computed
         * either way.
         */
        const val MAX_SUGGESTIONS = 4
    }
}
