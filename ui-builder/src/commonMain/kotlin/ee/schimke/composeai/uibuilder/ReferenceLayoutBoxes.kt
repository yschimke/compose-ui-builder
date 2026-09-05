package ee.schimke.composeai.uibuilder

/**
 * One rectangle read out of an imported SVG, in fractions of that SVG's own viewport.
 *
 * Fractions rather than user units so the drawing code needs nothing from the parse: the overlay
 * already knows the rectangle the reference is fitted into, and a fraction maps onto it whatever
 * the fit, the nudge and the scale currently are.
 */
data class ReferenceLayoutBox(
  /**
   * `data-name`, then `id`, then `aria-label` — Figma writes the first, hand-authored SVG the
   * second.
   */
  val name: String?,
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
  /** Nesting depth in the source SVG, so the overlay can shade an outer frame differently. */
  val depth: Int,
) {
  val width: Float
    get() = right - left

  val height: Float
    get() = bottom - top
}

/**
 * The axis-aligned boxes an SVG declares, for [ReferenceDiffMode.Boxes].
 *
 * **Geometry, not paint.** A design tool's SVG export carries the frames it was laid out with —
 * Figma emits one `<rect>` per frame and names it — and those rectangles are the thing worth
 * comparing a Compose layout against: "is this card the right width" is answered by the box, while
 * the fill, the gradient and the type it is painted with only get in the way of asking it.
 *
 * Reuses [parseStrictSvg], the export lane's own conservative parser, rather than adding a second
 * XML reader with its own idea of what is safe. A file that parser refuses yields no boxes at all;
 * the overlay then simply does not offer the mode, which is the honest answer for a picture whose
 * structure could not be read.
 *
 * **What it deliberately does not do:** rotation and skew. An element whose accumulated transform
 * is not axis-aligned is dropped rather than approximated by its bounding box, because a rotated
 * card's bounding box is not the card and a guide that lies is worse than a guide that is absent.
 * `translate`, `scale` and the axis-aligned `matrix` forms are honoured, which covers every export
 * a layout is normally read from.
 */
fun extractSvgLayoutBoxes(svg: String, limit: Int = MAX_LAYOUT_BOXES): List<ReferenceLayoutBox> {
  val document = parseStrictSvg(svg).structure ?: return emptyList()
  val viewport = document.root.svgViewport() ?: return emptyList()

  // One accumulated transform per open ancestor, indexed by depth. The parser emits elements in
  // document order with their depth, which is all the ancestry this needs: entering depth d means
  // the enclosing transform is the one recorded at d - 1.
  val enclosing = mutableListOf(SvgTransform.IDENTITY)
  val boxes = LinkedHashSet<ReferenceLayoutBox>()

  document.elements.forEach { element ->
    while (enclosing.size > element.depth + 1) enclosing.removeAt(enclosing.size - 1)
    val parent = enclosing.lastOrNull() ?: SvgTransform.IDENTITY
    val own = element.attributes["transform"]?.let(::parseSvgTransform)
    // An unreadable or rotated transform poisons the whole subtree, not just this element: every
    // descendant's coordinates are expressed through it.
    val combined = if (element.attributes["transform"] == null) parent else own?.let(parent::then)
    enclosing += (combined ?: SvgTransform.UNREADABLE)
    // `readable`, not just non-null: an unreadable transform anywhere above this element means its
    // coordinates are expressed through a rotation this reader will not vouch for, and the element
    // itself has no transform of its own to notice that with.
    if (combined?.readable != true || element.depth == 0) return@forEach

    val geometry = element.rectangle() ?: return@forEach
    val topLeft = combined.map(geometry.left, geometry.top)
    val bottomRight = combined.map(geometry.right, geometry.bottom)
    val left = (minOf(topLeft.first, bottomRight.first) - viewport.x) / viewport.width
    val right = (maxOf(topLeft.first, bottomRight.first) - viewport.x) / viewport.width
    val top = (minOf(topLeft.second, bottomRight.second) - viewport.y) / viewport.height
    val bottom = (maxOf(topLeft.second, bottomRight.second) - viewport.y) / viewport.height
    if (!left.isFinite() || !right.isFinite() || !top.isFinite() || !bottom.isFinite())
      return@forEach
    // Boxes entirely outside the viewport are clipped away by the renderer anyway, and a box with
    // no area is not a layout box.
    if (right <= 0f || bottom <= 0f || left >= 1f || top >= 1f) return@forEach
    if (right - left < MIN_BOX_FRACTION || bottom - top < MIN_BOX_FRACTION) return@forEach
    boxes +=
      ReferenceLayoutBox(
        name = element.layoutBoxName(),
        left = left.coerceIn(0f, 1f),
        top = top.coerceIn(0f, 1f),
        right = right.coerceIn(0f, 1f),
        bottom = bottom.coerceIn(0f, 1f),
        depth = element.depth,
      )
  }
  return boxes.take(limit)
}

/** Bounded so a pathological export cannot turn one frame into thousands of stroked rectangles. */
const val MAX_LAYOUT_BOXES: Int = 400

/** Below this a "box" is a hairline or a rounding artefact, not something to align against. */
private const val MIN_BOX_FRACTION = 0.002f

