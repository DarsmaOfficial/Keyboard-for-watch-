package dev.darsma.wearkey.imecore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipboardStoreTest {

    @Test
    fun add_putsNewestFirst() {
        val store = ClipboardStore()
        store.add("first")
        store.add("second")
        assertEquals(listOf("second", "first"), store.visibleEntries().map { it.text })
    }

    @Test
    fun add_deduplicatesByMovingToFront() {
        val store = ClipboardStore()
        store.add("a")
        store.add("b")
        store.add("a")
        assertEquals(listOf("a", "b"), store.visibleEntries().map { it.text })
    }

    @Test
    fun add_ignoresBlank() {
        val store = ClipboardStore()
        store.add("   ")
        assertTrue(store.visibleEntries().isEmpty())
    }

    @Test
    fun capacity_evictsOldestUnpinned() {
        val store = ClipboardStore(maxEntries = 3)
        store.add("1"); store.add("2"); store.add("3"); store.add("4")
        assertEquals(listOf("4", "3", "2"), store.visibleEntries().map { it.text })
    }

    @Test
    fun pinnedEntries_surviveEvictionAndSortFirst() {
        val store = ClipboardStore(maxEntries = 2)
        store.add("keep")
        store.pin("keep")
        store.add("a"); store.add("b"); store.add("c")
        val texts = store.visibleEntries().map { it.text }
        assertEquals("keep", texts.first())
        assertTrue(texts.contains("keep"))
    }

    @Test
    fun otpCode_isFlaggedSensitive() {
        assertTrue(ClipboardStore.looksSensitive("123456"))
        assertTrue(ClipboardStore.looksSensitive(" 8421 "))
    }

    @Test
    fun cardNumber_isFlaggedSensitive() {
        assertTrue(ClipboardStore.looksSensitive("4111 1111 1111 1111"))
    }

    @Test
    fun ordinaryText_isNotSensitive() {
        assertFalse(ClipboardStore.looksSensitive("hello world"))
        assertFalse(ClipboardStore.looksSensitive("12"))
    }

    @Test
    fun sensitiveEntries_expireAfterWindow() {
        var now = 0L
        val store = ClipboardStore(clock = { now })
        store.sensitiveExpiryMs = 1000L
        store.add("123456")   // sensitive
        store.add("greeting") // not sensitive
        now = 2000L
        assertEquals(listOf("greeting"), store.visibleEntries().map { it.text })
    }

    @Test
    fun pinnedSensitiveEntry_doesNotExpire() {
        var now = 0L
        val store = ClipboardStore(clock = { now })
        store.sensitiveExpiryMs = 1000L
        store.add("123456")
        store.pin("123456")
        now = 5000L
        assertEquals(listOf("123456"), store.visibleEntries().map { it.text })
    }

    @Test
    fun delete_removesEntry() {
        val store = ClipboardStore()
        store.add("gone")
        store.delete("gone")
        assertTrue(store.visibleEntries().isEmpty())
    }

    @Test
    fun clearAll_removesEverythingIncludingPinned() {
        val store = ClipboardStore()
        store.add("a")
        store.pin("a")
        store.add("b")
        store.clearAll()
        assertTrue(store.visibleEntries().isEmpty())
    }

    @Test
    fun listener_firesOnMutations() {
        val store = ClipboardStore()
        var count = 0
        store.addListener { count++ }
        store.add("a")
        store.pin("a")
        store.delete("a")
        store.clearAll()
        assertEquals(4, count)
    }
}
