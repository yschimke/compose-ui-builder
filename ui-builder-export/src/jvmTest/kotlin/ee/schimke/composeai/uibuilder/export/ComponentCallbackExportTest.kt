package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ScreenParameter
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * A design's components export as `internal` composables a catalog calls directly: a binding read
 * is a value parameter named for its key, and an event with no actions on a node inside a component
 * is a callback named for the node and the event. The screen itself still refuses a handler that
 * does nothing, and an event that writes state is still stateful authoring.
 */
class ComponentCallbackExportTest {
  private val json = Json { ignoreUnknownKeys = true }

  private val root =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .first { File(it, "docs/design/fixtures/ui-builder/m3-catalog-components-v1.json").isFile }

  /** The m3 record plus the foundation one, for the `Box` a component body is wrapped in. */
  private val record: ComponentRecordFile = run {
    fun read(name: String) =
      json.decodeFromString<ComponentRecordFile>(
        File(root, "docs/design/fixtures/ui-builder/$name").readText()
      )
    val m3 = read("m3-catalog-components-v1.json")
    m3
      .newBuilder()
      .also { b ->
        b.components = m3.components + read("compose-foundation-components-v1.json").components
      }
      .build()
  }

  private fun document(
    actions: String = "",
    label: String = """{"type": "string", "value": "Go"}""",
  ) =
    json.decodeFromString<DesignDocumentV1>(
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "choice", "title": "Choice screen", "revision": 0,
        "catalogPin": {"systemId": "m3-catalog", "catalogRevision": "candidate",
          "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
        "environment": {"widthDp": 360, "heightDp": 640, "density": 1.0, "theme": "light",
          "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr"},
        "roots": ["place"],
        "components": {"choice": {"name": "Choice", "root": "go-button"}},
        "nodes": {
          "place": {"id": "place", "componentId": "design/component-instance",
            "component": {"componentKey": "choice",
              "arguments": {"label": $label}}},
          "go-button": {"id": "go-button", "componentId": "m3/button",
            "properties": {"style": {"type": "enum", "value": "filled"}},
            "slots": {"content": ["text"]}, "eventBindings": {"click": [$actions]}},
          "text": {"id": "text", "componentId": "m3/text",
            "properties": {"text": {"type": "binding", "value": "label"}}}
        }
      }
      """
    )

  private fun component(document: DesignDocumentV1, structure: ProjectedStructure) =
    ScreenDocumentProjection.project(document, emptyMap(), structure = structure).let {
      assertIs<ScreenDocumentProjection.Outcome.Projected>(it, it.toString())
      it.document.functions.single()
    }

  @Test
  fun `a component's binding reads and action-free events are its named parameters`() {
    val choice = component(document(), ProjectedStructure())
    assertEquals("Choice", choice.name)
    val parameters = choice.parameters.associateBy { it.name }
    val label = assertIs<ScreenParameter.Value>(parameters["label"], parameters.toString())
    assertEquals("kotlin.String", label.typeFqn)
    assertIs<ScreenParameter.Callback>(parameters["onGoButtonClick"], parameters.toString())
    assertFalse(parameters.keys.any { it.startsWith("capture") }, parameters.toString())
  }

  @Test
  fun `a string resource argument types its binding as the translated string`() {
    val choice =
      component(
        document(label = """{"type": "resource", "resourceKey": "go_label"}"""),
        ProjectedStructure(stringResources = "example.generated.resources.Res"),
      )
    val label = choice.parameters.single { it.name == "label" }
    assertEquals("kotlin.String", assertIs<ScreenParameter.Value>(label).typeFqn)
  }

  @Test
  fun `the screen still refuses to place a component with a handler that does nothing`() {
    val outcome = ScreenExportGate.export(document(), record)
    assertIs<ScreenExportGate.Outcome.Refused>(outcome)
    assertTrue(outcome.reasons.any { "no actions" in it }, outcome.reasons.toString())
  }

  @Test
  fun `an event that writes state is still stateful authoring`() {
    if (UiBuilderBuildFeatures.remoteCompose) return
    val outcome =
      ScreenExportGate.export(
        document("""{"type": "set", "variable": "picked", "value": true}"""),
        record,
      )
    assertIs<ScreenExportGate.Outcome.Refused>(outcome, outcome.toString())
  }
}
