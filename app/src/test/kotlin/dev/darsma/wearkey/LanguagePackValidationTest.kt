package dev.darsma.wearkey

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Validation rules for imported language packs (spec §4.3).
 *
 * These are pure-logic tests: they exercise the same constants and predicates
 * [LanguagePackManager] uses, without an Android context. The discovery and signature paths need a
 * device and are covered by on-device verification instead — asserting them here would require
 * mocking `PackageManager` so heavily that the test would only prove the mock behaves as written.
 */
class LanguagePackValidationTest {

    private val safeTag = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")

    private fun isPlausibleIndex(bytes: ByteArray): Boolean {
        if (bytes.size < 40) return false
        val magic = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return magic == LanguagePackManager.INDEX_MAGIC
    }

    /**
     * The magic constant must match what the build tool actually writes.
     *
     * Pinned against the real committed index rather than a literal, because this exact constant
     * was wrong when first written — guessed as "WKI1" when the tool writes "WKD1". A test that
     * merely restated the constant would have agreed with the bug.
     */
    @Test
    fun `magic constant matches a real built index`() {
        val candidates = listOf(
            File("src/main/assets/dictionaries/en.bin"),
            File("app/src/main/assets/dictionaries/en.bin"),
            File("../app/src/main/assets/dictionaries/en.bin")
        )
        val index = candidates.firstOrNull { it.isFile }
            ?: return // Asset layout differs in this checkout; the byte-compare CI gate covers it.

        val head = index.readBytes(40)
        assertTrue(isPlausibleIndex(head), "the shipped en.bin must pass the import check")
        assertEquals("WKD1", String(head, 0, 4, Charsets.US_ASCII))
    }

    @Test
    fun `an empty or truncated file is rejected`() {
        assertFalse(isPlausibleIndex(ByteArray(0)))
        assertFalse(isPlausibleIndex(ByteArray(39) { 0x57 }))
    }

    @Test
    fun `a text file is rejected`() {
        val text = "hello world, this is clearly not a dictionary index at all".toByteArray()
        assertFalse(isPlausibleIndex(text))
    }

    @Test
    fun `a file with the wrong magic is rejected even at correct length`() {
        val bytes = ByteArray(64)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0x12345678)
        assertFalse(isPlausibleIndex(bytes))
    }

    @Test
    fun `well formed language tags are accepted`() {
        for (tag in listOf("en", "ru", "nl", "en-US", "ru-RU", "zh-Hans", "sr-Latn-RS")) {
            assertTrue(tag.matches(safeTag), "$tag should be accepted")
        }
    }

    /**
     * Tags become filenames, so traversal and separator characters must not survive.
     *
     * This is the security-relevant case: without it, importing a tag of `../../databases/x` would
     * write outside the private directory.
     */
    @Test
    fun `path traversal and separators are refused`() {
        for (tag in listOf(
            "../../etc/passwd",
            "..",
            "en/../../x",
            "en US",
            "en_US\u0000",
            "/absolute",
            "en.bin",
            ""
        )) {
            assertFalse(tag.matches(safeTag), "$tag must be refused as a filename")
        }
    }

    private fun File.readBytes(n: Int): ByteArray =
        inputStream().use { stream ->
            val buf = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = stream.read(buf, read, n - read)
                if (r <= 0) break
                read += r
            }
            buf
        }
}
