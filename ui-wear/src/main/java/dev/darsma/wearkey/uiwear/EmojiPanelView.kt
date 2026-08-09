package dev.darsma.wearkey.uiwear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import dev.darsma.wearkey.imecore.EmojiCatalogue
import dev.darsma.wearkey.imecore.EmojiRecents

/**
 * The emoji layer: a vertically scrolling grid with category headers (spec §11 v0.3).
 *
 * ## Font
 *
 * Rendering relies on the watch's own `NotoColorEmoji.ttf`, which every Wear OS build ships. Spec
 * §3.2 is explicit that no font is bundled: OFL-1.1 is free but carries Reserved Font Name rules
 * and its own redistribution notice, and the platform font avoids that obligation entirely while
 * saving several megabytes. `Paint` picks up the colour emoji font automatically through the
 * system fallback chain, so nothing here has to name it.
 *
 * ## Why a custom View rather than RecyclerView
 *
 * Spec §8.0 settles the rendering approach for the keyboard surface, and the same reasoning holds
 * here: this is a uniform grid of single glyphs with no view state worth recycling. A `Canvas` loop
 * over the visible rows costs no view inflation, no adapter, and no androidx dependency — and it
 * keeps the emoji layer inside the same frame budget as the key grid.
 */
class EmojiPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    fun interface OnEmojiListener {
        fun onEmoji(emoji: String)
    }

    fun interface OnCloseListener {
        fun onClose()
    }

    var onEmojiListener: OnEmojiListener? = null
    var onCloseListener: OnCloseListener? = null

    private val recents = EmojiRecents()

    /** Flattened display list: headers and emoji interleaved, in draw order. */
    private sealed class Cell {
        data class Header(val titleRes: Int) : Cell()
        data class Emoji(val value: String) : Cell()
    }

    private val rows = ArrayList<List<Cell>>()

    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }

    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        color = Color.parseColor("#9E9E9E")
    }

    private val backgroundPaint = Paint().apply { color = Color.BLACK }

    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A2A")
    }

    private val closePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textAlign = Paint.Align.CENTER
    }

    private val closeBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10272C")
    }

    private val scroller = OverScroller(context)
    private var scrollY = 0f
    private var maxScroll = 0f
    private var pressedRow = -1
    private var pressedColumn = -1
    private var downY = 0f
    private var dragging = false
    private var closePressed = false

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private var columns = 6
    private var cellSize = 0f
    private var headerHeight = 0f

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
    }

    /** Seeds recents from persisted storage. */
    fun restoreRecents(saved: List<String>) {
        recents.restore(saved)
        rebuild()
    }

    fun recentsSnapshot(): List<String> = recents.all()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Six columns on a 466 px display gives ~64 px cells: comfortably above the ~48 px minimum
        // touch target once the round display's usable width is accounted for.
        columns = 6
        cellSize = w / columns.toFloat()
        emojiPaint.textSize = cellSize * 0.62f
        headerHeight = cellSize * 0.5f
        headerPaint.textSize = headerHeight * 0.52f
        closePaint.textSize = cellSize * 0.34f
        rebuild()
    }

    private fun rebuild() {
        rows.clear()
        if (width == 0) return

        val recentList = recents.all()
        if (recentList.isNotEmpty()) {
            addSection(R.string.emoji_category_recent, recentList)
        }
        for (category in EmojiCatalogue.CATEGORIES) {
            addSection(titleResFor(category.id), category.emoji)
        }

        val contentHeight = rows.sumOf { row ->
            if (row.firstOrNull() is Cell.Header) headerHeight.toDouble() else cellSize.toDouble()
        }.toFloat()
        maxScroll = (contentHeight - (height - closeBarHeight())).coerceAtLeast(0f)
        scrollY = scrollY.coerceIn(0f, maxScroll)
        invalidate()
    }

    private fun addSection(titleRes: Int, emoji: List<String>) {
        rows.add(listOf(Cell.Header(titleRes)))
        var index = 0
        while (index < emoji.size) {
            val end = minOf(index + columns, emoji.size)
            rows.add(emoji.subList(index, end).map { Cell.Emoji(it) })
            index = end
        }
    }

    private fun titleResFor(id: String): Int = when (id) {
        "smileys" -> R.string.emoji_category_smileys
        "gestures" -> R.string.emoji_category_gestures
        "hearts" -> R.string.emoji_category_hearts
        "animals" -> R.string.emoji_category_animals
        "food" -> R.string.emoji_category_food
        "activity" -> R.string.emoji_category_activity
        "travel" -> R.string.emoji_category_travel
        "objects" -> R.string.emoji_category_objects
        else -> R.string.emoji_category_symbols
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.toFloat().coerceIn(0f, maxScroll)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        var y = -scrollY
        for ((rowIndex, row) in rows.withIndex()) {
            val rowHeight = if (row.firstOrNull() is Cell.Header) headerHeight else cellSize

            // Skip rows entirely off-screen — the catalogue is ~700 glyphs and drawing them all
            // would blow the frame budget for no visible benefit.
            if (y + rowHeight < 0f) {
                y += rowHeight
                continue
            }
            if (y > height) break

            when (val first = row.first()) {
                is Cell.Header -> {
                    canvas.drawText(
                        context.getString(first.titleRes),
                        cellSize * 0.15f,
                        y + headerHeight * 0.72f,
                        headerPaint
                    )
                }

                is Cell.Emoji -> {
                    for ((column, cell) in row.withIndex()) {
                        val value = (cell as Cell.Emoji).value
                        val cx = column * cellSize
                        if (rowIndex == pressedRow && column == pressedColumn) {
                            canvas.drawRoundRect(
                                cx + 3f, y + 3f, cx + cellSize - 3f, y + cellSize - 3f,
                                10f, 10f, pressedPaint
                            )
                        }
                        // Baseline offset centres the glyph optically: emoji sit on the text
                        // baseline, so centring the box is not the same as centring the drawing.
                        canvas.drawText(
                            value,
                            cx + cellSize / 2f,
                            y + cellSize * 0.72f,
                            emojiPaint
                        )
                    }
                }
            }
            y += rowHeight
        }

        // Fixed above the scrolling catalogue, so there is always an obvious route back even after
        // scrolling hundreds of glyphs. The emoji key itself is hidden while this panel is open.
        val barTop = height - closeBarHeight()
        closeBackgroundPaint.color = Color.parseColor(if (closePressed) "#1A3A41" else "#10272C")
        canvas.drawRoundRect(
            cellSize * 0.7f, barTop + 3f,
            width - cellSize * 0.7f, height - 3f,
            16f, 16f, closeBackgroundPaint
        )
        canvas.drawText(
            context.getString(R.string.emoji_back_to_keyboard),
            width / 2f,
            barTop + closeBarHeight() * 0.67f,
            closePaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downY = event.y
                dragging = false
                closePressed = event.y >= height - closeBarHeight()
                if (closePressed) {
                    pressedRow = -1
                    pressedColumn = -1
                } else {
                    locate(event.x, event.y)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (closePressed) {
                    closePressed = event.y >= height - closeBarHeight()
                    invalidate()
                    return true
                }
                val dy = downY - event.y
                if (!dragging && kotlin.math.abs(dy) > touchSlop) {
                    dragging = true
                    // Once it is a scroll it is not a tap: clearing the highlight here is what
                    // stops a flick from also committing whichever emoji it started on.
                    pressedRow = -1
                    pressedColumn = -1
                }
                if (dragging) {
                    scrollY = (scrollY + dy).coerceIn(0f, maxScroll)
                    downY = event.y
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (closePressed && event.y >= height - closeBarHeight()) {
                    onCloseListener?.onClose()
                } else if (!dragging) {
                    commitPressed()
                }
                closePressed = false
                pressedRow = -1
                pressedColumn = -1
                dragging = false
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                closePressed = false
                pressedRow = -1
                pressedColumn = -1
                dragging = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun locate(x: Float, y: Float) {
        pressedRow = -1
        if (y >= height - closeBarHeight()) return
        pressedColumn = -1
        var top = -scrollY
        for ((rowIndex, row) in rows.withIndex()) {
            val rowHeight = if (row.firstOrNull() is Cell.Header) headerHeight else cellSize
            if (y >= top && y < top + rowHeight) {
                if (row.firstOrNull() is Cell.Emoji) {
                    val column = (x / cellSize).toInt()
                    if (column in row.indices) {
                        pressedRow = rowIndex
                        pressedColumn = column
                    }
                }
                return
            }
            top += rowHeight
        }
    }

    private fun closeBarHeight(): Float = (cellSize * 0.72f).coerceAtLeast(44f)

    private fun commitPressed() {
        val row = rows.getOrNull(pressedRow) ?: return
        val cell = row.getOrNull(pressedColumn) as? Cell.Emoji ?: return
        onEmojiListener?.onEmoji(cell.value)
    }

    /**
     * Records a use and refreshes the recents row.
     *
     * Called by the host after it has decided the field permits personalised learning — see
     * [EmojiRecents] for why that decision does not live here.
     */
    fun noteUsed(emoji: String) {
        recents.record(emoji)
        rebuild()
    }
}
