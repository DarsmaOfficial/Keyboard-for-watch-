package dev.darsma.wearkey

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import dev.darsma.wearkey.imecore.ClipboardStore
import dev.darsma.wearkey.imecore.EditorState
import dev.darsma.wearkey.uiwear.ClipboardPanelView
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
    private val clipboardStore = ClipboardStore()

    override fun onCreateInputView(): View {
        val view = KeyboardSurfaceView(this)
        view.bind(editorState)
        view.keyGrid.onKeyListener = KeyGridView.OnKeyListener { action -> handleKey(action) }
        view.clipboardPanel.bind(clipboardStore)
        view.clipboardPanel.listener = object : ClipboardPanelView.Listener {
            override fun onPaste(text: String) {
                pasteText(text)
                view.hideClipboard()
            }

            override fun onPin(text: String, pinned: Boolean) {
                clipboardStore.pin(text, pinned)
                view.clipboardPanel.refresh()
            }

            override fun onDelete(text: String) {
                clipboardStore.delete(text)
                view.clipboardPanel.refresh()
            }

            override fun onClearAll() {
                clipboardStore.clearAll()
                view.clipboardPanel.refresh()
            }

            override fun onClose() {
                view.hideClipboard()
            }
        }
        surfaceView = view
        return view
    }

    /**
     * Pulls whatever is currently on the system clipboard into our local history.
     *
     * Android 10+ only permits clipboard reads while the IME actually holds focus (spec §6), so
     * this is called from onStartInputView — never from a background poll, and never via an
     * AccessibilityService workaround.
     */
    private fun captureSystemClipboard(info: EditorInfo?) {
        // Never learn from password / OTP / no-personalised-learning fields (spec §11.5).
        val noLearning = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        if (noLearning) return

        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount <= 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        clipboardStore.add(text)
    }

    private fun pasteText(text: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        try {
            editorState.commitText(text)
            ic.commitText(text, 1)
        } finally {
            ic.endBatchEdit()
        }
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

        // Clipboard reads are only legal while the IME holds focus (spec §6) — do it here.
        captureSystemClipboard(info)
        // Never leave the clipboard panel open across fields.
        surfaceView?.hideClipboard()

        surfaceView?.startFrameTiming()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        surfaceView?.stopFrameTiming()
        super.onFinishInputView(finishingInput)
    }

    /**
     * Spec §5.5: the real Android mechanism for language switching. Fires when the OS switches
     * subtypes on our behalf (system language picker, switchToNextInputMethod, or restoring the
     * user's last-used subtype for this field) — swap the key grid layout to match.
     */
    override fun onCurrentInputMethodSubtypeChanged(subtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(subtype)
        surfaceView?.keyGrid?.layout = when (subtype?.languageTag) {
            "ru-RU" -> KeyGridView.Layout.RU_RU
            else -> KeyGridView.Layout.EN_US
        }
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
                KeyGridView.KeyAction.SwitchLanguage -> switchLanguage()
                KeyGridView.KeyAction.Clipboard -> surfaceView?.toggleClipboard()
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    /**
     * Spec §5.5: prefer the real Android mechanism so the OS language picker and per-field
     * subtype memory stay authoritative. `onlyCurrentIme = true` is required — with `false` the
     * framework cycles across ALL enabled IMEs and hands control to Gboard entirely (confirmed
     * on-device 2026-08-04).
     *
     * Fallback: if the framework declines to switch (returns false — e.g. it only registered a
     * single subtype, or the user disabled one in system settings), swap the grid layout
     * directly so the key is never a dead control. The visible layout is what the user is
     * actually asking for; deferring to the system is the mechanism, not the goal.
     */
    private fun switchLanguage() {
        if (switchToNextInputMethod(true)) return
        val grid = surfaceView?.keyGrid ?: return
        grid.layout = when (grid.layout) {
            KeyGridView.Layout.EN_US -> KeyGridView.Layout.RU_RU
            KeyGridView.Layout.RU_RU -> KeyGridView.Layout.EN_US
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
