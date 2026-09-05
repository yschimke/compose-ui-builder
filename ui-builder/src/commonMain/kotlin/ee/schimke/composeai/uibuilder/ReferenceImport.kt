package ee.schimke.composeai.uibuilder

/**
 * Whether these bytes may be attached to a design as a reference, and why not when they may not.
 *
 * Runs in the editor so a bad paste says what is wrong the moment it happens rather than after a
 * round trip. It is **not** the authority: the serve host applies the same rules again before it
 * stores anything, because this half runs in a browser the host does not control. The two are
 * deliberately separate implementations of one rule — `:server` cannot depend on this Compose
 * module — so keep them in step, and keep this one no *stricter* than the host's, or the editor
 * will refuse imports the host would have kept.
 */
fun referenceImportRefusal(mediaType: String, byteCount: Int, decodedText: String?): String? {
  if (mediaType !in ReferenceImage.SUPPORTED_MEDIA_TYPES) {
    return "A reference must be a PNG, JPEG, WebP or SVG; this is $mediaType."
  }
  if (byteCount <= 0) return "The reference file is empty."
  if (byteCount > MAX_REFERENCE_BYTES) {
    return "A reference must be under ${MAX_REFERENCE_BYTES / (1024 * 1024)} MB; " +
      "this is ${byteCount / (1024 * 1024)} MB."
  }
  if (mediaType == ReferenceImage.SVG_MEDIA_TYPE) {
    val svg = decodedText ?: return "The SVG could not be read as text."
    return referenceSvgRefusal(svg)
  }
  return null
}

/**
 * Why an SVG may not be attached, or null when it may.
 *
 * An SVG is a document, not a picture, and this one is going to be drawn into the operator's own
 * editor and stored under their design. So the accepted subset is: markup this repository's own
 * parser can read, no active content, and **no reference that leaves the file**. The renderer
 * (Skia's `SVGDOM`) executes no script and is given no resource provider, so an external `href`
 * would fetch nothing anyway — it is refused at the door regardless, because "it happens not to
 * work today" is not a boundary.
 */
fun referenceSvgRefusal(svg: String): String? {
  if (svg.length > MAX_REFERENCE_BYTES) return "The SVG is too large to attach."
  val document =
    parseStrictSvg(svg).structure ?: return "The SVG could not be parsed as well-formed markup."
  document.elements.forEach { element ->
    if (element.name in UNSAFE_REFERENCE_SVG_ELEMENTS) {
      return "The SVG contains <${element.name}>, which a reference may not carry."
    }
    element.attributes.forEach { (name, value) ->
      if (name.startsWith("on")) return "The SVG contains the event handler `$name`."
      if (name == "href" || name == "xlink:href") {
        if (!value.isSelfContainedSvgReference()) {
          return "The SVG references something outside itself (`$name`)."
        }
      }
    }
  }
  return null
}

/** A fragment into the same file, or bytes carried inside it. Anything else leaves the document. */
private fun String.isSelfContainedSvgReference(): Boolean {
  val value = trim()
  return value.startsWith("#") || value.startsWith("data:image/", ignoreCase = true)
}

/**
 * Active content and embedding elements. `<animate>` and friends are not here on purpose: they move
 * pixels, which is all a reference does, and Skia ignores them.
 */
private val UNSAFE_REFERENCE_SVG_ELEMENTS =
  setOf("script", "foreignobject", "iframe", "object", "embed", "audio", "video", "handler")

/**
 * The ceiling on one attached reference.
 *
 * 8 MB rather than the 6 MB the document's own embedded-asset quota allows, because this is not a
 * document asset and does not share that budget: it never crosses the collaboration wire, never
 * enters an export, and is stored per design in its own file. It is large enough for a full-frame
 * PNG at 3× and small enough that a design's reference cannot quietly become the largest thing the
 * serve host holds.
 */
const val MAX_REFERENCE_BYTES: Int = 8 * 1024 * 1024
