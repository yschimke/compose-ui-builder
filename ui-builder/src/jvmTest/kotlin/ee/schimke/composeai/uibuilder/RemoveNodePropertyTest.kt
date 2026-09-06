package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A property can be unset again (yschimke/compose-preview-server#480).
 *
 * `setProperty` had no inverse: an author who tried a property and found it wrong could change its
 * value but not the shape of the node. `RemoveNodeProperty` takes an optional property off the node
 * so the component's own default applies, refuses a required one by name, and is undone and redone
 * like any other scalar write.
 */
class RemoveNodePropertyTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val validator = CapabilityPropertyWriteValidator(CapabilityValidator(catalog))

  @Test
  fun `an optional property is taken off the node, and the operation round trips`() {
    val remove = DesignOperation.RemoveNodeProperty("text", "color")
    val application =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command("remove", remove),
        validator,
      )

    assertIs<CommandOutcome.Accepted>(application.outcome, application.outcome.toString())
    assertFalse("color" in application.state.document.nodes.getValue("text").properties)
    val encoded = Json.encodeToString(command("remove", remove))
    assertTrue(encoded.contains("\"type\":\"removeNodeProperty\""), encoded)
    assertEquals(command("remove", remove), Json.decodeFromString<DesignCommand>(encoded))
  }

  @Test
  fun `a required property cannot be unset, and the refusal names it`() {
    val application =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command("remove", DesignOperation.RemoveNodeProperty("text", "text")),
        validator,
      )

    val rejected = assertIs<CommandOutcome.Rejected>(application.outcome)
    assertEquals(RejectionCode.INVALID_PROPERTY, rejected.code)
    assertEquals("text", rejected.nodeId)
    assertEquals("text", rejected.field)
    assertTrue(rejected.message.contains("required property text"), rejected.message)
    assertEquals(typed("string", "Hello"), application.state.nodeProperty("text", "text"))
  }

  @Test
  fun `unsetting a property the node does not hold is accepted as a no-op`() {
    val application =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command("remove", DesignOperation.RemoveNodeProperty("text", "maxLines")),
        validator,
      )

    assertIs<CommandOutcome.Accepted>(application.outcome, application.outcome.toString())
    assertNull(application.state.nodeProperty("text", "maxLines"))
  }

  @Test
  fun `undo puts the value back and redo takes it off again`() {
    val removed =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command("remove", DesignOperation.RemoveNodeProperty("text", "color")),
        validator,
      )
    assertIs<CommandOutcome.Accepted>(removed.outcome)

    val undone =
      CollaborationReducer.undo(
        removed.state,
        UndoCommand("design", "undo", "actor-a", "browser-a", 1, "remove"),
      )
    assertIs<CommandOutcome.Accepted>(undone.outcome, undone.outcome.toString())
    assertEquals(typed("color", "#5F6368"), undone.state.nodeProperty("text", "color"))

    val redone =
      CollaborationReducer.redo(
        undone.state,
        RedoCommand("design", "redo", "actor-a", "browser-a", 2, "undo"),
      )
    assertIs<CommandOutcome.Accepted>(redone.outcome, redone.outcome.toString())
    assertNull(redone.state.nodeProperty("text", "color"))
  }

  @Test
  fun `a removal needs the capability validator, like a write`() {
    val application =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command("remove", DesignOperation.RemoveNodeProperty("text", "color")),
        propertyValidator = null,
      )

    assertEquals(
      RejectionCode.MISSING_PROPERTY_VALIDATOR,
      assertIs<CommandOutcome.Rejected>(application.outcome).code,
    )
  }

  private fun CollaborationState.nodeProperty(nodeId: String, property: String) =
    document.nodes.getValue(nodeId).properties[property]

  private fun typed(type: String, value: String): JsonObject = buildJsonObject {
    put("type", type)
    put("value", value)
  }

  private fun command(operationId: String, vararg operations: DesignOperation): DesignCommand =
    DesignCommand(
      designId = "design",
      operationId = operationId,
      actorId = "actor-a",
      clientId = "browser-a",
      baseRevision = 0,
      operations = operations.toList(),
    )

  private fun document(): UiBuilderDocument {
    val text =
      UiBuilderNode(
        id = "text",
        componentId = "m3/text",
        properties =
          JsonObject(
            mapOf("text" to typed("string", "Hello"), "color" to typed("color", "#5F6368"))
          ),
      )
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1",
      id = "design",
      title = "Remove",
      revision = 0,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf(text.id),
      nodes = mapOf(text.id to text),
    )
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