/** Elements whose `x`/`y`/`width`/`height` really describe a box a layout was built from. */
private val BOX_ELEMENTS = setOf("rect", "image", "svg", "use", "foreignobject")

private data class SvgRectangle(
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
)

private fun ParsedSvgElement.rectangle(): SvgRectangle? {
  if (name !in BOX_ELEMENTS) return null
  val width = attributes["width"]?.svgLength() ?: return null
  val height = attributes["height"]?.svgLength() ?: return null
  if (width <= 0f || height <= 0f) return null
  val x = attributes["x"]?.svgLength() ?: 0f
  val y = attributes["y"]?.svgLength() ?: 0f
  return SvgRectangle(x, y, x + width, y + height)
}

private fun ParsedSvgElement.layoutBoxName(): String? =
  sequenceOf("data-name", "id", "aria-label")
    .mapNotNull { attributes[it]?.trim() }
    .firstOrNull { it.isNotEmpty() && it.length <= 120 }

private data class SvgViewportBox(
  val x: Float,
  val y: Float,
  val width: Float,
  val height: Float,
)

private fun ParsedSvgElement.svgViewport(): SvgViewportBox? {
  attributes["viewbox"]
    ?.split(',', ' ', '\t', '\n', '\r')
    ?.filter(String::isNotBlank)
    ?.mapNotNull { it.svgLength() }
    ?.takeIf { it.size == 4 && it[2] > 0f && it[3] > 0f }
    ?.let {
      return SvgViewportBox(it[0], it[1], it[2], it[3])
    }
  val width = attributes["width"]?.svgLength() ?: return null
  val height = attributes["height"]?.svgLength() ?: return null
  return if (width > 0f && height > 0f) SvgViewportBox(0f, 0f, width, height) else null
}

/**
 * A length in user units. Percentages are refused rather than resolved: resolving one needs the
 * containing viewport at that depth, which this walk deliberately does not track.
 */
private fun String.svgLength(): Float? =
  trim().removeSuffix("px").toFloatOrNull()?.takeIf(Float::isFinite)

/**
 * An axis-aligned affine transform: scale then translate, per axis.
 *
 * [UNREADABLE] is a distinct value rather than a null, so that a subtree under a rotation is
 * dropped in one place — [then] propagates it — instead of at every element that has to remember to
 * check its ancestry.
 */
private data class SvgTransform(
  val scaleX: Float,
  val scaleY: Float,
  val translateX: Float,
  val translateY: Float,
  val readable: Boolean = true,
) {
  fun then(inner: SvgTransform): SvgTransform =
    if (!readable || !inner.readable) UNREADABLE
    else
      SvgTransform(
        scaleX = scaleX * inner.scaleX,
        scaleY = scaleY * inner.scaleY,
        translateX = translateX + scaleX * inner.translateX,
        translateY = translateY + scaleY * inner.translateY,
      )

  fun map(x: Float, y: Float): Pair<Float, Float> =
    (translateX + scaleX * x) to (translateY + scaleY * y)

  companion object {
    val IDENTITY = SvgTransform(1f, 1f, 0f, 0f)
    val UNREADABLE = SvgTransform(1f, 1f, 0f, 0f, readable = false)
  }
}

private val TRANSFORM_FUNCTION = Regex("([A-Za-z]+)\\s*\\(([^)]*)\\)")

/** Null for a transform list this reader will not vouch for; the caller drops the subtree. */
private fun parseSvgTransform(value: String): SvgTransform? {
  var accumulated = SvgTransform.IDENTITY
  var matched = 0
  TRANSFORM_FUNCTION.findAll(value).forEach { match ->
    matched += match.value.length
    val numbers =
      match.groupValues[2].split(',', ' ', '\t', '\n', '\r').filter(String::isNotBlank).map {
        it.toFloatOrNull()
      }
    if (numbers.any { it == null || !it.isFinite() }) return null
    val arguments = numbers.filterNotNull()
    val step =
      when (match.groupValues[1].lowercase()) {
        "translate" ->
          when (arguments.size) {
            1 -> SvgTransform(1f, 1f, arguments[0], 0f)
            2 -> SvgTransform(1f, 1f, arguments[0], arguments[1])
            else -> return null
          }
        "scale" ->
          when (arguments.size) {
            1 -> SvgTransform(arguments[0], arguments[0], 0f, 0f)
            2 -> SvgTransform(arguments[0], arguments[1], 0f, 0f)
            else -> return null
          }
        "matrix" ->
          // b and c are the rotation/skew terms. Zero means the matrix is a scale plus a
          // translation, which is exactly what this reader can carry.
          if (arguments.size == 6 && arguments[1] == 0f && arguments[2] == 0f) {
            SvgTransform(arguments[0], arguments[3], arguments[4], arguments[5])
          } else return null
        // rotate, skewX, skewY and anything unknown.
        else -> return null
      }
    accumulated = accumulated.then(step)
  }
  // A transform attribute that is not entirely function calls (`transform="url(#a)"`, a typo) is
  // refused rather than partially applied.
  if (matched == 0 || value.replace(TRANSFORM_FUNCTION, "").isNotBlank()) return null
  return accumulated.takeIf { it.readable }
}
