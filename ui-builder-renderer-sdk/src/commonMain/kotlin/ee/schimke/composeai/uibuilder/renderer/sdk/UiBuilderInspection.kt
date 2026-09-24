package ee.schimke.composeai.uibuilder.renderer.sdk

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererInspectionGenerationV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererInspectionV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererNodeInspectionV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererPixelBoundsV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSemanticsInspectionV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSlotInspectionV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererTextInspectionV1
import kotlin.math.roundToInt
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
typealias UiBuilderInspectionSnapshot = UiBuilderRendererInspectionV1

/**
 * The expected inventories are authored document identities, including lazy/off-screen nodes.
 * `completed` means no renderer measurements changed for [stabilityFrames] browser frames; it does
 * not claim that virtualized nodes were composed.
 */
typealias UiBuilderInspectionGeneration = UiBuilderRendererInspectionGenerationV1

typealias UiBuilderNodeInspection = UiBuilderRendererNodeInspectionV1

typealias UiBuilderSlotInspection = UiBuilderRendererSlotInspectionV1

typealias UiBuilderPixelBounds = UiBuilderRendererPixelBoundsV1

val UiBuilderPixelBounds.right: Float
  get() = x + width

val UiBuilderPixelBounds.bottom: Float
  get() = y + height

typealias UiBuilderTextInspection = UiBuilderRendererTextInspectionV1

typealias UiBuilderSemanticsInspection = UiBuilderRendererSemanticsInspectionV1

/** Whether a catalog adapter draws one of the protocol's native text node kinds. */
fun String.isUiBuilderTextComponent(): Boolean =
  this == "material3/Text" || this == "m3/text" || this == "wear-m3/text"

/** Mutable layout collector whose snapshots are stable regardless of measurement callback order. */
class UiBuilderInspectionCollector(
  private val document: UiBuilderDocument,
  private val onSnapshot: (UiBuilderInspectionSnapshot) -> Unit = {},
  private val onInvalidated: ((UiBuilderInspectionCollector) -> Unit)? = null,
  /**
   * Which adapter draws each component, where the catalog names one.
   *
   * Needed because "is this a text node" is a question about what DREW the node, not about its id.
   * The canvas keys its dispatch on the adapter id (`UI_BUILDER_CATALOG_CONTRACT.md` item 17), so a
   * component the catalog points at the text adapter reports a text layout exactly as `m3/text`
   * does — and a check keyed on the component id alone rejects it, which is an
   * `IllegalArgumentException` out of a render rather than a wrong picture.
   *
   * Empty for every catalog today, which makes this inherit the previous behaviour exactly.
   */
  private val canvasAdapterIds: Map<String, String> = emptyMap(),
) {
  /** The id the canvas drew this node with: the catalog's adapter, or the component's own id. */
  private fun adapterFor(nodeId: String): String? =
    document.nodes[nodeId]?.componentId?.let { canvasAdapterIds[it] ?: it }

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
    // Both platforms' text ids. This used to ask the canvas's stand-in table whether the id mapped
    // to `m3/text`, back when `wear-m3/text` was drawn as one — keyed on the mobile id alone it
    // failed the first Wear render after the rename. Wear text is drawn by Wear's own `Text` now,
    // and the question here was never about drawing: a node is a text node if it is one.
    require(adapterFor(nodeId)?.isUiBuilderTextComponent() == true) {
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

  /** Publishes the current generation even before layout has measured any node bounds. */
  fun publishSnapshot() = publish()

  fun snapshot(): UiBuilderInspectionSnapshot =
    UiBuilderInspectionSnapshot(
      documentId = document.id,
      documentRevision = document.revision,
      generation =
        UiBuilderInspectionGeneration(
          key = "${document.id}@${document.revision}",
          expectedAuthoredNodeIds = document.nodes.keys.sorted(),
          expectedAuthoredTextNodeIds =
            document.nodes.values
              .filter {
                (canvasAdapterIds[it.componentId] ?: it.componentId).isUiBuilderTextComponent()
              }
              .map { it.id }
              .sorted(),
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
