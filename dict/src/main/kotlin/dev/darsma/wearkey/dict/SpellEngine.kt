package dev.darsma.wearkey.dict

import com.darkrockstudios.symspellkt.common.Verbosity
import com.darkrockstudios.symspellkt.impl.SymSpell

/**
 * Spell-check / autocorrect engine (spec §7.2).
 *
 * Uses **SymSpellKt** (MIT): pure Kotlin, symmetric-delete algorithm, O(1) lookups. Chosen over
 * the alternatives for reasons recorded in the spec:
 *  - Hunspell as a *runtime* would need NDK + JNI, ~8 MB heap, and reintroduces the 32-bit ABI
 *    risk this project avoids by shipping no native code at all.
 *  - Norvig-style candidate generation allocates heavily (~12 MB) and causes GC pauses on the
 *    main thread — fatal for something that runs on every keystroke.
 *
 * `maxEditDistance = 1` is deliberate and not tunable upward (spec §4.2): distance 2 costs
 * 15-16 MB per language, which would blow the 12 MB heap budget on its own.
 *
 * Pure Kotlin/JVM, so it is unit-testable without a device. The Android layer only supplies the
 * word list and decides when to apply a correction.
 */
class SpellEngine(
    private val maxEditDistance: Double = 1.0
) {

    private var checker: SymSpell? = null

    /** True once a word list is loaded and lookups will return something useful. */
    val isReady: Boolean
        get() = checker != null

    /**
     * Loads a word list. Each entry is `word` or `word<TAB>frequency`; frequency defaults to 1
     * when absent. Calling this again swaps languages — the previous dictionary is dropped, so
     * only one stays resident (spec §4.2: one resident dictionary, bounded heap).
     */
    fun load(entries: Sequence<String>) {
        val spellChecker = SymSpell()
        var loaded = 0

        entries.forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val parts = line.split('\t', limit = 2)
            val word = parts[0].trim().lowercase()
            if (word.isEmpty()) return@forEach
            val frequency = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: 1.0
            runCatching { spellChecker.createDictionaryEntry(word, frequency) }
                .onSuccess { loaded++ }
        }

        checker = if (loaded > 0) spellChecker else null
    }

    /** Drops the dictionary and frees its memory. */
    fun unload() {
        checker = null
    }

    /**
     * Correction candidates for [word], best first. Returns an empty list when no dictionary is
     * loaded — the keyboard degrades to layout-only input and never crashes (spec §11 failure
     * modes: a keyboard that dies leaves the user unable to type at all).
     */
    fun suggest(word: String): List<String> {
        if (word.isBlank()) return emptyList()
        val spellChecker = checker ?: return emptyList()
        return runCatching {
            spellChecker.lookup(word.lowercase(), Verbosity.Closest, maxEditDistance)
                .map { it.term }
                .filter { it.isNotBlank() }
                .distinct()
                .take(MAX_SUGGESTIONS)
        }.getOrDefault(emptyList())
    }

    /**
     * The single best correction, or null when the word is already fine or nothing is close
     * enough. Deliberately conservative: "correcting" a word the user actually meant is worse
     * than leaving a typo alone, especially on a watch where undoing is expensive.
     */
    fun bestCorrection(word: String): String? {
        if (word.isBlank()) return null
        if (word.any { !it.isLetter() }) return null // don't touch mixed alphanumerics
        val suggestions = suggest(word)
        if (suggestions.isEmpty()) return null
        val top = suggestions.first()
        if (top.equals(word, ignoreCase = true)) return null // already a dictionary word
        return top
    }

    companion object {
        /**
         * How many candidates the keyboard offers at once.
         *
         * Four rather than three, decided from on-device behaviour: typing "helo" produces
         * help / held / hero / hello / helm / halo, all at edit distance 1 and correctly ordered
         * by real frequency — but at three chips the word the user almost certainly meant fell
         * just off the end. A 466 px display fits four chips legibly, and offering one more costs
         * nothing in heap since the candidates are computed either way.
         */
        const val MAX_SUGGESTIONS = 4
    }
}
