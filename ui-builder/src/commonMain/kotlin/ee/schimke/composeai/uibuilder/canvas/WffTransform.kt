package ee.schimke.composeai.uibuilder.canvas

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlImporter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * [node] with its `wff/transform` children applied at [preview]'s time, in document order: each
 * evaluates the expression the exporter writes for it ([WatchFaceXmlExporter.transformOf], so a
 * "Rotate with" preset is its preset's expression) and sets the property its `target` names, or
 * adds to it with `mode="BY"`. A `<Transform>` held as `wff/raw` XML, as a design imported before
 * the transform had a builtin holds it, is read as the typed node an import would make of it.
 *
 * A `wff/gyro` then offsets the attributes it names (`x`, `y`, `scaleX`, `scaleY`, `angle`) by its
 * expressions of the tilt in [preview], and sets `alpha` to its expression's level
 * ([WffPreviewContext.swaying] on a live canvas, zero on a frozen one, where a flat watch leaves
 * the part where it is).
 */
internal fun wffTransformed(
  document: UiBuilderDocument,
  node: UiBuilderNode,
  preview: WffPreviewContext,
): UiBuilderNode {
  var properties = node.properties
  fun set(name: String, value: Double) {
    properties =
      JsonObject(
        properties +
          (name to
            JsonObject(mapOf("type" to JsonPrimitive("number"), "value" to JsonPrimitive(value)))),
      )
  }
  val gyros = mutableListOf<UiBuilderNode>()
  for (id in node.slots.values.flatten()) {
    val child = document.nodes[id] ?: continue
    if (child.componentId == WatchFaceXmlExporter.GYRO) {
      gyros += child
      continue
    }
    val transform =
      when (child.componentId) {
        WatchFaceXmlExporter.TRANSFORM -> child
        WatchFaceXmlExporter.RAW ->
          child
            .string("xml")
            .takeIf { it.trimStart().startsWith("<Transform") }
            ?.let { WatchFaceXmlImporter.transformNode(it, child.id) } ?: continue
        else -> continue
      }
    val reading =
      WatchFaceXmlExporter.transformOf(
        transform.string("target").ifEmpty { null },
        transform.string("rotateWith").ifEmpty { null },
        transform.string("value").ifEmpty { null },
      ) as? WatchFaceXmlExporter.TransformReading.Read ?: continue
    val value = evaluateWffExpression(reading.value, preview)?.asNumber() ?: continue
    val target = reading.target
    val result =
      if (transform.string("mode") == "BY") {
        val current = node.copy(properties = properties)
        current.float(target, wffUnsetDefault(document, target)) + value
      } else value.toDouble()
    set(target, result.toDouble())
  }
  for (gyro in gyros) {
    for (name in WatchFaceXmlExporter.GYRO_ATTRIBUTES) {
      val expression = gyro.string(name).ifEmpty { null } ?: continue
      val value = evaluateWffExpression(expression, preview)?.asNumber() ?: continue
      // `alpha` is the transparency the tilt gives the part, 0 to 255; the rest are offsets.
      if (name == "alpha") {
        set(name, value.coerceIn(0.0, 255.0))
        continue
      }
      if (value == 0.0) continue
      val current = node.copy(properties = properties)
      set(name, current.float(name, wffUnsetDefault(document, name)) + value)
    }
  }
  return if (properties === node.properties) node else node.copy(properties = properties)
}

/**
 * What a target reads as when the node does not set it, for a `mode="BY"` transform: what the
 * exporter writes for it (`Props.bounds`), so a part with no `width` grows from the scene's.
 */
private fun wffUnsetDefault(document: UiBuilderDocument, target: String): Float {
  fun scene(name: String) =
    document.roots.firstNotNullOfOrNull { document.nodes[it] }?.float(name, 450f) ?: 450f
  return when (target) {
    "alpha" -> 255f
    "scaleX",
    "scaleY" -> 1f
    // The centre, as `wffPlace` turns and scales about it.
    "pivotX",
    "pivotY" -> 0.5f
    "width" -> scene("width")
    "height" -> scene("height")
    else -> 0f
  }
}

/** The numeric attributes a face may drive with an expression, such as a ranged value's arc. */
private val WFF_NUMERIC_ATTRIBUTES =
  setOf(
    "x",
    "y",
    "width",
    "height",
    "angle",
    "alpha",
    "pivotX",
    "pivotY",
    "startAngle",
    "endAngle",
    "centerX",
    "centerY",
    "cornerRadiusX",
    "cornerRadiusY",
    "startX",
    "startY",
    "endX",
    "endY",
    "thickness",
    "shapeWidth",
    "shapeHeight",
  )

/**
 * [node] with each numeric attribute that holds an expression evaluated at [preview]: an arc's
 * `endAngle="[COMPLICATION.RANGED_VALUE_VALUE] * 3.6"`, a bar's `width`. An import keeps such a
 * value in `wffExtras` (a number property cannot hold it), so both places are read. An expression
 * the evaluator cannot read leaves the attribute as it was.
 */
internal fun wffResolvedNumbers(node: UiBuilderNode, preview: WffPreviewContext): UiBuilderNode {
  val extras =
    ((node.properties[WFF_EXTRAS] as? JsonObject)?.get("value") as? JsonPrimitive)
      ?.contentOrNull
      ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
  var properties = node.properties
  for (name in WFF_NUMERIC_ATTRIBUTES) {
    val own = node.properties[name] as? JsonObject
    val text =
      if (own != null)
        (own["value"] as? JsonPrimitive)?.contentOrNull?.takeIf {
          (own["type"] as? JsonPrimitive)?.contentOrNull == "string"
        }
      else (extras?.get(name) as? JsonPrimitive)?.contentOrNull
    if (text.isNullOrBlank()) continue
    val value = evaluateWffExpression(text, preview)?.asNumber() ?: continue
    properties =
      JsonObject(
        properties +
          (name to
            JsonObject(
              mapOf("type" to JsonPrimitive("number"), "value" to JsonPrimitive(value)),
            )),
      )
  }
  return if (properties === node.properties) node else node.copy(properties = properties)
}

/** The `wffExtras` property an import keeps unmodelled values in (the exporter's `EXTRAS`). */
private const val WFF_EXTRAS = "wffExtras"
