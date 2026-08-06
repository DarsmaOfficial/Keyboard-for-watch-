package dev.darsma.wearkey.uiwear

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider

/**
 * Exposes each key as its own accessibility node (spec §11.5).
 *
 * ## The problem this solves
 *
 * A `Canvas`-drawn key grid is, to the accessibility framework, a single blank rectangle. TalkBack
 * users could hear that "a keyboard" exists but could not explore it, and every exploratory touch
 * risked being interpreted as a keypress. Announcing the key after the fact — which is what this
 * view did before — tells the user what they *just typed*, which is exactly backwards: it is
 * feedback where guidance was needed.
 *
 * With a node provider, TalkBack takes over touch exploration: dragging a finger reads out each key
 * *without* activating it, and activation requires an explicit double-tap that arrives as
 * [AccessibilityNodeInfo.ACTION_CLICK]. Exploration can no longer misfire a keypress, which is the
 * property spec §11.5 actually asks for.
 *
 * ## Why hand-written rather than ExploreByTouchHelper
 *
 * `androidx.customview` provides `ExploreByTouchHelper`, which is the conventional answer. It is
 * Apache-2.0 and only 33 KB itself, but its POM pulls `androidx.core`, and spec §12 is explicit
 * that the keyboard module stays free of heavyweight transitive dependencies. The framework API it
 * wraps — `AccessibilityNodeProvider` — has been available since API 16 and this project's `minSdk`
 * is 30, so the compatibility shim buys nothing here. This class is the part that is actually
 * needed, written directly.
 *
 * ## Virtual view ids
 *
 * A key's id is its index in the grid's key list. Ids therefore change when the layout changes
 * (language switch, symbol layer), which is correct: those *are* different keys. The grid
 * invalidates the whole node tree on layout change so no stale node survives.
 */
