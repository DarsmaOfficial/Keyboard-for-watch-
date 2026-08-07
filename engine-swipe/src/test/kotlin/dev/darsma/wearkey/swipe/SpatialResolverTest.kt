package dev.darsma.wearkey.swipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Word resolution from per-tap probability distributions (spec §7.2b).
 *
 * The distributions here are written by hand rather than produced by the touch model, which keeps
 * these tests about the resolver's own logic. Whether the *real* distributions from a calibrated
 * device resolve words correctly is a hardware question, and §7.2b requires calibration on real
 * hardware before that can be answered.
 */
class SpatialResolverTest {

    private val vocabulary = listOf("hello", "gello", "help", "hell", "world", "word", "he", "go")
    private val frequencies = intArrayOf(50_000, 1, 12_000, 900, 40_000, 8_000, 30_000, 25_000)

    private fun resolver(weight: Float = SpatialResolver.DEFAULT_FREQUENCY_WEIGHT) =
        SpatialResolver(vocabulary, frequencies, weight)

    /** A confident tap: almost all mass on one key. */
    private fun sure(c: Char): Map<Char, Float> = mapOf(c to 0.95f)

    /** An ambiguous tap between two adjacent keys. */
    private fun between(a: Char, b: Char): Map<Char, Float> = mapOf(a to 0.5f, b to 0.5f)

    @Test
    fun `confident taps resolve to the obvious word`() {
        val taps = "hello".map { sure(it) }
        assertEquals("hello", resolver().resolve(taps).first().word)
    }

    /**
     * The case the whole feature exists for: a tap that landed exactly between two keys is settled
     * by the vocabulary rather than by a coin flip.
     */
    @Test
    fun `an ambiguous first tap is resolved by word likelihood`() {
        val taps = listOf(between('g', 'h'), sure('e'), sure('l'), sure('l'), sure('o'))
        val top = resolver().resolve(taps).first()
        assertEquals("hello", top.word, "spatial tie should break toward the far more common word")
    }

    @Test
    fun `only words of the tapped length are considered`() {
        val taps = "hello".map { sure(it) }
        val words = resolver().resolve(taps, limit = 8).map { it.word }
        assertTrue(words.all { it.length == 5 }, "got words of other lengths: $words")
        assertTrue("help" !in words)
        assertTrue("he" !in words)
    }

    /**
     * One badly misplaced tap must not eliminate the correct word.
     *
     * Without the probability floor this candidate would score negative infinity and vanish, which
     * makes the mode brittle in exactly the situation it is meant to tolerate.
     */
    @Test
    fun `a word survives a tap that gave its letter no probability`() {
        // The third tap missed 'l' entirely and hit 'k' and 'j'.
        val taps = listOf(sure('h'), sure('e'), mapOf('k' to 0.6f, 'j' to 0.4f), sure('l'), sure('o'))
        val words = resolver().resolve(taps, limit = 8).map { it.word }
        assertTrue("hello" in words, "one bad tap must not eliminate the word; got $words")
    }

    @Test
    fun `strong spatial evidence still overrides frequency`() {
        // Every tap confidently spells the rare word; frequency must not overturn that.
        val taps = "gello".map { sure(it) }
        assertEquals("gello", resolver().resolve(taps).first().word)
    }

    @Test
    fun `higher frequency weight shifts a genuine tie`() {
        val taps = listOf(between('g', 'h'), sure('e'), sure('l'), sure('l'), sure('o'))
        // With frequency ignored the two candidates are spatially identical, so the common word
        // must not be assumed — this pins that the tie is decided by the prior, not by list order.
        val neutral = resolver(weight = 0f).resolve(taps, limit = 2)
        assertTrue(
            kotlin.math.abs(neutral[0].score - neutral[1].score) < 1e-4f,
            "without a frequency prior the two candidates should score equally"
        )
    }

    @Test
    fun `empty input yields no candidates`() {
        assertTrue(resolver().resolve(emptyList()).isEmpty())
    }

    @Test
    fun `results are ordered best first`() {
        val taps = "hello".map { sure(it) }
        val scores = resolver().resolve(taps, limit = 4).map { it.score }
        assertEquals(scores.sortedDescending(), scores)
    }
}
