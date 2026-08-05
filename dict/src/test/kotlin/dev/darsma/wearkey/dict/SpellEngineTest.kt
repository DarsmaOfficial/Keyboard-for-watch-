package dev.darsma.wearkey.dict

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpellEngineTest {

    private fun engineWith(vararg words: String): SpellEngine =
        SpellEngine().apply { load(words.asSequence()) }

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
    }

    @Test
    fun load_ignoresBlankAndCommentLines() {
        val engine = SpellEngine()
        engine.load(sequenceOf("", "   ", "# comment", "hello"))
        assertTrue(engine.isReady)
    }

    @Test
    fun emptyWordList_leavesEngineUnready() {
        val engine = SpellEngine()
        engine.load(sequenceOf("", "# nothing here"))
        assertFalse(engine.isReady)
    }

    @Test
    fun singleCharacterTypo_isCorrected() {
        val engine = engineWith("hello", "world", "keyboard")
        assertEquals("hello", engine.bestCorrection("helo"))
    }

    @Test
    fun transposedLetters_areCorrected() {
        val engine = engineWith("keyboard", "watch")
        assertEquals("watch", engine.bestCorrection("wacth"))
    }

    @Test
    fun correctWord_returnsNoCorrection() {
        val engine = engineWith("hello", "world")
        assertNull(engine.bestCorrection("hello"))
    }

    @Test
    fun wordFarFromDictionary_isLeftAlone() {
        // Edit distance 1 only (spec §4.2), so a wildly different string must not be "fixed".
        val engine = engineWith("hello")
        assertNull(engine.bestCorrection("zzzzzz"))
    }

    @Test
    fun wordsWithDigits_areNeverCorrected() {
        val engine = engineWith("hello")
        assertNull(engine.bestCorrection("hello2"))
        assertNull(engine.bestCorrection("2fa"))
    }

    @Test
    fun blankInput_isSafe() {
        val engine = engineWith("hello")
        assertTrue(engine.suggest("").isEmpty())
        assertNull(engine.bestCorrection("   "))
    }

    @Test
    fun frequencyColumn_isParsed() {
        val engine = SpellEngine()
        engine.load(sequenceOf("cat\t100", "car\t5"))
        assertTrue(engine.isReady)
        // Both are one edit from "car"/"cat"; the higher-frequency word should win.
        assertEquals("cat", engine.bestCorrection("cst"))
    }

    @Test
    fun unload_freesDictionary() {
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
    fun loadingAgain_replacesPreviousDictionary() {
        val engine = engineWith("hello")
        engine.load(sequenceOf("zebra"))
        // "helo" is no longer close to anything in the new dictionary.
        assertNull(engine.bestCorrection("helo"))
    }

    /**
     * Regression test for a bug found on the watch: typing "helo" offered "halo / held / helm"
     * and never "hello".
     *
     * Every one of those is a legitimate edit-distance-1 neighbour of "helo" — halo/held/helm/help
     * by substituting a letter, hello by inserting one. Because the shipped word lists carried no
     * frequency column, all of them were loaded with frequency 1.0, so the order among them was
     * arbitrary and the three the user actually saw were effectively picked at random. With real
     * frequencies attached, the row is ordered by how common the words are and "hello" makes the
     * cut while the rarer "halo" does not.
     *
     * The numbers are the real Leipzig-derived values shipped in assets/dictionaries/en.txt.
     * "help" and "held" genuinely outrank "hello" in news text, and that is fine: nothing is ever
     * applied automatically (the keyboard only offers, the user taps), so the requirement is that
     * "hello" is *present*, not that it is first.
     */
    @Test
    fun helo_offersHello_onceFrequenciesAreRealistic() {
        val engine = engineWith(
            "hello\t119",
            "halo\t107",
            "helm\t110",
            "held\t559",
            "help\t1382"
        )
        val suggestions = engine.suggest("helo")
        assertTrue("hello" in suggestions, "expected 'hello' among $suggestions")
        // The rarer neighbours are the ones that should be squeezed out, not "hello".
        assertFalse("halo" in suggestions, "'halo' is rarer than 'hello': $suggestions")
    }

    /**
     * "helo" has six distance-1 neighbours in the real word list, and on the device the one the
     * user meant ("hello", 4th by frequency) fell just off the end of a three-chip row. This pins
     * the widened row so the cap cannot silently drop back.
     */
    @Test
    fun fourCandidates_areOfferedWhenAvailable() {
        val engine = engineWith(
            "help\t1382",
            "held\t559",
            "hero\t166",
            "hello\t119",
            "helm\t110",
            "halo\t107"
        )
        val suggestions = engine.suggest("helo")
        assertEquals(4, suggestions.size, "expected four chips, got $suggestions")
        assertEquals(listOf("help", "held", "hero", "hello"), suggestions)
    }

    /**
     * The shipped lists are `word<TAB>frequency`. A list that silently lost its frequency column
     * would still load and still "work", but every candidate would tie at 1.0 and ranking would
     * go back to being arbitrary — the original defect. Assert the parse explicitly.
     */
    @Test
    fun load_parsesTabSeparatedFrequencies() {
        val engine = engineWith("aaa\t5", "aab\t9999")
        assertEquals("aab", engine.suggest("aac").first())
    }

    /** The ordering invariant the fix relies on: more frequent candidates come first. */
    @Test
    fun suggestions_areOrderedByFrequency() {
        val engine = engineWith("hello\t119", "halo\t107", "help\t1382")
        assertEquals(listOf("help", "hello", "halo"), engine.suggest("helo"))
    }
}
