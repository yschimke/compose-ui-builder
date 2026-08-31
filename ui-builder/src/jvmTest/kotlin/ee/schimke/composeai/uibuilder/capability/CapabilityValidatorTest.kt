package ee.schimke.composeai.uibuilder.capability

import ee.schimke.composeai.uibuilder.UiBuilderDocument
import ee.schimke.composeai.uibuilder.UiBuilderNode
import ee.schimke.composeai.uibuilder.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class CapabilityValidatorTest {
  private val catalog by lazy {
    CapabilityCatalogParser.parse(resource("/jetcaster-discover-capabilities-v1.json"))
  }
  private val jetcasterDocument by lazy {
    val fixture =
      Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
    UiBuilderReducer.replay(fixture).document
  }
  private val validator by lazy { CapabilityValidator(catalog) }

  @Test
  fun `Jetcaster fixtures have complete component coverage and explicit Wasm gaps`() {
    val coverage = validator.coverage(jetcasterDocument)

    assertTrue(coverage.hasCompleteComponentCoverage)
    assertEquals(emptySet(), coverage.missingComponentIds)
    assertTrue(coverage.wasmStatuses.any { it.adapterStatus == WasmAdapterStatus.SUPPORTED })
    assertTrue(coverage.wasmStatuses.any { it.adapterStatus == WasmAdapterStatus.PLANNED })
    assertTrue(
      coverage.plannedOrUnsupported.any {
        it.componentId == "layout/supporting-pane-scaffold" &&
          it.adapterStatus == WasmAdapterStatus.UNSUPPORTED
      }
    )
  }

  @Test
  fun `complete Jetcaster operation fixture is structurally valid`() {
    val result =
      validateCapabilities(
        jetcasterDocument,
        resource("/jetcaster-discover-capabilities-v1.json"),
      )

    assertTrue(result.structurallyValid, result.issues.joinToString("\n") { it.message })
  }

  @Test
  fun `known supported Jetcaster node validates without substitution`() {
    val text = jetcasterDocument.nodes.getValue("search-placeholder")
    val document = isolated(text)

    val result = validator.validate(document)

    assertTrue(result.structurallyValid, result.issues.joinToString { it.message })
    assertTrue(result.wasmRenderable)
    assertEquals(WasmAdapterStatus.SUPPORTED, result.wasmStatuses.single().adapterStatus)
  }

  @Test
  fun `unknown component fails instead of being substituted`() {
    val document = isolated(validText().copy(componentId = "m3/not-a-real-component"))

    val result = validator.validate(document)

    assertIssue(result, CapabilityIssueCode.UNKNOWN_COMPONENT)
    assertFalse(result.wasmRenderable)
    assertTrue(result.wasmStatuses.isEmpty())
  }

  @Test
  fun `unknown property fails`() {
    val node =
      validText()
        .copy(
          properties =
            JsonObject(
              validText().properties + ("madeUp" to property("string", JsonPrimitive("x")))
            )
        )

    assertIssue(validator.validate(isolated(node)), CapabilityIssueCode.UNKNOWN_PROPERTY)
  }

  @Test
  fun `unknown modifier fails`() {
    val node =
      validText()
        .copy(modifiers = JsonArray(listOf(buildJsonObject { put("type", "paintItMagenta") })))

    assertIssue(validator.validate(isolated(node)), CapabilityIssueCode.UNKNOWN_MODIFIER)
  }

  @Test
  fun `required slot cardinality fails`() {
    val chip =
      UiBuilderNode(
        id = "chip",
        componentId = "m3/filter-chip",
        properties = JsonObject(mapOf("selected" to property("bool", JsonPrimitive(false)))),
      )

    assertIssue(validator.validate(isolated(chip)), CapabilityIssueCode.SLOT_CARDINALITY)
  }

  private fun validText() =
    UiBuilderNode(
      id = "text",
      componentId = "m3/text",
      properties = JsonObject(mapOf("text" to property("string", JsonPrimitive("Hello")))),
    )

  private fun isolated(node: UiBuilderNode) =
    UiBuilderDocument(
      schema = jetcasterDocument.schema,
      id = "capability-validator-test",
      title = "Capability validator test",
      revision = 1,
      catalogPin = jetcasterDocument.catalogPin,
      environment = jetcasterDocument.environment,
      stateVariables = JsonObject(emptyMap()),
      roots = listOf(node.id),
      nodes = mapOf(node.id to node),
    )

  private fun property(type: String, value: JsonPrimitive) = buildJsonObject {
    put("type", type)
    put("value", value)
  }

  private fun assertIssue(result: CapabilityValidationResult, code: CapabilityIssueCode) {
    assertFalse(result.structurallyValid)
    assertTrue(result.issues.any { it.code == code }, result.issues.joinToString { it.message })
  }

  private fun resource(path: String): String =
    checkNotNull(javaClass.getResource(path)) { "missing test resource $path" }.readText()
}
