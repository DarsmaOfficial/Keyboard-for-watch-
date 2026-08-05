package dev.darsma.wearkey.dict

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds a [WordIndex] buffer in memory, mirroring `tools/build_index.py` exactly.
 *
 * Having the format written twice is a deliberate trade: it means the tests verify the *format*
 * rather than just round-tripping the production writer, so a change to either side that breaks
 * compatibility fails a test instead of silently shipping an unreadable asset. The layout is
 * documented once, in build_index.py.
 */
object TestIndex {

    /** [entries] is word to frequency. */
    fun build(entries: Map<String, Int>): ByteBuffer {
        // Descending frequency, alphabetical within ties — the reader relies on this ordering to
        // return candidates already ranked.
        val sorted = entries.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
        )
        val words = sorted.map { it.key }
        val freqs = sorted.map { it.value }

        val blob = StringBuilder()
        val offsets = ArrayList<Int>(words.size)
        val lengths = ArrayList<Int>(words.size)
        var cursor = 0
        for (word in words) {
            val bytes = word.toByteArray(Charsets.UTF_8)
            offsets.add(cursor)
            lengths.add(bytes.size)
            cursor += bytes.size
            blob.append(word)
        }
        val blobBytes = blob.toString().toByteArray(Charsets.UTF_8)

        val buckets = sortedMapOf<Long, MutableList<Int>>()
        words.forEachIndexed { index, word ->
            for (variant in variantsOf(word)) {
                buckets.getOrPut(WordIndex.hash64(variant)) { ArrayList() }.add(index)
            }
        }

        val variantHashes = buckets.keys.toList()
        val pairStart = ArrayList<Int>(variantHashes.size + 1)
        val wordIndex = ArrayList<Int>()
        pairStart.add(0)
        for (hash in variantHashes) {
            wordIndex.addAll(buckets.getValue(hash).sorted())
            pairStart.add(wordIndex.size)
        }

        val size = 20 + words.size * 4 + words.size + words.size * 4 +
            variantHashes.size * 8 + pairStart.size * 4 + wordIndex.size * 4 + blobBytes.size

        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x31444B57) // "WKD1"
        buffer.putInt(words.size)
        buffer.putInt(variantHashes.size)
        buffer.putInt(wordIndex.size)
        buffer.putInt(blobBytes.size)
        offsets.forEach { buffer.putInt(it) }
        lengths.forEach { buffer.put(it.toByte()) }
        freqs.forEach { buffer.putInt(it) }
        variantHashes.forEach { buffer.putLong(it) }
        pairStart.forEach { buffer.putInt(it) }
        wordIndex.forEach { buffer.putInt(it) }
        buffer.put(blobBytes)
        buffer.rewind()
        return buffer
    }

    private fun variantsOf(word: String): Set<String> {
        val out = linkedSetOf(word)
        for (i in word.indices) out.add(word.removeRange(i, i + 1))
        return out
    }
}
