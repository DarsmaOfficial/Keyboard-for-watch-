package dev.darsma.wearkey

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import dev.darsma.wearkey.imecore.EditorState
import dev.darsma.wearkey.uiwear.KeyGridView
import dev.darsma.wearkey.uiwear.KeyboardSurfaceView

/**
 * Entry point 1 (spec §4.5): the classic InputMethodService path for standard EditText fields.
 * See LaunchKeyboardActivity for entry point 2 — both share KeyboardSurfaceView so the UI is
 * never forked (spec §4.5 explicit requirement).
 *
 * Wiring rule (spec §5 critical implementation rule): EditorState is the single source of
 * truth. Key input mutates EditorState first (synchronous, no IPC), then the same mutation is
 * forwarded to the real InputConnection wrapped in beginBatchEdit()/endBatchEdit() to prevent
 * flicker. Reconciliation the other way (app edits the field itself) happens only in
 * onUpdateSelection (spec §11.5: "do not fight the app; defer to onUpdateSelection").
 */
class WearKeyImeService : InputMethodService() {

    private var surfaceView: KeyboardSurfaceView? = null
    private val editorState = EditorState()

    override fun onCreateInputView(): View {
        val view = KeyboardSurfaceView(this)
        view.bind(editorState)
        view.keyGrid.onKeyListener = KeyGridView.OnKeyListener { action -> handleKey(action) }
        surfaceView = view
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        // Spec §11.5: never carry text between fields — full reset on every new editor, even
        // if the previous field was never explicitly finished (rapid field-switching case).
        val masked = isMaskedInputType(info?.inputType ?: 0)
        editorState.reset(masked = masked)

        // Prime EditorState with whatever the field already contains, if not masked — masked
        // fields must never see plaintext, not even transiently (spec §5).
        if (!masked) {
            val existing = currentInputConnection
                ?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            if (existing?.text != null) {
                editorState.commitText(existing.text.toString())
                editorState.syncSelection(existing.selectionStart, existing.selectionEnd)
            }
        }

        surfaceView?.startFrameTiming()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        surfaceView?.stopFrameTiming()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        // Spec §11.5: state must never leak into the next field.
        editorState.reset()
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // The app (or the system) moved the selection/caret itself — reconcile without fighting it.
        if (!editorState.masked) {
            editorState.syncSelection(newSelStart, newSelEnd)
        }
    }

    private fun handleKey(action: KeyGridView.KeyAction) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        try {
            when (action) {
                is KeyGridView.KeyAction.Character -> {
                    editorState.commitText(action.char.toString())
                    ic.commitText(action.char.toString(), 1)
                }
                KeyGridView.KeyAction.Space -> {
                    editorState.commitText(" ")
                    ic.commitText(" ", 1)
                }
                KeyGridView.KeyAction.Backspace -> {
                    editorState.backspace()
                    ic.deleteSurroundingText(1, 0)
                }
                KeyGridView.KeyAction.Enter -> {
                    ic.sendKeyEvent(
                        android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)
                    )
                    ic.sendKeyEvent(
                        android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER)
                    )
                }
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    /**
     * Spec §11.5 input-type awareness: masked content must never enter the plaintext preview
     * buffer, not even transiently. Covers password, visible-password and numeric-PIN variants.
     */
    private fun isMaskedInputType(inputType: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (cls) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
