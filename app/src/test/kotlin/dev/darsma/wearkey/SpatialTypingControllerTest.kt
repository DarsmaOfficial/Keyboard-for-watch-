package dev.darsma.wearkey

import dev.darsma.wearkey.dict.SpellEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpatialTypingControllerTest {
    @Test fun `empty controller has safe literal fallback`() {
        val c = SpatialTypingController(SpellEngine())
        c.refreshVocabulary()
        c.add('h', mapOf('h' to 0.6f, 'g' to 0.4f))
        c.add('i', mapOf('i' to 0.9f))
        assertEquals("hi", c.resolvedWord())
        assertTrue(c.isComposing)
        assertEquals("h", c.backspace())
        c.clear()
        assertFalse(c.isComposing)
    }

    @Test fun `backspace edits only the deferred word`() {
        val c = SpatialTypingController(SpellEngine())
        c.add('H', mapOf('h' to 1f))
        c.add('e', mapOf('e' to 1f))
        assertEquals("He", c.preview)
        assertEquals("H", c.backspace())
        assertEquals("H", c.resolvedWord())
    }
}
