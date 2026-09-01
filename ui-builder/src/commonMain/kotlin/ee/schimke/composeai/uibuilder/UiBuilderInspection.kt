package ee.schimke.composeai.uibuilder

import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A stable, renderer-owned capture of the clean design layer.
 *
 * Coordinates are root render pixels, quantized to 1/64 pixel so browser/JS serialization does not
 * turn insignificant floating-point noise into a changed manifest. Slot bounds are the union of the
 * measured immediate children in that slot; an empty or entirely off-screen lazy slot has no
 * bounds.
 */
@Serializable
data class UiBuilderInspectionSnapshot(
  val schema: String = "compose-ui-builder-inspection/v1",
  val documentId: String,
  val documentRevision: Int,
  val coordinateSpace: String = "root-render-pixels",
  val coordinatePrecision: String = "1/64px",
  val generation: UiBuilderInspectionGeneration,
  val nodes: List<UiBuilderNodeInspection>,
  val slots: List<UiBuilderSlotInspection>,
)

/**
 * The expected inventories are authored document identities, including lazy/off-screen nodes.
 * `completed` means no renderer measurements changed for [stabilityFrames] browser frames; it does
 * not claim that virtualized nodes were composed.
 */
@Serializable
data class UiBuilderInspectionGeneration(
  val key: String,
  val completed: Boolean = false,
  val stabilityFrames: Int = 2,
  val expectedAuthoredNodeIds: List<String>,
  val expectedAuthoredTextNodeIds: List<String>,
  val measuredNodeIds: List<String>,
  val measuredTextNodeIds: List<String>,
)

@Serializable
data class UiBuilderNodeInspection(
  val nodeId: String,
  val componentId: String,
  val bounds: UiBuilderPixelBounds? = null,
  /** Absolute root-pixel baselines; present only for native text nodes that were laid out. */
  val text: UiBuilderTextInspection? = null,
  val semantics: UiBuilderSemanticsInspection,
)

@Serializable
data class UiBuilderSlotInspection(
  val parentNodeId: String,
  val slotName: String,
  val childNodeIds: List<String>,
  val measuredChildNodeIds: List<String>,
  val bounds: UiBuilderPixelBounds? = null,
)

@Serializable
data class UiBuilderPixelBounds(
  val x: Float,
  val y: Float,
  val width: Float,
  val height: Float,
) {
  val right: Float
    get() = x + width

  val bottom: Float
    get() = y + height
}

@Serializable
data class UiBuilderTextInspection(
  val text: String,
  val lineCount: Int,
  val firstBaselineY: Float,
  val lastBaselineY: Float,
)

@Serializable
data class UiBuilderSemanticsInspection(
  /** This is authored-node metadata, not Compose's merged accessibility semantics tree. */
  val source: String = "authored-node-properties",
  val role: String,
  val label: String? = null,
  val contentDescription: String? = null,
  val enabled: Boolean? = null,
  val selected: Boolean? = null,
  val actions: List<String> = emptyList(),
)

