package ee.schimke.composeai.uibuilder.reference

import ee.schimke.composeai.uibuilder.svg.parseStrictSvg

/**
 * Whether these bytes may be attached to a design as a reference, and why not. Runs in the editor
 * for immediate feedback, but the serve host re-applies the same rules before storing; `:server`
 * can't depend on this module, so keep the two in step, and never make this one stricter.
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
 * Why an SVG may not be attached, or null when it may: markup our own parser can read, no active
 * content, and **no reference that leaves the file** (refused even though Skia's `SVGDOM` has no
 * resource provider and wouldn't fetch it).
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

/** Active content and embedding elements. `<animate>` is allowed: Skia ignores it. */
private val UNSAFE_REFERENCE_SVG_ELEMENTS =
  setOf("script", "foreignobject", "iframe", "object", "embed", "audio", "video", "handler")

/**
 * The ceiling on one attached reference: 8 MB (a full-frame PNG at 3×). Separate from the 6 MB
 * embedded-asset quota, since references never cross the collaboration wire or enter an export.
 */
const val MAX_REFERENCE_BYTES: Int = 8 * 1024 * 1024
