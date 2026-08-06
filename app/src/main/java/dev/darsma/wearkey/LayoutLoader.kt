package dev.darsma.wearkey

import android.content.Context
import android.util.Log
import dev.darsma.wearkey.layout.KeyboardLayout
import dev.darsma.wearkey.layout.LayoutParser

/**
 * Loads declarative layout files from `assets/layouts/` (spec §4.1, §9).
 *
 * ## Failure behaviour is the point
 *
 * Spec §11.5 requires defined behaviour when an asset is missing or corrupt, and singles out this
 * product category: *"A keyboard that crashes leaves the user with no way to type at all — that is
 * the worst possible failure."* A user in that state cannot even file a bug report, because filing
 * one needs a keyboard.
 *
 * So every method here returns null instead of throwing, and the caller keeps the compiled-in rows
 * that `KeyGridView` already holds. A broken layout file costs the user the *customisation*, never
 * the keyboard.
 *
 * Layouts are small (a few hundred bytes) and read once per language switch, so they are parsed on
 * demand rather than cached — there is nothing here worth the complexity of an eviction policy.
 */
class LayoutLoader(private val context: Context) {

    /** Returns the parsed layout for [id], or null if it is absent or malformed. */
    fun load(id: String): KeyboardLayout? = runCatching {
        val json = context.assets.open("$LAYOUT_DIR/$id.json").use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
        LayoutParser.parse(json)
    }.onFailure { error ->
        // Log the failure but never the file contents: a layout is not sensitive, but keeping a
        // strict "never log input-adjacent data" habit is what stops the security rule eroding.
        Log.w(TAG, "layout '$id' unavailable (${error.javaClass.simpleName}); using built-in rows")
    }.getOrNull()

    /** Lists the layout ids shipped in assets. Empty when the directory is missing. */
    fun available(): List<String> = runCatching {
        context.assets.list(LAYOUT_DIR)
            ?.filter { it.endsWith(".json") }
            ?.map { it.removeSuffix(".json") }
            ?.sorted()
            ?: emptyList()
    }.getOrDefault(emptyList())

    private companion object {
        const val LAYOUT_DIR = "layouts"
        const val TAG = "WearKeyLayout"
    }
}
