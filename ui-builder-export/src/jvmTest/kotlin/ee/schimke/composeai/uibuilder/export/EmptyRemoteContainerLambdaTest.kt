package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * An empty container still writes a content lambda unless its call has an overload without one:
 * only `RemoteBox(modifier)` and `RemoteFitBox` do, so an empty spacer cell with a
 * `contentAlignment`, or an empty column, would not compile as a bare call.
 */
class EmptyRemoteContainerLambdaTest {
  private fun typed(type: String, value: String) =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))

  private fun emitted(componentId: String, properties: Map<String, JsonObject>): String {
    val cell =
      UiBuilderNode(
        id = "cell",
        componentId = componentId,
        properties = JsonObject(properties),
        slots = mapOf("children" to emptyList()),
      )
    val inline =
      UiBuilderNode(
        id = "inline",
        componentId = REMOTE_COMPOSE_INLINE_COMPONENT_ID,
        slots = mapOf("content" to listOf("cell")),
      )
    val document =
      wearWidgetUiBuilderDocument(
          "cell",
          JsonObject(emptyMap()),
          JsonObject(emptyMap()),
          WearWidgetScaffoldSize.Small,
        )
        .let { it.copy(nodes = it.nodes + listOf(cell, inline).associateBy { node -> node.id }) }
    val result = InlineRemoteContentExporter.export(document, "inline")
    return assertIs<InlineRemoteContentExporter.Result.Emitted>(result, "$result").source
  }

  @Test
  fun `an empty aligned box writes an empty lambda`() {
    val source = emitted("layout/box", mapOf("contentAlignment" to typed("enum", "center")))
    assertTrue("RemoteBox(contentAlignment = RemoteAlignment.Center) {}" in source, source)
  }

  @Test
  fun `an empty unaligned box stays the spacer overload`() {
    val source = emitted("layout/box", emptyMap())
    assertTrue(
      Regex("""RemoteBox\(\)\s*$""", RegexOption.MULTILINE).containsMatchIn(source),
      source,
    )
  }

  @Test
  fun `an empty column writes an empty lambda`() {
    val source = emitted("layout/column", emptyMap())
    assertTrue("RemoteColumn {}" in source, source)
  }
}
