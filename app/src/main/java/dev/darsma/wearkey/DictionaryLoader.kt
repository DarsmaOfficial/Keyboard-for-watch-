package dev.darsma.wearkey

import android.content.Context
import dev.darsma.wearkey.dict.SpellEngine
import dev.darsma.wearkey.uiwear.KeyGridView
import java.util.concurrent.Executors

/**
 * Loads the bundled word lists into [SpellEngine], off the main thread.
 *
 * Loading ~30 000 words takes long enough that doing it on the UI thread would stall the first
 * keypress, so it happens on a single background thread. Until it finishes, [SpellEngine] simply
 * reports not-ready and the keyboard behaves as if autocorrect did not exist — which is the
 * required degradation path anyway (spec §11 failure modes: a missing or corrupt dictionary must
 * never crash the keyboard, only disable correction).
 *
 * Only one language is resident at a time (spec §4.2, heap budget). Switching layouts swaps the
 * dictionary rather than keeping both loaded.
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

    /** Loads the word list for [layout] unless it is already the resident one. */
    fun loadFor(layout: KeyGridView.Layout) {
        if (loadedLayout == layout) return
        loadedLayout = layout

        val asset = when (layout) {
            KeyGridView.Layout.EN_US -> "dictionaries/en.txt"
            KeyGridView.Layout.RU_RU -> "dictionaries/ru.txt"
        }

        executor.execute {
            runCatching {
                context.assets.open(asset).bufferedReader().use { reader ->
                    engine.load(reader.lineSequence())
                }
            }.onFailure {
                // Asset missing or unreadable — degrade to layout-only input rather than
                // crashing, and allow a later retry.
                engine.unload()
                loadedLayout = null
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
