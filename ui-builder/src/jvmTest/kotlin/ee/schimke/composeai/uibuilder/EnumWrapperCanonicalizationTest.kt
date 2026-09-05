package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityBenchmark
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.capability.ComponentCapability
import ee.schimke.composeai.uibuilder.capability.PropertyCapability
import ee.schimke.composeai.uibuilder.capability.WasmAdapterStatus
import ee.schimke.composeai.uibuilder.capability.WasmCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One wrapper per property, decided on the write.
 *
 * The catalog declares `m3/text.style` as `jsonType: "string"` with an `allowedValues` list, and
 * both `{"type":"enum","value":"bodyLarge"}` and `{"type":"string","value":"bodyLarge"}` were
 * accepted, committed and rendered — the reducer treated the two literal wrappers alike and the
 * renderer reads both through `string(name)`. Nothing anywhere said which one the property wanted,
 * and downstream they took unrelated export paths (issue #339).
 *
 * `enum` is canonical. The reasoning and the reason this is a **write-time** rule rather than a
 * document-wide one are in `CapabilityValidator.writeWrapperIssue`; what these tests hold is the
 * behaviour: the wrong spelling is rejected where a person chooses it, and a design that already
 * holds it stays editable everywhere else.
 */
class EnumWrapperCanonicalizationTest {

  @Test
  fun `a string wrapper on a property with allowed values is rejected, by node and field`() {
    val application =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command(DesignOperation.SetProperty("text", "style", typed("string", "bodyLarge"))),
        validator(),
      )

    val rejected = assertIs<CommandOutcome.Rejected>(application.outcome)
    assertEquals(RejectionCode.INVALID_PROPERTY, rejected.code)
    assertEquals("text", rejected.nodeId)
    assertEquals("style", rejected.field)
    // The message has to name the wrapper. The failure this replaces was an export refusal about
    // `TextStyle` on a document that rendered correctly, which named neither the wrapper nor
    // anything a person could act on from the builder.
    assertTrue(rejected.message.contains("`enum`"), rejected.message)
    assertTrue(rejected.message.contains("`string`"), rejected.message)
  }

  @Test
  fun `the enum wrapper is accepted for the same value`() {
    val application =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command(DesignOperation.SetProperty("text", "style", typed("enum", "bodyLarge"))),
        validator(),
      )

    assertIs<CommandOutcome.Accepted>(application.outcome)
  }

  @Test
  fun `a plain string property is untouched by the rule`() {
    // The rule keys off `allowedValues`, not off the wrapper being `string`. A property that really
    // is free text has to keep taking free text, or every label in every design becomes unwritable.
    val application =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command(DesignOperation.SetProperty("text", "text", typed("string", "Discover"))),
        validator(),
      )

    assertIs<CommandOutcome.Accepted>(application.outcome)
  }

  private fun typed(type: String, value: String): JsonObject = buildJsonObject {
    put("type", type)
    put("value", value)
  }

  private fun command(vararg operations: DesignOperation): DesignCommand =
    DesignCommand(
      designId = "design",
      operationId = "wrapper",
      actorId = "actor-a",
      clientId = "browser-a",
      baseRevision = 0,
      operations = operations.toList(),
    )

  private fun document(): UiBuilderDocument {
    val text = UiBuilderNode(id = "text", componentId = "m3/text")
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1",
      id = "design",
      title = "Wrapper",
      revision = 0,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf(text.id),
      nodes = mapOf(text.id to text),
    )
  }

  private fun validator(): CapabilityPropertyWriteValidator {
    val catalog =
      CapabilityCatalog(
        schema = "compose-ui-builder-capabilities/v1",
        benchmark =
          CapabilityBenchmark(
            id = "wrapper-test",
            sourceRevision = "test",
            catalogSystemId = "test-catalog",
            catalogRevision = "1",
            nativeRuntimeId = "test-runtime",
          ),
        components =
          listOf(
            ComponentCapability(
              componentId = "m3/text",
              displayName = "Text",
              role = "Leaf",
              properties =
                listOf(
                  PropertyCapability(name = "text", jsonType = JsonPrimitive("string")),
                  PropertyCapability(
                    name = "style",
                    jsonType = JsonPrimitive("string"),
                    allowedValues =
                      listOf(JsonPrimitive("bodyLarge"), JsonPrimitive("headlineSmall")),
                  ),
                ),
              wasm =
                WasmCapability(
                  platformSupported = JsonPrimitive(true),
                  adapterStatus = WasmAdapterStatus.SUPPORTED,
                ),
            )
          ),
      )
    return CapabilityPropertyWriteValidator(CapabilityValidator(catalog))
  }
}
