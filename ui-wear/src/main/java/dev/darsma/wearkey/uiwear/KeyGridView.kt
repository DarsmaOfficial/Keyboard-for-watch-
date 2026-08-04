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

    private fun computeLayout() {
        keys.clear()
        if (width == 0 || height == 0) return

        val density = resources.displayMetrics.density
        // The composition strip is a sibling view above this one (KeyboardSurfaceView is a
        // vertical LinearLayout), so no vertical space is reserved here any more — this view
        // gets exactly the area it should fill.
        val gridTop = 2f * density
        val gridBottom = height.toFloat() - 2f * density
        val gridHeight = gridBottom - gridTop

        // 3 letter rows + 1 function row (space/backspace/enter).
        val rowHeight = gridHeight / 4f
        labelPaint.textSize = rowHeight * 0.4f

        for ((rowIndex, row) in letterRows.withIndex()) {
            val keyWidth = width.toFloat() / row.length
            val top = gridTop + rowIndex * rowHeight
            val bottom = top + rowHeight
            for ((colIndex, char) in row.withIndex()) {
                val left = colIndex * keyWidth
                val right = left + keyWidth
                keys.add(
                    Key(
                        KeyAction.Character(char),
                        char.toString(),
                        RectF(left + 2f, top + 2f, right - 2f, bottom - 2f)
                    )
                )
            }
        }

        // Function row: backspace | switch-language | space (wide) | enter.
        // Spec §5.5: an in-keyboard language key is required, not optional — opening system
        // settings to change language is unusable on a watch.
        val funcTop = gridTop + 3 * rowHeight
        val funcBottom = funcTop + rowHeight
        val backspaceWidth = width * 0.20f
        val switchLangWidth = width * 0.16f
        val enterWidth = width * 0.20f
        val spaceWidth = width - backspaceWidth - switchLangWidth - enterWidth

        var x = 0f
        keys.add(
            Key(KeyAction.Backspace, "⌫", RectF(x + 2f, funcTop + 2f, x + backspaceWidth - 2f, funcBottom - 2f))
        )
        x += backspaceWidth
        keys.add(
            Key(
                KeyAction.SwitchLanguage, switchLanguageLabel(),
                RectF(x + 2f, funcTop + 2f, x + switchLangWidth - 2f, funcBottom - 2f)
            )
        )
        x += switchLangWidth
        keys.add(
            Key(KeyAction.Space, "␣", RectF(x + 2f, funcTop + 2f, x + spaceWidth - 2f, funcBottom - 2f))
        )
        x += spaceWidth
        keys.add(
            Key(KeyAction.Enter, "⏎", RectF(x + 2f, funcTop + 2f, width - 2f, funcBottom - 2f))
        )
    }

    private fun switchLanguageLabel(): String = when (layout) {
        Layout.EN_US -> "RU" // shows the language you'll switch TO, matching common IME convention
        Layout.RU_RU -> "EN"
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        for (key in keys) {
            val paint = if (key === pressedKey) keyPressedPaint else keyPaint
            canvas.drawRoundRect(key.rect, 8f, 8f, paint)
            canvas.drawRoundRect(key.rect, 8f, 8f, keyBorderPaint)
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

    private fun keyAt(x: Float, y: Float): Key? = keys.firstOrNull { it.rect.contains(x, y) }

    override fun onDetachedFromWindow() {
        stopFrameTiming()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }
}