internal class KeyAccessibilityProvider(
    private val host: View,
    private val keyCount: () -> Int,
    private val keyBounds: (Int) -> Rect?,
    private val keyDescription: (Int) -> CharSequence?,
    private val onKeyActivated: (Int) -> Unit
) : AccessibilityNodeProvider() {

    /** The virtual key currently under accessibility focus, or [HOST_ID] for the grid itself. */
    private var focusedVirtualId = HOST_ID

    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
        if (virtualViewId == HOST_ID) return createHostNode()

        val bounds = keyBounds(virtualViewId) ?: return null
        val description = keyDescription(virtualViewId) ?: return null

        return AccessibilityNodeInfo.obtain(host, virtualViewId).apply {
            packageName = host.context.packageName
            className = KEY_CLASS_NAME
            contentDescription = description
            setParent(host)
            setSource(host, virtualViewId)

            setBoundsInParent(bounds)
            val offset = IntArray(2)
            host.getLocationOnScreen(offset)
            setBoundsInScreen(
                Rect(
                    bounds.left + offset[0],
                    bounds.top + offset[1],
                    bounds.right + offset[0],
                    bounds.bottom + offset[1]
                )
            )

            isEnabled = true
            isVisibleToUser = true
            isFocusable = true
            isClickable = true
            // Screen readers activate through ACTION_CLICK, which arrives only on an explicit
            // double-tap. Exploration alone therefore cannot type a character.
            addAction(AccessibilityNodeInfo.ACTION_CLICK)
            addAction(
                if (focusedVirtualId == virtualViewId) AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                else AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
            )
            isAccessibilityFocused = focusedVirtualId == virtualViewId
        }
    }

    private fun createHostNode(): AccessibilityNodeInfo {
        val node = AccessibilityNodeInfo.obtain(host)
        host.onInitializeAccessibilityNodeInfo(node)
        // Children are added in layout order, which defines the traversal sequence: row by row,
        // left to right. Spec §11.5 requires this order to be deliberate rather than whatever the
        // view tree happens to produce.
        for (id in 0 until keyCount()) node.addChild(host, id)
        return node
    }

    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (virtualViewId == HOST_ID) return host.performAccessibilityAction(action, arguments)

        return when (action) {
            AccessibilityNodeInfo.ACTION_CLICK -> {
                onKeyActivated(virtualViewId)
                sendEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
                true
            }
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                if (focusedVirtualId == virtualViewId) return false
                focusedVirtualId = virtualViewId
                host.invalidate()
                sendEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                true
            }
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                if (focusedVirtualId != virtualViewId) return false
                focusedVirtualId = HOST_ID
                host.invalidate()
                sendEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                true
            }
            else -> false
        }
    }

    /**
     * Routes touch-exploration hovers to the right key node.
     *
     * ## Why this is load-bearing, not a nicety
     *
     * Declaring an [AccessibilityNodeProvider] is only half of touch exploration. Without hover
     * handling the framework has no node to move accessibility focus to, so the exploration gesture
     * falls straight through to `onTouchEvent` — and a screen-reader user's exploratory touch
     * *types the key they were trying to identify*. Verified on the watch: with TalkBack running, a
     * single tap on `h` put "h" in the field, which is exactly the failure spec §11.5 names when it
     * requires that "screen-reader exploration never misfires a keypress".
     *
     * With hovers consumed here, the two paths separate by construction: exploring moves focus and
     * announces, and typing requires an explicit double-tap that arrives as `ACTION_CLICK` on the
     * node rather than as a touch. Neither depends on remembering to check a flag at each call site.
     *
     * ## Containment, deliberately — not the probabilistic touch model
     *
     * Keys are matched by rectangle containment here, even though tap input uses the bivariate
     * Gaussian model (§7.1). That model exists so a sloppy *tap* still produces the intended letter.
     * A screen-reader user dragging a finger to learn the layout needs the opposite: the
     * announcement must change exactly at the visible key boundary. Nearest-key semantics would
     * announce a key while the finger is demonstrably not on it, misrepresenting the very layout
     * being learned.
     */
    fun dispatchHoverEvent(event: android.view.MotionEvent): Boolean = when (event.action) {
        android.view.MotionEvent.ACTION_HOVER_ENTER,
        android.view.MotionEvent.ACTION_HOVER_MOVE -> {
            val id = virtualViewAt(event.x, event.y)
            if (id != focusedVirtualId) {
                if (id == HOST_ID) {
                    performAction(focusedVirtualId, AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null)
                } else {
                    performAction(id, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
                }
            }
            // Consume only while actually over a key: hovering the gaps should not swallow the
            // event, or the host loses the chance to handle it.
            id != HOST_ID
        }

        android.view.MotionEvent.ACTION_HOVER_EXIT -> {
            if (focusedVirtualId != HOST_ID) {
                performAction(focusedVirtualId, AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null)
            }
            false
        }

        else -> false
    }

    /** The key under a point, or [HOST_ID] when the point is not on any key. */
    private fun virtualViewAt(x: Float, y: Float): Int {
        for (id in 0 until keyCount()) {
            val bounds = keyBounds(id) ?: continue
            if (bounds.contains(x.toInt(), y.toInt())) return id
        }
        return HOST_ID
    }

    /** Announces a state change on a key, e.g. shift toggling (spec §11.5). */
    fun notifyKeyChanged(virtualViewId: Int) {
        sendEvent(virtualViewId, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    /** Invalidates the whole tree — call whenever the layout changes and ids are reassigned. */
    fun notifyLayoutChanged() {
        focusedVirtualId = HOST_ID
        sendEvent(HOST_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    private fun sendEvent(virtualViewId: Int, eventType: Int) {
        val manager = host.context
            .getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        // Building and dispatching events costs real work; skip it entirely when nothing is
        // listening, which is the overwhelmingly common case.
        if (manager?.isEnabled != true) return

        val event = AccessibilityEvent.obtain(eventType).apply {
            packageName = host.context.packageName
            className = if (virtualViewId == HOST_ID) host.javaClass.name else KEY_CLASS_NAME
            isEnabled = true
            setSource(host, virtualViewId)
            if (virtualViewId != HOST_ID) {
                keyDescription(virtualViewId)?.let { text.add(it) }
            }
        }
        host.parent?.requestSendAccessibilityEvent(host, event)
    }

    private companion object {
        const val HOST_ID = View.NO_ID

        /** Reported so screen readers describe keys as buttons rather than anonymous views. */
        const val KEY_CLASS_NAME = "android.widget.Button"
    }
}