/** Mutable layout collector whose snapshots are stable regardless of measurement callback order. */
class UiBuilderInspectionCollector(
  private val document: UiBuilderDocument,
  private val onSnapshot: (UiBuilderInspectionSnapshot) -> Unit = {},
  private val onInvalidated: ((UiBuilderInspectionCollector) -> Unit)? = null,
) {
  private val bounds = mutableMapOf<String, UiBuilderPixelBounds>()
  private val textLayouts = mutableMapOf<String, LocalTextLayout>()
  private var state: Map<String, String?> = initialState(document)

  fun updateState(value: Map<String, String?>) {
    if (state == value) return
    state = value.toMap()
    publish()
  }

  fun recordNodeBounds(nodeId: String, left: Float, top: Float, right: Float, bottom: Float) {
    require(nodeId in document.nodes) { "unknown inspection node: $nodeId" }
    bounds[nodeId] =
      UiBuilderPixelBounds(
        x = left.quantized(),
        y = top.quantized(),
        width = (right - left).quantized(),
        height = (bottom - top).quantized(),
      )
    publish()
  }

  fun recordTextLayout(
    nodeId: String,
    lineCount: Int,
    firstBaseline: Float,
    lastBaseline: Float,
    contentOffsetY: Float = 0f,
  ) {
    require(document.nodes[nodeId]?.componentId == "m3/text") {
      "text layout belongs to a native text node: $nodeId"
    }
    textLayouts[nodeId] =
      LocalTextLayout(
        lineCount = lineCount,
        firstBaseline = (contentOffsetY + firstBaseline).quantized(),
        lastBaseline = (contentOffsetY + lastBaseline).quantized(),
      )
    publish()
  }

  private fun publish() {
    if (onInvalidated == null) onSnapshot(snapshot()) else onInvalidated.invoke(this)
  }

  fun snapshot(): UiBuilderInspectionSnapshot =
    UiBuilderInspectionSnapshot(
      documentId = document.id,
      documentRevision = document.revision,
      generation =
        UiBuilderInspectionGeneration(
          key = "${document.id}@${document.revision}",
          expectedAuthoredNodeIds = document.nodes.keys.sorted(),
          expectedAuthoredTextNodeIds =
            document.nodes.values.filter { it.componentId == "m3/text" }.map { it.id }.sorted(),
          measuredNodeIds = bounds.keys.sorted(),
          measuredTextNodeIds = textLayouts.keys.sorted(),
        ),
      nodes =
        document.nodes.values
          .sortedBy { it.id }
          .map { node ->
            val nodeBounds = bounds[node.id]
            val layout = textLayouts[node.id]
            UiBuilderNodeInspection(
              nodeId = node.id,
              componentId = node.componentId,
              bounds = nodeBounds,
              text =
                if (nodeBounds != null && layout != null) {
                  UiBuilderTextInspection(
                    text = node.propertyText("text").orEmpty(),
                    lineCount = layout.lineCount,
                    firstBaselineY = (nodeBounds.y + layout.firstBaseline).quantized(),
                    lastBaselineY = (nodeBounds.y + layout.lastBaseline).quantized(),
                  )
                } else null,
              semantics = node.inspectionSemantics(state),
            )
          },
      slots =
        document.nodes.values
          .flatMap { parent ->
            parent.slots.entries.map { (slotName, childIds) ->
              val measuredIds = childIds.filter(bounds::containsKey)
              UiBuilderSlotInspection(
                parentNodeId = parent.id,
                slotName = slotName,
                childNodeIds = childIds,
                measuredChildNodeIds = measuredIds,
                bounds = measuredIds.mapNotNull(bounds::get).unionBounds(),
              )
            }
          }
          .sortedWith(
            compareBy(UiBuilderSlotInspection::parentNodeId, UiBuilderSlotInspection::slotName)
          ),
    )

  private data class LocalTextLayout(
    val lineCount: Int,
    val firstBaseline: Float,
    val lastBaseline: Float,
  )
}

private fun initialState(document: UiBuilderDocument): Map<String, String?> =
  document.stateVariables.mapValues { (_, declaration) ->
    declaration.jsonObject["initialValue"]
      ?.takeUnless { it is JsonNull }
      ?.jsonPrimitive
      ?.contentOrNull
  }

private fun UiBuilderNode.inspectionSemantics(
  state: Map<String, String?>
): UiBuilderSemanticsInspection {
  val directSelected = propertyBoolean("selected")
  val selectedProperty = properties["selected"]?.jsonObject
  val selected =
    if (selectedProperty?.get("type")?.jsonPrimitive?.contentOrNull == "stateEquals") {
      state[selectedProperty["variable"]?.jsonPrimitive?.contentOrNull] ==
        selectedProperty["value"]?.jsonPrimitive?.contentOrNull
    } else directSelected
  return UiBuilderSemanticsInspection(
    role = componentId.substringAfterLast('/'),
    label = propertyText("text"),
    contentDescription = propertyText("contentDescription"),
    enabled =
      if (componentId in SEMANTICALLY_INTERACTIVE_COMPONENTS) propertyBoolean("enabled") ?: true
      else null,
    selected = selected,
    actions =
      eventBindings.entries
        .filter { (_, actions) -> actions is JsonArray && actions.isNotEmpty() }
        .map { it.key }
        .sorted(),
  )
}

private fun UiBuilderNode.propertyText(name: String): String? =
  properties[name]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull

private fun UiBuilderNode.propertyBoolean(name: String): Boolean? =
  properties[name]?.jsonObject?.get("value")?.jsonPrimitive?.booleanOrNull

private fun List<UiBuilderPixelBounds>.unionBounds(): UiBuilderPixelBounds? {
  if (isEmpty()) return null
  val left = minOf { it.x }
  val top = minOf { it.y }
  val right = maxOf { it.right }
  val bottom = maxOf { it.bottom }
  return UiBuilderPixelBounds(left, top, (right - left).quantized(), (bottom - top).quantized())
}

private fun Float.quantized(): Float {
  val value = (this * INSPECTION_PIXEL_SCALE).roundToInt() / INSPECTION_PIXEL_SCALE
  return if (value == -0f) 0f else value
}

private const val INSPECTION_PIXEL_SCALE = 64f

private val SEMANTICALLY_INTERACTIVE_COMPONENTS =
  setOf("m3/button", "m3/filter-chip", "m3/icon-button", "m3/search-input-field", "m3/tab")
