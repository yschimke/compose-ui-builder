package ee.schimke.composeai.uibuilder

internal val STRUCTURAL_SVG_ELEMENTS =
  setOf("g", "path", "rect", "circle", "ellipse", "text", "line", "polyline", "polygon")

internal data class ParsedSvgElement(
  val name: String,
  val attributes: Map<String, String>,
  val nameEnd: Int,
  val startTagEnd: Int,
  val depth: Int,
)

internal data class ParsedSvgDocument(
  val root: ParsedSvgElement,
  val elements: List<ParsedSvgElement>,
) {
  val images: List<ParsedSvgElement>
    get() = elements.filter { it.name == "image" }
}

internal data class StrictSvgParseResult(
  val document: ParsedSvgDocument?,
  val blockers: List<DocumentSvgExportBlocker>,
)

/**
 * Conservative XML/SVG parser for the export trust boundary. Unsupported XML constructs reject;
 * this intentionally accepts less than a browser rather than trying to repair ambiguous markup.
 */
internal fun parseStrictSvg(svg: String): StrictSvgParseResult {
  val blockers = mutableListOf<DocumentSvgExportBlocker>()
  val elements = mutableListOf<ParsedSvgElement>()
  val stack = mutableListOf<String>()
  var root: ParsedSvgElement? = null
  var rootClosed = false
  var xmlDeclarationSeen = false
  var cursor = 0

  fun reject(code: String, message: String) {
    blockers += DocumentSvgExportBlocker(code = code, message = message)
  }

  while (cursor < svg.length) {
    val opening = svg.indexOf('<', cursor)
    val textEnd = if (opening < 0) svg.length else opening
    val text = svg.substring(cursor, textEnd)
    if (stack.isEmpty() && text.isNotBlank() && text != "\uFEFF") {
      reject("SVG_MULTIPLE_ROOTS", "non-whitespace content exists outside the SVG root")
      break
    }
    if (!hasOnlySafeXmlEntities(text)) {
      reject("SVG_ENTITY_REFERENCE", "SVG contains an unknown or malformed entity reference")
      break
    }
    if (opening < 0) {
      cursor = svg.length
      break
    }
    cursor = opening

    when {
      svg.startsWith("<!--", cursor) -> {
        val end = svg.indexOf("-->", cursor + 4)
        if (end < 0 || svg.substring(cursor + 4, end).contains("--")) {
          reject("SVG_XML_MALFORMED", "SVG contains a malformed XML comment")
          break
        }
        cursor = end + 3
      }
      svg.startsWith("<?xml", cursor) && svg.getOrNull(cursor + 5)?.isWhitespace() == true -> {
        val end = svg.indexOf("?>", cursor + 5)
        val declaration = if (end < 0) "" else svg.substring(cursor, end + 2)
        if (
          xmlDeclarationSeen ||
            root != null ||
            stack.isNotEmpty() ||
            end < 0 ||
            !declaration.isSafeXmlDeclaration()
        ) {
          reject(
            "SVG_XML_DECLARATION_INVALID",
            "only a standard UTF-8 XML declaration is accepted before the SVG root",
          )
          break
        }
        xmlDeclarationSeen = true
        cursor = end + 2
      }
      svg.startsWith("<?", cursor) -> {
        reject(
          "SVG_PROCESSING_INSTRUCTION",
          "processing instructions are not accepted by this SVG boundary",
        )
        break
      }
      svg.startsWith("<!", cursor) -> {
        reject(
          "SVG_FORBIDDEN_DECLARATION",
          "DOCTYPE, ENTITY, CDATA, and other declarations are not allowed in SVG",
        )
        break
      }
      svg.startsWith("</", cursor) -> {
        val end = svg.indexOf('>', cursor + 2)
        if (end < 0) {
          reject("SVG_XML_MALFORMED", "unterminated closing tag")
          break
        }
        val closing = svg.substring(cursor + 2, end).trim()
        if (!closing.isXmlName() || stack.lastOrNull() != closing) {
          reject("SVG_XML_MALFORMED", "closing tag '$closing' does not match the open element")
          break
        }
        stack.removeAt(stack.lastIndex)
        if (stack.isEmpty()) rootClosed = true
        cursor = end + 1
      }
      else -> {
        if (rootClosed && stack.isEmpty()) {
          reject("SVG_MULTIPLE_ROOTS", "SVG must contain exactly one root element")
          break
        }
        val tagEnd = findTagEnd(svg, cursor + 1)
        if (tagEnd < 0) {
          reject("SVG_XML_MALFORMED", "unterminated SVG start tag")
          break
        }
        val parsedTag = parseStartTag(svg, cursor, tagEnd)
        if (parsedTag == null) {
          reject("SVG_XML_MALFORMED", "SVG contains a malformed start tag or attribute")
          break
        }
        val element =
          ParsedSvgElement(
            name = parsedTag.name.lowercase(),
            attributes = parsedTag.attributes,
            nameEnd = parsedTag.nameEnd,
            startTagEnd = tagEnd,
            depth = stack.size,
          )
        if (stack.isEmpty()) {
          if (root != null) {
            reject("SVG_MULTIPLE_ROOTS", "SVG must contain exactly one root element")
            break
          }
          root = element
        }
        elements += element
        validateSafeSvgElement(element, blockers)
        if (!parsedTag.selfClosing) stack += parsedTag.name
        else if (stack.isEmpty()) rootClosed = true
        cursor = tagEnd + 1
      }
    }
  }

  if (stack.isNotEmpty()) {
    reject("SVG_XML_MALFORMED", "SVG contains unclosed elements")
  }
  val parsedRoot = root
  if (parsedRoot == null || parsedRoot.name != "svg") {
    reject("SVG_ROOT_MISSING", "recorder output must have one SVG root element")
  }
  validateCssReferences(svg, blockers)
  if (parsedRoot != null && parsedRoot.name == "svg") {
    validateRasterGeometry(parsedRoot, elements.filter { it.name == "image" }, blockers)
  }
  return StrictSvgParseResult(
    document =
      if (blockers.isEmpty() && parsedRoot != null) ParsedSvgDocument(parsedRoot, elements)
      else null,
    blockers = blockers.distinct(),
  )
}

