package dev.darsma.wearkey.uiwear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.darsma.wearkey.imecore.ClipboardStore
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Clipboard history panel (spec §6). Replaces the key grid while open, so it gets the full
 * keyboard area — on a 233dp round display there is no room for a side-by-side layout.
 *
 * Interaction:
 *  - tap an entry  -> paste it (one-tap paste, per spec §6)
 *  - tap the pin dot on the left -> pin/unpin (pinned entries never expire or get evicted)
 *  - tap the x on the right -> delete that entry
 *  - "Clear all" row at the bottom -> wipes history (spec §11.5 "clear all learned data")
 *
 * Rendering stays View + Canvas (spec §8.0) and rows are fitted to the display circle's chord
 * so nothing hides under the bezel, same approach as KeyGridView.
 */
class ClipboardPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    fun interface Listener {
        fun onPaste(text: String)
        fun onPin(text: String, pinned: Boolean)
        fun onDelete(text: String)
        fun onClearAll()
        fun onClose()
    }

    var listener: Listener? = null

    private var store: ClipboardStore? = null
    private var entries: List<ClipboardStore.Entry> = emptyList()
    private var scrollY = 0f

    private val bgPaint = Paint().apply { color = Color.BLACK }
    private val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1C1C1E") }
    private val rowPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A3A3C") }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E8E93")
        textAlign = Paint.Align.CENTER
    }
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00E5FF") }
    private val pinOffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5A5A5E") }
    private val deletePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E8E93")
        textAlign = Paint.Align.CENTER
    }
    private val sensitivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9F0A")
        textAlign = Paint.Align.LEFT
    }

    private data class Row(val entry: ClipboardStore.Entry?, val rect: RectF, val isClearAll: Boolean = false)

    private val rows = mutableListOf<Row>()
    private var pressedRow: Row? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    init {
        val density = resources.displayMetrics.density
        textPaint.textSize = 15f * density
        hintPaint.textSize = 13f * density
        deletePaint.textSize = 16f * density
        sensitivePaint.textSize = 11f * density
    }

    fun bind(store: ClipboardStore) {
        this.store = store
        refresh()
    }

    fun refresh() {
        entries = store?.visibleEntries().orEmpty()
        scrollY = 0f
        computeRows()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeRows()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) computeRows()
    }

    private fun computeRows() {
        rows.clear()
        if (width == 0 || height == 0) return

        val density = resources.displayMetrics.density
        val rowHeight = 40f * density
        val gap = 4f * density

        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val screenW = resources.displayMetrics.widthPixels.toFloat()
        val screenH = resources.displayMetrics.heightPixels.toFloat()
        val radius = minOf(screenW, screenH) / 2f
        val centerYInView = radius - loc[1].toFloat()
        val centerX = radius - loc[0].toFloat()

        var y = 4f * density - scrollY
        val items = entries.size + 1 // + "clear all"

        for (i in 0 until items) {
            val top = y
            val bottom = y + rowHeight
            val dy = maxOf(abs(top - centerYInView), abs(bottom - centerYInView))
            val half = chordHalf(radius, dy)
            val l = (centerX - half).coerceAtLeast(0f) + 8f
            val r = (centerX + half).coerceAtMost(width.toFloat()) - 8f
            if (r > l) {
                val rect = RectF(l, top, r, bottom)
                if (i < entries.size) {
                    rows.add(Row(entries[i], rect))
                } else {
                    rows.add(Row(null, rect, isClearAll = true))
                }
            }
            y = bottom + gap
        }
    }

    private fun chordHalf(radius: Float, dy: Float): Float {
        val d = abs(dy).coerceAtMost(radius)
        return sqrt(radius * radius - d * d)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (entries.isEmpty()) {
            canvas.drawText(
                context.getString(R.string.clipboard_empty),
                width / 2f,
                height / 2f,
                hintPaint
            )
        }

        val density = resources.displayMetrics.density
        for (row in rows) {
            if (row.rect.bottom < 0 || row.rect.top > height) continue
            val paint = if (row === pressedRow) rowPressedPaint else rowPaint
            val radius = 10f * density
            canvas.drawRoundRect(row.rect, radius, radius, paint)

            if (row.isClearAll) {
                canvas.drawText(
                    context.getString(R.string.clipboard_clear_all),
                    row.rect.centerX(),
                    row.rect.centerY() - (hintPaint.ascent() + hintPaint.descent()) / 2,
                    hintPaint
                )
                continue
            }

            val entry = row.entry ?: continue

            // Pin indicator on the left.
            val pinCx = row.rect.left + 14f * density
            val pinCy = row.rect.centerY()
            canvas.drawCircle(pinCx, pinCy, 4f * density, if (entry.pinned) pinPaint else pinOffPaint)

            // Delete affordance on the right.
            canvas.drawText(
                "×",
                row.rect.right - 14f * density,
                row.rect.centerY() - (deletePaint.ascent() + deletePaint.descent()) / 2,
                deletePaint
            )

            // Entry text, ellipsised to the available width.
            val textLeft = pinCx + 12f * density
            val textRight = row.rect.right - 26f * density
            val avail = (textRight - textLeft).coerceAtLeast(1f)
            val singleLine = entry.text.replace("\n", " ")
            val shown = ellipsise(singleLine, avail)
            val baseline = row.rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2
            canvas.drawText(shown, textLeft, baseline, textPaint)

            if (entry.sensitive && !entry.pinned) {
                canvas.drawText(
                    context.getString(R.string.clipboard_expires),
                    textLeft,
                    row.rect.bottom - 3f * density,
                    sensitivePaint
                )
            }
        }
    }

    private fun ellipsise(text: String, maxWidth: Float): String {
        if (textPaint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && textPaint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                pressedRow = rowAt(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.y - downY) > 10f * density) {
                    dragging = true
                    pressedRow = null
                }
                if (dragging) {
                    scrollY = (scrollY + (downY - event.y)).coerceAtLeast(0f)
                    downY = event.y
                    computeRows()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val row = pressedRow
                pressedRow = null
                invalidate()
                if (dragging || row == null) return true

                if (row.isClearAll) {
                    listener?.onClearAll()
                    return true
                }
                val entry = row.entry ?: return true

                val pinZoneRight = row.rect.left + 28f * density
                val deleteZoneLeft = row.rect.right - 28f * density
                when {
                    event.x <= pinZoneRight -> listener?.onPin(entry.text, !entry.pinned)
                    event.x >= deleteZoneLeft -> listener?.onDelete(entry.text)
                    else -> listener?.onPaste(entry.text)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedRow = null
                invalidate()
                return true
            }
        }
        return false
    }

    private fun rowAt(x: Float, y: Float): Row? = rows.firstOrNull { it.rect.contains(x, y) }
}
