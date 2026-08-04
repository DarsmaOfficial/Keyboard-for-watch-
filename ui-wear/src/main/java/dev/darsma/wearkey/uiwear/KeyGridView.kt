package dev.darsma.wearkey.uiwear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Custom View + Canvas key grid (spec §8.0 — no androidx.compose.* in this module).
 *
 * Phase 0 proved the two Wear OS entry points both reach this surface and measured frame time.
 * Phase 1 adds: actual key press handling (tap → character/action callback) and a functional
 * row of space/backspace/enter so a full type-and-see-it-composed loop works end to end.
 *
 * Deliberately NOT the final key grid — no circular hit-zone extension / bivariate Gaussian
 * touch model yet (spec §7.1, Phase 2), no press animation / spring physics yet (spec §8.0,
 * Phase 3). Geometry here is a plain rectangular grid, replaced in Phase 2.
 */
class KeyGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    sealed class KeyAction {
        data class Character(val char: Char) : KeyAction()
        object Space : KeyAction()
        object Backspace : KeyAction()
        object Enter : KeyAction()
        object SwitchLanguage : KeyAction()
    }

    fun interface OnKeyListener {
        fun onKey(action: KeyAction)
    }

    var onKeyListener: OnKeyListener? = null

    /**
     * Layout enum backing the in-keyboard language key (spec §5.5: "on a watch, opening system
     * settings to change language is unusable" — so a key here is required, not optional, even
     * though InputMethodSubtype is the system-level mechanism of record).
     */
    enum class Layout { EN_US, RU_RU }

    // Letter rows approximate QWERTY/ЙЦУКЕН — geometry replaced in Phase 2 with round-optimised
    // layout (spec §7.1). Row *shapes* differ between locales (Russian has an extra row char);
    // that's fine here, real geometry work happens in Phase 2 regardless of language.
    private val enRows = listOf(
        "QWERTYUIOP",
        "ASDFGHJKL",
        "ZXCVBNM"
    )
    private val ruRows = listOf(
        "ЙЦУКЕНГШЩЗ",
        "ФЫВАПРОЛДЖ",
        "ЯЧСМИТЬБЮ"
    )

    var layout: Layout = Layout.EN_US
        set(value) {
            if (field == value) return
            field = value
            computeLayout()
            invalidate()
        }

    private val letterRows: List<String>
        get() = when (layout) {
            Layout.EN_US -> enRows
            Layout.RU_RU -> ruRows
        }

    private data class Key(val action: KeyAction, val label: String, val rect: RectF)

    private val keys = mutableListOf<Key>()
    private var pressedKey: Key? = null

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1C1E")
        style = Paint.Style.FILL
    }

    private val keyPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3A3C")
        style = Paint.Style.FILL
    }

    private val keyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3A3C")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private val backgroundPaint = Paint().apply {
        color = Color.BLACK // AMOLED true-black per spec §8.0
        style = Paint.Style.FILL
    }

    // --- Frame-time instrumentation (Phase 0 exit criterion, kept for ongoing regression checks) ---
    private var frameTimingEnabled = false
    private var frameCount = 0
    private var droppedFrames = 0
    private var lastFrameNanos = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lastFrameNanos != 0L) {
                val deltaMs = (frameTimeNanos - lastFrameNanos) / 1_000_000.0
                frameCount++
                if (deltaMs > 16.6) droppedFrames++
            }
            lastFrameNanos = frameTimeNanos
            invalidate()
            if (frameTimingEnabled) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    fun startFrameTiming() {
        if (frameTimingEnabled) return
        frameTimingEnabled = true
        frameCount = 0
        droppedFrames = 0
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stopFrameTiming() {
        frameTimingEnabled = false
    }

    fun frameStats(): Pair<Int, Int> = frameCount to droppedFrames

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeLayout()
    }

    /**
     * Round-display layout (spec §7.1). A rectangular grid on a 466x466 circular screen clips
     * the outer keys of every row — Q/P, A/L, Z/M literally lose usable area to the bezel, which
     * is exactly the Fitts's-law failure the spec calls out.
     *
     * Fix: each row is fitted to the *chord* of the display circle at that row's vertical
     * centre, so keys never extend past the visible glass. Rows therefore get progressively
     * narrower toward the bottom of the screen, and every key stays fully tappable.
     *
     * Note this view is positioned in the LOWER part of the display (the keyboard occupies the
     * bottom ~62% so the app's real field stays visible), so the circle centre sits *above*
     * this view's own coordinate space — hence [circleCenterYInView] is negative.
     */
    private fun computeLayout() {
        keys.clear()
        if (width == 0 || height == 0) return

        val density = resources.displayMetrics.density
        val gridTop = 2f * density
        val gridBottom = height.toFloat() - 2f * density
        val gridHeight = gridBottom - gridTop

        // 3 letter rows + 1 function row.
        val rowHeight = gridHeight / 4f
        labelPaint.textSize = rowHeight * 0.40f

        val screenH = resources.displayMetrics.heightPixels.toFloat()
        val screenW = resources.displayMetrics.widthPixels.toFloat()
        val radius = minOf(screenW, screenH) / 2f
        // Where the display's circle centre lies relative to this view's top edge.
        val circleCenterYInView = radius - (screenH - height)
        val circleCenterX = width / 2f

        for ((rowIndex, row) in letterRows.withIndex()) {
            val top = gridTop + rowIndex * rowHeight
            val bottom = top + rowHeight
            // Use whichever edge of the row is closer to the bottom of the circle — that's the
            // narrower chord, and using it guarantees the whole key rect stays inside the glass.
            val yForChord = maxOf(abs(top - circleCenterYInView), abs(bottom - circleCenterYInView))
            val halfChord = chordHalfWidth(radius, yForChord)
            val rowLeft = (circleCenterX - halfChord).coerceAtLeast(0f) + KEY_EDGE_INSET_PX
            val rowRight = (circleCenterX + halfChord).coerceAtMost(width.toFloat()) - KEY_EDGE_INSET_PX
            val usableWidth = (rowRight - rowLeft).coerceAtLeast(1f)
            val keyWidth = usableWidth / row.length

            for ((colIndex, char) in row.withIndex()) {
                val left = rowLeft + colIndex * keyWidth
                val right = left + keyWidth
                keys.add(
                    Key(
                        KeyAction.Character(char),
                        char.toString(),
                        RectF(left + KEY_GAP_PX, top + KEY_GAP_PX, right - KEY_GAP_PX, bottom - KEY_GAP_PX)
                    )
                )
            }
        }

        // Function row: backspace | switch-language | space (wide) | enter.
        // Spec §5.5: an in-keyboard language key is required, not optional — opening system
        // settings to change language is unusable on a watch.
        val funcTop = gridTop + 3 * rowHeight
        val funcBottom = gridBottom
        val funcYForChord = maxOf(abs(funcTop - circleCenterYInView), abs(funcBottom - circleCenterYInView))
        val funcHalfChord = chordHalfWidth(radius, funcYForChord)
        val funcLeft = (circleCenterX - funcHalfChord).coerceAtLeast(0f) + KEY_EDGE_INSET_PX
        val funcRight = (circleCenterX + funcHalfChord).coerceAtMost(width.toFloat()) - KEY_EDGE_INSET_PX
        val funcWidth = (funcRight - funcLeft).coerceAtLeast(1f)

        val backspaceWidth = funcWidth * 0.22f
        val switchLangWidth = funcWidth * 0.18f
        val enterWidth = funcWidth * 0.22f
        val spaceWidth = funcWidth - backspaceWidth - switchLangWidth - enterWidth

        var x = funcLeft
        keys.add(
            Key(KeyAction.Backspace, "⌫",
                RectF(x + KEY_GAP_PX, funcTop + KEY_GAP_PX, x + backspaceWidth - KEY_GAP_PX, funcBottom - KEY_GAP_PX))
        )
        x += backspaceWidth
        keys.add(
            Key(KeyAction.SwitchLanguage, switchLanguageLabel(),
                RectF(x + KEY_GAP_PX, funcTop + KEY_GAP_PX, x + switchLangWidth - KEY_GAP_PX, funcBottom - KEY_GAP_PX))
        )
        x += switchLangWidth
        keys.add(
            Key(KeyAction.Space, "␣",
                RectF(x + KEY_GAP_PX, funcTop + KEY_GAP_PX, x + spaceWidth - KEY_GAP_PX, funcBottom - KEY_GAP_PX))
        )
        x += spaceWidth
        keys.add(
            Key(KeyAction.Enter, "⏎",
                RectF(x + KEY_GAP_PX, funcTop + KEY_GAP_PX, funcRight - KEY_GAP_PX, funcBottom - KEY_GAP_PX))
        )
    }

    /** Half-width of the horizontal chord of a circle of [radius] at vertical offset [dy]. */
    private fun chordHalfWidth(radius: Float, dy: Float): Float {
        val d = abs(dy).coerceAtMost(radius)
        return sqrt(radius * radius - d * d)
    }

    private fun switchLanguageLabel(): String = when (layout) {
        Layout.EN_US -> "RU" // shows the language you'll switch TO, matching common IME convention
        Layout.RU_RU -> "EN"
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        for (key in keys) {
            val paint = if (key === pressedKey) keyPressedPaint else keyPaint
            val radius = minOf(key.rect.width(), key.rect.height()) * 0.28f
            canvas.drawRoundRect(key.rect, radius, radius, paint)
            canvas.drawRoundRect(key.rect, radius, radius, keyBorderPaint)
            canvas.drawText(
                key.label,
                key.rect.centerX(),
                key.rect.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2,
                labelPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pressedKey = keyAt(event.x, event.y)
                invalidate()
                return pressedKey != null
            }
            MotionEvent.ACTION_MOVE -> {
                val current = keyAt(event.x, event.y)
                if (current !== pressedKey) {
                    pressedKey = current
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val released = pressedKey
                pressedKey = null
                invalidate()
                if (released != null) {
                    onKeyListener?.onKey(released.action)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedKey = null
                invalidate()
                return true
            }
        }
        return false
    }

    /**
     * Hit-testing is deliberately more forgiving than the drawn rect: a tap that lands in the
     * gap between keys, or just outside the row's chord near the bezel, resolves to the nearest
     * key centre instead of being swallowed. This is the cheap half of spec §7.1's
     * "extend outer-key hit regions to the physical display edge" — the full bivariate Gaussian
     * touch model lands in Phase 2 proper.
     */
    private fun keyAt(x: Float, y: Float): Key? {
        keys.firstOrNull { it.rect.contains(x, y) }?.let { return it }

        var best: Key? = null
        var bestDistSq = Float.MAX_VALUE
        for (key in keys) {
            val cx = key.rect.centerX()
            val cy = key.rect.centerY()
            // Only consider keys on roughly the same row, so a sloppy horizontal tap never
            // jumps a row vertically.
            if (abs(cy - y) > key.rect.height()) continue
            val dx = cx - x
            val dy = cy - y
            val distSq = dx * dx + dy * dy
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                best = key
            }
        }
        return best
    }

    override fun onDetachedFromWindow() {
        stopFrameTiming()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    companion object {
        /** Gap drawn between adjacent keys. Small — touch slop is handled in [keyAt] instead. */
        private const val KEY_GAP_PX = 3f
        /** Keeps the outermost keys clear of the physical bezel curve. */
        private const val KEY_EDGE_INSET_PX = 2f
    }
}
