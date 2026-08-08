package dev.darsma.wearkey

import dev.darsma.wearkey.dict.SpellEngine
import dev.darsma.wearkey.swipe.SpatialResolver

/** Owns one deferred spatial-typing word (spec §7.2b). */
class SpatialTypingController(private val spellEngine: SpellEngine) {
    private val taps = ArrayList<Map<Char, Float>>()
    private val literal = StringBuilder()
    private var resolver: SpatialResolver? = null

    val isComposing: Boolean get() = taps.isNotEmpty()
    val preview: String get() = literal.toString()

    fun refreshVocabulary() {
        val (words, frequencies) = spellEngine.vocabularySnapshot(MAX_WORDS)
        resolver = if (words.isEmpty()) null else SpatialResolver(words, frequencies)
        clear()
    }

    fun add(displayed: Char, distribution: Map<Char, Float>): String {
        literal.append(displayed)
        taps.add(distribution.mapKeys { it.key.lowercaseChar() })
        return preview
    }

    fun backspace(): String {
        if (taps.isNotEmpty()) taps.removeAt(taps.lastIndex)
        if (literal.isNotEmpty()) literal.deleteCharAt(literal.lastIndex)
        return preview
    }

    fun candidates(): List<String> = resolver?.resolve(taps)?.map { it.word }.orEmpty()

    fun resolvedWord(): String {
        val resolved = candidates().firstOrNull() ?: return preview
        return if (preview.firstOrNull()?.isUpperCase() == true) {
            resolved.replaceFirstChar { it.uppercaseChar() }
        } else resolved
    }

    fun clear() {
        taps.clear()
        literal.setLength(0)
    }

    companion object {
        // Resolution stores words/frequencies only; unlike glide typing it builds no path templates.
        const val MAX_WORDS = 10_000
    }
}
