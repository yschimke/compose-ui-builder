package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Every component a Wear widget design can have as its root: the two fixed containers and the
 * adaptive one. Each check that asks "is this a widget?" reads this set, so the adaptive container
 * is a widget everywhere the fixed ones are.
 */
val WEAR_WIDGET_CONTAINER_IDS: Set<String> =
  WearWidgetScaffoldSize.entries.map { it.componentId }.toSet() + AdaptiveWearWidget.COMPONENT_ID

/**
 * **Experimental.** A Wear widget authored once and adapted to whichever container the host gives
 * it.
 *
 * A widget is placed in a Small (216×76dp) or a Large (216×124dp) container, and the launcher tells
 * it which through `WearWidgetParams.containerType`. The two fixed containers,
 * `remote-m3/widget-container-small` and `-large`, make a designer pick one; this one takes the
 * content of the Large widget as named slots and decides how each slot survives the move to Small:
 *
 * | slot         | Large                       | Small                         |
 * |--------------|-----------------------------|-------------------------------|
 * | `headline`   | top of a column             | start of a row                |
 * | `supporting` | middle, filling the space   | **hidden** — there is no room |
 * | `action`     | bottom of the column        | end of the row                |
 * | `background` | the container's brush chain | the same brush chain          |
 *
 * The rule is fixed, which is what makes this a template rather than a layout: the designer fills
 * slots and the template owns the arrangement. Only the top level of a widget can be adaptive,
 * because only the top level is told its container size.
 *
 * ## One resolution, read by every surface
 *
 * Nothing downstream learns a third container. [resolve] turns an adaptive design into the ordinary
 * Small or Large design it becomes at that size — a `layout/row` or `layout/column` holding the
 * slot contents, inside the fixed container — so the preview panes, the native render and the code
 * export each run the lane that already exists for a fixed container, twice. The adaptive rule
 * therefore lives in exactly one place, and a surface cannot draw one arrangement while the export
 * writes another.
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
   * The root keeps its id, its properties and its background slot, and becomes the fixed container
   * for [size]. Its content is two synthesized layout nodes: an outer column (Large) or row (Small)
   * holding a weighted inner column for the text slots and, after it, the action. The weight gives
   * the text whatever the action leaves, so the action sits at the bottom of a Large widget and at
   * the end of a Small one — with nothing but `weight` and `spacedBy`, which the Remote Compose
   * emitter writes, so the canvas and the export lay it out the same way.
   *
   * Every node the designer placed keeps its id through the resolution, so a selection, a comment
   * or a diagnostic about `adaptive-headline` still names the node the designer placed. A slot the
   * size hides takes its subtree out of the document entirely, so nothing downstream has to know to
   * skip it.
   */
  fun resolve(document: UiBuilderDocument, size: WearWidgetScaffoldSize): UiBuilderDocument {
    val rootId = document.roots.singleOrNull() ?: return document
    val root = document.nodes[rootId]?.takeIf { it.componentId == COMPONENT_ID } ?: return document
    val visible = visibleSlots(size)
    val layoutId = "$rootId-${size.name.lowercase()}"
    val textId = "$layoutId-text"
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

  /**
   * A new adaptive widget, its slots already filled so both sizes have something to show.
   *
   * A meeting reminder, because it is the shape the rule is for: the headline is what a glance
   * needs, the supporting line is what a Large widget has room to add, and the action is what the
   * wearer taps — so the Small widget keeps the headline and the button and drops the detail.
   */
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
          componentId = "m3/text",
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

  private fun UiBuilderDocument.subtree(id: String): List<String> =
    listOf(id) + nodes[id]?.slots?.values.orEmpty().flatten().flatMap { subtree(it) }

  private fun text(id: String, text: String, style: String, color: String) =
    UiBuilderNode(
      id = id,
      componentId = "m3/text",
      properties =
        JsonObject(
          mapOf(
            "text" to literal("string", JsonPrimitive(text)),
            "style" to literal("typographyToken", JsonPrimitive(style)),
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