private fun String.isSafeXmlDeclaration(): Boolean {
  val inner = removePrefix("<?xml").removeSuffix("?>").trim()
  val synthetic = "<xml $inner/>"
  val parsed = parseStartTag(synthetic, 0, synthetic.lastIndex) ?: return false
  if (!parsed.selfClosing || parsed.name != "xml") return false
  if (parsed.attributes.keys.any { it !in setOf("version", "encoding", "standalone") }) return false
  if (parsed.attributes["version"] !in setOf("1.0", "1.1")) return false
  if (parsed.attributes["encoding"]?.uppercase() !in setOf(null, "UTF-8")) return false
  if (parsed.attributes["standalone"] !in setOf(null, "yes", "no")) return false
  return true
}

internal fun validateRasterRecords(
  parsed: ParsedSvgDocument,
  records: List<StructuredSvgRasterRecord>,
  declaredNodeIds: List<String>,
): List<DocumentSvgExportBlocker> {
  val blockers = mutableListOf<DocumentSvgExportBlocker>()
  val images = parsed.images
  if (images.size != records.size) {
    blockers +=
      DocumentSvgExportBlocker(
        "RASTER_RECORD_COUNT_MISMATCH",
        "SVG contains ${images.size} image elements but recorder correlated ${records.size}",
      )
  }
  val recordNodeIds = records.map(StructuredSvgRasterRecord::nodeId).sorted()
  if (recordNodeIds != declaredNodeIds) {
    blockers +=
      DocumentSvgExportBlocker(
        "RASTER_RECORD_NODE_MISMATCH",
        "recorder raster nodes $recordNodeIds do not equal declared fallback nodes $declaredNodeIds",
      )
  }
  if (records.map(StructuredSvgRasterRecord::imageOrdinal).sorted() != images.indices.toList()) {
    blockers +=
      DocumentSvgExportBlocker(
        "RASTER_RECORD_ORDINAL_MISMATCH",
        "recorder must correlate every image ordinal exactly once",
      )
  }
  records.forEach { record ->
    if (record.reason.isBlank()) {
      blockers +=
        DocumentSvgExportBlocker(
          "RASTER_RECORD_REASON_MISSING",
          "raster record for ${record.nodeId} must explain its fallback",
          nodeId = record.nodeId,
        )
    }
  }
  if (
    images.any {
      "data-compose-node-id" in it.attributes || "data-compose-raster-reason" in it.attributes
    }
  ) {
    blockers +=
      DocumentSvgExportBlocker(
        "RASTER_METADATA_PREEXISTS",
        "recorder output must not self-assert Compose raster provenance",
      )
  }
  if (
    parsed.elements.any {
      it.name == "metadata" && it.attributes["id"] == "compose-ui-builder-export"
    }
  ) {
    blockers +=
      DocumentSvgExportBlocker(
        "SVG_METADATA_PREEXISTS",
        "recorder output must not self-assert saved-document export metadata",
      )
  }
  return blockers.distinct()
}

