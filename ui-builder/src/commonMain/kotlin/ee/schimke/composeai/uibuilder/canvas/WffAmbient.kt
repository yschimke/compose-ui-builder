package ee.schimke.composeai.uibuilder.canvas

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The environment entry a preview pane sets to draw a watch face in ambient mode. It is on the
 * pane's copy of the document only: never saved, never exported.
 */
internal const val WFF_AMBIENT_ENV = "wffAmbient"

/** Whether [this] is a Watch Face Format design, whose scene is its root. */
internal fun UiBuilderDocument.isWatchFace(): Boolean =
  roots.firstNotNullOfOrNull { nodes[it] }?.componentId == WatchFaceXmlExporter.SCENE

/** Whether the surface drawing [this] asks for ambient mode. */
internal fun UiBuilderDocument.isWffAmbient(): Boolean =
  (environment[WFF_AMBIENT_ENV] as? JsonPrimitive)?.contentOrNull == "true"

/** [this] drawn in ambient mode, or in the interactive one. */
internal fun UiBuilderDocument.withWffAmbient(ambient: Boolean): UiBuilderDocument {
  if (isWffAmbient() == ambient) return this
  return copy(
    environment =
      JsonObject(
        if (ambient) environment + (WFF_AMBIENT_ENV to JsonPrimitive("true"))
        else environment - WFF_AMBIENT_ENV,
      ),
  )
}

/**
 * Whether the face moves between whole seconds: it reads `[SECOND_MILLISECOND]` (a sweeping second
 * hand), `[MILLISECOND]` or `[UTC_TIMESTAMP]`, or has a `<Gyro>`, which the preview sways. Without
 * either the face only changes each whole second, and is redrawn that often.
 */
internal fun UiBuilderDocument.wffReadsSubSecond(): Boolean =
  wffHasGyro() ||
    nodes.values.any { node ->
      node.properties.entries.any { (name, property) ->
        val value = ((property as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull
        // A transform's "Rotate with" preset stands for its expression.
        val expression =
          if (node.componentId == WatchFaceXmlExporter.TRANSFORM && name == "rotateWith")
            WatchFaceXmlExporter.ROTATE_WITH[value]
          else value
        expression != null && SUB_SECOND_SOURCE.containsMatchIn(expression)
      }
    }

/**
 * [node] as the watch draws it at [preview]'s time and in its mode: its expression-valued numbers
 * evaluated ([wffResolvedNumbers]), its transforms applied ([wffTransformed]), then, in ambient,
 * the `<Variant mode="AMBIENT" target value>` children, each replacing the property its `target`
 * names (a number when `value` evaluates to one, else the text).
 */
internal fun wffNodeIn(
  document: UiBuilderDocument,
  node: UiBuilderNode,
  preview: WffPreviewContext,
): UiBuilderNode {
  val moved = wffTransformed(document, wffResolvedNumbers(node, preview), preview)
  if (!preview.ambient) return moved
  var properties = moved.properties
  for (id in node.slots.values.flatten()) {
    val variant = document.nodes[id]?.takeIf { it.componentId == WatchFaceXmlExporter.VARIANT }
    if (variant == null || variant.string("mode").ifEmpty { "AMBIENT" } != "AMBIENT") continue
    val target = variant.string("target")
    val raw = variant.string("value")
    if (target.isEmpty()) continue
    val number = evaluateWffExpression(raw, preview) as? WffValue.Num
    val encoded =
      if (number != null)
        JsonObject(
          mapOf("type" to JsonPrimitive("number"), "value" to JsonPrimitive(number.value)),
        )
      else JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(raw)))
    properties = JsonObject(properties + (target to encoded))
  }
  return if (properties === moved.properties) moved else moved.copy(properties = properties)
}

/**
 * Whether [node] is drawn at all in [preview]'s mode: ambient hides what its variants make clear.
 */
internal fun wffVisibleIn(node: UiBuilderNode, preview: WffPreviewContext): Boolean =
  !preview.ambient || node.float("alpha", 255f) > 0f

/** Whether a part of the face moves with the wrist's tilt, which the preview sways. */
internal fun UiBuilderDocument.wffHasGyro(): Boolean =
  nodes.values.any { it.componentId == WatchFaceXmlExporter.GYRO }

/**
 * A source that changes within a second, bracketed as an expression reads it: the parser trims the
 * name, so `[ SECOND_MILLISECOND ]` is the same source.
 */
private val SUB_SECOND_SOURCE = Regex("""\[\s*(SECOND_MILLISECOND|MILLISECOND|UTC_TIMESTAMP)\s*]""")
