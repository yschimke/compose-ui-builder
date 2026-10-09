package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorStateAction
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.export.UiExpressions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** `= count + 2` in an action's value is a formula, stored as the tree the inspector shows back. */
class FormulaActionEditorTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document
      .let {
        it.copy(
          stateVariables =
            JsonObject(
              it.stateVariables +
                Json.parseToJsonElement(
                    """{"count":{"type":"value","valueType":"int","nullable":false,
                      "initialValue":0,"persistence":"preview"}}"""
                  )
                  .jsonObject
            )
        )
      }
  private val nodeId = "chip-comedy"

  private fun append(action: EditorStateAction) =
    reducer.reduce(
      reducer.initial(document, selectedNodeId = nodeId),
      UiBuilderEditorEvent.AppendAction(nodeId, "click", action, null),
    )

  @Test
  fun `a value starting with = is stored as a formula`() {
    val state = append(EditorStateAction.Set("count", "= count + 2"))

    assertIs<CommandOutcome.Accepted>(state.lastOutcome)
    val written =
      (state.document.nodes.getValue(nodeId).eventBindings["click"] as JsonArray).last()
        as JsonObject
    assertEquals("count + 2", UiExpressions.format(written.getValue("value")))
  }

  @Test
  fun `a formula amount increments by what it computes`() {
    val state = append(EditorStateAction.Increment("count", "=count * 2"))

    assertIs<CommandOutcome.Accepted>(state.lastOutcome)
    val written =
      (state.document.nodes.getValue(nodeId).eventBindings["click"] as JsonArray).last()
        as JsonObject
    assertEquals("count * 2", UiExpressions.format(written.getValue("amount")))
  }

  @Test
  fun `a formula that does not parse or type is refused, and a plain value is still a literal`() {
    assertIs<CommandOutcome.Rejected>(
      append(EditorStateAction.Set("count", "= count +")).lastOutcome
    )
    val wrongKind = append(EditorStateAction.Set("count", "= count / 2.5"))
    val outcome = assertIs<CommandOutcome.Rejected>(wrongKind.lastOutcome)
    assertTrue("count" in outcome.message, outcome.message)
    assertIs<CommandOutcome.Accepted>(append(EditorStateAction.Set("count", "7")).lastOutcome)
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
