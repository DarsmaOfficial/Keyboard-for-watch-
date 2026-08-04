package dev.darsma.wearkey.uiwear

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.widget.FrameLayout
import dev.darsma.wearkey.imecore.EditorState

/**
 * The single, shared keyboard surface: composition strip pinned on top of the key grid.
 *
 * Spec §4.5 explicitly requires the IME entry point and the LAUNCH_KEYBOARD Activity entry
 * point to render identically — "no forked UI". This class is that shared piece: both
 * WearKeyImeService and LaunchKeyboardActivity instantiate exactly one of these and wire it to
 * their own EditorState + key-action handling, but never draw anything themselves.
 */
class KeyboardSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    val compositionStrip: CompositionStripView
    val keyGrid: KeyGridView

    init {
        setBackgroundColor(Color.BLACK)

        val density = resources.displayMetrics.density
        // Spec §5: strip is 38dp tall, positioned Y 16dp -> 54dp (16dp top margin + 38dp height).
        val stripTopMarginPx = (16f * density).toInt()
        val stripHeightPx = (38f * density).toInt()

        keyGrid = KeyGridView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(keyGrid)

        compositionStrip = CompositionStripView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, stripHeightPx).also {
                it.topMargin = stripTopMarginPx
            }
        }
        addView(compositionStrip)
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

    fun startFrameTiming() = keyGrid.startFrameTiming()
    fun stopFrameTiming() = keyGrid.stopFrameTiming()
    fun frameStats(): Pair<Int, Int> = keyGrid.frameStats()
}
