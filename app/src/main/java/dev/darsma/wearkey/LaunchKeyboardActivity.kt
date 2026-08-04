package dev.darsma.wearkey

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import dev.darsma.wearkey.imecore.EditorState
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val view = KeyboardSurfaceView(this)
        view.bind(editorState)
        view.keyGrid.onKeyListener = KeyGridView.OnKeyListener { action -> handleKey(action) }
        surfaceView = view
        setContentView(view)

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

    override fun onResume() {
        super.onResume()
        surfaceView?.startFrameTiming()
    }

    override fun onPause() {
        surfaceView?.stopFrameTiming()
        super.onPause()
    }

    private fun handleKey(action: KeyGridView.KeyAction) {
        when (action) {
            is KeyGridView.KeyAction.Character -> editorState.commitText(action.char.toString())
            KeyGridView.KeyAction.Space -> editorState.commitText(" ")
            KeyGridView.KeyAction.Backspace -> editorState.backspace()
            KeyGridView.KeyAction.Enter -> commitAndFinish()
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
