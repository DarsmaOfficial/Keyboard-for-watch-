package dev.darsma.wearkey.uiwear

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.animation.PathInterpolator
import android.util.AttributeSet
import android.widget.FrameLayout
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
    val clipboardPanel: ClipboardPanelView
    val emojiPanel: EmojiPanelView

    private var themeAnimator: ValueAnimator? = null
    private var renderedTheme: KeyboardTheme = KeyboardTheme.MIDNIGHT

    var theme: KeyboardTheme = KeyboardTheme.MIDNIGHT
        set(value) {
            if (field == value) return
            field = value
            morphThemeTo(value)
        }

    private fun applyThemeFrame(value: KeyboardTheme) {
        renderedTheme = value
        setBackgroundColor(value.background)
        compositionStrip.applyTheme(value)
        suggestionStrip.applyTheme(value)
        keyGrid.theme = value
        clipboardPanel.applyTheme(value)
        emojiPanel.applyTheme(value)
    }

    private fun morphThemeTo(target: KeyboardTheme) {
        themeAnimator?.cancel()
        if (!MotionPolicy.decorativeAnimationEnabled(context) || !isAttachedToWindow) {
            applyThemeFrame(target)
            return
        }
        val start = renderedTheme
        themeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = THEME_MORPH_MS
            interpolator = MOTION_EASING
            addUpdateListener { animation ->
                applyThemeFrame(
                    KeyboardTheme.interpolate(start, target, animation.animatedValue as Float)
                )
            }
            start()
        }
    }

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

        // All mutually exclusive interaction surfaces occupy one fixed slot. Their bounds can
        // never reflow when switching panels, which protects the key geometry under the finger.
        val interactionSlot = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0).also { it.weight = 1f }
        }
        addView(interactionSlot)

        keyGrid = KeyGridView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        interactionSlot.addView(keyGrid)

        clipboardPanel = ClipboardPanelView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = GONE
        }
        interactionSlot.addView(clipboardPanel)

        emojiPanel = EmojiPanelView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = GONE
        }
        interactionSlot.addView(emojiPanel)

        // Apply once after all children exist; subsequent assignments propagate live.
        applyThemeFrame(theme)
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

    private fun showSurface(target: android.view.View) {
        val surfaces = listOf(keyGrid, clipboardPanel, emojiPanel)
        val outgoing = surfaces.firstOrNull { it.visibility == VISIBLE && it !== target }
        surfaces.forEach { it.animate().cancel() }

        // Input ownership changes immediately. The outgoing surface may remain visible for the
        // visual handoff, but it can no longer receive a touch.
        outgoing?.isEnabled = false
        target.isEnabled = true
        target.visibility = VISIBLE

        if (!MotionPolicy.decorativeAnimationEnabled(context)) {
            surfaces.forEach { view ->
                view.visibility = if (view === target) VISIBLE else GONE
                resetTransform(view)
            }
            return
        }

        target.alpha = 0f
        target.scaleX = 0.90f
        target.scaleY = 0.90f
        target.translationY = 8f * resources.displayMetrics.density
        target.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setInterpolator(MOTION_EASING)
            .setDuration(PANEL_MORPH_MS)
            .start()

        outgoing?.animate()
            ?.alpha(0f)
            ?.scaleX(1.035f)
            ?.scaleY(1.035f)
            ?.translationY(-4f * resources.displayMetrics.density)
            ?.setInterpolator(MOTION_EASING)
            ?.setDuration(PANEL_EXIT_MS)
            ?.withEndAction {
                outgoing.visibility = GONE
                resetTransform(outgoing)
            }
            ?.start()

        surfaces.filter { it !== target && it !== outgoing }.forEach { view ->
            view.visibility = GONE
            resetTransform(view)
        }
    }

    private fun resetTransform(view: android.view.View) {
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationY = 0f
    }

    /** True while the clipboard history panel is showing instead of the key grid. */
    val isClipboardOpen: Boolean
        get() = clipboardPanel.visibility == VISIBLE

    fun showClipboard() {
        clipboardPanel.refresh()
        // Symmetric with showEmoji: exactly one panel may occupy the key slot. Opening the
        // clipboard from the emoji layer previously left both VISIBLE, and which one the user saw
        // depended on child order rather than on intent.
        showSurface(clipboardPanel)
    }

    fun hideClipboard() {
        showSurface(keyGrid)
    }

    fun toggleClipboard() {
        if (isClipboardOpen) hideClipboard() else showClipboard()
    }

    /** True while the emoji layer is showing instead of the key grid. */
    val isEmojiOpen: Boolean
        get() = emojiPanel.visibility == VISIBLE

    fun showEmoji() {
        // Closing the clipboard first keeps the invariant that exactly one panel occupies the slot;
        // without it both could be VISIBLE and the later child would silently win.
        showSurface(emojiPanel)
    }

    fun hideEmoji() {
        showSurface(keyGrid)
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

    override fun onDetachedFromWindow() {
        themeAnimator?.cancel()
        themeAnimator = null
        listOf(keyGrid, clipboardPanel, emojiPanel).forEach { it.animate().cancel() }
        super.onDetachedFromWindow()
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
        private const val PANEL_MORPH_MS = 180L
        private const val PANEL_EXIT_MS = 120L
        private const val THEME_MORPH_MS = 260L
        private val MOTION_EASING = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
