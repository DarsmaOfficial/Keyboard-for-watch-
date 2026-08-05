package dev.darsma.wearkey.dict

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Read-only symmetric-delete index over a packed binary buffer (spec §4.2).
 *
 * ## Why this exists instead of SymSpellKt
 *
 * SymSpellKt is a fine library but its in-memory representation is
 * `Map<Long, ArrayList<String>>` for the delete table plus `Map<String, Double>` for
 * frequencies. That is three Java objects per delete variant, and a 10 000-word English list has
 * 68 625 variants. Measured on the target watch with `dumpsys meminfo` (Dalvik Heap → Alloc),
 * one resident dictionary cost **15.5 MB** against the specification's 8 MB gate — and that was
 * already after cutting the list from 30 000 words (39.8 MB) to 10 000, so the remainder is
 * per-object overhead rather than vocabulary size.
 *
 * Spec §4.2 sets the decision rule in advance: benchmark SymSpellKt's real retained heap, and if
 * it does not fit, write a flat-index reader with a genuine mmap path — "pick one, do not specify
 * both and hope". It does not fit, so this is that reader.
 *
 * ## Why this is cheap
 *
 * There are no per-entry objects at all. Every table is a primitive slice of one [ByteBuffer],
 * which the Android layer supplies by memory-mapping the asset. Mapped pages are clean and
 * file-backed, so the kernel can evict them under pressure and they are accounted as mapped
 * pages rather than as Java heap. The only allocations per lookup are the result strings the
 * caller actually consumes.
 *
 * ## Ordering
 *
 * Words are stored in descending frequency order by the build script, so candidates come out
 * pre-ranked and no sorting happens per keystroke. This is what fixes the original defect, where
 * every word had frequency 1.0 and "helo" suggested "halo" instead of "hello".
 *
 * The buffer layout is documented in `tools/build_index.py`; [hash64] must stay identical to the
 * `hash64` there or nothing will be found.
 */
class WordIndex private constructor(
    private val buffer: ByteBuffer,
    private val wordCount: Int,
    private val variantCount: Int,
    private val wordOffsetPos: Int,
    private val wordLengthPos: Int,
    private val frequencyPos: Int,
    private val variantHashPos: Int,
    private val pairStartPos: Int,
    private val wordIndexPos: Int,
    private val blobPos: Int
) {

    /** Number of words in the index. */
    val size: Int get() = wordCount

    /**
     * Candidate words within edit distance 1 of [term], best first.
     *
     * Both directions of the symmetric-delete algorithm are covered: the term's own delete
     * variants are looked up, which finds dictionary words reachable by deleting from either
     * side, so insertions, deletions and substitutions of one character are all matched.
     */
    fun lookup(term: String, limit: Int): List<String> {
        if (term.isEmpty() || wordCount == 0) return emptyList()

        // Small fixed-size scratch: candidates are gathered as word indices, deduplicated, then
        // resolved to strings once. Word indices are ascending in frequency rank, so sorting the
        // indices sorts by frequency for free.
        val found = sortedSetOf<Int>()

        collectInto(found, term)
        for (i in term.indices) {
            collectInto(found, buildString(term.length - 1) {
                append(term, 0, i)
                append(term, i + 1, term.length)
            })
        }

        if (found.isEmpty()) return emptyList()
        val result = ArrayList<String>(minOf(limit, found.size))
        for (index in found) {
            result.add(wordAt(index))
            if (result.size >= limit) break
        }
        return result
    }

    /** True when [term] is itself a word in the index. */
    fun contains(term: String): Boolean {
        val slot = findVariant(hash64(term))
        if (slot < 0) return false
        val start = int(pairStartPos, slot)
        val end = int(pairStartPos, slot + 1)
        for (i in start until end) {
            if (wordAt(int(wordIndexPos, i)) == term) return true
        }
        return false
    }

    /** Corpus frequency of the word at [index]; higher is more common. */
    fun frequencyAt(index: Int): Int = int(frequencyPos, index)

    private fun collectInto(sink: MutableSet<Int>, variant: String) {
        val slot = findVariant(hash64(variant))
        if (slot < 0) return
        val start = int(pairStartPos, slot)
        val end = int(pairStartPos, slot + 1)
        for (i in start until end) sink.add(int(wordIndexPos, i))
    }

    /** Binary search over the ascending variant-hash table. Returns -1 when absent. */
    private fun findVariant(hash: Long): Int {
        var low = 0
        var high = variantCount - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = buffer.getLong(variantHashPos + mid * 8)
            when {
                value < hash -> low = mid + 1
                value > hash -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun wordAt(index: Int): String {
        val offset = int(wordOffsetPos, index)
        val length = buffer.get(wordLengthPos + index).toInt() and 0xFF
        val bytes = ByteArray(length)
        // Absolute reads only: the buffer's position is never touched, so instances are safe to
        // share between threads without synchronisation.
        for (i in 0 until length) bytes[i] = buffer.get(blobPos + offset + i)
        return String(bytes, Charsets.UTF_8)
    }

    private fun int(base: Int, index: Int): Int = buffer.getInt(base + index * 4)

    companion object {
        private const val MAGIC = 0x31444B57 // "WKD1" little-endian
        private const val FNV_OFFSET = -3750763034362895579L // 0xCBF29CE484222325
        private const val FNV_PRIME = 0x100000001B3L

        /** Must match `hash64` in tools/build_index.py byte for byte. */
        fun hash64(text: String): Long {
            var h = FNV_OFFSET
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                h = h xor (byte.toLong() and 0xFF)
                h *= FNV_PRIME
            }
            return h
        }

        /**
         * Wraps a buffer produced by tools/build_index.py, or returns null when it is not a valid
         * index. A corrupt or truncated asset must degrade to layout-only typing rather than
         * crash the keyboard (spec §11 failure modes).
         */
        fun from(buffer: ByteBuffer): WordIndex? {
            val buf = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            if (buf.capacity() < 20) return null
            if (buf.getInt(0) != MAGIC) return null

            val wordCount = buf.getInt(4)
            val variantCount = buf.getInt(8)
            val pairCount = buf.getInt(12)
            val blobLength = buf.getInt(16)
            if (wordCount < 0 || variantCount < 0 || pairCount < 0 || blobLength < 0) return null

            val wordOffsetPos = 20
            val wordLengthPos = wordOffsetPos + wordCount * 4
            val frequencyPos = wordLengthPos + wordCount
            val variantHashPos = frequencyPos + wordCount * 4
            val pairStartPos = variantHashPos + variantCount * 8
            val wordIndexPos = pairStartPos + (variantCount + 1) * 4
            val blobPos = wordIndexPos + pairCount * 4

            if (blobPos + blobLength > buf.capacity()) return null

            return WordIndex(
                buffer = buf,
                wordCount = wordCount,
                variantCount = variantCount,
                wordOffsetPos = wordOffsetPos,
                wordLengthPos = wordLengthPos,
                frequencyPos = frequencyPos,
                variantHashPos = variantHashPos,
                pairStartPos = pairStartPos,
                wordIndexPos = wordIndexPos,
                blobPos = blobPos
            )
        }
    }
}
