package dev.darsma.wearkey.imecore

/**
 * Most-recently-used emoji, shown as the first category (spec §11 v0.3).
 *
 * ## Why recents matter more here than on a phone
 *
 * Emoji use is heavily repetitive — a handful account for most of what any given person sends. On a
 * phone, browsing a full grid to find one is mildly annoying; on a 466 px round watch it means
 * paging through hundreds of tiny glyphs. Recents turn the common case into zero scrolling, which
 * is the difference between the layer being usable and being decorative.
 *
 * ## Privacy
 *
 * This is usage data about the user's messages, so it follows the same rule as the clipboard
 * (§11.5): it must never be recorded while typing into a password or no-personalised-learning
 * field. The caller enforces that by simply not calling [record] there — the store deliberately has
 * no opinion about field types, because a component that tries to infer sensitivity from data it
 * was handed will eventually infer wrong.
 */
class EmojiRecents(private val capacity: Int = DEFAULT_CAPACITY) {

    private val entries = ArrayList<String>(capacity)

    /** Recents, most recent first. */
    fun all(): List<String> = entries.toList()

    /**
     * Records one use, moving it to the front.
     *
     * Unknown strings are ignored. Recents are persisted as plain text, so without this check a
     * corrupted or hand-edited file could inject arbitrary strings that the grid would then render
     * as if they were emoji — and commit into the user's message when tapped.
     */
    fun record(emoji: String) {
        if (emoji !in EmojiCatalogue.ALL) return
        entries.remove(emoji)
        entries.add(0, emoji)
        while (entries.size > capacity) entries.removeAt(entries.size - 1)
    }

    /** Replaces the contents, e.g. when loading from disk. Invalid entries are dropped. */
    fun restore(saved: List<String>) {
        entries.clear()
        for (e in saved) {
            if (e in EmojiCatalogue.ALL && e !in entries) {
                entries.add(e)
                if (entries.size == capacity) break
            }
        }
    }

    fun clear() = entries.clear()

    companion object {
        /**
         * One full row on the emoji grid. Fewer would waste the row; more would push the first
         * real category off-screen, which defeats the purpose of having recents at the top.
         */
        const val DEFAULT_CAPACITY = 16
    }
}