internal fun annotateRasterFallbacks(
  svg: String,
  parsed: ParsedSvgDocument,
  records: List<StructuredSvgRasterRecord>,
): String {
  var annotated = svg
  records
    .sortedByDescending { parsed.images[it.imageOrdinal].nameEnd }
    .forEach { record ->
      val insertion = parsed.images[record.imageOrdinal].nameEnd
      val attributes =
        " data-compose-node-id=\"${record.nodeId.escapeXmlAttribute()}\"" +
          " data-compose-raster-reason=\"${record.reason.escapeXmlAttribute()}\""
      annotated = annotated.substring(0, insertion) + attributes + annotated.substring(insertion)
    }
  return annotated
}

private data class StartTag(
  val name: String,
  val attributes: Map<String, String>,
  val nameEnd: Int,
  val selfClosing: Boolean,
)

private fun findTagEnd(svg: String, start: Int): Int {
  var quote: Char? = null
  for (index in start until svg.length) {
    val character = svg[index]
    if (quote != null) {
      if (character == quote) quote = null
    } else {
      when (character) {
        '\'',
        '"' -> quote = character
        '<' -> return -1
        '>' -> return index
      }
    }
  }
  return -1
}

private fun parseStartTag(svg: String, start: Int, end: Int): StartTag? {
  var cursor = start + 1
  while (cursor < end && svg[cursor].isWhitespace()) cursor++
  val nameStart = cursor
  while (cursor < end && svg[cursor].isXmlNameCharacter()) cursor++
  if (cursor == nameStart) return null
  val name = svg.substring(nameStart, cursor)
  if (!name.isXmlName()) return null
  val nameEnd = cursor
  val attributes = linkedMapOf<String, String>()
  var selfClosing = false
  while (cursor < end) {
    while (cursor < end && svg[cursor].isWhitespace()) cursor++
    if (cursor >= end) break
    if (svg[cursor] == '/') {
      selfClosing = true
      cursor++
      while (cursor < end && svg[cursor].isWhitespace()) cursor++
      if (cursor != end) return null
      break
    }
    val attributeStart = cursor
    while (cursor < end && svg[cursor].isXmlNameCharacter()) cursor++
    if (cursor == attributeStart) return null
    val attributeName = svg.substring(attributeStart, cursor).lowercase()
    if (attributeName in attributes) return null
    while (cursor < end && svg[cursor].isWhitespace()) cursor++
    if (cursor >= end || svg[cursor] != '=') return null
    cursor++
    while (cursor < end && svg[cursor].isWhitespace()) cursor++
    if (cursor >= end || (svg[cursor] != '"' && svg[cursor] != '\'')) return null
    val quote = svg[cursor++]
    val valueStart = cursor
    while (cursor < end && svg[cursor] != quote) cursor++
    if (cursor >= end) return null
    val rawValue = svg.substring(valueStart, cursor)
    if (!hasOnlySafeXmlEntities(rawValue)) return null
    attributes[attributeName] = decodeXmlEntities(rawValue)
    cursor++
  }
  return StartTag(name, attributes, nameEnd, selfClosing)
}

private fun validateSafeSvgElement(
  element: ParsedSvgElement,
  blockers: MutableList<DocumentSvgExportBlocker>,
) {
  if (element.name in setOf("script", "foreignobject", "iframe", "object", "embed")) {
    blockers +=
      DocumentSvgExportBlocker(
        "SVG_UNSAFE_ELEMENT",
        "SVG element <${element.name}> is not allowed in self-contained export",
      )
  }
  if (element.attributes.keys.any { it.startsWith("on") }) {
    blockers +=
      DocumentSvgExportBlocker(
        "SVG_EVENT_HANDLER",
        "SVG event-handler attributes are not allowed",
      )
  }
  element.attributes.forEach { (name, value) ->
    if (name == "href" || name == "xlink:href" || name == "src") {
      if (!value.startsWith("#") && !value.isEmbeddedRasterDataUri()) {
        blockers +=
          DocumentSvgExportBlocker(
            "SVG_EXTERNAL_REFERENCE",
            "SVG reference '$value' is not an internal fragment or embedded image",
          )
      }
    }
  }
}

private fun String.isEmbeddedRasterDataUri(): Boolean {
  val lowercase = lowercase()
  return lowercase.startsWith("data:image/png;base64,") ||
    lowercase.startsWith("data:image/jpeg;base64,") ||
    lowercase.startsWith("data:image/webp;base64,")
}

private fun validateCssReferences(
  svg: String,
  blockers: MutableList<DocumentSvgExportBlocker>,
) {
  val decoded = decodeXmlEntities(svg)
  if (decoded.contains("@import", ignoreCase = true)) {
    blockers += DocumentSvgExportBlocker("SVG_EXTERNAL_REFERENCE", "CSS @import is not allowed")
  }
  Regex("url\\(\\s*(['\"]?)([^)'\"]+)\\1\\s*\\)", RegexOption.IGNORE_CASE)
    .findAll(decoded)
    .forEach { match ->
      val reference = match.groupValues[2].trim()
      if (!reference.startsWith("#") && !reference.startsWith("data:", ignoreCase = true)) {
        blockers +=
          DocumentSvgExportBlocker(
            "SVG_EXTERNAL_REFERENCE",
            "CSS URL '$reference' is not embedded or an internal fragment",
          )
      }
    }
}

