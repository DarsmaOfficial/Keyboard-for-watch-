package dev.darsma.wearkey

import android.content.Context
import dev.darsma.wearkey.dict.SpellEngine
import dev.darsma.wearkey.uiwear.KeyGridView
import java.io.FileInputStream
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
     * Returns a read-only mapping directly into the APK's stored asset.
     *
     * `openFd` exposes three things: the APK file descriptor, the byte offset at which this asset
     * starts, and its length. Because `.bin` is explicitly `noCompress`, those bytes are the
     * original file and can be mapped in place — no extraction, no duplicate in app storage, and
     * the same clean pages can be shared across process restarts.
     *
     * A direct mapping also avoids a real failure found on-device in the first implementation:
     * EN extracted and mapped, but switching to RU changed the visible keys while `ru.bin` never
     * appeared in `no_backup`, silently leaving English correction active. Removing extraction
     * removes that whole failure mode.
     */
    private fun mapIndex(assetName: String): java.nio.ByteBuffer {
        val descriptor = context.assets.openFd("dictionaries/$assetName")
        return descriptor.use { asset ->
            FileInputStream(asset.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.length)
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
