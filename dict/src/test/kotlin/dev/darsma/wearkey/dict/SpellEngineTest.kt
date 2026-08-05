package dev.darsma.wearkey.dict

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpellEngineTest {

    private fun engineWith(vararg entries: Pair<String, Int>): SpellEngine =
        SpellEngine().apply { load(TestIndex.build(entries.toMap())) }

    /** Equal weights, for cases where frequency is irrelevant to what is being asserted. */
    private fun engineWith(vararg words: String): SpellEngine =
        SpellEngine().apply { load(TestIndex.build(words.associateWith { 1 })) }

    @Test
    fun beforeLoading_isNotReadyAndSuggestsNothing() {
        val engine = SpellEngine()
        assertFalse(engine.isReady)
        assertTrue(engine.suggest("helo").isEmpty())
        assertNull(engine.bestCorrection("helo"))
    }

    @Test
    fun load_marksEngineReady() {
        val engine = engineWith("hello", "world")
        assertTrue(engine.isReady)
        assertEquals(2, engine.wordCount)
    }

    @Test
    fun suggest_findsSingleSubstitution() {
        val engine = engineWith("cat")
        assertTrue("cat" in engine.suggest("cot"))
    }

    @Test
    fun suggest_findsSingleInsertion() {
        // "hello" is reached from "helo" by inserting a character, which the symmetric-delete
        // algorithm covers from the other direction.
        val engine = engineWith("hello")
        assertTrue("hello" in engine.suggest("helo"))
    }

    @Test
    fun suggest_findsSingleDeletion() {
        val engine = engineWith("cat")
        assertTrue("cat" in engine.suggest("catt"))
    }

    @Test
    fun suggest_ignoresWordsTwoEditsAway() {
        val engine = engineWith("elephant")
        assertTrue(engine.suggest("elphnt").isEmpty())
    }

    @Test
    fun bestCorrection_leavesKnownWordsAlone() {
        val engine = engineWith("cat", "cot")
        assertNull(engine.bestCorrection("cat"))
    }

    @Test
    fun bestCorrection_ignoresMixedAlphanumerics() {
        val engine = engineWith("cat")
        assertNull(engine.bestCorrection("c4t"))
    }

    @Test
    fun bestCorrection_prefersTheMoreFrequentCandidate() {
        val engine = engineWith("cat" to 100, "car" to 5)
        assertEquals("cat", engine.bestCorrection("cst"))
    }

    @Test
    fun unload_freesIndex() {
        val engine = engineWith("hello")
        assertTrue(engine.isReady)
        engine.unload()
        assertFalse(engine.isReady)
        assertTrue(engine.suggest("helo").isEmpty())
    }

    @Test
    fun suggest_isCappedAtMaxSuggestions() {
        val engine = engineWith("bat", "cat", "hat", "mat", "rat", "sat")
        assertTrue(engine.suggest("aat").size <= SpellEngine.MAX_SUGGESTIONS)
    }

    @Test
    fun loadingAgain_replacesPreviousIndex() {
        val engine = engineWith("hello")
        engine.load(TestIndex.build(mapOf("zebra" to 1)))
        assertNull(engine.bestCorrection("helo"))
        assertEquals(1, engine.wordCount)
    }

    /**
     * Regression test for the bug found on the watch: typing "helo" offered "halo / held / helm"
     * and never "hello".
     *
     * All six words are edit-distance 1 from "helo" — halo/held/helm/help/hero by substituting a
     * letter, hello by inserting one. The word lists shipped without frequencies, so every entry
     * weighed the same and the order among them was arbitrary; the three the user saw were
     * effectively chosen at random. Frequencies are the fix, and they are the real Leipzig-derived
     * values from the shipped English list.
     *
     * "help" and "held" genuinely outrank "hello" in news text, which is fine: nothing is applied
     * automatically, so the requirement is that "hello" is *offered*, not that it is first.
     */
    @Test
    fun helo_offersHello_onceFrequenciesAreRealistic() {
        val engine = engineWith(
            "help" to 1382,
            "held" to 559,
            "hero" to 166,
            "hello" to 119,
            "helm" to 110,
            "halo" to 107
        )
        val suggestions = engine.suggest("helo")
        assertEquals("hello", suggestions.first(), "expected 'hello' first, got $suggestions")
        assertFalse("halo" in suggestions, "'halo' is rarer than 'hello': $suggestions")
    }

    /**
     * Four chips rather than three: "helo" has six neighbours and at three the intended word fell
     * off the end on the device.
     */
    @Test
    fun fourCandidates_areOfferedWhenAvailable() {
        val engine = engineWith(
            "help" to 1382,
            "held" to 559,
            "hero" to 166,
            "hello" to 119,
            "helm" to 110,
            "halo" to 107
        )
        assertEquals(listOf("hello", "help", "held", "hero"), engine.suggest("helo"))
    }

    /**
     * Words several edits away must never be offered. Found on the watch: typing "hel" produced
     * "the / he / she", because words that merely share a delete variant were accepted without
     * checking the real distance — and being common words, they outranked the correct candidates.
     */
    @Test
    fun distantWordsAreNeverOffered() {
        val engine = engineWith(
            "the" to 116854, "he" to 9734, "she" to 4516, "help" to 1382
        )
        val suggestions = engine.suggest("hel")
        assertFalse("the" in suggestions, "'the' is three edits from 'hel': $suggestions")
        assertFalse("she" in suggestions, "'she' is two edits from 'hel': $suggestions")
        assertEquals("help", suggestions.first())
    }

    @Test
    fun isKnown_reportsDictionaryMembership() {
        val engine = engineWith("hello")
        assertTrue(engine.isKnown("hello"))
        assertTrue(engine.isKnown("HELLO"), "lookups must be case-insensitive")
        assertFalse(engine.isKnown("helo"))
    }

    @Test
    fun suggest_isCaseInsensitive() {
        val engine = engineWith("hello")
        assertTrue("hello" in engine.suggest("HELO"))
    }

    // --- robustness: a corrupt asset must disable correction, never crash (spec §11) ---

    @Test
    fun load_rejectsBufferWithWrongMagic() {
        val junk = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        junk.putInt(0xDEADBEEF.toInt())
        junk.rewind()
        val engine = SpellEngine().apply { load(junk) }
        assertFalse(engine.isReady)
        assertTrue(engine.suggest("helo").isEmpty())
    }

    @Test
    fun load_rejectsTruncatedBuffer() {
        val full = TestIndex.build(mapOf("hello" to 5, "world" to 3))
        val truncated = ByteBuffer.allocate(full.capacity() / 2).order(ByteOrder.LITTLE_ENDIAN)
        full.limit(truncated.capacity())
        truncated.put(full)
        truncated.rewind()
        val engine = SpellEngine().apply { load(truncated) }
        assertFalse(engine.isReady, "a half-written index must be rejected, not mapped")
    }

    @Test
    fun load_rejectsEmptyBuffer() {
        val engine = SpellEngine().apply { load(ByteBuffer.allocate(0)) }
        assertFalse(engine.isReady)
    }

    @Test
    fun emptyIndex_isHandledCleanly() {
        val engine = SpellEngine().apply { load(TestIndex.build(emptyMap())) }
        assertTrue(engine.suggest("anything").isEmpty())
        assertFalse(engine.isKnown("anything"))
    }
}
