package dev.darsma.wearkey.imecore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the invariant that makes spec §11.5's "state must survive process death" already true.
 *
 * The keyboard commits every keystroke to the `InputConnection` immediately and never leaves a
 * composing region open. Consequently the authoritative copy of what the user typed lives in the
 * *host app's* field, and when the IME is killed and recreated, `onStartInputView` restores it via
 * `getExtractedText`. There is no in-flight state of ours to lose, and therefore no reason to write
 * typed text into our own storage — which would create a place for it to leak while recovering data
 * that was never at risk.
 *
 * That reasoning holds only while the invariant does. `EditorState` *supports* composing regions,
 * because it mirrors `InputConnection` semantics faithfully and an IME may legitimately need them
 * later (glide typing in §7.3 is the obvious candidate). These tests do not forbid that — they
 * document the consequence, so that whoever introduces composing text is confronted with the
 * decision rather than silently invalidating a privacy argument made elsewhere.
 */
class CommitInvariantTest {

    @Test
    fun `committed text leaves no composing region`() {
        val state = EditorState()
        state.commitText("hello")
        state.commitText(" ")
        state.commitText("world")

        assertEquals("hello world", state.text)
        assertNull(state.composingStart, "commitText must not leave a composing region open")
        assertNull(state.composingEnd, "commitText must not leave a composing region open")
    }

    @Test
    fun `backspace after commits leaves no composing region`() {
        val state = EditorState()
        state.commitText("test")
        state.backspace()

        assertEquals("tes", state.text)
        assertNull(state.composingStart)
        assertNull(state.composingEnd)
    }

    /**
     * The recovery path: everything the keyboard produced is plain committed text, so re-priming
     * from the field reproduces the exact state — including the caret — with nothing persisted by
     * the keyboard itself.
     */
    @Test
    fun `state is fully reconstructible from field contents and caret`() {
        val original = EditorState()
        original.commitText("hello world")
        original.setCaret(5)

        // Simulate process death and recreation: a fresh EditorState primed from what the host
        // app's field reports, which is what onStartInputView does.
        val recreated = EditorState()
        recreated.reset(masked = false)
        recreated.commitText(original.text)
        recreated.syncSelection(original.selectionStart, original.selectionEnd)

        assertEquals(original.text, recreated.text)
        assertEquals(original.selectionStart, recreated.selectionStart)
        assertEquals(original.selectionEnd, recreated.selectionEnd)
        assertNull(recreated.composingStart, "a recovered state has nothing pending")
    }

    /**
     * A masked field holds no plaintext, so nothing sensitive can be recovered from it — the
     * property that makes it safe to prime from the field without any special-casing for secrets.
     */
    @Test
    fun `masked state never exposes plaintext to recovery`() {
        val state = EditorState()
        state.reset(masked = true)
        state.commitText("hunter2")

        assertTrue(
            state.text.none { it.isLetterOrDigit() },
            "a masked field must not retain plaintext, got '${state.text}'"
        )
        assertEquals(7, state.text.length, "masking should preserve length for the caret to work")
    }
}
