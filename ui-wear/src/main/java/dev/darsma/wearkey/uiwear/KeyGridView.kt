package dev.darsma.wearkey.uiwear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View

/**
 * Phase 0 scaffold: a trivial custom View + Canvas key grid.
 *
 * Purpose right now is narrow — confirm the two Wear OS entry points (IME + LAUNCH_KEYBOARD
 * Activity, spec §4.5) both reach a rendering surface, and measure real frame time on the W5
 * chipset (spec Phase 0 exit criterion: >=95% of frames under 16.6 ms).
 *
 * Deliberately NOT the final key grid:
 *  - no circular hit-zone extension / bivariate Gaussian touch model (spec §7.1, Phase 2)
 *  - no per-key press animation / spring physics (spec §8.0, Phase 3)
 *  - keys are laid out as a plain rectangular grid, not tuned for the round bezel yet
 *
 * Rendering stays strictly View + Canvas, per spec §8.0 — no androidx.compose.* in this module.
 */
class KeyGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Rows approximate QWERTY layout — geometry will be replaced in Phase 2.
    private val rows = listOf(
        "QWERTYUIOP",
        "ASDFGHJKL",
        "ZXCVBNM"
    )

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1C1E")
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
        isFakeBoldText = false
    }

    private val backgroundPaint = Paint().apply {
        color = Color.BLACK // AMOLED true-black per spec §8.0
        style = Paint.Style.FILL
    }

    private val keyRect = RectF()

    // --- Frame-time instrumentation (Phase 0 exit criterion) ---
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

    /** Starts continuous invalidation + frame-time sampling for Phase 0 measurement. */
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

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        // Reserve top area for the composition strip (spec §5: Y 16dp-54dp) — Phase 0 just
        // leaves the space empty; EditorState + strip land in Phase 1.
        val density = resources.displayMetrics.density
        val stripBottomPx = 54f * density
        val gridTop = stripBottomPx + 8f * density
        val gridBottom = height.toFloat() - 8f * density
        val gridHeight = gridBottom - gridTop

        val rowHeight = gridHeight / rows.size
        labelPaint.textSize = rowHeight * 0.4f

        for ((rowIndex, row) in rows.withIndex()) {
            val keyWidth = width.toFloat() / row.length
            val top = gridTop + rowIndex * rowHeight
            val bottom = top + rowHeight
            for ((colIndex, char) in row.withIndex()) {
                val left = colIndex * keyWidth
                val right = left + keyWidth
                keyRect.set(left + 2f, top + 2f, right - 2f, bottom - 2f)
                canvas.drawRoundRect(keyRect, 8f, 8f, keyPaint)
                canvas.drawRoundRect(keyRect, 8f, 8f, keyBorderPaint)
                canvas.drawText(
                    char.toString(),
                    keyRect.centerX(),
                    keyRect.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2,
                    labelPaint
                )
            }
        }
    }

    override fun onDetachedFromWindow() {
        stopFrameTiming()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }
}
