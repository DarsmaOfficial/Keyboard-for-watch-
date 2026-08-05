package dev.darsma.wearkey.dict

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Format-level tests for the packed index.
 *
 * The most important one is [hash64_matchesReferenceValues]. The Kotlin reader and the Python
 * writer (`tools/build_index.py`) each implement FNV-1a independently, and if they ever disagree
 * the app would still start, still map its asset and simply find nothing — a silent, total loss of
 * autocorrect with no error anywhere. Pinning known hash values catches that immediately.
 */
class WordIndexTest {

    /**
     * Expected values produced by the reference implementation in tools/build_index.py:
     *
     *     python3 -c "import sys; sys.path.insert(0,'tools'); import build_index as b; \
     *                 print(b.hash64('hello'), b.hash64('helo'), b.hash64(''), b.hash64('привет'))"
     */
    @Test
    fun hash64_matchesReferenceValues() {
        assertEquals(-6615550055289275125L, WordIndex.hash64("hello"))
        assertEquals(763636992035666291L, WordIndex.hash64("helo"))
        assertEquals(-3750763034362895579L, WordIndex.hash64(""))
        assertEquals(2006539529158035231L, WordIndex.hash64("привет"))
    }

    @Test
    fun wordsAreStoredInDescendingFrequencyOrder() {
        val index = WordIndex.from(TestIndex.build(mapOf("rare" to 1, "common" to 900)))!!
        assertEquals(2, index.size)
        // Index 0 must be the most frequent word, since lookups return low indices first.
        assertEquals(900, index.frequencyAt(0))
        assertEquals(1, index.frequencyAt(1))
    }

    @Test
    fun contains_distinguishesRealWordsFromHashNeighbours() {
        val index = WordIndex.from(TestIndex.build(mapOf("hello" to 5)))!!
        assertTrue(index.contains("hello"))
        // "helo" hashes into the same delete bucket but is not itself a word.
        assertTrue(!index.contains("helo"))
    }

    @Test
    fun lookup_respectsTheLimit() {
        val index = WordIndex.from(
            TestIndex.build(mapOf("bat" to 6, "cat" to 5, "hat" to 4, "mat" to 3, "rat" to 2))
        )!!
        assertEquals(2, index.lookup("aat", limit = 2).size)
    }

    @Test
    fun lookup_handlesNonLatinScript() {
        val index = WordIndex.from(TestIndex.build(mapOf("привет" to 111)))!!
        // One character deleted from a multi-byte word — checks that offsets are byte-based, not
        // char-based, which is the classic UTF-8 indexing bug.
        assertTrue("привет" in index.lookup("привт", limit = 4))
    }

    /**
     * Regression test for a defect found on the watch: typing "hel" offered "the / he / she".
     *
     * The delete-variant table is a *filter*, not an answer — two words can share a delete variant
     * and still be several edits apart. "hel" and "the" both reduce to "he", so gathering bucket
     * members without verifying the real edit distance produced confident, completely wrong
     * suggestions. Worse, they outranked the correct ones because they are common words.
     */
    @Test
    fun lookup_rejectsCandidatesMoreThanOneEditAway() {
        val index = WordIndex.from(
            TestIndex.build(mapOf("the" to 116854, "he" to 9734, "she" to 4516, "help" to 1382))
        )!!
        val hits = index.lookup("hel", limit = 4)
        assertTrue("help" in hits, "'help' is one edit from 'hel': $hits")
        assertTrue("the" !in hits, "'the' is three edits from 'hel': $hits")
        assertTrue("she" !in hits, "'she' is two edits from 'hel': $hits")
        // "he" is one deletion from "hel", so it legitimately stays.
        assertTrue("he" in hits, "'he' is one edit from 'hel': $hits")
    }

    /**
     * The ranking model, pinned with the real shipped frequencies.
     *
     * Candidates are scored as `frequency x P(this slip | this word)` rather than by frequency
     * alone. Corpus frequency answers "which word is commoner"; the question the keyboard actually
     * faces is "which word was this person trying to type". On the device, ranking "helo" by
     * frequency alone put hello 5th behind help/held/hero/hell and off the end of the row, even
     * though omitting one of a doubled letter is a far likelier slip than typing a different word.
     */
    @Test
    fun doubledLetterTypo_outranksCommonerNeighbours() {
        val index = WordIndex.from(
            TestIndex.build(
                mapOf(
                    "help" to 1382, "held" to 559, "hero" to 166,
                    "hell" to 161, "hello" to 119, "helm" to 110, "halo" to 107
                )
            )
        )!!
        assertEquals("hello", index.lookup("helo", limit = 4).first())
    }

    /** A prefix match must beat a shorter word, however common that shorter word is. */
    @Test
    fun prefixMatch_outranksAShorterWord() {
        val index = WordIndex.from(TestIndex.build(mapOf("he" to 9734, "help" to 1382)))!!
        assertEquals("help", index.lookup("hel", limit = 4).first())
    }

    @Test
    fun lookup_acceptsAllThreeSingleEditKinds() {
        val index = WordIndex.from(
            TestIndex.build(mapOf("cat" to 9, "cot" to 8, "cart" to 7, "at" to 6))
        )!!
        val hits = index.lookup("cat", limit = 8)
        assertTrue("cot" in hits, "substitution: $hits")
        assertTrue("cart" in hits, "insertion: $hits")
        assertTrue("at" in hits, "deletion: $hits")
    }

    @Test
    fun from_rejectsGarbage() {
        val bogus = java.nio.ByteBuffer.allocate(8)
        assertEquals(null, WordIndex.from(bogus))
    }
}
