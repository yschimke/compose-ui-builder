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
    button: String =
      """"componentId": "m3/button",
            "properties": {"style": {"type": "enum", "value": "filled"}}""",
    event: String = "click",
    binding: String = "label",
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
              "arguments": {"$binding": $label}}},
          "go-button": {"id": "go-button", $button,
            "slots": {"content": ["text"]}, "eventBindings": {"$event": [$actions]}},
          "text": {"id": "text", "componentId": "m3/text",
            "properties": {"text": {"type": "binding", "value": "$binding"}}}
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
  fun `an action-free click on a layout node is the caller's callback on its modifier`() {
    val projected =
      ScreenDocumentProjection.project(
        document(button = """"componentId": "layout/box", "properties": {}""").let {
          it.copy(
            nodes =
              it.nodes +
                ("go-button" to
                  it.nodes.getValue("go-button").let { b ->
                    b.copy(slots = mapOf("children" to listOf("text")))
                  })
          )
        },
        emptyMap(),
        structure = ProjectedStructure(),
      )
    assertIs<ScreenDocumentProjection.Outcome.Projected>(projected, projected.toString())
    val choice = projected.document.functions.single()
    assertTrue(
      choice.parameters.any { it is ScreenParameter.Callback && it.name == "onGoButtonClick" },
      choice.toString(),
    )
    assertTrue("clickable" in choice.root.toString(), choice.root.toString())
  }

  @Test
  fun `an action-free event that carries a value is not a zero-argument callback`() {
    val projected =
      ScreenDocumentProjection.project(
        document(event = "valueChange"),
        emptyMap(),
        structure = ProjectedStructure(),
      )
    if (projected is ScreenDocumentProjection.Outcome.Projected) {
      val names = projected.document.functions.single().parameters.map { it.name }
      assertFalse(names.any { "ValueChange" in it }, names.toString())
    }
  }

  @Test
  fun `an action-free toggle change is an intent callback`() {
    val choice =
      component(
        document(
          event = "checkedChange",
          button = """"componentId": "m3/checkbox", "properties": {}""",
        ),
        ProjectedStructure(),
      )
    assertTrue(
      choice.parameters.any {
        it is ScreenParameter.Callback && it.name == "onGoButtonCheckedChange"
      },
      choice.toString(),
    )
  }

  @Test
  fun `a binding keeps clear of a callback that already took its name`() {
    val choice = component(document(binding = "onGoButtonClick"), ProjectedStructure())
    val names = choice.parameters.map { it.name }
    assertEquals(names.toSet().size, names.size, names.toString())
    assertTrue("onGoButtonClick" in names, names.toString())
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
