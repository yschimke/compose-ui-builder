package ee.schimke.composeai.uibuilder.canvas

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlImporter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [node] with its `wff/transform` children applied at [preview]'s time, in document order: each
 * evaluates the expression the exporter writes for it ([WatchFaceXmlExporter.transformOf], so a
 * "Rotate with" preset is its preset's expression) and sets the property its `target` names, or
 * adds to it with `mode="BY"`. A `<Transform>` held as `wff/raw` XML, as a design imported before
 * the transform had a builtin holds it, is read as the typed node an import would make of it.
 *
 * A `wff/gyro` changes nothing: the preview holds the watch flat, where the tilt is zero.
 */
internal fun wffTransformed(
  document: UiBuilderDocument,
  node: UiBuilderNode,
  preview: WffPreviewContext,
): UiBuilderNode {
  var properties = node.properties
  for (id in node.slots.values.flatten()) {
    val child = document.nodes[id] ?: continue
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
    properties =
      JsonObject(
        properties +
          (target to
            JsonObject(
              mapOf("type" to JsonPrimitive("number"), "value" to JsonPrimitive(result.toDouble())),
            )),
      )
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
    "width" -> scene("width")
    "height" -> scene("height")
    else -> 0f
  }
}
