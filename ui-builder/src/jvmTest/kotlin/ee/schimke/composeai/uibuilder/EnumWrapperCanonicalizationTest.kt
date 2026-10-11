package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityBenchmark
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.capability.ComponentCapability
import ee.schimke.composeai.uibuilder.capability.PropertyCapability
import ee.schimke.composeai.uibuilder.capability.WasmAdapterStatus
import ee.schimke.composeai.uibuilder.capability.WasmCapability
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One wrapper per property, decided on the write: `enum` is canonical for an `allowedValues`
 * property like `m3/text.style` (see #339). Rationale, and why it's write-time only, in
 * `CapabilityValidator.writeWrapperIssue`. The wrong spelling is rejected where a person chooses
 * it; existing designs holding it stay editable.
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
    // The message must name the wrapper so it is actionable from the builder.
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

  /**
   * The write path refuses an invented wrapper (`CollaborationReducer` asks
   * `propertyWrapperIssue`), as `MALFORMED_PROPERTY`. The document path (`UiBuilderReducer.replay`)
   * is covered by `CapabilityValidator.validate`; `WrapperVocabularyTest` holds the shared
   * vocabulary.
   */
  @Test
  fun `an invented wrapper type is rejected on the write, by node and field`() {
    val application =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command(
          DesignOperation.SetProperty(
            "text",
            "text",
            buildJsonObject {
              put("type", "fixedGrid")
              put("columns", 7)
            },
          )
        ),
        validator(),
      )

    val rejected = assertIs<CommandOutcome.Rejected>(application.outcome)
    assertEquals(RejectionCode.MALFORMED_PROPERTY, rejected.code)
    assertTrue("fixedGrid" in rejected.message, rejected.message)
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
