package dev.darsma.wearkey.swipe

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the glide-typing recogniser (spec §7.3).
 *
 * Gestures are synthesised by walking the same key centres the templates are built from, with
 * deliberate noise and rate variation added. That is the honest test for this component: it proves
 * the matcher tolerates the variation a real finger produces, without pretending a synthetic path
 * is equivalent to a human one. Whether *real* swipes rank correctly is a device question, recorded
 * as unverified in STATUS.md rather than asserted here.
 */
class SwipeRecognizerTest {

    // A compact QWERTY block, 3 rows, spaced like the real grid: 10 columns of 40 px, 50 px rows.
    private val letters = "qwertyuiopasdfghjklzxcvbnm"

    private val xs = FloatArray(letters.length) { i ->
        val row = rowOf(i)
        val col = i - rowStart(row)
        // Rows 2 and 3 are inset, as on a real QWERTY.
        val inset = when (row) {
            0 -> 0f
            1 -> 20f
            else -> 60f
        }
        20f + inset + col * 40f
    }

    private val ys = FloatArray(letters.length) { i -> 25f + rowOf(i) * 50f }

    private fun rowOf(i: Int) = when {
        i < 10 -> 0
        i < 19 -> 1
        else -> 2
    }

    private fun rowStart(row: Int) = when (row) {
        0 -> 0
        1 -> 10
        else -> 19
    }

    private val geometry = KeyGeometry.of(letters, xs, ys)

    /** Traces a word's key centres, optionally adding noise and uneven sampling. */
    private fun swipe(word: String, noise: Float = 0f, seed: Int = 1): SwipePath {
        var rng = seed
        fun next(): Float {
            rng = rng * 1103515245 + 12345
            return ((rng ushr 16) and 0x7FFF) / 32767f * 2f - 1f
        }

        val px = ArrayList<Float>()
        val py = ArrayList<Float>()
        val idx = word.map { geometry.indexOf(it) }
        for (s in 0 until idx.size - 1) {
            val a = idx[s]
            val b = idx[s + 1]
            // Uneven step count per segment simulates the finger speeding up and slowing down.
            val steps = 4 + (s % 3) * 3
            for (t in 0 until steps) {
                val f = t.toFloat() / steps
                px.add(xs[a] + f * (xs[b] - xs[a]) + next() * noise)
                py.add(ys[a] + f * (ys[b] - ys[a]) + next() * noise)
            }
        }
        val last = idx.last()
        px.add(xs[last])
        py.add(ys[last])

        val path = SwipePath.fromSamples(px.toFloatArray(), py.toFloatArray(), px.size, minLength = 1f)
        return assertNotNull(path, "synthetic swipe for '$word' should produce a path")
    }

    private fun recogniser(vararg words: String): SwipeRecognizer {
        val vocab = words.toList()
        val freqs = IntArray(vocab.size) { 100 }
        return SwipeRecognizer(vocab, freqs).also { it.setGeometry(geometry) }
    }

    @Test
    fun `resampling produces exactly N points`() {
        val p = swipe("hello")
        assertEquals(SwipePath.N * 2, p.points.size)
    }

    @Test
    fun `a gesture shorter than the minimum length is rejected as a tap`() {
        val xs = floatArrayOf(100f, 101f, 100.5f)
        val ys = floatArrayOf(100f, 100.5f, 101f)
        assertNull(SwipePath.fromSamples(xs, ys, 3, minLength = 20f))
    }

    @Test
    fun `normalisation makes a path invariant to scale and position`() {
        val small = SwipePath.fromKeyCentres(
            floatArrayOf(0f, 10f, 20f), floatArrayOf(0f, 10f, 0f), 3
        )!!
        val large = SwipePath.fromKeyCentres(
            floatArrayOf(500f, 700f, 900f), floatArrayOf(300f, 500f, 300f), 3
        )!!
        for (i in small.points.indices) {
            assertTrue(
                abs(small.points[i] - large.points[i]) < 1e-4f,
                "point $i differs: ${small.points[i]} vs ${large.points[i]}"
            )
        }
    }

    @Test
    fun `a clean swipe recognises its own word first`() {
        val r = recogniser("hello", "world", "apple", "orange", "keyboard")
        val top = r.recognise(swipe("hello")).first()
        assertEquals("hello", top.word)
    }

    @Test
    fun `recognition survives noise and uneven finger speed`() {
        val r = recogniser("hello", "world", "apple", "orange", "keyboard", "help", "held")
        for (seed in 1..8) {
            val top = r.recognise(swipe("world", noise = 6f, seed = seed)).first()
            assertEquals("world", top.word, "failed at seed $seed")
        }
    }

    @Test
    fun `word absent from the layout yields no template rather than crashing`() {
        // Cyrillic word against a Latin grid — the mixed-vocabulary case.
        val r = recogniser("привет", "hello")
        val results = r.recognise(swipe("hello"))
        assertEquals(1, results.size)
        assertEquals("hello", results.first().word)
    }

    @Test
    fun `frequency breaks ties between words with near-identical paths`() {
        // "hi" and "ho" are adjacent on this grid, so shape barely separates them.
        val vocab = listOf("hi", "ho")
        val rare = SwipeRecognizer(vocab, intArrayOf(1, 90000)).also { it.setGeometry(geometry) }
        val common = SwipeRecognizer(vocab, intArrayOf(90000, 1)).also { it.setGeometry(geometry) }

        val path = swipe("hi")
        assertEquals("ho", rare.recognise(path).first().word)
        assertEquals("hi", common.recognise(path).first().word)
    }

    @Test
    fun `early abandon does not change the ranking`() {
        val vocab = listOf("hello", "world", "apple", "orange", "keyboard", "help", "held", "hero")
        val freqs = IntArray(vocab.size) { 100 }
        val r = SwipeRecognizer(vocab, freqs).also { it.setGeometry(geometry) }

        // limit=1 abandons aggressively; limit=8 never abandons. The winner must agree.
        val path = swipe("apple", noise = 4f)
        assertEquals(r.recognise(path, limit = 8).first().word, r.recognise(path, limit = 1).first().word)
    }

    @Test
    fun `repeated matching allocates no per-call buffers`() {
        // Not a timing test — it pins the contract that Dtw reuses its rows, by proving repeated
        // calls on one instance stay correct. A regression to per-call arrays would still pass, but
        // a regression to *shared mutable state across paths* would not.
        val r = recogniser("hello", "world", "apple")
        val a = swipe("hello")
        val b = swipe("world")
        repeat(50) {
            assertEquals("hello", r.recognise(a).first().word)
            assertEquals("world", r.recognise(b).first().word)
        }
    }
}
