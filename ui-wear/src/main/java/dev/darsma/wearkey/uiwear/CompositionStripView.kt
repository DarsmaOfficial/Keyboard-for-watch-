package dev.darsma.wearkey.uiwear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.darsma.wearkey.imecore.EditorState
import kotlin.math.abs

/**
 * Headline feature (spec §5): a persistent strip, 38dp tall, pinned at Y 16dp-54dp, that always
 * renders the full field contents in real time with a visible blinking caret. Structurally
 * isolated from the key grid — this view owns its own vertical band and is never occluded.
 *
 * Reads exclusively from the local [EditorState] (spec §5 critical rule: never query
 * InputConnection per frame). Registers as an [EditorState.Listener] and only repaints on actual
 * state changes plus a lightweight caret-blink timer.
 *
 * Masking: EditorState itself already stores mask characters instead of plaintext when the
 * field is masked (spec §5/§11.5) — this view just renders whatever EditorState.text is, so it
 * never needs (and never gets) access to real password/OTP content.
 */
class CompositionStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), EditorState.Listener {

    /** Caret tap-to-position / drag-to-scrub (spec §5). Index is a character offset into text. */
    fun interface OnCaretRequestListener {
        fun onCaretRequested(charIndex: Int)
    }

    var onCaretRequestListener: OnCaretRequestListener? = null

    private var editorState: EditorState? = null

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#000000")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }

    private val caretPaint = Paint().apply {
        color = Color.parseColor("#00E5FF") // matches launcher icon accent
        style = Paint.Style.FILL
    }

    private val underlinePaint = Paint().apply {
        color = Color.parseColor("#5A5A5E")
        strokeWidth = 2f
    }

    /** Horizontal scroll offset in px, kept so the caret is always visible (spec §5 auto-scroll). */
    private var scrollOffsetPx = 0f

    private var caretVisible = true
    private val caretBlinkRunnable = object : Runnable {
        override fun run() {
            caretVisible = !caretVisible
            invalidate()
            postDelayed(this, CARET_BLINK_INTERVAL_MS)
        }
    }

    // Drag-to-scrub tracking.
    private var dragStartX = 0f
    private var isDragging = false

    init {
        val density = resources.displayMetrics.density
        textPaint.textSize = 16f * density
    }

    fun applyTheme(theme: KeyboardTheme) {
        backgroundPaint.color = theme.background
        textPaint.color = theme.label
        caretPaint.color = theme.accent
        underlinePaint.color = theme.functionKey
        invalidate()
    }

    fun bind(state: EditorState) {
        editorState?.removeListener(this)
        editorState = state
        state.addListener(this)
        recomputeScroll()
        invalidate()
    }

    fun unbind() {
        editorState?.removeListener(this)
        editorState = null
    }

    override fun onEditorStateChanged(state: EditorState) {
        recomputeScroll()
        // Reset blink to visible on every edit so the caret doesn't disappear right after typing.
        caretVisible = true
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(caretBlinkRunnable)
        postDelayed(caretBlinkRunnable, CARET_BLINK_INTERVAL_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(caretBlinkRunnable)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val state = editorState ?: return
        val text = state.text
        val density = resources.displayMetrics.density
        val paddingPx = 8f * density
        val baselineY = height / 2f - (textPaint.ascent() + textPaint.descent()) / 2

        canvas.save()
        canvas.clipRect(paddingPx, 0f, width - paddingPx, height.toFloat())
        canvas.translate(paddingPx - scrollOffsetPx, 0f)

        if (text.isNotEmpty()) {
            canvas.drawText(text, 0f, baselineY, textPaint)
        }

        // Caret: blinking vertical bar at selectionStart (collapsed-selection assumption for v0.1).
        if (caretVisible) {
            val caretX = textPaint.measureText(text, 0, state.selectionStart)
            canvas.drawRect(
                caretX, baselineY + textPaint.ascent(),
                caretX + CARET_WIDTH_PX, baselineY + textPaint.descent(),
                caretPaint
            )
        }

        canvas.restore()

        // Bottom hairline separates the strip from the key grid — visually isolates it per spec §5.
        canvas.drawLine(0f, height - 1f, width.toFloat(), height - 1f, underlinePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val state = editorState ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.x
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - dragStartX) > TOUCH_SLOP_PX) {
                    isDragging = true
                    requestCaretAt(event.x, state)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    // Tap-to-position (spec §5).
                    requestCaretAt(event.x, state)
                }
                isDragging = false
                return true
            }
        }
        return false
    }

    private fun requestCaretAt(touchX: Float, state: EditorState) {
        val density = resources.displayMetrics.density
        val paddingPx = 8f * density
        val localX = touchX - paddingPx + scrollOffsetPx
        val index = indexForX(state.text, localX)
        onCaretRequestListener?.onCaretRequested(index)
    }

    /** Binary-searches the character index whose glyph boundary is closest to [targetX]. */
    private fun indexForX(text: String, targetX: Float): Int {
        if (text.isEmpty()) return 0
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            val w = textPaint.measureText(text, 0, mid)
            if (w <= targetX) low = mid else high = mid - 1
        }
        return low.coerceIn(0, text.length)
    }

    private fun recomputeScroll() {
        val state = editorState ?: return
        val density = resources.displayMetrics.density
        val paddingPx = 8f * density
        val visibleWidth = (width - 2 * paddingPx).coerceAtLeast(0f)
        val caretX = textPaint.measureText(state.text, 0, state.selectionStart)

        scrollOffsetPx = when {
            visibleWidth <= 0f -> 0f
            caretX - scrollOffsetPx > visibleWidth -> caretX - visibleWidth
            caretX - scrollOffsetPx < 0f -> caretX
            else -> scrollOffsetPx
        }.coerceAtLeast(0f)
    }

    companion object {
        private const val CARET_BLINK_INTERVAL_MS = 500L
        private const val CARET_WIDTH_PX = 3f
        private const val TOUCH_SLOP_PX = 12f
    }
}
