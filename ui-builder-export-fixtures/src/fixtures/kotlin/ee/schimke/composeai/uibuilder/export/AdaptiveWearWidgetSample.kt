package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A new adaptive widget with filled slots (a meeting reminder) so both sizes show something. */
fun AdaptiveWearWidget.newDocument(
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
    title = "Wear widget · Adaptive (experimental)",
    revision = 0,
    catalogPin = catalogPin,
    environment = environment,
    stateVariables = JsonObject(emptyMap()),
    roots = listOf(rootId),
    nodes = nodes.associateBy(UiBuilderNode::id),
  )
}

/**
 * `remote-m3/remote-text`, not `m3/text`, which the published `remote-m3` catalog does not declare.
 * [style] is a `RemoteTypography` Wear-scale role.
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

private fun colorToken(token: String) = literal("colorToken", JsonPrimitive(token))

private fun literal(type: String, value: JsonPrimitive): JsonObject =
  JsonObject(mapOf("type" to JsonPrimitive(type), "value" to value))
