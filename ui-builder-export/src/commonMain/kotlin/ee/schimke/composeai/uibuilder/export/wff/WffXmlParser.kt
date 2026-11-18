// Vendored from yschimke/watchface-tools wff-core 0.2.1
// (wff-core/src/commonMain/kotlin/ee/schimke/watchface/wff/WffXmlParser.kt).
// Only visibility (public -> internal) and the package differ. wff-core 0.2.1 is compiled for Java
// 21 bytecode;
// this repository's server floor is Java 17, so it cannot be consumed by coordinate yet.

package ee.schimke.composeai.uibuilder.export.wff

/**
 * Pure-Kotlin XML reader producing the lossless [WffDocument] tree. Works on every Kotlin target.
 *
 * What is kept: element/attribute names and attribute order, text, CDATA, comments (including those
 * around the root). What is dropped, by design: the XML declaration, other processing instructions,
 * a DOCTYPE, and *ignorable* whitespace ? whitespace-only text in an element that has no real text
 * or CDATA content. An element that does have real text or CDATA is "mixed" and keeps every text
 * node verbatim (so a `<Template>` and its `<Parameter>` children are exact).
 *
 * Input is normalised the way an XML processor would: `\r\n`/`\r` become `\n`, and literal
 * whitespace in attribute values becomes a space (character references are kept).
 */
internal object WffXmlParser {
  internal fun parse(xml: String): WffDocument = Parser(normalizeInput(xml)).parseDocument()

  internal fun parseOrNull(xml: String): WffDocument? = runCatching { parse(xml) }.getOrNull()

  private fun normalizeInput(raw: String): String {
    val s = if (raw.isNotEmpty() && raw[0] == '?') raw.substring(1) else raw
    return if ('\r' in s) s.replace("\r\n", "\n").replace('\r', '\n') else s
  }

  private class Parser(private val src: String) {
    private var pos = 0
    private val len = src.length

    fun parseDocument(): WffDocument {
      val prolog = mutableListOf<WffComment>()
      var root: WffElement? = null
      val epilog = mutableListOf<WffComment>()
      while (true) {
        skipWhitespace()
        if (pos >= len) break
        when {
          startsWith("<!--") -> {
            val c = readComment()
            if (root == null) prolog += c else epilog += c
          }
          startsWith("<?") -> skipUntilAfter("?>")
          startsWith("<!DOCTYPE") || startsWith("<!doctype") -> skipDoctype()
          src[pos] == '<' -> {
            if (root != null) fail("Content after the root element")
            root = parseElement()
          }
          else -> fail("Unexpected character '${src[pos]}' outside the root element")
        }
      }
      return WffDocument(root ?: fail("No root element found"), prolog, epilog)
    }

    private fun parseElement(): WffElement {
      expect('<')
      val name = readName()
      if (name.isEmpty()) fail("Expected element name")
      val attrs = mutableListOf<WffAttribute>()
      while (true) {
        skipWhitespace()
        if (pos >= len) fail("Unexpected end of input inside <$name>")
        if (startsWith("/>")) {
          pos += 2
          return WffElement(name, attrs)
        }
        if (src[pos] == '>') {
          pos++
          break
        }
        val attrName = readName()
        if (attrName.isEmpty()) fail("Expected attribute name in <$name>")
        if (attrs.any { it.name == attrName }) fail("Duplicate attribute '$attrName' in <$name>")
        skipWhitespace()
        expect('=')
        skipWhitespace()
        attrs += WffAttribute(attrName, readQuotedValue())
      }

      val children = mutableListOf<WffNode>()
      while (true) {
        if (pos >= len) fail("Unterminated element <$name>")
        when {
          startsWith("</") -> {
            pos += 2
            val close = readName()
            skipWhitespace()
            expect('>')
            if (close != name) fail("Mismatched closing tag </$close> for <$name>")
            return WffElement(name, attrs, dropIgnorableWhitespace(children))
          }
          startsWith("<!--") -> children += readComment()
          startsWith("<![CDATA[") -> {
            pos += 9
            val end = src.indexOf("]]>", pos)
            if (end < 0) fail("Unterminated CDATA section")
            children += WffCData(src.substring(pos, end))
            pos = end + 3
          }
          startsWith("<?") -> skipUntilAfter("?>")
          src[pos] == '<' -> children += parseElement()
          else -> {
            val start = pos
            while (pos < len && src[pos] != '<') pos++
            children += WffText(unescape(src.substring(start, pos), start))
          }
        }
      }
    }

