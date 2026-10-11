package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Every component a Wear widget design can have as its root: the two fixed containers and the
 * adaptive one. Every "is this a widget?" check reads this set.
 */
val WEAR_WIDGET_CONTAINER_IDS: Set<String> =
  WearWidgetScaffoldSize.entries.map { it.componentId }.toSet() + AdaptiveWearWidget.COMPONENT_ID

/**
 * Experimental. A Wear widget authored once and adapted to the Small (216×76dp) or Large
 * (216×124dp) container the host gives it (`WearWidgetParams.containerType`):
 *
 * | slot         | Large                       | Small                         |
 * |--------------|-----------------------------|-------------------------------|
 * | `headline`   | top of a column             | start of a row                |
 * | `supporting` | middle, filling the space   | **hidden** — there is no room |
 * | `action`     | bottom of the column        | end of the row                |
 * | `background` | the container's brush chain | the same brush chain          |
 *
 * The template owns the arrangement; only the top level can be adaptive. [resolve] turns an
 * adaptive design into the ordinary fixed-container design for a size, so every surface (preview,
 * native render, export) reuses its fixed-container lane and the rule lives in one place.
 */
object AdaptiveWearWidget {
  const val COMPONENT_ID: String = "remote-m3/widget-container-adaptive"

  const val HEADLINE: String = "headline"
  const val SUPPORTING: String = "supporting"
  const val ACTION: String = "action"
  const val BACKGROUND: String = "background"

  /** The content slots, in the order a Large widget stacks them. */
  val CONTENT_SLOTS: List<String> = listOf(HEADLINE, SUPPORTING, ACTION)

  /** Which content slots a container of [size] shows. */
  fun visibleSlots(size: WearWidgetScaffoldSize): List<String> =
    when (size) {
      WearWidgetScaffoldSize.Large -> CONTENT_SLOTS
      WearWidgetScaffoldSize.Small -> listOf(HEADLINE, ACTION)
    }

  /** Whether this design's root is the adaptive container. */
  fun isAdaptive(document: UiBuilderDocument): Boolean =
    document.roots.singleOrNull()?.let(document.nodes::get)?.componentId == COMPONENT_ID

