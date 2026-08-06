package dev.darsma.wearkey

import android.content.Context
import dev.darsma.wearkey.dict.SpellEngine
import dev.darsma.wearkey.uiwear.KeyGridView
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Memory-maps the bundled dictionary index into [SpellEngine].
 *
 * ## Why this is synchronous
 *
 * The original SymSpellKt implementation parsed 10–30k text entries and built tens of thousands
 * of Java objects, so loading correctly ran on a background executor. The mapped index does none
 * of that: `openFd` + `FileChannel.map` is one fast system call and [SpellEngine.load] only checks
 * a 20-byte header. Keeping the executor after the work disappeared introduced a real race:
 * switching EN→RU changed the visible key labels immediately while queued dictionary requests
 * could complete out of order, silently leaving English correction behind the Cyrillic layout.
 * Found on-device when `привт` produced no `привет` suggestion even though direct inspection of
 * ru.bin found the correct bucket.
 *
 * Mapping synchronously makes the invariant structural: when the visible layout changes, the
 * matching index is resident before control returns. No queue, no stale completion, no need for
 * callbacks. The operation is far below one frame and touches no index pages until lookup.
 *
 * ## Why mapping rather than reading
 *
 * The indexes are stored uncompressed in the APK (`noCompress.add("bin")`, CI-enforced), so
 * [android.content.res.AssetManager.openFd] exposes the APK file descriptor plus the asset's byte
 * offset and length. We map that range directly: pages are clean, file-backed, shareable across
 * process restarts and evictable under pressure. Measured Dalvik heap stays ~2.5 MB with either
 * language, against the specification's 8 MB gate; the old SymSpellKt object graph used 15.5 MB
 * for the same 10k vocabulary.
 *
 * ## Failure behaviour
 *
 * A missing, compressed, truncated or corrupt index unloads correction and returns false. The
 * keyboard continues as a layout-only IME — it must never crash and leave the user unable to type
 * (spec §11 failure modes).
 */
class DictionaryLoader(
    private val context: Context,
    private val engine: SpellEngine
) {

    /** The language whose index successfully loaded — never a merely requested language. */
    @Volatile
    private var loadedLayout: KeyGridView.Layout? = null

    /**
     * Maps the index for [layout]. Returns true only when the engine accepted it.
     *
     * Synchronized for defensive correctness if a future caller invokes this off the main thread;
     * current calls all come from InputMethodService callbacks on the main thread.
     */
    @Synchronized
    fun loadFor(layout: KeyGridView.Layout): Boolean {
        if (loadedLayout == layout && engine.isReady) return true

        val assetName = when (layout) {
            KeyGridView.Layout.EN_US -> "en.bin"
            KeyGridView.Layout.RU_RU -> "ru.bin"
        }

        return runCatching {
            engine.load(mapIndex(assetName))
            check(engine.isReady) { "invalid dictionary index: $assetName" }
        }.fold(
            onSuccess = {
                loadedLayout = layout
                true
            },
            onFailure = {
                engine.unload()
                loadedLayout = null
                false
            }
        )
    }

    /** Directly maps one stored APK asset; no extracted copy and no heap-sized byte array. */
    private fun mapIndex(assetName: String): ByteBuffer {
        val descriptor = context.assets.openFd("dictionaries/$assetName")
        return descriptor.use { asset ->
            FileInputStream(asset.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.length)
            }
        }
    }

    /** Kept so the service lifecycle stays stable; there is no background worker to stop now. */
    fun shutdown() = Unit
}
