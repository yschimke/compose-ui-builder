package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.client.toProtocolSubmission
import ee.schimke.composeai.uibuilder.editor.EditorStateAction
import ee.schimke.composeai.uibuilder.editor.EditorSubmission
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.HostActionV1
import ee.schimke.composeai.uibuilder.protocol.SetEventBindingMutationV1
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The behaviour inspector authors a host action, and the edit reaches the protocol as one. */
class HostActionEditorTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject,
      )
      .document
  private val nodeId = "chip-comedy"

  private fun append(action: EditorStateAction) =
    reducer.reduce(
      reducer.initial(document, selectedNodeId = nodeId),
      UiBuilderEditorEvent.AppendAction(nodeId, "click", action, null),
    )

  @Test
  fun `a host action needs no state and keeps its value's type`() {
    val state = append(EditorStateAction.HostAction("calc.key", "7"))

    assertIs<CommandOutcome.Accepted>(state.lastOutcome)
    val written = state.document.nodes.getValue(nodeId).eventBindings["click"] as JsonArray
    assertEquals(
      Json.parseToJsonElement("""{"type":"hostAction","name":"calc.key","value":7}"""),
      written.last(),
    )

    val text = append(EditorStateAction.HostAction("calc.key", "\"7\""))
    assertEquals(
      JsonPrimitive("7"),
      ((text.document.nodes.getValue(nodeId).eventBindings["click"] as JsonArray)
        .last()
        .jsonObject)["value"],
    )
    val bare = append(EditorStateAction.HostAction(" calc.clear ", ""))
    assertEquals(
      Json.parseToJsonElement("""{"type":"hostAction","name":"calc.clear"}"""),
      (bare.document.nodes.getValue(nodeId).eventBindings["click"] as JsonArray).last(),
    )
  }

  @Test
  fun `a host action an export could not send is refused where it is authored`() {
    val state = append(EditorStateAction.HostAction(" ", "7"))

    val rejected = assertIs<CommandOutcome.Rejected>(state.lastOutcome)
    assertContains(rejected.message, "needs a name")
  }

  @Test
  fun `the edit reaches the protocol as a host action`() {
    val operation =
      DesignOperation.SetEventBinding(
        nodeId,
        "click",
        JsonArray(
          listOf(
            Json.parseToJsonElement("""{"type":"hostAction","name":"calc.key","value":"7"}"""),
          ),
        ),
      )
    val submission =
      EditorSubmission.Batch(DesignCommand("d", "o", "a", "c", 1, listOf(operation)))
        .toProtocolSubmission(actorId = "a", clientId = "c", authoritativeRevision = 1)
    val mutation =
      assertIs<SetEventBindingMutationV1>(assertIs<DesignCommandV1>(submission).operations.single())
    assertEquals(
      listOf(HostActionV1.Builder("calc.key").apply { value = JsonPrimitive("7") }.build()),
      mutation.actions,
    )
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