private data class SvgViewport(val x: Float, val y: Float, val width: Float, val height: Float)

private fun validateRasterGeometry(
  root: ParsedSvgElement,
  images: List<ParsedSvgElement>,
  blockers: MutableList<DocumentSvgExportBlocker>,
) {
  if (images.isEmpty()) return
  val viewport = root.viewport()
  if (viewport == null) {
    blockers +=
      DocumentSvgExportBlocker(
        "SVG_VIEWPORT_UNVERIFIED",
        "SVG with raster fallbacks must declare a numeric viewBox or width and height",
      )
    return
  }
  images.forEachIndexed { index, image ->
    val x = image.coordinate("x", viewport.width, 0f)
    val y = image.coordinate("y", viewport.height, 0f)
    val width = image.coordinate("width", viewport.width, null)
    val height = image.coordinate("height", viewport.height, null)
    if (x == null || y == null || width == null || height == null || width <= 0f || height <= 0f) {
      blockers +=
        DocumentSvgExportBlocker(
          "RASTER_GEOMETRY_UNVERIFIED",
          "image fallback $index must have finite positive x/y/width/height geometry",
        )
    } else if (
      x <= viewport.x &&
        y <= viewport.y &&
        x + width >= viewport.x + viewport.width &&
        y + height >= viewport.y + viewport.height
    ) {
      blockers +=
        DocumentSvgExportBlocker(
          "FULL_SCREEN_RASTER_WRAPPER",
          "image fallback $index covers the complete SVG viewport",
        )
    }
  }
}

private fun ParsedSvgElement.viewport(): SvgViewport? {
  val viewBox = attributes["viewbox"]?.split(Regex("[,\\s]+"))?.filter(String::isNotBlank)
  if (viewBox?.size == 4) {
    val numbers = viewBox.map { it.toFloatOrNull() }
    if (numbers.all { it != null }) {
      val values = numbers.filterNotNull()
      if (values[2] > 0f && values[3] > 0f) {
        return SvgViewport(values[0], values[1], values[2], values[3])
      }
    }
  }
  val width = attributes["width"]?.svgNumberOrNull()
  val height = attributes["height"]?.svgNumberOrNull()
  return if (width != null && height != null && width > 0f && height > 0f) {
    SvgViewport(0f, 0f, width, height)
  } else null
}

private fun ParsedSvgElement.coordinate(name: String, extent: Float, default: Float?): Float? {
  val value = attributes[name] ?: return default
  return if (value.endsWith('%')) {
    value.dropLast(1).toFloatOrNull()?.let { extent * it / 100f }
  } else value.svgNumberOrNull()
}

private fun String.svgNumberOrNull(): Float? {
  val normalized = removeSuffix("px")
  return normalized.toFloatOrNull()?.takeIf(Float::isFinite)
}

private fun String.isXmlName(): Boolean =
  isNotEmpty() &&
    (first().isLetter() || first() == '_' || first() == ':') &&
    drop(1).all(Char::isXmlNameCharacter)

private fun Char.isXmlNameCharacter(): Boolean = isLetterOrDigit() || this in "_.:-"

private fun hasOnlySafeXmlEntities(value: String): Boolean {
  var cursor = 0
  while (true) {
    val ampersand = value.indexOf('&', cursor)
    if (ampersand < 0) return true
    val end = value.indexOf(';', ampersand + 1)
    if (end < 0) return false
    val entity = value.substring(ampersand + 1, end)
    if (
      entity !in setOf("amp", "lt", "gt", "quot", "apos") &&
        !entity.matches(Regex("#[0-9]+")) &&
        !entity.matches(Regex("#x[0-9A-Fa-f]+"))
    ) {
      return false
    }
    cursor = end + 1
  }
}

private fun decodeXmlEntities(value: String): String =
  Regex("&(?:amp|lt|gt|quot|apos|#[0-9]+|#x[0-9A-Fa-f]+);").replace(value) { match ->
    when (val entity = match.value.substring(1, match.value.length - 1)) {
      "amp" -> "&"
      "lt" -> "<"
      "gt" -> ">"
      "quot" -> "\""
      "apos" -> "'"
      else -> {
        val codePoint =
          if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16)
          else entity.drop(1).toIntOrNull()
        codePoint?.toChar()?.toString() ?: match.value
      }
    }
  }

private fun String.escapeXmlAttribute(): String =
  replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")
