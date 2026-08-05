package dev.darsma.wearkey

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import dev.darsma.wearkey.dict.SpellEngine
import dev.darsma.wearkey.imecore.ClipboardStore
import dev.darsma.wearkey.imecore.EditorState
import dev.darsma.wearkey.uiwear.ClipboardPanelView
import dev.darsma.wearkey.uiwear.KeyGridView
import dev.darsma.wearkey.uiwear.KeyboardSurfaceView
import dev.darsma.wearkey.uiwear.SuggestionStripView

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
    private val clipboardPersistence by lazy { EncryptedClipboardPersistence(this) }

    private val spellEngine = SpellEngine()
    private val dictionaryLoader by lazy { DictionaryLoader(this, spellEngine) }

    /**
     * True while the current field forbids learning or is masked. Autocorrect stays completely
     * silent in those fields — no suggestions, no dictionary lookups (spec §11.5 security).
     */
    private var suggestionsDisabled = false

    override fun onCreate() {
        super.onCreate()
        // Restore encrypted history once, up front (spec §6: history survives process death).
        clipboardPersistence.load(clipboardStore)
        // Persist on every change so a kill by memory pressure never loses entries.
        clipboardStore.addListener(ClipboardStore.Listener { clipboardPersistence.save(clipboardStore) })
        // Word list loads on a background thread; until it lands, autocorrect is simply absent.
        dictionaryLoader.loadFor(KeyGridView.Layout.EN_US)
    }

    override fun onDestroy() {
        dictionaryLoader.shutdown()
        super.onDestroy()
    }

    /**
     * The word currently being typed — everything back to the last space or start of text.
     * Suggestions are computed from this rather than from the whole field.
     */
    private fun currentWord(): String {
        val text = editorState.text
        val caret = editorState.selectionStart.coerceIn(0, text.length)
        val start = text.lastIndexOfAny(WORD_SEPARATORS, caret - 1) + 1
        return text.substring(start, caret)
    }

    /** Refreshes the candidate row for whatever word is being typed right now. */
    private fun refreshSuggestions() {
        val strip = surfaceView?.suggestionStrip ?: return
        if (suggestionsDisabled || !spellEngine.isReady) {
            strip.clear()
            return
        }
        val word = currentWord()
        // Nothing worth suggesting for a single letter — everything is one edit away from it.
        if (word.length < MIN_WORD_FOR_SUGGESTIONS) {
            strip.clear()
            return
        }
        // A correctly spelled dictionary word needs no correction row. This also prevents a race
        // after accepting a chip: replaceCurrentWord() clears the strip, but EditorState's
        // synchronous change notification immediately refreshes it for the replacement word and
        // used to put the same chips straight back on screen (observed on-device after accepting
        // "hello").
        if (spellEngine.isKnown(word)) {
            strip.clear()
            return
        }
        strip.setSuggestions(spellEngine.suggest(word))
    }

    /** Replaces the word being typed with [word]. Used by both tap-to-accept and space-commit. */
    private fun replaceCurrentWord(word: String) {
        val ic = currentInputConnection ?: return
        val typed = currentWord()
        if (typed.isEmpty()) return

        ic.beginBatchEdit()
        try {
            repeat(typed.length) { editorState.backspace() }
            ic.deleteSurroundingText(typed.length, 0)
            editorState.commitText(word)
            ic.commitText(word, 1)
        } finally {
            ic.endBatchEdit()
        }
        surfaceView?.suggestionStrip?.clear()
    }

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
        view.suggestionStrip.onSuggestionListener =
            SuggestionStripView.OnSuggestionListener { word -> replaceCurrentWord(word) }
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

        // Apply the saved haptic intensity every time the keyboard is shown, so a change in
        // settings takes effect on the next field without needing a restart.
        surfaceView?.keyGrid?.haptics?.intensity = SettingsStore(this).hapticIntensity

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

        // Spec §11.5: "a keyboard that shows QWERTY for a phone-number field is broken."
        applyInputTypeAwareness(info)

        // Spec §11.5: never learn from, or suggest into, password / OTP / no-personalised-
        // learning fields. Autocorrect goes completely silent there.
        val noLearning = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        suggestionsDisabled = masked || noLearning
        surfaceView?.suggestionStrip?.clear()

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
        val layout = when (subtype?.languageTag) {
            "ru-RU" -> KeyGridView.Layout.RU_RU
            else -> KeyGridView.Layout.EN_US
        }
        surfaceView?.keyGrid?.layout = layout
        // Swap the resident dictionary to match — only one language stays loaded (spec §4.2).
        dictionaryLoader.loadFor(layout)
        surfaceView?.suggestionStrip?.clear()
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

    /**
     * While the clipboard panel is open, the hardware/system back gesture closes the panel
     * instead of dismissing the whole keyboard — a second back then dismisses as usual. Without
     * this, opening the panel and pressing back would hide the keyboard entirely, which is not
     * what the user means.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && surfaceView?.isClipboardOpen == true) {
            surfaceView?.hideClipboard()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleKey(action: KeyGridView.KeyAction) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        try {
            when (action) {
                is KeyGridView.KeyAction.Character -> {
                    editorState.commitText(action.char.toString())
                    ic.commitText(action.char.toString(), 1)
                    refreshSuggestions()
                }
                KeyGridView.KeyAction.Space -> {
                    // Space ends the word: clear the candidate row rather than leaving stale
                    // suggestions for a word that is already finished. The correction is NOT
                    // applied automatically — on a watch, silently rewriting what someone typed
                    // is expensive to undo, so acceptance stays an explicit tap (spec §7.2).
                    editorState.commitText(" ")
                    ic.commitText(" ", 1)
                    surfaceView?.suggestionStrip?.clear()
                }
                KeyGridView.KeyAction.Backspace -> {
                    editorState.backspace()
                    ic.deleteSurroundingText(1, 0)
                    refreshSuggestions()
                }
                KeyGridView.KeyAction.Enter -> {
                    // If the field asked for a specific action (Search / Send / Go / Next /
                    // Done), perform that instead of inserting a newline — otherwise a search
                    // box just gains a line break and never searches (spec §11.5).
                    val action = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                    if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                        ic.performEditorAction(action)
                    } else {
                        ic.sendKeyEvent(
                            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)
                        )
                        ic.sendKeyEvent(
                            android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER)
                        )
                    }
                }
                // Shift and layer switching are handled inside KeyGridView, which owns that
                // state — nothing to do here, but the branches must exist for exhaustiveness.
                KeyGridView.KeyAction.Shift,
                KeyGridView.KeyAction.SymbolLayer -> Unit
                KeyGridView.KeyAction.SwitchLanguage -> switchLanguage()
                KeyGridView.KeyAction.Clipboard -> {
                    // Re-read the system clipboard on every open, not just when the field is
                    // first focused: the user may have copied something without the keyboard
                    // being dismissed in between. Still legal — we hold focus right now.
                    captureSystemClipboard(currentInputEditorInfo)
                    surfaceView?.toggleClipboard()
                }
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
     * Spec §11.5 input-type awareness. Picks a sensible starting layer for the field, and sets
     * the action key's label from `imeOptions` (Go / Search / Send / Next / Done), so the enter
     * key says what it will actually do.
     */
    private fun applyInputTypeAwareness(info: EditorInfo?) {
        val grid = surfaceView?.keyGrid ?: return
        val inputType = info?.inputType ?: 0
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        // Number, phone and datetime fields open straight on the symbol layer, where the digits
        // live — opening on QWERTY would mean an extra tap on every single such field.
        val wantsDigits = cls == InputType.TYPE_CLASS_NUMBER ||
            cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME
        grid.symbolLayerVisible = wantsDigits
        grid.shiftState = KeyGridView.ShiftState.OFF

        // Email and URI fields keep letters, but '@' '.' '/' matter enough there to be worth
        // surfacing later — noted rather than silently forgotten.
        val isEmailOrUri = cls == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_URI)
        grid.emailOrUriHints = isEmailOrUri

        grid.actionLabel = when ((info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> getString(R.string.action_go)
            EditorInfo.IME_ACTION_SEARCH -> getString(R.string.action_search)
            EditorInfo.IME_ACTION_SEND -> getString(R.string.action_send)
            EditorInfo.IME_ACTION_NEXT -> getString(R.string.action_next)
            EditorInfo.IME_ACTION_DONE -> getString(R.string.action_done)
            else -> null
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

    companion object {
        /** Characters that end a word for suggestion purposes. */
        private val WORD_SEPARATORS = charArrayOf(' ', '\n', '\t', '.', ',', '!', '?', ';', ':')

        /**
         * Below this length every dictionary word is within one edit, so suggestions would be
         * noise rather than help.
         */
        private const val MIN_WORD_FOR_SUGGESTIONS = 3
    }
}
