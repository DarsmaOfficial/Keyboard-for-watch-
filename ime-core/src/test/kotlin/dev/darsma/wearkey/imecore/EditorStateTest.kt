package dev.darsma.wearkey.imecore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditorStateTest {

    @Test
    fun commitText_insertsAtCaretAndAdvancesCaret() {
        val state = EditorState()
        state.commitText("hello")
        assertEquals("hello", state.text)
        assertEquals(5, state.selectionStart)
        assertEquals(5, state.selectionEnd)
    }

    @Test
    fun setComposingText_thenCommitText_replacesComposingRegion() {
        val state = EditorState()
        state.setComposingText("hel")
        assertEquals("hel", state.text)
        assertEquals(0, state.composingStart)
        assertEquals(3, state.composingEnd)

        state.setComposingText("hell")
        assertEquals("hell", state.text)

        state.commitText("hello")
        assertEquals("hello", state.text)
        assertNull(state.composingStart)
        assertNull(state.composingEnd)
    }

    @Test
    fun backspace_deletesOneCharacterBeforeCaret() {
        val state = EditorState()
        state.commitText("hello")
        state.backspace()
        assertEquals("hell", state.text)
        assertEquals(4, state.selectionStart)
    }

    @Test
    fun backspace_onEmptyText_isNoOp() {
        val state = EditorState()
        state.backspace()
        assertEquals("", state.text)
        assertEquals(0, state.selectionStart)
    }

    @Test
    fun deleteSurroundingText_removesAroundCaret() {
        val state = EditorState()
        state.commitText("hello world")
        state.setCaret(5) // caret right after "hello"
        state.deleteSurroundingText(5, 1) // delete "hello" before + " " after
        assertEquals("world", state.text)
        assertEquals(0, state.selectionStart)
    }

    @Test
    fun setCaret_clampsToTextBounds() {
        val state = EditorState()
        state.commitText("hi")
        state.setCaret(999)
        assertEquals(2, state.selectionStart)
        state.setCaret(-5)
        assertEquals(0, state.selectionStart)
    }

    @Test
    fun maskedField_neverStoresPlaintext() {
        val state = EditorState(masked = true)
        state.commitText("s3cr3t")
        assertEquals("••••••", state.text)
        assertEquals(6, state.selectionStart)
    }

    @Test
    fun reset_clearsEverythingAndNeverLeaksBetweenFields() {
        val state = EditorState()
        state.commitText("leftover text")
        state.reset()
        assertEquals("", state.text)
        assertEquals(0, state.selectionStart)
        assertEquals(0, state.selectionEnd)
        assertNull(state.composingStart)
    }

    @Test
    fun reset_canSwitchMaskedFlagForNewField() {
        val state = EditorState(masked = false)
        state.commitText("plain")
        state.reset(masked = true)
        state.commitText("secret")
        assertEquals("••••••", state.text)
    }

    @Test
    fun listener_isNotifiedSynchronouslyOnEveryMutation() {
        val state = EditorState()
        var notifications = 0
        state.addListener { notifications++ }

        state.commitText("a")
        state.setComposingText("ab")
        state.backspace()
        state.setCaret(0)
        state.reset()

        assertEquals(5, notifications)
    }

    @Test
    fun listener_canBeRemoved() {
        val state = EditorState()
        var notifications = 0
        val listener = EditorState.Listener { notifications++ }
        state.addListener(listener)
        state.commitText("a")
        state.removeListener(listener)
        state.commitText("b")
        assertEquals(1, notifications)
    }

    @Test
    fun syncSelection_clampsToCurrentTextLength() {
        val state = EditorState()
        state.commitText("hi")
        state.syncSelection(10, 10)
        assertEquals(2, state.selectionStart)
        assertEquals(2, state.selectionEnd)
    }

    @Test
    fun commitText_withMultiCharNewCursorPosition_placesCaretCorrectly() {
        val state = EditorState()
        // Simulates autocorrect committing a full word and moving caret to just after it.
        state.commitText("hello", newCursorPosition = 1)
        assertEquals(5, state.selectionStart)
    }
}
