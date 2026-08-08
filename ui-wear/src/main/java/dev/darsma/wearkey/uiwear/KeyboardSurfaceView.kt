package dev.darsma.wearkey.uiwear

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.widget.LinearLayout
import dev.darsma.wearkey.imecore.EditorState

/**
 * The single, shared keyboard surface: composition strip pinned on top of the key grid.
 *
 * Spec §4.5 explicitly requires the IME entry point and the LAUNCH_KEYBOARD Activity entry
 * point to render identically — "no forked UI". This class is that shared piece: both
 * WearKeyImeService and LaunchKeyboardActivity instantiate exactly one of these and wire it to
 * their own EditorState + key-action handling, but never draw anything themselves.
 *
 * CRITICAL SIZING RULE (spec §1 — the actual problem being solved): the keyboard must NOT fill
 * the display. If it does, the app's real text field is completely hidden behind it and the
 * user is back to Gboard's failure mode — guessing at what they typed. The surface therefore
 * claims only [KEYBOARD_HEIGHT_FRACTION] of the screen height; the system resizes/pans the
 * target app into the space above, keeping the genuine field on screen. The composition strip
 * is the *backup* guarantee for the cases where an app still can't be resized — not a
 * replacement for seeing the real field.
 */
class KeyboardSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    val compositionStrip: CompositionStripView
    val suggestionStrip: SuggestionStripView
    val keyGrid: KeyGridView

    // Optional panels are deliberately absent from the first-frame hierarchy. Perfetto on the
    // physical watch attributed a large part of true-cold startup to initial class/view creation,
    // while both panels start GONE. Constructing them on first use removes work the user cannot
    // see without changing the shared surface or either entry point's behaviour.
    private var clipboardPanelView: ClipboardPanelView? = null
    private var emojiPanelView: EmojiPanelView? = null
    private var clipboardStore: dev.darsma.wearkey.imecore.ClipboardStore? = null
    private var clipboardListener: ClipboardPanelView.Listener? = null
    private var emojiRecentsProvider: (() -> List<String>)? = null
    private var emojiListener: EmojiPanelView.OnEmojiListener? = null

    init {
        setBackgroundColor(Color.BLACK)

        orientation = VERTICAL

        val density = resources.displayMetrics.density
        // Strip is now a compact band at the TOP OF THE KEYBOARD (not floating over a
        // full-screen surface). It is deliberately thin: the app's own field above the keyboard
        // is the primary place the user reads their text; this is the fallback mirror.
        val stripHeightPx = (28f * density).toInt()

        compositionStrip = CompositionStripView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, stripHeightPx)
        }
        addView(compositionStrip)

        // Candidate row: height is reserved permanently (INVISIBLE when empty, never GONE).
        // Letting it collapse re-flows the key grid mid-word and moves keys under the user's
        // finger — reproduced on-device, see SuggestionStripView.setSuggestions.
        suggestionStrip = SuggestionStripView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (26f * density).toInt())
            visibility = INVISIBLE
        }
        addView(suggestionStrip)

        keyGrid = KeyGridView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0).also { it.weight = 1f }
        }
        addView(keyGrid)

    }

    /** Wires both sub-views to the given state in one call — keeps entry points' code trivial. */
    fun bind(state: EditorState) {
        compositionStrip.bind(state)
        compositionStrip.onCaretRequestListener = CompositionStripView.OnCaretRequestListener { index ->
            state.setCaret(index)
        }
    }

    fun unbind() {
        compositionStrip.unbind()
        compositionStrip.onCaretRequestListener = null
    }

    /** Supplies clipboard state without constructing its hidden panel on the first frame. */
    fun bindClipboard(
        store: dev.darsma.wearkey.imecore.ClipboardStore,
        listener: ClipboardPanelView.Listener
    ) {
        clipboardStore = store
        clipboardListener = listener
        clipboardPanelView?.apply {
            bind(store)
            this.listener = listener
        }
    }

    /** Supplies emoji state without loading its catalogue or panel until the user opens it. */
    fun bindEmoji(
        recentsProvider: () -> List<String>,
        listener: EmojiPanelView.OnEmojiListener
    ) {
        emojiRecentsProvider = recentsProvider
        emojiListener = listener
        emojiPanelView?.onEmojiListener = listener
    }

    private fun clipboardPanel(): ClipboardPanelView {
        clipboardPanelView?.let { return it }
        return ClipboardPanelView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0).also { it.weight = 1f }
            visibility = GONE
            clipboardStore?.let(::bind)
            listener = clipboardListener
            this@KeyboardSurfaceView.addView(this)
            clipboardPanelView = this
        }
    }

    private fun emojiPanel(): EmojiPanelView {
        emojiPanelView?.let { return it }
        return EmojiPanelView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0).also { it.weight = 1f }
            visibility = GONE
            restoreRecents(emojiRecentsProvider?.invoke().orEmpty())
            onEmojiListener = emojiListener
            this@KeyboardSurfaceView.addView(this)
            emojiPanelView = this
        }
    }

    /** Runs [block] only if the emoji panel has already been opened and constructed. */
    fun withEmojiPanel(block: (EmojiPanelView) -> Unit) {
        emojiPanelView?.let(block)
    }

    /** Refreshes an existing clipboard panel; unopened panels read current state when created. */
    fun refreshClipboardPanel() {
        clipboardPanelView?.refresh()
    }

    /** True while the clipboard history panel is showing instead of the key grid. */
    val isClipboardOpen: Boolean
        get() = clipboardPanelView?.visibility == VISIBLE

    fun showClipboard() {
        val clipboardPanel = clipboardPanel()
        clipboardPanel.refresh()
        // Symmetric with showEmoji: exactly one panel may occupy the key slot. Opening the
        // clipboard from the emoji layer previously left both VISIBLE, and which one the user saw
        // depended on child order rather than on intent.
        emojiPanelView?.visibility = GONE
        keyGrid.visibility = GONE
        clipboardPanel.visibility = VISIBLE
    }

    fun hideClipboard() {
        clipboardPanelView?.visibility = GONE
        keyGrid.visibility = VISIBLE
    }

    fun toggleClipboard() {
        if (isClipboardOpen) hideClipboard() else showClipboard()
    }

    /** True while the emoji layer is showing instead of the key grid. */
    val isEmojiOpen: Boolean
        get() = emojiPanelView?.visibility == VISIBLE

    fun showEmoji() {
        val emojiPanel = emojiPanel()
        // Closing the clipboard first keeps the invariant that exactly one panel occupies the slot;
        // without it both could be VISIBLE and the later child would silently win.
        clipboardPanelView?.visibility = GONE
        keyGrid.visibility = GONE
        emojiPanel.visibility = VISIBLE
    }

    fun hideEmoji() {
        emojiPanelView?.visibility = GONE
        keyGrid.visibility = VISIBLE
    }

    fun toggleEmoji() {
        if (isEmojiOpen) hideEmoji() else showEmoji()
    }

    /**
     * Constrains the keyboard to the bottom fraction of the display so the app's real field
     * stays visible above it. Without this the IME window fills all 466x466 px and hides the
     * very thing the user is trying to see.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screenHeight = resources.displayMetrics.heightPixels
        val targetHeight = (screenHeight * KEYBOARD_HEIGHT_FRACTION).toInt()
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
        )
    }

    fun startFrameTiming() = keyGrid.startFrameTiming()
    fun stopFrameTiming() = keyGrid.stopFrameTiming()
    fun frameStats(): KeyGridView.FrameStats? = keyGrid.frameStats()

    companion object {
        /**
         * Fraction of screen height the keyboard is allowed to occupy. 0.66 leaves ~158px on a
         * 466px round display — still enough for a Wear text field plus its app chrome to stay
         * visible, while giving each key row comfortable height. Tuned on-device: below ~0.60
         * the rows get cramped, above ~0.70 the app's field starts getting pushed off screen.
         */
        const val KEYBOARD_HEIGHT_FRACTION = 0.66f
    }
}