  /**
   * The fixed-container design this adaptive design becomes at [size], or the document unchanged
   * when its root is not adaptive.
   *
   * The root keeps its id, properties and background, becoming the fixed container. Its content is
   * an outer column (Large) or row (Small) holding a weighted text column and then the action,
   * using only `weight` and `spacedBy` so canvas and export agree. Authored node ids are preserved;
   * a hidden slot's subtree is removed entirely.
   */
  fun resolve(document: UiBuilderDocument, size: WearWidgetScaffoldSize): UiBuilderDocument {
    val rootId = document.roots.singleOrNull() ?: return document
    val root = document.nodes[rootId]?.takeIf { it.componentId == COMPONENT_ID } ?: return document
    val visible = visibleSlots(size)
    // Allocated against existing ids so a synthesized node cannot overwrite an authored one.
    val layoutId = document.freshId("$rootId-${size.name.lowercase()}")
    val textId = document.freshId("$layoutId-text", taken = setOf(layoutId))
    val large = size == WearWidgetScaffoldSize.Large
    val text =
      UiBuilderNode(
        id = textId,
        componentId = "layout/column",
        properties = JsonObject(mapOf("verticalSpacingDp" to number(TEXT_SPACING_DP))),
        modifiers =
          JsonArray(
            listOf(
              JsonObject(mapOf("type" to JsonPrimitive("weight"), "weight" to JsonPrimitive(1)))
            )
          ),
        slots = mapOf("children" to (visible - ACTION).flatMap { root.slots[it].orEmpty() }),
      )
    val layout =
      UiBuilderNode(
        id = layoutId,
        componentId = if (large) "layout/column" else "layout/row",
        properties =
          JsonObject(
            if (large) mapOf("verticalSpacingDp" to number(ACTION_SPACING_DP))
            else
              mapOf(
                "horizontalSpacingDp" to number(ACTION_SPACING_DP),
                "verticalAlignment" to enum("center"),
              )
          ),
        modifiers = JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("fillMaxSize"))))),
        slots = mapOf("children" to listOf(textId) + root.slots[ACTION].orEmpty()),
      )
    val container =
      root.copy(
        componentId = size.componentId,
        slots =
          mapOf(BACKGROUND to root.slots[BACKGROUND].orEmpty(), "content" to listOf(layoutId)),
      )
    val hidden =
      (CONTENT_SLOTS - visible.toSet())
        .flatMap { root.slots[it].orEmpty() }
        .flatMapTo(mutableSetOf()) { document.subtree(it) }
    return document.copy(
      nodes =
        document.nodes - hidden + (rootId to container) + (layoutId to layout) + (textId to text)
    )
  }

  /** Between the headline and the supporting line; the canvas reads it too. */
  const val TEXT_SPACING_DP: Int = 2

  /** Between the text and the action, on whichever axis the size stacks them. */
  const val ACTION_SPACING_DP: Int = 8

  /** A new adaptive widget with filled slots (a meeting reminder) so both sizes show something. */
  fun newDocument(
    designId: String,
    catalogPin: JsonObject,
    environment: JsonObject,
  ): UiBuilderDocument {
    require(designId.isNotBlank()) { "wear widget design id must not be blank" }
    val rootId = "wear-widget-adaptive"
    val nodes =
      listOf(
        UiBuilderNode(
          id = rootId,
          componentId = COMPONENT_ID,
          properties = JsonObject(mapOf("background" to colorToken("surfaceContainer"))),
          modifiers = JsonArray(emptyList()),
          slots =
            mapOf(
              BACKGROUND to emptyList(),
              HEADLINE to listOf("adaptive-headline"),
              SUPPORTING to listOf("adaptive-supporting"),
              ACTION to listOf("adaptive-action"),
            ),
        ),
        text("adaptive-headline", "Next meeting", "titleMedium", "onSurface"),
        text("adaptive-supporting", "10:30 · Room 4", "bodyMedium", "onSurfaceVariant"),
        UiBuilderNode(
          id = "adaptive-action",
          componentId = "remote-m3/remote-button",
          properties = JsonObject(emptyMap()),
          modifiers = JsonArray(emptyList()),
          slots = mapOf("content" to listOf("adaptive-action-label")),
        ),
        UiBuilderNode(
          id = "adaptive-action-label",
          componentId = REMOTE_TEXT_COMPONENT_ID,
          properties = JsonObject(mapOf("text" to literal("string", JsonPrimitive("Join")))),
          modifiers = JsonArray(emptyList()),
          slots = emptyMap(),
        ),
      )
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = designId,
      title = "Wear widget · $LABEL",
      revision = 0,
      catalogPin = catalogPin,
      environment = environment,
      stateVariables = JsonObject(emptyMap()),
      roots = listOf(rootId),
      nodes = nodes.associateBy(UiBuilderNode::id),
    )
  }

  /** How the container and its new-design template are named to a person. */
  const val LABEL: String = "Adaptive (experimental)"

  /** The new-design template id. */
  const val TEMPLATE_ID: String = "wear-widget-adaptive"

  /** [base], or `base-2`, `base-3`… — the first that no node and nothing in [taken] uses. */
  private fun UiBuilderDocument.freshId(base: String, taken: Set<String> = emptySet()): String =
    generateSequence(1) { it + 1 }
      .map { if (it == 1) base else "$base-$it" }
      .first { it !in nodes && it !in taken }

  private fun UiBuilderDocument.subtree(id: String): List<String> =
    listOf(id) + nodes[id]?.slots?.values.orEmpty().flatten().flatMap { subtree(it) }

  /**
   * `remote-m3/remote-text`, not `m3/text`, which the published `remote-m3` catalog does not
   * declare. [style] is a `RemoteTypography` Wear-scale role.
   */
  private fun text(id: String, text: String, style: String, color: String) =
    UiBuilderNode(
      id = id,
      componentId = REMOTE_TEXT_COMPONENT_ID,
      properties =
        JsonObject(
          mapOf(
            "text" to literal("string", JsonPrimitive(text)),
            "style" to enum(style),
            "color" to colorToken(color),
          )
        ),
      modifiers = JsonArray(emptyList()),
      slots = emptyMap(),
    )

  private fun enum(value: String) = literal("enum", JsonPrimitive(value))

  private fun number(value: Int) = literal("float", JsonPrimitive(value))

  private fun colorToken(token: String) = literal("colorToken", JsonPrimitive(token))

  private fun literal(type: String, value: JsonPrimitive): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to value))
}