    private fun dropIgnorableWhitespace(children: List<WffNode>): List<WffNode> {
      val mixed = children.any { (it is WffText && !it.text.isXmlWhitespace()) || it is WffCData }
      return if (mixed) children
      else children.filterNot { it is WffText && it.text.isXmlWhitespace() }
    }

    private fun readComment(): WffComment {
      pos += 4
      val end = src.indexOf("-->", pos)
      if (end < 0) fail("Unterminated comment")
      val text = src.substring(pos, end)
      pos = end + 3
      return WffComment(text)
    }

    private fun readQuotedValue(): String {
      if (pos >= len || (src[pos] != '"' && src[pos] != '\''))
        fail("Attribute value must be quoted")
      val quote = src[pos++]
      val start = pos
      while (pos < len && src[pos] != quote) {
        if (src[pos] == '<') fail("Unescaped '<' in attribute value; write &lt;")
        pos++
      }
      if (pos >= len) fail("Unterminated attribute value")
      val raw = src.substring(start, pos)
      pos++
      val normalized =
        if ('\n' in raw || '\t' in raw) raw.replace('\n', ' ').replace('\t', ' ') else raw
      return unescape(normalized, start)
    }

    private fun readName(): String {
      val start = pos
      while (pos < len) {
        val c = src[pos]
        if (c.isLetterOrDigit() || c == '_' || c == '-' || c == ':' || c == '.') pos++ else break
      }
      return src.substring(start, pos)
    }

    private fun skipDoctype() {
      var depth = 0
      while (pos < len) {
        val c = src[pos++]
        if (c == '<' || c == '[') depth++
        else if (c == ']') depth--
        else if (c == '>') {
          depth--
          if (depth <= 0) return
        }
      }
    }

    private fun skipUntilAfter(marker: String) {
      val idx = src.indexOf(marker, pos)
      if (idx < 0) fail("Unterminated '$marker' section")
      pos = idx + marker.length
    }

    private fun skipWhitespace() {
      while (pos < len && src[pos].isWhitespace()) pos++
    }

    private fun startsWith(prefix: String) = src.startsWith(prefix, pos)

    private fun expect(ch: Char) {
      if (pos >= len || src[pos] != ch) {
        fail("Expected '$ch' but found ${if (pos < len) "'${src[pos]}'" else "end of input"}")
      }
      pos++
    }

    private fun unescape(text: String, base: Int): String {
      if ('&' !in text) return text
      val out = StringBuilder(text.length)
      var i = 0
      while (i < text.length) {
        val c = text[i]
        if (c == '&') {
          val semi = text.indexOf(';', i + 1)
          val decoded =
            if (semi > i + 1 && semi - i <= 12) decodeEntity(text.substring(i + 1, semi)) else null
          if (decoded == null) {
            pos = base + i
            fail("Unknown or malformed entity reference")
          }
          out.append(decoded)
          i = semi + 1
        } else {
          out.append(c)
          i++
        }
      }
      return out.toString()
    }

    private fun decodeEntity(entity: String): String? =
      when (entity) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        else -> {
          val cp =
            when {
              entity.startsWith("#x") || entity.startsWith("#X") ->
                entity.substring(2).toIntOrNull(16)
              entity.startsWith("#") -> entity.substring(1).toIntOrNull(10)
              else -> null
            }
          cp?.let(::codePointToString)
        }
      }

    private fun codePointToString(cp: Int): String? =
      when {
        !isXmlChar(cp) -> null
        cp <= 0xFFFF -> cp.toChar().toString()
        else -> {
          val o = cp - 0x10000
          "${((o ushr 10) + 0xD800).toChar()}${((o and 0x3FF) + 0xDC00).toChar()}"
        }
      }

    private fun fail(message: String): Nothing {
      var line = 1
      var col = 1
      val end = pos.coerceAtMost(len)
      for (i in 0 until end) {
        if (src[i] == '\n') {
          line++
          col = 1
        } else col++
      }
      throw WffParseException(message, line, col)
    }
  }
}

/**
 * True when every character is XML whitespace (space, tab, CR, LF). Unlike [CharSequence.isBlank],
 * a non-breaking space or other Unicode space is character content, not ignorable formatting.
 */
internal fun String.isXmlWhitespace(): Boolean = all {
  it == ' ' || it == '\t' || it == '\n' || it == '\r'
}

/** XML 1.0 `Char`: #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]. */
internal fun isXmlChar(cp: Int): Boolean =
  cp == 0x9 ||
    cp == 0xA ||
    cp == 0xD ||
    cp in 0x20..0xD7FF ||
    cp in 0xE000..0xFFFD ||
    cp in 0x10000..0x10FFFF
