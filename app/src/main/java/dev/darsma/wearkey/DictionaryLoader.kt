package dev.darsma.wearkey

import android.content.Context
import dev.darsma.wearkey.dict.SpellEngine
import dev.darsma.wearkey.uiwear.KeyGridView
import java.io.File
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

/**
 * Memory-maps the bundled dictionary index into [SpellEngine], off the main thread.
 *
 * ## Why mapping rather than reading
 *
 * The index is mapped with [FileChannel.map], so its pages are clean, file-backed and evictable
 * under memory pressure. They are accounted as mapped pages rather than as Java heap, which is
 * what keeps one resident dictionary inside the specification's 8 MB heap gate (§14). Reading the
 * same data into Java objects is what the previous SymSpellKt implementation did, and it measured
 * 15.5 MB on the watch — see `WordIndex` for the full measurement history.
 *
 * ## Why the asset is copied out first
 *
 * `AssetManager` cannot hand back a mappable file descriptor for a *compressed* asset, and the
 * Android build compresses assets by default. Two options exist: mark the extension
 * `noCompress` so `openFd` can be used directly, or copy the asset into app storage once and map
 * that. This uses `noCompress` (declared in build.gradle.kts) **and** falls back to a one-time
 * copy, because a mapped file must stay valid for the life of the buffer and relying on the
 * packaging flag alone would fail silently if it were ever dropped.
 *
 * ## Failure behaviour
 *
 * Every path is wrapped: a missing, truncated or corrupt index leaves the engine unloaded and the
 * keyboard degrades to layout-only typing. A keyboard that crashes leaves the user unable to type
 * at all, which is far worse than one without autocorrect (spec §11 failure modes).
 *
 * Only one language is resident at a time (spec §4.2, heap budget). Switching layouts swaps the
 * index rather than keeping both mapped.
 */
class DictionaryLoader(
    private val context: Context,
    private val engine: SpellEngine
) {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wearkey-dict").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    @Volatile
    private var loadedLayout: KeyGridView.Layout? = null

    /** Maps the index for [layout] unless it is already the resident one. */
    fun loadFor(layout: KeyGridView.Layout) {
        if (loadedLayout == layout) return
        loadedLayout = layout

        val assetName = when (layout) {
            KeyGridView.Layout.EN_US -> "en.bin"
            KeyGridView.Layout.RU_RU -> "ru.bin"
        }

        executor.execute {
            runCatching { mapIndex(assetName) }
                .onSuccess { engine.load(it) }
                .onFailure {
                    engine.unload()
                    // Allow a later retry rather than pinning the failure permanently.
                    loadedLayout = null
                }
        }
    }

    /**
     * Returns a read-only mapping of the named index, extracting it into app storage on first use.
     *
     * The extracted copy is validated by size: a partially written file from an interrupted
     * earlier run would otherwise be mapped and rejected forever.
     */
    private fun mapIndex(assetName: String): java.nio.ByteBuffer {
        val target = File(context.noBackupFilesDir, assetName)
        val expected = context.assets.openFd("dictionaries/$assetName").use { it.length }

        if (!target.exists() || target.length() != expected) {
            val temporary = File(context.noBackupFilesDir, "$assetName.part")
            context.assets.open("dictionaries/$assetName").use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            // Rename only once the copy is complete, so an interrupted extraction can never be
            // mistaken for a valid index.
            if (!temporary.renameTo(target)) {
                temporary.delete()
                throw IllegalStateException("could not place $assetName")
            }
        }

        return FileChannel.open(target.toPath()).use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
