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
}
