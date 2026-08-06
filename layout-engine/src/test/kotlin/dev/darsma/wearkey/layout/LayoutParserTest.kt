package dev.darsma.wearkey.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayoutParserTest {

    private val english = """
        {
          "id": "en_US",
          "languageTag": "en-US",
          "displayName": "English",
          "rightToLeft": false,
          "letterRows": ["QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"],
          "symbolPages": [
            ["1234567890", "-/:;()€&@", ".,?!'\""],
            ["[]{}#%^*+=", "_\\|~<>${'$'}£¥", "•°·§…"]
          ]
        }
    """.trimIndent()

    @Test
    fun `parses a complete layout`() {
        val layout = LayoutParser.parse(english)
        assertEquals("en_US", layout.id)
        assertEquals("en-US", layout.languageTag)
        assertEquals("English", layout.displayName)
        assertFalse(layout.rightToLeft)
        assertEquals(3, layout.letterRows.size)
        assertEquals("QWERTYUIOP", layout.letterRows[0])
        assertEquals(2, layout.symbolPages.size)
        assertEquals(10, layout.widestRow)
    }

    @Test
    fun `preserves characters that need escaping`() {
        val layout = LayoutParser.parse(english)
        // Quote, backslash and dollar all survive the round trip — these are exactly the
        // characters a naive parser mangles.
        assertTrue(layout.symbolPages[0][2].contains('"'))
        assertTrue(layout.symbolPages[1][1].contains('\\'))
        assertTrue(layout.symbolPages[1][1].contains('$'))
    }

    @Test
    fun `parses cyrillic written literally`() {
        val json = """
            {
              "id": "ru_RU",
              "languageTag": "ru-RU",
              "displayName": "Русский",
              "letterRows": ["ЙЦУКЕНГШЩЗ", "ФЫВАПРОЛДЖ", "ЯЧСМИТЬБЮ"]
            }
        """.trimIndent()
        val layout = LayoutParser.parse(json)
        assertEquals("Русский", layout.displayName)
        assertEquals("ЙЦУКЕНГШЩЗ", layout.letterRows[0])
    }

    @Test
    fun `parses unicode escapes`() {
        // The same Cyrillic row a tool might emit escaped rather than literal.
        val json = """
            {
              "id": "ru_esc",
              "languageTag": "ru-RU",
              "letterRows": ["\u0419\u0426\u0423"]
            }
        """.trimIndent()
        assertEquals("ЙЦУ", LayoutParser.parse(json).letterRows[0])
    }

    @Test
    fun `defaults displayName to the id and rightToLeft to false`() {
        val json = """
            {"id": "minimal", "languageTag": "xx", "letterRows": ["AB"]}
        """.trimIndent()
        val layout = LayoutParser.parse(json)
        assertEquals("minimal", layout.displayName)
        assertFalse(layout.rightToLeft)
        assertTrue(layout.symbolPages.isEmpty())
    }

    @Test
    fun `reads an rtl layout`() {
        val json = """
            {"id": "ar", "languageTag": "ar", "rightToLeft": true, "letterRows": ["ضصثق"]}
        """.trimIndent()
        assertTrue(LayoutParser.parse(json).rightToLeft)
    }

    @Test
    fun `rejects a layout with no letter rows`() {
        val json = """{"id": "empty", "languageTag": "xx", "letterRows": []}"""
        assertFailsWith<LayoutParseException> { LayoutParser.parse(json) }
    }

    @Test
    fun `rejects a layout with an empty row`() {
        val json = """{"id": "gap", "languageTag": "xx", "letterRows": ["AB", ""]}"""
        assertFailsWith<LayoutParseException> { LayoutParser.parse(json) }
    }

    @Test
    fun `rejects missing required fields`() {
        assertFailsWith<LayoutParseException> {
            LayoutParser.parse("""{"languageTag": "xx", "letterRows": ["AB"]}""")
        }
        assertFailsWith<LayoutParseException> {
            LayoutParser.parse("""{"id": "x", "letterRows": ["AB"]}""")
        }
        assertFailsWith<LayoutParseException> {
            LayoutParser.parse("""{"id": "x", "languageTag": "xx"}""")
        }
    }

    @Test
    fun `rejects an unknown field rather than ignoring it`() {
        // Silently ignoring unknown fields hides typos in hand-written layout files.
        val json = """{"id": "x", "languageTag": "xx", "letterRows": ["AB"], "lettreRows": ["AB"]}"""
        assertFailsWith<LayoutParseException> { LayoutParser.parse(json) }
    }

    @Test
    fun `rejects malformed json`() {
        val broken = listOf(
            """{"id": "x", "languageTag": "xx", "letterRows": ["AB"]""",
            """{"id": "x" "languageTag": "xx"}""",
            """not json at all""",
            """{"id": "x", "languageTag": "xx", "letterRows": "AB"}""",
            """{"id": "x", "languageTag": "xx", "rightToLeft": yes, "letterRows": ["AB"]}"""
        )
        broken.forEach { json ->
            assertFailsWith<LayoutParseException>("should reject: $json") { LayoutParser.parse(json) }
        }
    }

    @Test
    fun `rejects a truncated unicode escape`() {
        val json = """{"id": "x", "languageTag": "xx", "letterRows": ["\u041"]}"""
        assertFailsWith<LayoutParseException> { LayoutParser.parse(json) }
    }

    @Test
    fun `tolerates generous whitespace`() {
        val json = "  {\n\t\"id\"  :  \"x\" ,\n \"languageTag\":\"xx\",\n \"letterRows\" : [ \"AB\" , \"CD\" ]\n}  "
        val layout = LayoutParser.parse(json)
        assertEquals(listOf("AB", "CD"), layout.letterRows)
    }
}
