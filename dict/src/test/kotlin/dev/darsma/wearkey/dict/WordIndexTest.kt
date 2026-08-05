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

    @Test
    fun from_rejectsGarbage() {
        val bogus = java.nio.ByteBuffer.allocate(8)
        assertEquals(null, WordIndex.from(bogus))
    }
}
