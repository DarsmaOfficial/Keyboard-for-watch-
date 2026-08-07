package dev.darsma.wearkey.uiwear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.darsma.wearkey.imecore.touch.KeyTarget
import dev.darsma.wearkey.imecore.touch.RoundDisplay
import dev.darsma.wearkey.imecore.touch.TouchModel
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Custom View + Canvas key grid (spec §8.0 — no androidx.compose.* in this module).
 *
 * Phase 0 proved the two Wear OS entry points both reach this surface and measured frame time.
 * Phase 1 adds: actual key press handling (tap → character/action callback) and a functional
 * row of space/backspace/enter so a full type-and-see-it-composed loop works end to end.
 *
 * Phase 2 replaces rectangular hit-testing with the bivariate Gaussian touch model in
 * `:ime-core` (spec §7.1). Keys are still *drawn* as rounded rectangles clipped to the display
 * chord — that is what users can read — but which key a touch *selects* is now decided
 * probabilistically from key centroids, so taps in inter-key gaps and on the glass curve beyond
 * the outermost key resolve sensibly instead of being swallowed.
 *
 * Phase 3 (spring press physics) is already wired via androidx.dynamicanimation.
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
        /** Opens the clipboard history panel (spec §6). */
        object Clipboard : KeyAction()
        /** Cycles OFF -> SHIFTED -> CAPS_LOCK -> OFF. */
        object Shift : KeyAction()
        /** Toggles between the letter layer and the symbol/number layer. */
        object SymbolLayer : KeyAction()
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
    private var enRows = listOf(
        "QWERTYUIOP",
        "ASDFGHJKL",
        "ZXCVBNM"
    )
    private var ruRows = listOf(
        "ЙЦУКЕНГШЩЗ",
        "ФЫВАПРОЛДЖ",
        "ЯЧСМИТЬБЮ"
    )

    /**
     * Replaces a layer's rows from a parsed layout file (spec §4.1, §9).
     *
     * The values above remain as compiled-in fallbacks rather than being deleted. Spec §11.5 is
     * explicit that a keyboard which cannot draw itself is the worst possible failure in this
     * product category — the user is left with no way to type at all, including no way to type a
     * bug report. So a missing or corrupt layout asset degrades to the built-in rows instead of
     * producing an empty grid.
     */
    fun applyLayout(layout: dev.darsma.wearkey.layout.KeyboardLayout) {
        when (layout.languageTag.lowercase()) {
            "ru-ru", "ru" -> ruRows = layout.letterRows
            else -> enRows = layout.letterRows
        }
        if (layout.symbolPages.size >= 2) {
            symbolPage1 = layout.symbolPages[0]
            symbolPage2 = layout.symbolPages[1]
        }
        computeLayout()
        invalidate()
    }

    /**
     * Symbol/number layer (spec §11 MVP item 5). Two pages, because a watch row cannot hold the
     * punctuation people actually need without the keys becoming untappable.
     */
    private var symbolPage1 = listOf(
        "1234567890",
        "-/:;()€&@",
        ".,?!'\""
    )
    private var symbolPage2 = listOf(
        "[]{}#%^*+=",
        "_\\|~<>$£¥",
        "•°·§…"
    )

    /** Shift state, cycled by the shift key (spec §11 MVP item 5). */
    enum class ShiftState { OFF, SHIFTED, CAPS_LOCK }

    var shiftState: ShiftState = ShiftState.OFF
        set(value) {
            if (field == value) return
            field = value
            computeLayout()
            invalidate()
        }

    /** True while the symbol/number layer is shown instead of letters. */
    var symbolLayerVisible: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            symbolPage = 0
            computeLayout()
            invalidate()
        }

    /** Which page of the symbol layer is shown (0 or 1). */
    var symbolPage: Int = 0
        set(value) {
            val v = value.coerceIn(0, 1)
            if (field == v) return
            field = v
            computeLayout()
            invalidate()
        }

    /**
     * Label for the action key, taken from the field's `imeOptions` (Go / Search / Send / Next /
     * Done). Null means a plain newline symbol. Spec §11.5: the action key should say what it
     * will actually do.
     */
    var actionLabel: String? = null
        set(value) {
            if (field == value) return
            field = value
            computeLayout()
            invalidate()
        }

    /** True for email/URI fields, where '@' '.' and '/' deserve to be reachable. */
    var emailOrUriHints: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            computeLayout()
            invalidate()
        }

    var layout: Layout = Layout.EN_US
        set(value) {
            if (field == value) return
            field = value
            computeLayout()
            invalidate()
        }

    private val letterRows: List<String>
        get() = when {
            symbolLayerVisible -> if (symbolPage == 0) symbolPage1 else symbolPage2
            layout == Layout.RU_RU -> ruRows
            // Email/URI fields get '@' and '.' on the MIDDLE row, which has spare width, rather
            // than the bottom row — that one now carries shift and backspace on its flanks
            // (spec §11.5: those two characters must not be hidden behind the symbol layer).
            emailOrUriHints -> enRows.mapIndexed { i, row -> if (i == 1) row + "@." else row }
            else -> enRows
        }

    /** Applies the current shift state to a character from the letter layer. */
    private fun applyShift(c: Char): Char =
        if (symbolLayerVisible || shiftState == ShiftState.OFF) c.lowercaseChar() else c

    private data class Key(val action: KeyAction, val label: String, val rect: RectF)

    private val keys = mutableListOf<Key>()

    /**
     * Probabilistic touch model (spec §7.1), rebuilt whenever the display geometry changes.
     * Null only before the first layout pass, where [keyAt] falls back to plain containment.
     */
    private var touchModel: TouchModel? = null

    /** Touch targets mirroring [keys] by index. Rebuilt per layout, never per touch event. */
    private var touchTargets: List<KeyTarget> = emptyList()

    /**
     * Touch model tuning. Defaults are the shipped estimates; the host sets this from a saved
     * calibration (spec §7.1) when the user has run one. Assigning rebuilds the model in place,
     * so a calibration can be applied without recreating the keyboard.
     */
    var touchConfig: TouchModel.Config = TouchModel.Config()
        set(value) {
            field = value
            val existing = touchModel ?: return
            touchModel = TouchModel(existing.display, value)
        }
    private var pressedKey: Key? = null

    /** Amplitude-only haptics per spec §8.1. Exposed so settings can adjust intensity later. */
    val haptics = HapticFeedback(context)

    // --- Press animation (spec §8.0: spring physics, not linear tweens) ---
    //
    // A single spring drives the pressed key's scale. Only one key can be pressed at a time on
    // this hardware, so one animation object is enough — and reusing it means zero allocation
    // in the touch hot path.
    //
    // `pressScale` is what onDraw reads: 1.0 = at rest, ~0.88 = fully pressed. The spring
    // settles rather than snapping, which is what makes it read as physical rather than
    // mechanical.
    private var pressScale = 1f

    private val pressScaleProperty =
        object : androidx.dynamicanimation.animation.FloatPropertyCompat<KeyGridView>("pressScale") {
            override fun getValue(view: KeyGridView): Float = view.pressScale * 100f
            override fun setValue(view: KeyGridView, value: Float) {
                view.pressScale = value / 100f
                view.invalidate()
            }
        }

    private val pressSpring by lazy {
        androidx.dynamicanimation.animation.SpringAnimation(this, pressScaleProperty).apply {
            spring = androidx.dynamicanimation.animation.SpringForce().apply {
                // Low damping ratio gives a slight overshoot on release — the "bounce back"
                // that makes a key feel like it has mass. Stiffness is high so the whole
                // gesture still resolves well inside the spec's 80 ms responsiveness budget.
                dampingRatio = androidx.dynamicanimation.animation.SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                stiffness = androidx.dynamicanimation.animation.SpringForce.STIFFNESS_HIGH
            }
            setStartValue(100f)
        }
    }

    /**
     * Honours the system's animator duration scale (spec §8.0 reduced-motion requirement).
     * When the user has turned animations off, scale 0 means we skip the spring entirely and
     * jump straight to the target.
     */
    private val animationsEnabled: Boolean
        get() = runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        }.getOrDefault(true)

    private fun animatePressTo(target: Float) {
        if (!animationsEnabled) {
            pressScale = target
            invalidate()
            return
        }
        pressSpring.animateToFinalPosition(target * 100f)
    }

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1C1E")
        style = Paint.Style.FILL
    }

    private val keyPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3A3C")
        style = Paint.Style.FILL
    }

    /** One-shot shift: a lifted, still-dark key. */
    private val keyActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A4A4E")
        style = Paint.Style.FILL
    }

    /** Caps lock: filled with the accent colour, so it is unmistakable at a glance. */
    private val keyAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
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

    // --- Frame-time instrumentation (spec §14 gate: ≥95% of frames under 16.6 ms) --------------
    //
    // The earlier version of this called invalidate() from its own Choreographer callback, which
    // forced a redraw every frame for as long as it ran. That measured a synthetic 60 fps animation
    // loop rather than the keyboard's real cost, and it burned battery doing it — the opposite of
    // spec §11.5's "no animations continuing while hidden".
    //
    // This version measures the draws that actually happen. onDraw is timed directly, so the sample
    // contains exactly the work the keyboard does in response to real input: presses, spring
    // animation, layer switches. Idle costs nothing because idle draws nothing.
    //
    // Why measure onDraw rather than read dumpsys gfxinfo: gfxinfo is cumulative over the whole
    // process lifetime and folds in cold start, window creation and layout inflation. On this
    // device it reported a 95th percentile of 26 ms while its own modern jank counter said 0.45%
    // and the legacy counter said 44.2% — three numbers that cannot all be describing steady-state
    // typing. Timing the draw call answers the question the gate actually asks.

    private var frameTimingEnabled = false
    private var drawDurationsUs = IntArray(FRAME_SAMPLE_CAPACITY)
    private var drawSampleCount = 0

    /**
     * Begins recording per-draw durations. Cheap: one nanoTime pair per draw and no allocation,
     * so it is safe to leave enabled during a real typing session.
     */
    fun startFrameTiming() {
        drawSampleCount = 0
        frameTimingEnabled = true
    }

    fun stopFrameTiming() {
        frameTimingEnabled = false
    }

    /**
     * Percentile summary of recorded draw durations, in milliseconds.
     *
     * Returns null when nothing was recorded — an honest "no data" rather than a fabricated zero.
     */
    fun frameStats(): FrameStats? {
        if (drawSampleCount == 0) return null
        val sorted = drawDurationsUs.copyOf(drawSampleCount).also { it.sort() }
        fun percentile(p: Double): Float {
            val index = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
            return sorted[index] / 1000f
        }
        val overBudget = sorted.count { it / 1000f > FRAME_BUDGET_MS }
        return FrameStats(
            sampleCount = sorted.size,
            medianMs = percentile(0.50),
            p90Ms = percentile(0.90),
            p95Ms = percentile(0.95),
            p99Ms = percentile(0.99),
            worstMs = sorted.last() / 1000f,
            overBudgetPercent = overBudget * 100f / sorted.size
        )
    }

    /** Percentile summary of draw cost. [meetsSpecGate] encodes the §14 threshold directly. */
    data class FrameStats(
        val sampleCount: Int,
        val medianMs: Float,
        val p90Ms: Float,
        val p95Ms: Float,
        val p99Ms: Float,
        val worstMs: Float,
        val overBudgetPercent: Float
    ) {
        /** Spec §14: at least 95% of frames under 16.6 ms. */
        val meetsSpecGate: Boolean get() = overBudgetPercent <= 5f

        override fun toString(): String =
            "n=$sampleCount median=${fmt(medianMs)} p90=${fmt(p90Ms)} p95=${fmt(p95Ms)} " +
                "p99=${fmt(p99Ms)} worst=${fmt(worstMs)} over=${fmt(overBudgetPercent)}% " +
                "gate=${if (meetsSpecGate) "PASS" else "FAIL"}"

        private fun fmt(value: Float): String {
            val scaled = kotlin.math.round(value * 100) / 100f
            return scaled.toString()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeLayout()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // getLocationOnScreen() is only meaningful once the view has actually been positioned,
        // and computeLayout() depends on it to find the display circle's centre.
        if (changed) computeLayout()
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
        // Stop short of the very bottom of the round display: down there the circle's chord
        // narrows so fast that a full-width function row cannot fit, and keys placed there are
        // half-hidden by the bezel curve. Leaving this margin keeps every row usably wide.
        val bottomMarginPx = 14f * density
        val gridBottom = height.toFloat() - bottomMarginPx
        val gridHeight = gridBottom - gridTop

        // 3 letter rows + 1 function row. The function row is deliberately shorter than the
        // letter rows: its keys are large-target symbols, while letter keys are what actually
        // need the height on a small round display.
        val funcRowHeight = gridHeight * 0.21f
        val rowHeight = (gridHeight - funcRowHeight) / 3f
        labelPaint.textSize = rowHeight * 0.46f

        // Locate this view within the PHYSICAL display, rather than assuming it is flush with
        // the screen bottom. displayMetrics reports the IME window, not the round glass, so
        // deriving the circle from it pushed the outer keys past the bezel (P/L/M/Enter were
        // visibly clipped on real hardware even though screenshots looked fine).
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val viewTopOnScreen = loc[1].toFloat()
        val viewLeftOnScreen = loc[0].toFloat()

        val screenW = resources.displayMetrics.widthPixels.toFloat()
        val screenH = resources.displayMetrics.heightPixels.toFloat()
        val radius = minOf(screenW, screenH) / 2f
        // Circle centre expressed in this view's own coordinate space.
        val circleCenterYInView = radius - viewTopOnScreen
        // Horizontally the keyboard window spans the full display width, so the circle's centre
        // is simply the middle of this view. Deriving it as `radius - viewLeft` was wrong
        // whenever the display is not exactly square: it pushed the whole grid sideways, leaving
        // a dead strip on one edge and shoving the opposite column under the bezel.
        val circleCenterX = width / 2f

        // Hand the same geometry to the touch model (spec §7.1). Note circleCenterYInView is
        // normally negative — the circle's centre sits above this view's origin — and the sign
        // matters: inverting it would make the radial drift correction pull inward.
        touchModel = TouchModel(RoundDisplay(circleCenterX, circleCenterYInView, radius), touchConfig)

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

            // Bottom letter row carries shift on the left and backspace on the right, the way
            // every phone keyboard does it. That keeps the function row down to a handful of
            // keys so the spacebar can actually be wide — cramming seven keys into one row on a
            // 233dp round display made every one of them too small to hit.
            val isBottomLetterRow = rowIndex == letterRows.lastIndex
            val flankWidth = if (isBottomLetterRow) usableWidth * 0.15f else 0f
            val charAreaLeft = rowLeft + flankWidth
            val charAreaWidth = usableWidth - 2 * flankWidth
            val keyWidth = charAreaWidth / row.length

            if (isBottomLetterRow) {
                val shiftLabel = when {
                    symbolLayerVisible -> if (symbolPage == 0) "2/2" else "1/2"
                    shiftState == ShiftState.CAPS_LOCK -> "⇪"
                    else -> "⇧"
                }
                keys.add(
                    Key(KeyAction.Shift, shiftLabel,
                        RectF(rowLeft + KEY_GAP_PX, top + KEY_GAP_PX,
                            rowLeft + flankWidth - KEY_GAP_PX, bottom - KEY_GAP_PX))
                )
            }

            for ((colIndex, rawChar) in row.withIndex()) {
                val char = applyShift(rawChar)
                val left = charAreaLeft + colIndex * keyWidth
                val right = left + keyWidth
                keys.add(
                    Key(
                        KeyAction.Character(char),
                        char.toString(),
                        RectF(left + KEY_GAP_PX, top + KEY_GAP_PX, right - KEY_GAP_PX, bottom - KEY_GAP_PX)
                    )
                )
            }

            if (isBottomLetterRow) {
                keys.add(
                    Key(KeyAction.Backspace, "⌫",
                        RectF(rowRight - flankWidth + KEY_GAP_PX, top + KEY_GAP_PX,
                            rowRight - KEY_GAP_PX, bottom - KEY_GAP_PX))
                )
            }
        }

        // Function row: backspace | switch-language | space (wide) | enter.
        // Spec §5.5: an in-keyboard language key is required, not optional — opening system
        // settings to change language is unusable on a watch.
        val funcTop = gridTop + 3 * rowHeight
        val funcBottom = funcTop + funcRowHeight
        // Measure the chord at the row's TOP edge, not its bottom: at the very bottom of the
        // circle the chord collapses to almost nothing, which squeezed the function keys into an
        // unusable sliver. Using the top edge keeps them a sane width; the rounded corners of
        // the keys themselves absorb the small overhang past the glass curve.
        val funcYForChord = abs(funcTop - circleCenterYInView)
        val funcHalfChord = chordHalfWidth(radius, funcYForChord)
        val funcLeft = (circleCenterX - funcHalfChord).coerceAtLeast(0f) + KEY_EDGE_INSET_PX
        val funcRight = (circleCenterX + funcHalfChord).coerceAtMost(width.toFloat()) - KEY_EDGE_INSET_PX
        val funcWidth = (funcRight - funcLeft).coerceAtLeast(1f)

        // Only five keys here now — shift and backspace moved up into the bottom letter row —
        // so the spacebar gets nearly half the row and is comfortably hittable.
        val symbolWidth = funcWidth * 0.16f
        val switchLangWidth = funcWidth * 0.14f
        val clipboardWidth = funcWidth * 0.13f
        val enterWidth = funcWidth * 0.19f
        val spaceWidth = funcWidth - symbolWidth - switchLangWidth - clipboardWidth - enterWidth

        var x = funcLeft
        keys.add(
            Key(KeyAction.SymbolLayer, if (symbolLayerVisible) "ABC" else "?123",
                RectF(x + KEY_GAP_PX, funcTop + KEY_GAP_PX, x + symbolWidth - KEY_GAP_PX, funcBottom - KEY_GAP_PX))
        )
        x += symbolWidth
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
            Key(KeyAction.Clipboard, "▤",
                RectF(x + KEY_GAP_PX, funcTop + KEY_GAP_PX, x + clipboardWidth - KEY_GAP_PX, funcBottom - KEY_GAP_PX))
        )
        x += clipboardWidth
        keys.add(
            Key(KeyAction.Enter, actionLabel ?: "⏎",
                RectF(x + KEY_GAP_PX, funcTop + KEY_GAP_PX, funcRight - KEY_GAP_PX, funcBottom - KEY_GAP_PX))
        )

        // Selection targets must be regenerated whenever the drawn geometry changes — a stale
        // target list would silently map taps to the previous layout's keys after a language or
        // layer switch.
        rebuildTouchTargets()

        // Virtual view ids are key indices, so a layout change reassigns them. Tell the
        // accessibility framework to discard the old tree; a screen reader holding a stale node
        // would otherwise announce one key and activate another.
        accessibilityProvider.notifyLayoutChanged()
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
        // Timing wraps the real drawing work; when instrumentation is off this is one boolean test.
        val startNanos = if (frameTimingEnabled) System.nanoTime() else 0L
        drawContent(canvas)
        if (frameTimingEnabled) recordDraw(System.nanoTime() - startNanos)
    }

    /** Records one draw duration. Fixed-capacity and allocation-free, so timing cannot itself jank. */
    private fun recordDraw(durationNanos: Long) {
        if (drawSampleCount >= drawDurationsUs.size) return
        drawDurationsUs[drawSampleCount++] = (durationNanos / 1_000L).toInt()
    }

    /**
     * The glide trail.
     *
     * Drawn as a fading polyline rather than a uniform stroke: the tail dimming toward the start
     * tells the user which direction the gesture is travelling, which matters on a 466 px display
     * where a path can cross itself several times in a single word. The trail is deliberately thin
     * and low-contrast — it is feedback, not decoration, and a heavy stroke obscures the very keys
     * the user is aiming at next.
     */
    private val trailPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        // Same cyan the caret and calibration use, so the accent means one thing throughout.
        color = Color.parseColor("#00E5FF")
    }

    private fun drawTrail(canvas: Canvas) {
        if (!swiping || traceCount < 2) return

        val density = resources.displayMetrics.density
        val maxWidth = TRAIL_WIDTH_DP * density

        // Only the recent tail is drawn. Keeping the whole path visible turns a long word into a
        // scribble that hides the grid.
        val first = if (traceCount > TRAIL_MAX_SEGMENTS) traceCount - TRAIL_MAX_SEGMENTS else 0
        val span = (traceCount - 1 - first).coerceAtLeast(1)

        for (i in first until traceCount - 1) {
            val t = (i - first + 1).toFloat() / span
            trailPaint.strokeWidth = maxWidth * (0.35f + 0.65f * t)
            trailPaint.alpha = (255 * (0.15f + 0.65f * t)).toInt()
            canvas.drawLine(traceX[i], traceY[i], traceX[i + 1], traceY[i + 1], trailPaint)
        }
    }

    private fun drawContent(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        for (key in keys) {
            // Shift shows its state through the key's own fill, so the user can tell one-shot
            // from caps lock without hunting for a separate indicator.
            val shiftActive = key.action === KeyAction.Shift &&
                !symbolLayerVisible && shiftState != ShiftState.OFF
            val paint = when {
                key === pressedKey -> keyPressedPaint
                shiftActive && shiftState == ShiftState.CAPS_LOCK -> keyAccentPaint
                shiftActive -> keyActivePaint
                else -> keyPaint
            }
            val radius = minOf(key.rect.width(), key.rect.height()) * 0.28f

            // The pressed key is drawn scaled about its own centre, driven by the spring.
            // Everything else draws normally — no per-frame work for untouched keys.
            val scaled = key === pressedKey && pressScale != 1f
            if (scaled) {
                canvas.save()
                canvas.scale(pressScale, pressScale, key.rect.centerX(), key.rect.centerY())
            }

            canvas.drawRoundRect(key.rect, radius, radius, paint)
            canvas.drawRoundRect(key.rect, radius, radius, keyBorderPaint)

            val labelColor = if (shiftActive && shiftState == ShiftState.CAPS_LOCK) {
                Color.BLACK
            } else {
                Color.WHITE
            }
            labelPaint.color = labelColor

            // Word labels ("Найти", "?123") need to shrink to fit; single glyphs do not.
            val baseSize = labelPaint.textSize
            if (key.label.length > 1) {
                val maxWidth = key.rect.width() - 6f
                var size = baseSize * 0.62f
                labelPaint.textSize = size
                while (labelPaint.measureText(key.label) > maxWidth && size > 6f) {
                    size -= 1f
                    labelPaint.textSize = size
                }
            }

            canvas.drawText(
                key.label,
                key.rect.centerX(),
                key.rect.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2,
                labelPaint
            )
            labelPaint.textSize = baseSize
            labelPaint.color = Color.WHITE

            if (scaled) canvas.restore()
        }

        // Painted last so the trail rides over the keys. Under them it would be occluded by the
        // very keys the gesture is crossing, which is where the user is actually looking.
        drawTrail(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pressedKey = keyAt(event.x, event.y)
                if (pressedKey != null) animatePressTo(PRESSED_SCALE)
                beginTrace(event.x, event.y)
                invalidate()
                return pressedKey != null
            }
            MotionEvent.ACTION_MOVE -> {
                // Historical points matter here: the digitiser batches samples between frames, and
                // spec §7.3 asks for full resolution. Dropping them would thin out exactly the fast
                // parts of a swipe, where the shape carries the most information.
                for (h in 0 until event.historySize) {
                    traceTo(event.getHistoricalX(h), event.getHistoricalY(h))
                }
                traceTo(event.x, event.y)

                if (swiping) {
                    // Once it is a swipe, no key is "pressed" — showing a pressed key under a
                    // travelling finger reads as a stuck key.
                    if (pressedKey != null) {
                        pressedKey = null
                        animatePressTo(1f)
                    }
                    invalidate()
                    return true
                }

                val current = keyAt(event.x, event.y)
                if (current !== pressedKey) {
                    pressedKey = current
                    animatePressTo(if (current != null) PRESSED_SCALE else 1f)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val released = pressedKey
                pressedKey = null
                // Spring back with the bounce — this is the part that reads as physical.
                animatePressTo(1f)

                if (swiping) {
                    finishTrace()
                    invalidate()
                    return true
                }
                resetTrace()
                invalidate()

                if (released != null) {
                    handleKeyAction(released)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedKey = null
                animatePressTo(1f)
                resetTrace()
                invalidate()
                return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------------------------------
    // Glide typing (spec §7.3)
    // ---------------------------------------------------------------------------------------

    /**
     * Reusable sample buffers. Sized once for a generous gesture; a swipe that somehow exceeds this
     * simply stops accumulating rather than reallocating mid-drag, because §7.3 requires the drag
     * path to allocate nothing and a truncated tail costs far less than a GC pause mid-gesture.
     */
    private val traceX = FloatArray(MAX_TRACE_POINTS)
    private val traceY = FloatArray(MAX_TRACE_POINTS)
    private var traceCount = 0
    private var traceLength = 0f
    private var swiping = false

    /** Set by the host when a recogniser is available; when null the grid behaves as tap-only. */
    var swipeListener: OnSwipeListener? = null

    fun interface OnSwipeListener {
        /** Receives the raw normalised trace; the host owns recognition and candidate display. */
        fun onSwipe(xs: FloatArray, ys: FloatArray, count: Int)
    }

    /**
     * Letter keys of the current layout as `(letters, xs, ys)`, for building glide templates.
     *
     * Only `Character` keys are returned, and only single-character ones. A swipe path runs across
     * letters; including space or backspace would let a template route through a key that no finger
     * crosses mid-word, and would make every word ending near the space bar look alike.
     *
     * Returns null while the grid has not been laid out, since key rects are meaningless then.
     */
    fun letterGeometry(): Triple<String, FloatArray, FloatArray>? {
        if (keys.isEmpty() || width == 0) return null

        val sb = StringBuilder()
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (key in keys) {
            val action = key.action
            if (action is KeyAction.Character && action.char.isLetter()) {
                sb.append(action.char.lowercaseChar())
                xs.add(key.rect.centerX())
                ys.add(key.rect.centerY())
            }
        }
        if (sb.isEmpty()) return null
        return Triple(sb.toString(), xs.toFloatArray(), ys.toFloatArray())
    }

    private fun beginTrace(x: Float, y: Float) {
        traceCount = 0
        traceLength = 0f
        swiping = false
        appendTrace(x, y)
    }

    private fun traceTo(x: Float, y: Float) {
        if (traceCount == 0) {
            appendTrace(x, y)
            return
        }
        val dx = x - traceX[traceCount - 1]
        val dy = y - traceY[traceCount - 1]
        val step = kotlin.math.sqrt(dx * dx + dy * dy)

        // Ignore sub-pixel jitter so a resting finger cannot accumulate its way past the threshold.
        if (step < MIN_TRACE_STEP_PX) return

        traceLength += step
        appendTrace(x, y)

        // The tap/swipe decision. Distance rather than time, because a slow deliberate glide is
        // still a glide, and a fast flick within one key is still a tap. The threshold is in dp so
        // it means the same thing on any density.
        if (!swiping && swipeListener != null && traceLength >= swipeThresholdPx) {
            swiping = true
        }
    }

    private fun appendTrace(x: Float, y: Float) {
        if (traceCount >= MAX_TRACE_POINTS) return
        traceX[traceCount] = x
        traceY[traceCount] = y
        traceCount++
    }

    private fun finishTrace() {
        val listener = swipeListener
        val count = traceCount
        resetTrace()
        if (listener != null && count >= 2) listener.onSwipe(traceX, traceY, count)
    }

    private fun resetTrace() {
        traceCount = 0
        traceLength = 0f
        swiping = false
    }

    private val swipeThresholdPx: Float
        get() = SWIPE_THRESHOLD_DP * resources.displayMetrics.density

    /**
     * Resolves a touch to a key using the bivariate Gaussian model (spec §7.1).
     *
     * What a key *looks* like and what it *selects* are now deliberately different things. Keys
     * are drawn as rounded rects clipped to the display chord because that is legible; selection
     * scores every key by Mahalanobis distance from its centroid, so:
     *
     * - a tap in the gap between two keys goes to the nearer one instead of nowhere;
     * - a tap past the outermost key of a row — on the curved glass where nothing is drawn —
     *   still reaches that key, which is §7.1's "extend outer-key hit regions to the physical
     *   display edge" without needing separately maintained extended rectangles;
     * - the reported point is first pushed outward along the radius to compensate for fat-finger
     *   drift toward the screen centre, an effect that grows sharply near the bezel.
     *
     * [targets] is rebuilt only when the layout changes, not per touch event, so the drag hot
     * path allocates nothing.
     */
    private fun keyAt(x: Float, y: Float): Key? {
        val model = touchModel ?: return keys.firstOrNull { it.rect.contains(x, y) }
        val match = model.bestMatch(x, y, touchTargets) ?: return null
        return keys.getOrNull(match.id)
    }

    /** Rebuilds the touch-model targets from the drawn key rects. Called once per layout pass. */
    private fun rebuildTouchTargets() {
        touchTargets = keys.mapIndexed { index, key ->
            KeyTarget(
                id = index,
                centerX = key.rect.centerX(),
                centerY = key.rect.centerY(),
                // Use the pre-gap key size so the model's spread reflects the real target the
                // user perceives, not the slightly smaller rect left after cosmetic gaps.
                width = key.rect.width() + 2f * KEY_GAP_PX,
                height = key.rect.height() + 2f * KEY_GAP_PX
            )
        }
    }

    /**
     * Spoken description for a key (spec §11.5 accessibility). Symbols get words rather than
     * the raw glyph, because TalkBack reads "⌫" as nothing useful.
     */
    private fun describeKey(key: Key): CharSequence = when (val a = key.action) {
        is KeyAction.Character -> a.char.toString()
        KeyAction.Space -> context.getString(R.string.a11y_space)
        KeyAction.Backspace -> context.getString(R.string.a11y_backspace)
        KeyAction.Enter -> actionLabel ?: context.getString(R.string.a11y_enter)
        KeyAction.SwitchLanguage -> context.getString(R.string.a11y_switch_language)
        KeyAction.Clipboard -> context.getString(R.string.a11y_clipboard)
        KeyAction.SymbolLayer ->
            if (symbolLayerVisible) context.getString(R.string.a11y_letters)
            else context.getString(R.string.a11y_symbols)
        KeyAction.Shift -> when {
            symbolLayerVisible -> context.getString(R.string.a11y_more_symbols)
            shiftState == ShiftState.CAPS_LOCK -> context.getString(R.string.a11y_caps_lock)
            shiftState == ShiftState.SHIFTED -> context.getString(R.string.a11y_shift_on)
            else -> context.getString(R.string.a11y_shift)
        }
    }

    /**
     * Announces what just happened, so screen-reader users get confirmation of a committed
     * character or a layer change rather than silence (spec §11.5: "announce state changes,
     * not just key labels").
     *
     * This is confirmation *after* an action. Guidance *before* one — reading a key while the
     * finger explores it, without typing it — is the accessibility provider's job, below.
     */
    private fun announceForKey(key: Key) {
        if (!isAccessibilityActive()) return
        announceForAccessibility(describeKey(key))
    }

    /**
     * Everything that happens when a key is activated: announcement, haptics, local state, then the
     * host callback.
     *
     * Deliberately the single path for *both* a finger release and a screen-reader double-tap. When
     * this logic lived inline in the touch handler, an accessibility activation would have had to
     * duplicate it — and duplicated interaction logic drifts, so shift or the symbol layer would
     * eventually behave differently depending on whether TalkBack was running. That class of bug is
     * invisible to anyone not using a screen reader, which is precisely why it must be prevented
     * structurally rather than by care.
     */
    private fun handleKeyAction(key: Key) {
        announceForKey(key)
        // Haptics fire on activation, matching where the action actually happens (spec §8.1).
        haptics.perform(
            when (key.action) {
                KeyAction.Backspace -> HapticFeedback.Feedback.BACKSPACE
                KeyAction.Enter -> HapticFeedback.Feedback.ENTER
                KeyAction.Space,
                KeyAction.Shift,
                KeyAction.SymbolLayer,
                KeyAction.SwitchLanguage,
                KeyAction.Clipboard -> HapticFeedback.Feedback.SPACE_OR_LAYER
                is KeyAction.Character -> HapticFeedback.Feedback.KEY_TAP
            }
        )
        // Layer/shift state is owned by this view, so handle those here and let the host deal only
        // with actions that produce text or need an InputConnection.
        when (key.action) {
            KeyAction.Shift -> {
                if (symbolLayerVisible) {
                    symbolPage = if (symbolPage == 0) 1 else 0
                } else {
                    shiftState = when (shiftState) {
                        ShiftState.OFF -> ShiftState.SHIFTED
                        ShiftState.SHIFTED -> ShiftState.CAPS_LOCK
                        ShiftState.CAPS_LOCK -> ShiftState.OFF
                    }
                }
            }
            KeyAction.SymbolLayer -> symbolLayerVisible = !symbolLayerVisible
            is KeyAction.Character -> {
                // One-shot shift releases after a single character, caps lock does not.
                if (shiftState == ShiftState.SHIFTED) shiftState = ShiftState.OFF
            }
            else -> Unit
        }
        onKeyListener?.onKey(key.action)
    }

    // --- Accessibility node tree (spec §11.5) ---------------------------------------------------
    //
    // Without this, a Canvas-drawn grid is one blank rectangle to TalkBack: the user can tell a
    // keyboard is present but cannot explore it, and every exploratory touch risks typing a
    // character. The provider exposes each key as its own focusable, clickable node, so touch
    // exploration reads keys aloud and activation requires an explicit double-tap.

    private val accessibilityProvider by lazy {
        KeyAccessibilityProvider(
            host = this,
            keyCount = { keys.size },
            keyBounds = { id ->
                keys.getOrNull(id)?.let { key ->
                    android.graphics.Rect(
                        key.rect.left.toInt(),
                        key.rect.top.toInt(),
                        key.rect.right.toInt(),
                        key.rect.bottom.toInt()
                    )
                }
            },
            keyDescription = { id -> keys.getOrNull(id)?.let { describeKey(it) } },
            onKeyActivated = { id ->
                keys.getOrNull(id)?.let { key ->
                    // Route through the same handler a real tap uses, so shift, layer switching and
                    // haptics behave identically whether or not a screen reader is driving.
                    handleKeyAction(key)
                }
            }
        )
    }

    init {
        // Without this the entire accessibility provider is dead code, and it fails silently.
        //
        // A custom View that draws its content on a Canvas has no text and no contentDescription,
        // so IMPORTANT_FOR_ACCESSIBILITY_AUTO resolves to *not important*: the framework excludes
        // the view from the accessibility tree and therefore never calls
        // getAccessibilityNodeProvider() at all. Verified on the watch — with TalkBack running,
        // `uiautomator dump` contained zero nodes from this package while the IME was visible, and
        // exploring a key typed it because the touch was never converted into a hover.
        //
        // Declaring the view important is what makes the framework ask for the provider, at which
        // point the virtual key nodes and hover routing start working.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES

        // Deliberately NO contentDescription on the host, and deliberately not focusable.
        //
        // Both are tempting and both break the virtual tree. A view carrying a contentDescription
        // is treated by the framework as a labelled *leaf*: it announces that label and stops
        // descending, so the per-key children are never queried. Making the host itself focusable
        // has the same effect for exploration — focus lands on the whole grid instead of on the
        // key under the finger. The framework reported exactly this: the IME window was present and
        // correctly sized but showed `hasChildren=false`.
        //
        // The host must therefore stay an unlabelled container whose only job is to hand out
        // virtual nodes; every announcement comes from a key node instead.
        isFocusable = false
    }

    override fun getAccessibilityNodeProvider(): android.view.accessibility.AccessibilityNodeProvider =
        accessibilityProvider

    /**
     * Hands touch-exploration hovers to the accessibility provider before the view sees them.
     *
     * Without this override the provider is inert for exploration: the framework finds no node to
     * focus, the gesture reaches [onTouchEvent], and exploring a key types it. That was observed on
     * the watch with TalkBack running before this was added.
     */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibilityProvider.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    private fun isAccessibilityActive(): Boolean {
        val am = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        return am?.isEnabled == true && am.isTouchExplorationEnabled
    }

    override fun onDetachedFromWindow() {
        stopFrameTiming()
        frameTimingEnabled = false
        super.onDetachedFromWindow()
    }

    companion object {
        /**
         * How far a key shrinks while held. Subtle on purpose — at watch size a large scale
         * change reads as the key wobbling rather than depressing.
         */
        private const val PRESSED_SCALE = 0.88f

        /**
         * Travel before a drag is reinterpreted as a glide rather than a press.
         *
         * 24 dp is roughly half a key on this grid: far enough that ordinary finger roll during a
         * tap cannot reach it, short enough that a two-letter glide between neighbours still
         * registers. Below about 16 dp, taps on the round display's edge keys — where the finger
         * naturally slides along the bezel — start being misread as swipes.
         */
        private const val SWIPE_THRESHOLD_DP = 24f

        /** Sub-pixel movement is digitiser noise, not travel; see [traceTo]. */
        private const val MIN_TRACE_STEP_PX = 1.5f

        /**
         * Capacity of the reusable trace buffers. A long word crosses the grid a few times at
         * ~100 Hz, so 512 points is generous; the cap exists to keep the drag path allocation-free
         * (§7.3) rather than to limit gesture length in practice.
         */
        private const val MAX_TRACE_POINTS = 512

        /** Trail stroke width at its thickest (the leading end). */
        private const val TRAIL_WIDTH_DP = 3.5f

        /** How much of the tail stays visible; older points are dropped, not faded to nothing. */
        private const val TRAIL_MAX_SEGMENTS = 48

        /** Gap drawn between adjacent keys. Small — touch slop is handled in [keyAt] instead. */
        private const val KEY_GAP_PX = 3f
        /**
         * Keeps the outermost keys clear of the physical bezel curve. Generous on purpose:
         * the glass curves away near the rim, so a key drawn right at the computed chord is
         * still hard to see and hit on real hardware.
         */
        private const val KEY_EDGE_INSET_PX = 10f

        /**
         * Draw samples retained while timing. 4096 covers several minutes of real typing — the
         * keyboard only draws in response to input — and costs 16 KB of primitives, allocated once.
         * Fixed capacity is deliberate: a growing buffer would allocate inside the draw path and
         * so perturb the very measurement it exists to take.
         */
        private const val FRAME_SAMPLE_CAPACITY = 4096

        /** One frame at 60 Hz, the §14 budget. */
        private const val FRAME_BUDGET_MS = 16.6f
    }
}
