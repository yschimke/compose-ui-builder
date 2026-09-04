package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityIssueCode
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * `minLines` / `maxLines` read as a stated number, and are simply not compared when they are not
 * one.
 *
 * The validator used to reach for `.jsonPrimitive` on whatever those properties held. A property
 * **bound to state** is an object with no `value` key, so that threw — out of the validator, which
 * runs on every write and, through `canBindToState`, on every composition of the inspector. Since
 * `m3/text` declares both properties, selecting any text node in a document that has a state
 * variable took the whole editor down.
 */
class BindableLineCountPropertyTest {
  private val catalog =
    CapabilityCatalogParser.parse(resource("/jetcaster-discover-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  /** An `m3/text` node, in a fixture that has a state variable for a binding to name. */
  private val textNodeId = "search-placeholder"

  @Test
  fun `probing a line-count property for a state binding answers instead of throwing`() {
    val state = reducer.initial(document, selectedNodeId = textNodeId)
    assertTrue(document.stateVariables.isNotEmpty(), "fixture must offer a variable to bind")

    // The reducer refuses an integer property a bare state read cannot satisfy — the useful answer,
    // and the one the inspector needs to decide whether to offer the binding at all.
    assertFalse(reducer.canBindToState(state, textNodeId, "minLines"))
    assertFalse(reducer.canBindToState(state, textNodeId, "maxLines"))
  }

  @Test
  fun `the whole inspector's property set can be probed`() {
    // What `UiBuilderEditor` does on every composition. One property in the set was enough to
    // throw.
    val state = reducer.initial(document, selectedNodeId = textNodeId)
    val fields = reducer.propertyFields(state)
    assertTrue(fields.any { it.name == "minLines" }, "m3/text declares minLines")
    assertEquals(
      emptyList(),
      fields.filter { reducer.canBindToState(state, textNodeId, it.name) }.map { it.name },
    )
  }

  @Test
  fun `a bound line count validates without a min-versus-max verdict`() {
    // A bound count has no value at author time, so there is nothing to compare it against. The
    // document is still walked; it simply says nothing about min versus max.
    val bound = document.withProperty(textNodeId, "minLines", stateBinding())
    val issues = CapabilityValidator(catalog).validate(bound).issues

    assertNull(
      issues.firstOrNull { it.message.contains("minLines must not exceed maxLines") },
      "a bound minLines has no number to compare",
    )
  }

  @Test
  fun `a stated line count is still compared`() {
    // The check the fix must not have quietly disabled.
    val inverted =
      document
        .withProperty(textNodeId, "minLines", JsonPrimitive(4))
        .withProperty(textNodeId, "maxLines", JsonPrimitive(2))
    val issues = CapabilityValidator(catalog).validate(inverted).issues

    assertEquals(
      listOf("minLines", "maxLines"),
      issues
        .filter { it.message.contains("minLines must not exceed maxLines") }
        .mapNotNull { it.field },
    )
    assertTrue(
      issues.any { it.code == CapabilityIssueCode.INVALID_PROPERTY_VALUE },
      "an inverted range is still an invalid value",
    )
  }

  private fun stateBinding(): JsonObject =
    JsonObject(
      mapOf(
        "type" to JsonPrimitive("state"),
        "variable" to JsonPrimitive(document.stateVariables.keys.first()),
      )
    )

  private fun UiBuilderDocument.withProperty(
    nodeId: String,
    name: String,
    value: JsonObject,
  ): UiBuilderDocument = withPropertyElement(nodeId, name, value)

  private fun UiBuilderDocument.withProperty(
    nodeId: String,
    name: String,
    value: JsonPrimitive,
  ): UiBuilderDocument = withPropertyElement(nodeId, name, value)

  private fun UiBuilderDocument.withPropertyElement(
    nodeId: String,
    name: String,
    value: kotlinx.serialization.json.JsonElement,
  ): UiBuilderDocument {
    val node = nodes.getValue(nodeId)
    return copy(
      nodes =
        nodes + (nodeId to node.copy(properties = JsonObject(node.properties + (name to value))))
    )
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
