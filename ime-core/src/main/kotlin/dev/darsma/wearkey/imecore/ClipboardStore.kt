package dev.darsma.wearkey.imecore

/**
 * Local-only clipboard history (spec §6). Pure Kotlin so it is unit-testable without a device;
 * the Android-specific encrypted persistence layer wraps this in the :app module.
 *
 * Deliberately NOT implemented here (they belong to the Android layer):
 *  - Android Keystore AES-GCM encryption at rest
 *  - credential-protected storage context
 *  - reading the system clipboard (only legal while the IME holds focus, Android 10+)
 *
 * Security posture (spec §6, §11.5): entries flagged sensitive auto-expire, and nothing here is
 * ever written to logs. Callers must never pass content from a masked/NO_PERSONALIZED_LEARNING
 * field into [add].
 */
class ClipboardStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis
) {

    data class Entry(
        val text: String,
        val createdAtMs: Long,
        val pinned: Boolean = false,
        /** Detected OTP / card number / password-like content — expires early (spec §6). */
        val sensitive: Boolean = false
    )

    fun interface Listener {
        fun onClipboardChanged(entries: List<Entry>)
    }

    private val entries = mutableListOf<Entry>()
    private val listeners = mutableListOf<Listener>()

    /** Auto-expiry window for sensitive entries, configurable 1-5 min per spec §6. */
    var sensitiveExpiryMs: Long = DEFAULT_SENSITIVE_EXPIRY_MS

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        val snapshot = visibleEntries()
        listeners.toList().forEach { it.onClipboardChanged(snapshot) }
    }

    /**
     * Adds a copied string. Duplicates move to the front rather than accumulating. Sensitive
     * content is detected automatically and marked for early expiry.
     */
    fun add(text: String) {
        if (text.isBlank()) return
        entries.removeAll { it.text == text && !it.pinned }
        entries.add(
            0,
            Entry(
                text = text,
                createdAtMs = clock(),
                sensitive = looksSensitive(text)
            )
        )
        trim()
        notifyChanged()
    }

    /** Entries that have not expired, pinned ones first, newest first within each group. */
    fun visibleEntries(): List<Entry> {
        purgeExpired()
        return entries.sortedWith(
            compareByDescending<Entry> { it.pinned }.thenByDescending { it.createdAtMs }
        )
    }

    fun pin(text: String, pinned: Boolean = true) {
        val index = entries.indexOfFirst { it.text == text }
        if (index < 0) return
        entries[index] = entries[index].copy(pinned = pinned)
        notifyChanged()
    }

    fun delete(text: String) {
        if (entries.removeAll { it.text == text }) notifyChanged()
    }

    /** "Clear all learned data" action required by spec §11.5. Pinned entries go too. */
    fun clearAll() {
        entries.clear()
        notifyChanged()
    }

    private fun trim() {
        // Pinned entries never count against the cap and are never evicted.
        val unpinned = entries.filter { !it.pinned }
        if (unpinned.size <= maxEntries) return
        val toDrop = unpinned.drop(maxEntries).toSet()
        entries.removeAll(toDrop)
    }

    private fun purgeExpired() {
        val now = clock()
        val removed = entries.removeAll { entry ->
            entry.sensitive && !entry.pinned && (now - entry.createdAtMs) > sensitiveExpiryMs
        }
        if (removed) {
            // Deliberately not calling notifyChanged() here — purge happens inside read paths
            // and re-entrant notification during a read would surprise callers.
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 25
        const val DEFAULT_SENSITIVE_EXPIRY_MS = 2 * 60 * 1000L // 2 min, inside spec's 1-5 range

        private val OTP_REGEX = Regex("""^\s*\d{4,8}\s*$""")
        private val CARD_REGEX = Regex("""\b(?:\d[ -]?){13,19}\b""")

        /**
         * Heuristic sensitive-content detection (spec §6). Conservative by design: a false
         * positive only means an entry expires sooner, which is the safe direction to err.
         */
        fun looksSensitive(text: String): Boolean {
            if (OTP_REGEX.matches(text)) return true
            if (CARD_REGEX.containsMatchIn(text) && text.count { it.isDigit() } >= 13) return true
            return false
        }
    }
}
