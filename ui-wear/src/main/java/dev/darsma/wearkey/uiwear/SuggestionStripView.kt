package dev.darsma.wearkey.uiwear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Suggestion strip shown above the keys (spec §7.2).
 *
 * Sits between the composition strip and the key grid, and only takes space when it has
 * something to offer — an empty candidate row on a 233 dp round display is stolen space.
 *
 * Deliberately restrained: it *offers* corrections, it does not apply them silently. On a watch
 * an unwanted autocorrection is expensive to undo, so the user taps to accept.
 */
class SuggestionStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    fun interface OnSuggestionListener {
        fun onSuggestionPicked(word: String)
    }

    var onSuggestionListener: OnSuggestionListener? = null

    private var suggestions: List<String> = emptyList()
    private val slots = mutableListOf<Pair<String, RectF>>()
    private var pressedIndex = -1

    private val bgPaint = Paint().apply { color = Color.BLACK }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1C1E")
    }
    private val chipPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3A3C")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    init {
        val density = resources.displayMetrics.density
        textPaint.textSize = 13f * density
    }

    /** Replaces the offered candidates. Passing an empty list hides the strip entirely. */
    /**
     * Replaces the offered candidates.
     *
     * The row uses INVISIBLE rather than GONE when empty, and this is deliberate. Collapsing it
     * re-lays-out the key grid *while the user is mid-word*: found on-device, typing "helo" put
     * the third tap on a candidate chip because the strip had appeared after "hel" and pushed
     * every key down by its own height. A keyboard whose keys move under your finger is worse
     * than one that spends 26 dp on an occasionally-empty row, so the space is reserved
     * permanently and key geometry never changes while a field is focused.
     */
    fun setSuggestions(words: List<String>) {
        if (suggestions == words) return
        suggestions = words
        // Reserve the height either way; only the painting is suppressed when there is nothing
        // to offer. INVISIBLE still occupies its measured space, GONE does not.
        visibility = if (words.isEmpty()) INVISIBLE else VISIBLE
        computeSlots()
        invalidate()
    }

    fun clear() = setSuggestions(emptyList())

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeSlots()
    }

    private fun computeSlots() {
        slots.clear()
        if (width == 0 || height == 0 || suggestions.isEmpty()) return

        val density = resources.displayMetrics.density
        val margin = 6f * density
        // Keep clear of the round display's edge at this height.
        val usable = width - 2 * margin
        val slotWidth = usable / suggestions.size

        suggestions.forEachIndexed { index, word ->
            val left = margin + index * slotWidth
            slots.add(word to RectF(left + 2f, 3f, left + slotWidth - 2f, height - 3f))
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        slots.forEachIndexed { index, (word, rect) ->
            val paint = if (index == pressedIndex) chipPressedPaint else chipPaint
            val radius = rect.height() * 0.35f
            canvas.drawRoundRect(rect, radius, radius, paint)

            val shown = ellipsise(word, rect.width() - 10f)
            canvas.drawText(
                shown,
                rect.centerX(),
                rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2,
                textPaint
            )
        }
    }

    private fun ellipsise(text: String, maxWidth: Float): String {
        if (textPaint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && textPaint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = slots.indexOfFirst { it.second.contains(event.x, event.y) }
                invalidate()
                return pressedIndex >= 0
            }
            MotionEvent.ACTION_UP -> {
                val index = pressedIndex
                pressedIndex = -1
                invalidate()
                if (index >= 0 && index < slots.size) {
                    onSuggestionListener?.onSuggestionPicked(slots[index].first)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                invalidate()
                return true
            }
        }
        return false
    }
}
