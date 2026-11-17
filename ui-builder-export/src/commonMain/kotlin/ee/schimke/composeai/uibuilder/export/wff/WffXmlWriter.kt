// Vendored from yschimke/watchface-tools wff-core (commit 54fc4b7):
//   wff-core/src/commonMain/kotlin/ee/schimke/watchface/wff/WffXmlWriter.kt
// Only visibility (public -> internal) and the package differ.
// TODO: replace with ee.schimke.watchface:wff-core once published
package ee.schimke.composeai.uibuilder.export.wff

/**
 * Canonical, deterministic XML writer.
 *
 * Rules (so that `write(parse(write(x))) == write(x)` byte for byte):
 * - always `<?xml version="1.0" encoding="utf-8"?>` then `\n`;
 * - 2-space indent, one element per line, attributes in stored order on the element's line;
 * - childless elements are self-closed (`<A b="c"/>`);
 * - "mixed" elements (any non-blank text or CDATA child) are written inline with every text node
 *   verbatim and no added whitespace, so text content is never altered;
 * - attribute values escape `& < "` and literal tab/newline/CR as character references;
 * - text escapes `& < >` and CR; CDATA and comments are verbatim (`]]>` inside CDATA is split);
 * - output ends with a single `\n`.
 */
internal object WffXmlWriter {
  internal const val DECLARATION: String = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"

  internal fun write(doc: WffDocument): String = buildString {
    append(DECLARATION).append('\n')
    doc.prolog.forEach { append("<!--").append(it.text).append("-->\n") }
    writeElement(doc.root, 0)
    append('\n')
    doc.epilog.forEach { append("<!--").append(it.text).append("-->\n") }
  }

  /** Writes a single element subtree (no declaration), without a trailing newline. */
  internal fun write(element: WffElement): String = buildString { writeElement(element, 0) }

  private fun isMixed(e: WffElement) =
    e.children.any { (it is WffText && !it.text.isXmlWhitespace()) || it is WffCData }

  private fun StringBuilder.writeElement(e: WffElement, depth: Int) {
    append('<').append(e.name)
    for (a in e.attributes) {
      append(' ').append(a.name).append("=\"")
      escapeAttr(a.value)
      append('"')
    }
    if (e.children.isEmpty()) {
      append("/>")
      return
    }
    append('>')
    if (isMixed(e)) {
      for (c in e.children) writeInline(c, depth + 1)
    } else {
      val pad = "  ".repeat(depth + 1)
      for (c in e.children) {
        // Whitespace-only text cannot occur in element-only content after parsing; skip any built
        // by hand.
        if (c is WffText) continue
        append('\n').append(pad)
        writeInline(c, depth + 1)
      }
      append('\n').append("  ".repeat(depth))
    }
    append("</").append(e.name).append('>')
  }

  private fun StringBuilder.writeInline(n: WffNode, depth: Int) {
    when (n) {
      is WffElement -> writeElement(n, depth)
      is WffText -> escapeText(n.text)
      is WffComment -> append("<!--").append(n.text).append("-->")
      is WffCData -> {
        // A CR inside CDATA would be normalised to LF on parse, so close the section around it
        // and write it as a character reference.
        val body = n.text.replace("]]>", "]]]]><![CDATA[>").replace("\r", "]]>&#13;<![CDATA[")
        append("<![CDATA[").append(body).append("]]>")
      }
    }
  }

  private fun StringBuilder.escapeAttr(s: String) {
    for (c in s) {
      when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '"' -> append("&quot;")
        '\n' -> append("&#10;")
        '\r' -> append("&#13;")
        '\t' -> append("&#9;")
        else -> append(c)
      }
    }
  }

  private fun StringBuilder.escapeText(s: String) {
    for (c in s) {
      when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '\r' -> append("&#13;")
        else -> append(c)
      }
    }
  }
}

/** Convenience: canonical XML for this document. */
internal fun WffDocument.toXml(): String = WffXmlWriter.write(this)
