package dev.darsma.wearkey

import android.content.Context
import java.io.File

/**
 * Persists the emoji recents list across keyboard sessions (spec §11 v0.3).
 *
 * ## Why this is not encrypted, unlike the clipboard
 *
 * The clipboard store is encrypted with an Android Keystore key because it holds arbitrary copied
 * text — passwords, tokens, whatever the user happened to copy. Recents hold at most sixteen emoji
 * drawn from a fixed public list, so encryption would add a Keystore dependency and a failure mode
 * (key invalidated after a biometric change) to protect information whose entire content space is
 * public knowledge. Storing it in `noBackupFilesDir` keeps it off cloud backup, which is the part
 * that actually matters.
 *
 * The privacy rule that *does* apply is upstream: nothing is recorded at all while typing into a
 * password or no-personalised-learning field. See `WearKeyImeService.commitEmoji`.
 */
class EmojiRecentsStore(private val context: Context) {

    private val file: File
        get() = File(context.noBackupFilesDir, FILE_NAME)

    /** Returns the saved list, or empty when absent or unreadable. */
    fun load(): List<String> = runCatching {
        if (!file.isFile) return emptyList()
        file.readText(Charsets.UTF_8)
            .split('\n')
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    /**
     * Writes the list, replacing any previous contents.
     *
     * Failures are swallowed deliberately: losing the recents list is a cosmetic regression, and
     * throwing here would take down the IME mid-keystroke over a cache file.
     */
    fun save(recents: List<String>) {
        runCatching {
            file.writeText(recents.joinToString("\n"), Charsets.UTF_8)
        }
    }

    /** Removes the file, for the Settings "clear data" action. */
    fun clear() {
        runCatching { file.delete() }
    }

    companion object {
        private const val FILE_NAME = "emoji-recents.txt"
    }
}
