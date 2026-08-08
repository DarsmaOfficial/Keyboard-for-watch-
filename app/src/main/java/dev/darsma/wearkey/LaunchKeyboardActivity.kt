package dev.darsma.wearkey

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import dev.darsma.wearkey.imecore.ClipboardStore
import dev.darsma.wearkey.imecore.EditorState
import dev.darsma.wearkey.uiwear.ClipboardPanelView
import dev.darsma.wearkey.uiwear.KeyGridView
import dev.darsma.wearkey.uiwear.KeyboardSurfaceView

/**
 * Entry point 2 (spec §4.5). On Wear OS most real input (notification replies, WhatsApp,
 * browser URL bars) is routed through RemoteInputActivity, which launches an activity declaring
 * com.google.android.wearable.action.LAUNCH_KEYBOARD — NOT the InputMethodService. Verified on
 * real hardware 2026-08-04: the system's "What to use?" chooser lists WearKey alongside Gboard.
 *
 * Shares KeyboardSurfaceView with WearKeyImeService — same composition strip, same key grid,
 * same EditorState wiring pattern (spec requirement: "no forked UI"). Enter commits the
 * composed text back to the caller via RemoteInput.addResultsToIntent(), matching how
 * RemoteInputActivity expects a keyboard-chooser result to be returned.
 */
class LaunchKeyboardActivity : Activity() {

    private var surfaceView: KeyboardSurfaceView? = null
    private val editorState = EditorState()
    private val clipboardStore = ClipboardStore()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val view = KeyboardSurfaceView(this)
        view.bind(editorState)
        view.keyGrid.onKeyListener = KeyGridView.OnKeyListener { action -> handleKey(action) }
        view.bindClipboard(clipboardStore, object : ClipboardPanelView.Listener {
            override fun onPaste(text: String) {
                editorState.commitText(text)
                view.hideClipboard()
            }

            override fun onPin(text: String, pinned: Boolean) {
                clipboardStore.pin(text, pinned)
                view.refreshClipboardPanel()
            }

            override fun onDelete(text: String) {
                clipboardStore.delete(text)
                view.refreshClipboardPanel()
            }

            override fun onClearAll() {
                clipboardStore.clearAll()
                view.refreshClipboardPanel()
            }

            override fun onClose() {
                view.hideClipboard()
            }
        })
        surfaceView = view
        setContentView(view)

        // Same rule as the IME path: clipboard is read only while we're in the foreground.
        getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.let { clip ->
            if (clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(this)?.toString()?.let { clipboardStore.add(it) }
            }
        }

        // Pre-fill from any existing RemoteInput results the caller supplied, if present —
        // mirrors how WearKeyImeService primes EditorState from the field's existing text.
        RemoteInput.getResultsFromIntent(intent)?.let { results ->
            remoteInputKey()?.let { key ->
                results.getCharSequence(key)?.let { existing ->
                    editorState.commitText(existing.toString())
                }
            }
        }
    }

    override fun onPause() {
        // Frame timing is started explicitly from the frame-stats screen rather than on every
        // resume: auto-starting reset the sample buffer each time this Activity appeared, so a
        // measurement could never span a real session.
        surfaceView?.stopFrameTiming()
        super.onPause()
    }

    private fun handleKey(action: KeyGridView.KeyAction) {
        when (action) {
            is KeyGridView.KeyAction.Character -> editorState.commitText(action.char.toString())
            KeyGridView.KeyAction.Space -> editorState.commitText(" ")
            KeyGridView.KeyAction.Backspace -> editorState.backspace()
            KeyGridView.KeyAction.Enter -> commitAndFinish()
            KeyGridView.KeyAction.Clipboard -> surfaceView?.toggleClipboard()
            KeyGridView.KeyAction.Emoji -> surfaceView?.toggleEmoji()
            // Owned by KeyGridView itself; listed for exhaustiveness.
            KeyGridView.KeyAction.Shift,
            KeyGridView.KeyAction.SymbolLayer -> Unit
            KeyGridView.KeyAction.SwitchLanguage -> {
                // Activity (not an InputMethodService) has no switchToNextInputMethod() /
                // InputMethodSubtype API — this is a plain Activity per spec §4.5, so the
                // language key here just toggles the shared KeyGridView's local layout. It
                // still exercises the exact same layout-swap code path as the IME (spec
                // requirement: "no forked UI"), just driven differently since a RemoteInput
                // chooser activity has no subtype concept of its own to defer to.
                val grid = surfaceView?.keyGrid ?: return
                grid.layout = when (grid.layout) {
                    KeyGridView.Layout.EN_US -> KeyGridView.Layout.RU_RU
                    KeyGridView.Layout.RU_RU -> KeyGridView.Layout.EN_US
                }
            }
        }
    }

    private fun commitAndFinish() {
        val key = remoteInputKey()
        if (key != null) {
            val resultIntent = Intent()
            val bundle = Bundle().apply { putCharSequence(key, editorState.text) }
            RemoteInput.addResultsToIntent(remoteInputsFromIntent(), resultIntent, bundle)
            setResult(RESULT_OK, resultIntent)
        } else {
            // No RemoteInput extras present (e.g. launched directly for testing, spec §4.5
            // verification) — nothing meaningful to return, just finish cleanly.
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    private fun remoteInputsFromIntent(): Array<RemoteInput> {
        // android.app.RemoteInput has no public EXTRA_REMOTE_INPUTS constant (that constant
        // lives only on androidx.core.app.RemoteInput, which we don't depend on to avoid
        // pulling androidx.core into this activity). The wire format is stable AOSP-wide.
        @Suppress("DEPRECATION")
        val parcelables = intent.getParcelableArrayExtra(REMOTE_INPUTS_EXTRA)
        return parcelables?.filterIsInstance<RemoteInput>()?.toTypedArray() ?: emptyArray()
    }

    private fun remoteInputKey(): String? = remoteInputsFromIntent().firstOrNull()?.resultKey

    companion object {
        private const val REMOTE_INPUTS_EXTRA = "android.remoteinput.extraRemoteInputs"
    }
}
