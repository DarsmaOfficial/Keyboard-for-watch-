package dev.darsma.wearkey.imecore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Emoji catalogue and recents behaviour (spec §11 v0.3). */
class EmojiTest {

    @Test
    fun `catalogue has no duplicates across categories`() {
        val all = EmojiCatalogue.CATEGORIES.flatMap { it.emoji }
        val seen = HashSet<String>()
        val duplicates = all.filterNot { seen.add(it) }
        assertTrue(duplicates.isEmpty(), "duplicated emoji: $duplicates")
    }

    /**
     * Every entry must be a single Unicode scalar.
     *
     * Multi-codepoint sequences (skin tones, ZWJ families) render only if the platform font has
     * the composed glyph; on an older `NotoColorEmoji.ttf` they break into separate glyphs while
     * still committing the full sequence, so the user sends something they never saw.
     */
    @Test
    fun `every entry is a single code point`() {
        for (category in EmojiCatalogue.CATEGORIES) {
            for (emoji in category.emoji) {
                assertEquals(
                    1,
                    emoji.codePointCount(0, emoji.length),
                    "$emoji in ${category.id} is a multi-codepoint sequence"
                )
            }
        }
    }

    @Test
    fun `no category is empty`() {
        for (category in EmojiCatalogue.CATEGORIES) {
            assertTrue(category.emoji.isNotEmpty(), "${category.id} is empty")
        }
    }

    @Test
    fun `recents move a repeated emoji to the front without duplicating it`() {
        val recents = EmojiRecents()
        recents.record("😀")
        recents.record("😂")
        recents.record("😀")
        assertEquals(listOf("😀", "😂"), recents.all())
    }

    @Test
    fun `recents evict the oldest beyond capacity`() {
        val recents = EmojiRecents(capacity = 3)
        listOf("😀", "😂", "😍", "🥰").forEach { recents.record(it) }
        assertEquals(listOf("🥰", "😍", "😂"), recents.all())
    }

    /**
     * The security-relevant case: the recents file is plain text, so a corrupted or hand-edited
     * file must not be able to inject strings the grid would render and then commit.
     */
    @Test
    fun `unknown strings are refused by record and restore`() {
        val recents = EmojiRecents()
        recents.record("not-an-emoji")
        recents.record("<script>")
        assertTrue(recents.all().isEmpty())

        recents.restore(listOf("😀", "garbage", "🐶", ""))
        assertEquals(listOf("😀", "🐶"), recents.all())
    }

    @Test
    fun `restore drops duplicates and respects capacity`() {
        val recents = EmojiRecents(capacity = 2)
        recents.restore(listOf("😀", "😀", "😂", "😍"))
        assertEquals(listOf("😀", "😂"), recents.all())
    }

    @Test
    fun `every catalogue entry is accepted by recents`() {
        val recents = EmojiRecents(capacity = EmojiCatalogue.ALL.size)
        EmojiCatalogue.ALL.forEach { recents.record(it) }
        assertEquals(EmojiCatalogue.ALL.size, recents.all().size)
    }

    @Test
    fun `flattened set matches the categories`() {
        val fromCategories = EmojiCatalogue.CATEGORIES.flatMap { it.emoji }.toSet()
        assertEquals(fromCategories, EmojiCatalogue.ALL)
        assertFalse(EmojiCatalogue.ALL.isEmpty())
    }
}
