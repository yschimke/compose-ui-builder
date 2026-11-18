package ee.schimke.composeai.uibuilder.export

import ee.schimke.watchface.wff.WffAttributeType
import ee.schimke.watchface.wff.WffElement
import ee.schimke.watchface.wff.WffNode
import ee.schimke.watchface.wff.WffSchema
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Makes the geometry of an exported face valid against the Watch Face Format's integer-typed
 * attributes. The editor holds fractional geometry (a part dragged to `211.3`); WFF's `x`, `y`,
 * `width` and `height` on `PartDraw`, `Group`, `PartText` and the rest are integers, and a
 * fractional one fails validation.
 *
 * A `PartDraw` keeps its geometry exactly: its integer outer bounds are the floor of the origin and
 * the ceiling of the far edge, and the float-typed shapes inside it (`Ellipse`, `Rectangle`,
 * `RoundRectangle`, `Arc`, `Line`) are shifted by the difference, so every shape is drawn at the
 * same pixels. Elements with no float-typed child to absorb an offset are rounded to the nearest
 * integer and each says so in a note. Which attributes are integers is read from [WffSchema], the
 * same table the validator judges with.
 */
internal object WffIntegerGeometry {
  private val GEOMETRY = listOf("x", "y", "width", "height")

  fun normalise(element: WffElement, notes: MutableList<String>): WffElement {
    val children = element.children.map { if (it is WffElement) normalise(it, notes) else it }
    val rebuilt = if (children == element.children) element else element.copy(children = children)
    return if (rebuilt.name == "PartDraw") partDraw(rebuilt, notes) else rounded(rebuilt, notes)
  }

  private fun isInteger(element: String, attribute: String): Boolean {
    val specs = WffSchema.named(element)
    // An element can have several specs (a `ComplicationSlot` in a `Flavor` has no geometry): it is
    // an integer where it is declared as one, and no context declares it a float.
    val types = specs.mapNotNull { it.attribute(attribute)?.type }
    return WffAttributeType.INT in types && WffAttributeType.FLOAT !in types
  }

  private fun WffElement.literal(name: String): Double? =
    attr(name)?.trim()?.takeIf { LITERAL.matches(it) }?.toDoubleOrNull()

  private fun fractional(value: Double) = value != floor(value)

  private fun partDraw(part: WffElement, notes: MutableList<String>): WffElement {
    var next = part
    var dx = 0.0
    var dy = 0.0
    for ((origin, extent, shift) in
      listOf(Triple("x", "width", true), Triple("y", "height", false))) {
      val start = part.literal(origin) ?: continue
      val size = part.literal(extent) ?: continue
      if (!fractional(start) && !fractional(size)) continue
      val whole = floor(start)
      val span = ceil(start + size) - whole
      next = next.withAttribute(origin, format(whole)).withAttribute(extent, format(span))
      if (shift) dx = start - whole else dy = start - whole
    }
    if (dx != 0.0 || dy != 0.0) next = next.withChildren(next.children.map { shifted(it, dx, dy) })
    return rounded(next, notes)
  }

  /** A shape child moved by ([dx], [dy]); a coordinate that is an expression is left alone. */
  private fun shifted(node: WffNode, dx: Double, dy: Double): WffNode {
    if (node !is WffElement) return node
    val attributes =
      when (node.name) {
        "Ellipse",
        "Rectangle",
        "RoundRectangle" -> listOf("x" to dx, "y" to dy)
        "Arc" -> listOf("centerX" to dx, "centerY" to dy)
        "Line" -> listOf("startX" to dx, "endX" to dx, "startY" to dy, "endY" to dy)
        else -> return node
      }
    return attributes.fold(node) { acc, (name, by) ->
      val value = acc.literal(name)
      if (by == 0.0 || value == null) acc else acc.withAttribute(name, format(value + by))
    }
  }

  /** Any integer attribute still fractional is rounded, with a note. */
  private fun rounded(element: WffElement, notes: MutableList<String>): WffElement {
    var next = element
    for (name in GEOMETRY) {
      val value = element.literal(name) ?: continue
      if (!fractional(value) || !isInteger(element.name, name)) continue
      val whole =
        floor(value + 0.5).let { if (name == "width" || name == "height") maxOf(it, 0.0) else it }
      next = next.withAttribute(name, format(whole))
      notes +=
        "<${element.name}> $name $value written as ${format(whole)}: it is an integer in Watch Face Format"
    }
    return next
  }

  private fun format(value: Double): String {
    val tidy = kotlin.math.round(value * 1_000_000) / 1_000_000
    return if (tidy == floor(tidy) && kotlin.math.abs(tidy) < 1e15) tidy.toLong().toString()
    else tidy.toString()
  }

  private val LITERAL = Regex("[-+]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][-+]?[0-9]+)?")
}
