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
import dev.darsma.wearkey.uiwear.EmojiPanelView
import dev.darsma.wearkey.uiwear.HapticFeedback
import dev.darsma.wearkey.uiwear.KeyGridView
import dev.darsma.wearkey.uiwear.KeyboardTheme
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
    private val swipeController = SwipeController(spellEngine)
    private val spatialController = SpatialTypingController(spellEngine)
    private var spatialTypingEnabled = false
    private val emojiRecentsStore by lazy { EmojiRecentsStore(this) }
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
        // Mapping the index is synchronous and far below one frame — it maps pages rather than
        // reading them — so autocorrect and glide typing are available from the first field.
        dictionaryLoader.loadFor(KeyGridView.Layout.EN_US)
    }

    override fun onDestroy() {
        dictionaryLoader.shutdown()
        // Capture whatever was measured before dropping the reference, otherwise the numbers die
        // with the view that produced them and the stats screen can never show anything.
        onKeyGridHidden()
        // Drop the static view reference before the service goes away, so a torn-down keyboard
        // cannot be retained by the frame-stats screen.
        liveKeyGrid = null
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

    /**
     * Commits a glide-typed word and offers the runners-up (spec §7.3).
     *
     * ## Why a leading space is inserted here
     *
     * Glide typing produces whole words, so the user never presses space between them. Without this
     * the second swipe would append directly to the first and produce "helloworld". The space is
     * suppressed at the very start of a field and after existing whitespace or an opening bracket,
     * where a leading space would be wrong rather than helpful.
     *
     * ## Why the alternatives go to the suggestion strip
     *
     * A swipe is inherently ambiguous — "hello" and "hell" trace nearly the same path. Committing
     * the top candidate silently and offering the rest lets the user fix a miss with one tap
     * instead of deleting five characters, and it matches how the tap-typing correction path
     * already behaves.
     */
    private fun handleSwipe(xs: FloatArray, ys: FloatArray, count: Int) {
        if (suggestionsDisabled) return
        val ic = currentInputConnection ?: return

        val density = resources.displayMetrics.density
        val candidates = swipeController.recognise(xs, ys, count, density)
        if (candidates.isEmpty()) return

        val word = candidates.first()
        val needsSpace = editorState.text.lastOrNull()?.let { prev ->
            prev != ' ' && prev != '\n' && prev != '(' && prev != '[' && prev != '"'
        } ?: false
        val insertion = if (needsSpace) " $word" else word

        ic.beginBatchEdit()
        try {
            editorState.commitText(insertion)
            ic.commitText(insertion, 1)
        } finally {
            ic.endBatchEdit()
        }

        surfaceView?.keyGrid?.haptics?.perform(HapticFeedback.Feedback.ENTER)
        surfaceView?.suggestionStrip?.setSuggestions(candidates.drop(1))
    }

    /**
     * Commits an emoji and records it as recent (spec §11 v0.3).
     *
     * The panel stays open: emoji are usually sent in twos and threes, and closing after each one
     * would force a round trip through the emoji key every time.
     *
     * Recents are usage data about the user's messages, so they follow the clipboard rule (§11.5)
     * and are not recorded in password or no-personalised-learning fields. The emoji is still
     * *committed* there — refusing to type would be absurd — only the learning is suppressed.
     */
    private fun commitEmoji(emoji: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        try {
            editorState.commitText(emoji)
            ic.commitText(emoji, 1)
        } finally {
            ic.endBatchEdit()
        }

        if (!suggestionsDisabled) {
            surfaceView?.emojiPanel?.let { panel ->
                panel.noteUsed(emoji)
                emojiRecentsStore.save(panel.recentsSnapshot())
            }
        }
    }

    override fun onCreateInputView(): View {
        val view = KeyboardSurfaceView(this)
        view.bind(editorState)
        view.keyGrid.onKeyListener = KeyGridView.OnKeyListener { action -> handleKey(action) }
        view.keyGrid.onSpatialTapListener = KeyGridView.OnSpatialTapListener { displayed, distribution ->
            handleSpatialTap(displayed, distribution)
        }
        view.keyGrid.swipeListener = KeyGridView.OnSwipeListener { xs, ys, count ->
            handleSwipe(xs, ys, count)
        }
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
        view.emojiPanel.restoreRecents(emojiRecentsStore.load())
        view.emojiPanel.onEmojiListener = EmojiPanelView.OnEmojiListener { emoji ->
            commitEmoji(emoji)
        }
        view.emojiPanel.onCloseListener = EmojiPanelView.OnCloseListener {
            view.hideEmoji()
        }

        view.suggestionStrip.onSuggestionListener =
            SuggestionStripView.OnSuggestionListener { word ->
                if (spatialController.isComposing) {
                    val ic = currentInputConnection ?: return@OnSuggestionListener
                    editorState.setComposingText(word)
                    editorState.finishComposingText()
                    ic.setComposingText(word, 1)
                    ic.finishComposingText()
                    spatialController.clear()
                    view.suggestionStrip.clear()
                } else {
                    replaceCurrentWord(word)
                }
            }

        // Spec §4.1/§9: layouts are data. Each file that parses replaces the compiled-in rows for
        // its language; each that does not simply leaves them in place, so a corrupt asset can
        // never leave the user without a keyboard (spec §11.5).
        val layoutLoader = LayoutLoader(this)
        listOf("en_US", "ru_RU").forEach { id ->
            layoutLoader.load(id)?.let { view.keyGrid.applyLayout(it) }
        }

        surfaceView = view
        // Registers the grid and starts recording if measurement was requested from Settings,
        // where no keyboard existed yet to receive the request.
        onKeyGridShown(view.keyGrid)
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
        val settings = SettingsStore(this)
        surfaceView?.keyGrid?.haptics?.intensity = settings.hapticIntensity

        // Same for touch calibration (spec §7.1): a fit accepted in settings must take effect on
        // the very next field, not after an IME restart. Absent calibration leaves the shipped
        // defaults untouched.
        val driftPx = settings.touchDriftPx
        val exponent = settings.touchDriftExponent
        if (driftPx != null && exponent != null) {
            surfaceView?.keyGrid?.touchConfig = dev.darsma.wearkey.imecore.touch.TouchModel.Config(
                maxRadialDriftPx = driftPx,
                driftExponent = exponent
            )
        }

        // Spec §11.5: never carry text between fields — full reset on every new editor, even
        // if the previous field was never explicitly finished (rapid field-switching case).
        val masked = isMaskedInputType(info?.inputType ?: 0)
        editorState.reset(masked = masked)

        // Prime EditorState with whatever the field already contains, if not masked — masked
        // fields must never see plaintext, not even transiently (spec §5).
        //
        // This is also what satisfies spec §11.5's "state must survive process death". It is worth
        // being precise about why, because the obvious reading suggests persisting the composition
        // to disk, and that would be a privacy cost for no benefit:
        //
        // This IME never leaves text uncommitted. Every keystroke goes to InputConnection
        // .commitText immediately (see handleKey), so the text lives in the *host app's* field, not
        // in a buffer of ours. When the IME is killed under memory pressure and recreated, this
        // getExtractedText call restores the full contents and caret from the field itself. Nothing
        // was ever ours to lose.
        //
        // Writing typed text to our own storage would therefore add a place for it to leak while
        // recovering data that is already safe. The invariant this depends on — commit immediately,
        // never hold a composing region — is pinned by ImeCommitInvariantTest.
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

        spatialTypingEnabled = settings.spatialTypingEnabled && !suggestionsDisabled && spellEngine.isReady
        spatialController.refreshVocabulary()
        surfaceView?.keyGrid?.onSpatialTapListener = if (spatialTypingEnabled) {
            KeyGridView.OnSpatialTapListener { displayed, distribution ->
                handleSpatialTap(displayed, distribution)
            }
        } else null

        // Glide templates depend on the laid-out grid, so this is the earliest safe point. The
        // controller no-ops when nothing changed, which keeps opening a field cheap.
        surfaceView?.keyGrid?.let { swipeController.refresh(it) }

        // Re-read on every field so a theme change in Settings takes effect at the next keyboard
        // show, without needing the IME to be restarted.
        surfaceView?.keyGrid?.theme = KeyboardTheme.byId(SettingsStore(this).themeId)

        // Clipboard reads are only legal while the IME holds focus (spec §6) — do it here.
        captureSystemClipboard(info)
        // Never leave the clipboard panel open across fields.
        surfaceView?.hideClipboard()

        // Frame timing is NOT started here. It used to be, which meant every field switch reset the
        // sample buffer — so a measurement could only ever describe the last field opened, and the
        // "start recording" control could not work at all. Recording is now begun explicitly from
        // the frame-stats screen and survives field switches, which is what measuring a real typing
        // session requires.
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Deliberately does not stop timing either: a session spans several fields, and stopping
        // here would discard the samples at the moment the user moves to the next one.
        //
        // It does snapshot them. Reading the numbers means leaving the field for Settings, which
        // is precisely the action that tears the keyboard down — so without a snapshot here the
        // measurement would be unreadable by construction.
        onKeyGridHidden()
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
        // Templates and the deferred resolver are language-specific.
        swipeController.clear()
        spatialController.refreshVocabulary()
        surfaceView?.suggestionStrip?.clear()
    }

    override fun onFinishInput() {
        // Spec §11.5: state must never leak into the next field.
        spatialController.clear()
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
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (surfaceView?.isEmojiOpen == true) {
                surfaceView?.hideEmoji()
                return true
            }
            if (surfaceView?.isClipboardOpen == true) {
                surfaceView?.hideClipboard()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------------------------------
    // Test hooks (spec §9 instrumented smoke test)
    //
    // Deliberately minimal, and `internal` rather than public so nothing outside this module can
    // reach them. No @VisibleForTesting annotation: it would mean adding androidx.annotation as a
    // dependency purely for documentation, and the §12 rule is to justify every dependency. An IME
    // cannot be bound by a test
    // without making it the *selected* keyboard, which would change a system setting and leave the
    // device altered after the run — so the test constructs the service directly and needs these
    // three seams. They expose observation and context attachment only: no hook can put the service
    // into a state that ordinary use could not also reach.
    // ---------------------------------------------------------------------------------------

    internal fun attachBaseContextForTest(context: android.content.Context) {
        attachBaseContext(context)
    }

    /** The composed text as the user would see it — bullets, not plaintext, in masked fields. */
    internal fun editorTextForTest(): String = editorState.text

    /**
     * Seeds mirrored editor state for detached lifecycle tests.
     *
     * A service constructed directly by instrumentation has no framework InputConnection, so it
     * cannot truthfully exercise [handleKey] — production input correctly returns when no target
     * editor exists. This seam establishes lifecycle preconditions only; key/commit semantics are
     * covered by EditorStateTest and CommitInvariantTest, while real editor delivery belongs to the
     * context-matrix tests.
     */
    internal fun seedEditorForTest(text: String) {
        editorState.commitText(text)
    }

    private fun handleSpatialTap(displayed: Char, distribution: Map<Char, Float>) {
        if (!spatialTypingEnabled || suggestionsDisabled || distribution.isEmpty()) {
            handleKey(KeyGridView.KeyAction.Character(displayed))
            return
        }
        val ic = currentInputConnection ?: return
        val preview = spatialController.add(displayed, distribution)
        editorState.setComposingText(preview)
        ic.setComposingText(preview, 1)
        surfaceView?.suggestionStrip?.setSuggestions(spatialController.candidates().drop(1))
    }

    /** Commits the deferred word, returning true when a spatial composition existed. */
    private fun finishSpatialWord(): Boolean {
        if (!spatialController.isComposing) return false
        val ic = currentInputConnection ?: return false
        val word = spatialController.resolvedWord()
        editorState.setComposingText(word)
        editorState.finishComposingText()
        ic.setComposingText(word, 1)
        ic.finishComposingText()
        spatialController.clear()
        surfaceView?.suggestionStrip?.clear()
        return true
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
                    finishSpatialWord()
                    // Space ends the word: clear the candidate row rather than leaving stale
                    // suggestions for a word that is already finished. The correction is NOT
                    // applied automatically — on a watch, silently rewriting what someone typed
                    // is expensive to undo, so acceptance stays an explicit tap (spec §7.2).
                    editorState.commitText(" ")
                    ic.commitText(" ", 1)
                    surfaceView?.suggestionStrip?.clear()
                }
                KeyGridView.KeyAction.Backspace -> {
                    if (spatialController.isComposing) {
                        val preview = spatialController.backspace()
                        if (preview.isEmpty()) {
                            editorState.setComposingText("")
                            editorState.finishComposingText()
                            ic.setComposingText("", 1)
                            ic.finishComposingText()
                            surfaceView?.suggestionStrip?.clear()
                        } else {
                            editorState.setComposingText(preview)
                            ic.setComposingText(preview, 1)
                            surfaceView?.suggestionStrip?.setSuggestions(spatialController.candidates().drop(1))
                        }
                    } else {
                        editorState.backspace()
                        ic.deleteSurroundingText(1, 0)
                        refreshSuggestions()
                    }
                }
                KeyGridView.KeyAction.Enter -> {
                    finishSpatialWord()
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
                KeyGridView.KeyAction.Emoji -> surfaceView?.toggleEmoji()
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
        val next = when (grid.layout) {
            KeyGridView.Layout.EN_US -> KeyGridView.Layout.RU_RU
            KeyGridView.Layout.RU_RU -> KeyGridView.Layout.EN_US
        }
        grid.layout = next
        // The framework only registers one implicit subtype on this watch, so this local fallback
        // is the path that actually runs in ordinary use. It previously swapped the key labels but
        // forgot the dictionary, leaving Russian typing backed by en.bin (found on-device: layout
        // was Cyrillic while no_backup contained only en.bin). Keep the resident index in lockstep
        // with the visible layout, exactly as onCurrentInputMethodSubtypeChanged does.
        dictionaryLoader.loadFor(next)
        swipeController.clear()
        spatialController.refreshVocabulary()
        surfaceView?.suggestionStrip?.clear()
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
        /**
         * The live keyboard view, for the frame-stats screen only (spec §14).
         *
         * A static reference to a View is normally a leak waiting to happen. It is acceptable here
         * only because it is cleared in onDestroy, and because the alternative — a bound Service or
         * a ContentProvider — is a great deal of machinery to move six floats across a process
         * boundary for a developer-facing measurement.
         *
         * Deliberately a plain nullable rather than a WeakReference: null after teardown is exactly
         * the semantics wanted, and a WeakReference would add the possibility of the stats vanishing
         * mid-session for reasons unrelated to the keyboard.
         */
        @Volatile
        private var liveKeyGrid: KeyGridView? = null

        /**
         * Set when measurement has been requested but no keyboard was showing to receive it.
         *
         * This is the whole difficulty with measuring an IME from a settings screen: the two are
         * never on screen at the same time. Pressing "start" in Settings used to call straight
         * through to `liveKeyGrid`, which was necessarily null at that moment, so the request was
         * silently dropped and a full typing session afterwards recorded nothing — the screen just
         * kept reporting "no data", which looked like a measurement of zero draws rather than a
         * request that never arrived.
         *
         * Latching the request instead means the next keyboard to appear starts recording, which
         * is exactly the session the user is about to perform.
         */
        @Volatile
        private var frameTimingRequested = false

        /** Percentiles from the last completed session, kept after the keyboard is dismissed. */
        @Volatile
        private var lastFrameStats: KeyGridView.FrameStats? = null

        /**
         * Requests draw-duration recording. Applies immediately when a keyboard is showing, and
         * otherwise arms the next one — so this works from Settings, where no IME is visible.
         */
        fun startFrameTiming() {
            frameTimingRequested = true
            lastFrameStats = null
            liveKeyGrid?.startFrameTiming()
        }

        /**
         * Percentile summary of the recorded draws, or null when nothing has been measured.
         *
         * Prefers the live grid, then falls back to the snapshot captured when the keyboard was
         * last dismissed. Without that fallback the numbers would be unreadable by construction:
         * leaving the field to open Settings is what tears down the very view holding them.
         */
        /** Applies settings immediately when the Settings activity changes them. */
        fun refreshVisualSettings(theme: KeyboardTheme, hapticIntensity: Float) {
            liveKeyGrid?.theme = theme
            liveKeyGrid?.haptics?.intensity = hapticIntensity
        }

        fun frameStats(): KeyGridView.FrameStats? =
            liveKeyGrid?.frameStats() ?: lastFrameStats

        /** Called by the service as the keyboard view appears, to honour a pending request. */
        internal fun onKeyGridShown(grid: KeyGridView) {
            liveKeyGrid = grid
            if (frameTimingRequested) grid.startFrameTiming()
        }

        /** Called as the keyboard goes away, preserving whatever it measured. */
        internal fun onKeyGridHidden() {
            liveKeyGrid?.frameStats()?.let { lastFrameStats = it }
        }

        /** Characters that end a word for suggestion purposes. */
        private val WORD_SEPARATORS = charArrayOf(' ', '\n', '\t', '.', ',', '!', '?', ';', ':')

        /**
         * Below this length every dictionary word is within one edit, so suggestions would be
         * noise rather than help.
         */
        private const val MIN_WORD_FOR_SUGGESTIONS = 3
    }
}
