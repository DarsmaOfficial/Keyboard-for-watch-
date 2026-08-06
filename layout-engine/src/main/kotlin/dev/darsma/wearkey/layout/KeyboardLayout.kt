package dev.darsma.wearkey.layout

/**
 * A parsed keyboard layout (spec §4.1, §9).
 *
 * Layouts are *data*, not code. Spec §4.1 draws the distinction that makes this project's language
 * support tractable: layouts are cheap — "dozens of layouts cost kilobytes" — while dictionaries
 * are expensive and strictly limited to one resident at a time. Keeping layouts declarative is
 * what lets a new language ship without touching the rendering path.
 *
 * @property id stable identifier, e.g. `en_US`
 * @property languageTag BCP-47 tag used to match an `InputMethodSubtype` (spec §5.5)
 * @property displayName shown on the language key and in pickers
 * @property rightToLeft structural RTL support, required even before an RTL language ships
 * @property letterRows the alphabetic layer, outermost list is rows
 * @property symbolPages the symbol/number layer; each page is itself a list of rows
 */
data class KeyboardLayout(
    val id: String,
    val languageTag: String,
    val displayName: String,
    val rightToLeft: Boolean,
    val letterRows: List<String>,
    val symbolPages: List<List<String>>
) {
    init {
        require(id.isNotBlank()) { "layout id must not be blank" }
        require(languageTag.isNotBlank()) { "layout $id: languageTag must not be blank" }
        require(letterRows.isNotEmpty()) { "layout $id: needs at least one letter row" }
        require(letterRows.none { it.isEmpty() }) { "layout $id: letter rows must not be empty" }
        require(symbolPages.none { page -> page.any { it.isEmpty() } }) {
            "layout $id: symbol rows must not be empty"
        }
    }

    /** Longest row, which determines how narrow the keys must become. */
    val widestRow: Int get() = letterRows.maxOf { it.length }
}

/** Thrown when a layout file is malformed. Carries the layout id when one was parsed. */
class LayoutParseException(message: String) : Exception(message)

/**
 * Minimal JSON reader for layout files.
 *
 * ## Why hand-written
 *
 * The obvious move is `kotlinx.serialization` or Gson. Both are rejected here for the reason spec
 * §12 gives: no heavyweight dependencies in the keyboard path. The layout schema is six fields of
 * strings, booleans and nested string arrays — a full reflective serialisation framework would add
 * more bytes to the APK than every layout it parses, and `kotlinx.serialization` additionally
 * requires a compiler plugin in a module that otherwise needs none.
 *
 * This parser is deliberately *not* a general JSON implementation. It handles exactly the subset
 * the schema uses: objects, string arrays, arrays of string arrays, quoted strings with escapes,
 * and booleans. Anything else is a parse error rather than a silent misreading — which is the
 * right trade for a file format that only this project writes.
 *
 * Unicode escapes are supported because Cyrillic, and later Greek or Arabic, layouts are far more
 * legible as literal characters but must survive a tool that decides to escape them.
 */
object LayoutParser {

    fun parse(json: String): KeyboardLayout {
        val reader = Reader(json)
        reader.skipWhitespace()
        reader.expect('{')

        var id: String? = null
        var languageTag: String? = null
        var displayName: String? = null
        var rightToLeft = false
        var letterRows: List<String>? = null
        var symbolPages: List<List<String>> = emptyList()

        while (true) {
            reader.skipWhitespace()
            if (reader.peek() == '}') { reader.next(); break }

            val key = reader.readString()
            reader.skipWhitespace()
            reader.expect(':')
            reader.skipWhitespace()

            when (key) {
                "id" -> id = reader.readString()
                "languageTag" -> languageTag = reader.readString()
                "displayName" -> displayName = reader.readString()
                "rightToLeft" -> rightToLeft = reader.readBoolean()
                "letterRows" -> letterRows = reader.readStringArray()
                "symbolPages" -> symbolPages = reader.readStringArrayArray()
                else -> throw LayoutParseException("unknown field '$key'")
            }

            reader.skipWhitespace()
            if (reader.peek() == ',') reader.next()
        }

        val resolvedId = id ?: throw LayoutParseException("missing 'id'")
        return try {
            KeyboardLayout(
                id = resolvedId,
                languageTag = languageTag ?: throw LayoutParseException("layout $resolvedId: missing 'languageTag'"),
                displayName = displayName ?: resolvedId,
                rightToLeft = rightToLeft,
                letterRows = letterRows ?: throw LayoutParseException("layout $resolvedId: missing 'letterRows'"),
                symbolPages = symbolPages
            )
        } catch (e: IllegalArgumentException) {
            // Surface the data class's own invariants as parse errors, so callers handle one type.
            throw LayoutParseException(e.message ?: "invalid layout $resolvedId")
        }
    }

    private class Reader(private val text: String) {
        private var pos = 0

        fun peek(): Char {
            if (pos >= text.length) throw LayoutParseException("unexpected end of input")
            return text[pos]
        }

        fun next(): Char = peek().also { pos++ }

        fun expect(expected: Char) {
            val actual = next()
            if (actual != expected) {
                throw LayoutParseException("expected '$expected' at offset ${pos - 1}, found '$actual'")
            }
        }

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun readString(): String {
            skipWhitespace()
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = next()
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> out.append(readEscape())
                    else -> out.append(c)
                }
            }
        }

        private fun readEscape(): Char = when (val c = next()) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'n' -> '\n'
            't' -> '\t'
            'r' -> '\r'
            'b' -> '\b'
            'f' -> '\u000C'
            'u' -> {
                if (pos + 4 > text.length) throw LayoutParseException("truncated unicode escape")
                val hex = text.substring(pos, pos + 4)
                pos += 4
                hex.toIntOrNull(16)?.toChar()
                    ?: throw LayoutParseException("invalid unicode escape '\\u$hex'")
            }
            else -> throw LayoutParseException("invalid escape '\\$c'")
        }

        fun readBoolean(): Boolean {
            skipWhitespace()
            return when {
                text.startsWith("true", pos) -> { pos += 4; true }
                text.startsWith("false", pos) -> { pos += 5; false }
                else -> throw LayoutParseException("expected a boolean at offset $pos")
            }
        }

        fun readStringArray(): List<String> {
            skipWhitespace()
            expect('[')
            val out = mutableListOf<String>()
            while (true) {
                skipWhitespace()
                if (peek() == ']') { next(); return out }
                out.add(readString())
                skipWhitespace()
                if (peek() == ',') next()
            }
        }

        fun readStringArrayArray(): List<List<String>> {
            skipWhitespace()
            expect('[')
            val out = mutableListOf<List<String>>()
            while (true) {
                skipWhitespace()
                if (peek() == ']') { next(); return out }
                out.add(readStringArray())
                skipWhitespace()
                if (peek() == ',') next()
            }
        }
    }
}
