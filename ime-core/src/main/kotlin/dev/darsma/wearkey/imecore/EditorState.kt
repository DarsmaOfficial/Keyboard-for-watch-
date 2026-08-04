package dev.darsma.wearkey.imecore

/**
 * Single source of truth for the composed text, caret and composing region.
 *
 * Spec §5 critical implementation rule: the composition strip must NEVER query
 * InputConnection per frame (it's IPC and would destroy frame timing). Instead, all key input
 * flows through this class, which mirrors (a subset of) InputConnection's mutation semantics
 * locally and notifies listeners synchronously. The IME service is responsible for also
 * forwarding the same mutations to the real InputConnection, and for reconciling this state
 * from `onUpdateSelection` when the target app performs its own edits (spec §11.5: "do not
 * fight the app; defer to onUpdateSelection").
 *
 * Masking (spec §5, §11.5 security): when [masked] is true, plaintext characters are NEVER
 * retained here — not even transiently. Only a character count is tracked so the caret and a
 * bullet-masked display string ("•••") can still be rendered; [text] never contains the real
 * characters typed into a password/OTP field.
 */
class EditorState(masked: Boolean = false) {

    /** Notified synchronously on every mutation — the composition strip observes this. */
    fun interface Listener {
        fun onEditorStateChanged(state: EditorState)
    }

    var masked: Boolean = masked
        private set

    /** Visible text: real content, or a same-length mask-character string if [masked]. */
    var text: String = ""
        private set

    /** Caret / selection, always within `0..text.length`. Caret == selectionStart == selectionEnd. */
    var selectionStart: Int = 0
        private set
    var selectionEnd: Int = 0
        private set

    /** Composing region (IME pre-commit underline), or null when nothing is being composed. */
    var composingStart: Int? = null
        private set
    var composingEnd: Int? = null
        private set

    private val listeners = mutableListOf<Listener>()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        // Copy to tolerate listeners that add/remove listeners during the callback.
        listeners.toList().forEach { it.onEditorStateChanged(this) }
    }

    /**
     * Full reset on field focus change (spec §11.5: "never carry text between fields").
     * Must be called from onStartInputView for every new editor, even if the previous field
     * was never explicitly finished.
     */
    fun reset(masked: Boolean = false) {
        this.masked = masked
        text = ""
        selectionStart = 0
        selectionEnd = 0
        composingStart = null
        composingEnd = null
        notifyChanged()
    }

    /**
     * Reconciles local state with the real field content reported by the system
     * (onStartInputView with existing text, or onUpdateSelection after the app edits the field
     * itself). Never call this with plaintext when [masked] is true — pass only [selStart]/[selEnd].
     */
    fun syncSelection(selStart: Int, selEnd: Int) {
        val len = text.length
        selectionStart = selStart.coerceIn(0, len)
        selectionEnd = selEnd.coerceIn(0, len)
        notifyChanged()
    }

    /** Mirrors InputConnection#commitText: replaces the composing region (or inserts at caret). */
    fun commitText(newText: String, newCursorPosition: Int = 1) {
        val (repStart, repEnd) = composingRangeOrSelection()
        replaceRange(repStart, repEnd, newText, newCursorPosition)
        composingStart = null
        composingEnd = null
        notifyChanged()
    }

    /** Mirrors InputConnection#setComposingText: sets/replaces the composing (pre-commit) region. */
    fun setComposingText(newText: String, newCursorPosition: Int = 1) {
        val (repStart, repEnd) = composingRangeOrSelection()
        replaceRange(repStart, repEnd, newText, newCursorPosition)
        composingStart = repStart
        composingEnd = repStart + effectiveLength(newText)
        notifyChanged()
    }

    /** Mirrors InputConnection#finishComposingText: commits the composing region as-is, no text change. */
    fun finishComposingText() {
        composingStart = null
        composingEnd = null
        notifyChanged()
    }

    /** Mirrors InputConnection#deleteSurroundingText. Operates around the caret, ignores composing region. */
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
        val len = text.length
        val delStart = (selectionStart - beforeLength).coerceIn(0, len)
        val delEnd = (selectionEnd + afterLength).coerceIn(0, len)
        if (delStart >= delEnd) return
        text = text.removeRange(delStart, delEnd)
        val removedBeforeCaret = (selectionStart - delStart).coerceAtLeast(0)
        selectionStart = (selectionStart - removedBeforeCaret).coerceIn(0, text.length)
        selectionEnd = selectionStart
        composingStart = null
        composingEnd = null
        notifyChanged()
    }

    /** Tap-to-position / drag-to-scrub the caret (spec §5). Collapses any selection. */
    fun setCaret(position: Int) {
        val p = position.coerceIn(0, text.length)
        selectionStart = p
        selectionEnd = p
        notifyChanged()
    }

    /** Backspace convenience: deletes one character (or the whole selection) before the caret. */
    fun backspace() {
        if (selectionStart != selectionEnd) {
            deleteSurroundingText(0, 0) // collapse selection first
            text = text.removeRange(selectionStart, selectionEnd)
            selectionEnd = selectionStart
            notifyChanged()
            return
        }
        deleteSurroundingText(1, 0)
    }

    private fun composingRangeOrSelection(): Pair<Int, Int> {
        val cs = composingStart
        val ce = composingEnd
        return if (cs != null && ce != null) cs to ce else selectionStart to selectionEnd
    }

    /** Length as stored internally — masked fields store a fixed 1-char-per-char bullet run. */
    private fun effectiveLength(newText: String): Int = newText.length

    private fun replaceRange(start: Int, end: Int, newText: String, newCursorPosition: Int) {
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = end.coerceIn(safeStart, text.length)
        val insertion = if (masked) MASK_CHAR.toString().repeat(newText.length) else newText
        text = text.replaceRange(safeStart, safeEnd, insertion)

        val insertEndAbsolute = safeStart + insertion.length
        val caret = if (newCursorPosition > 0) {
            insertEndAbsolute + (newCursorPosition - 1)
        } else {
            safeStart + newCursorPosition
        }.coerceIn(0, text.length)
        selectionStart = caret
        selectionEnd = caret
    }

    companion object {
        const val MASK_CHAR = '•'
    }
}
