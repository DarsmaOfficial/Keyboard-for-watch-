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
    val keyGrid: KeyGridView
    val clipboardPanel: ClipboardPanelView

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

        keyGrid = KeyGridView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0).also { it.weight = 1f }
        }
        addView(keyGrid)

        // Occupies the same slot as the key grid — on a 233dp round display there is no room to
        // show both at once, so the clipboard panel replaces the keys while it is open.
        clipboardPanel = ClipboardPanelView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0).also { it.weight = 1f }
            visibility = GONE
        }
        addView(clipboardPanel)
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

    /** True while the clipboard history panel is showing instead of the key grid. */
    val isClipboardOpen: Boolean
        get() = clipboardPanel.visibility == VISIBLE

    fun showClipboard() {
        clipboardPanel.refresh()
        keyGrid.visibility = GONE
        clipboardPanel.visibility = VISIBLE
    }

    fun hideClipboard() {
        clipboardPanel.visibility = GONE
        keyGrid.visibility = VISIBLE
    }

    fun toggleClipboard() {
        if (isClipboardOpen) hideClipboard() else showClipboard()
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
    fun frameStats(): Pair<Int, Int> = keyGrid.frameStats()

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
