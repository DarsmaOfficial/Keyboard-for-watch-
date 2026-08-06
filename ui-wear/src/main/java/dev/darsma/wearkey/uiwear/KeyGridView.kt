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

    /**
     * Symbol/number layer (spec §11 MVP item 5). Two pages, because a watch row cannot hold the
     * punctuation people actually need without the keys becoming untappable.
     */
    private val symbolPage1 = listOf(
        "1234567890",
        "-/:;()€&@",
        ".,?!'\""
    )
    private val symbolPage2 = listOf(
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
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pressedKey = keyAt(event.x, event.y)
                if (pressedKey != null) animatePressTo(PRESSED_SCALE)
                invalidate()
                return pressedKey != null
            }
            MotionEvent.ACTION_MOVE -> {
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
                invalidate()
                if (released != null) {
                    announceForKey(released)
                    // Haptics fire on release, matching where the action actually happens
                    // (spec §8.1 amplitude map).
                    haptics.perform(
                        when (released.action) {
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
                    // Layer/shift state is owned by this view, so handle those here and let the
                    // host only deal with actions that produce text or need an InputConnection.
                    when (released.action) {
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
                    onKeyListener?.onKey(released.action)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedKey = null
                animatePressTo(1f)
                invalidate()
                return true
            }
        }
        return false
    }

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
     */
    private fun announceForKey(key: Key) {
        if (!isAccessibilityActive()) return
        announceForAccessibility(describeKey(key))
    }

    private fun isAccessibilityActive(): Boolean {
        val am = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        return am?.isEnabled == true && am.isTouchExplorationEnabled
    }

    override fun onDetachedFromWindow() {
        stopFrameTiming()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    companion object {
        /**
         * How far a key shrinks while held. Subtle on purpose — at watch size a large scale
         * change reads as the key wobbling rather than depressing.
         */
        private const val PRESSED_SCALE = 0.88f

        /** Gap drawn between adjacent keys. Small — touch slop is handled in [keyAt] instead. */
        private const val KEY_GAP_PX = 3f
        /**
         * Keeps the outermost keys clear of the physical bezel curve. Generous on purpose:
         * the glass curves away near the rim, so a key drawn right at the computed chord is
         * still hard to see and hit on real hardware.
         */
        private const val KEY_EDGE_INSET_PX = 10f
    }
}
