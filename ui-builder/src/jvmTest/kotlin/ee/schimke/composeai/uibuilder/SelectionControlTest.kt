package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `m3/checkbox` and `m3/switch`: the first two of the selection controls the m3-catalog app has and
 * this catalog did not.
 *
 * They are one pair rather than two changes because they are the same component twice — the same
 * `checked`/`enabled` pair, the same `onCheckedChange`, the same binding to a declared state
 * variable — and the interesting risk is that the renderer and the exporter read that binding
 * differently. Both are asserted here against one document.
 */
class SelectionControlTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  /** The same design with one flag declared, which is the only thing a bound control needs. */
  private val withFlag =
    document.copy(
      stateVariables =
        JsonObject(
          document.stateVariables +
            ("notify" to
              JsonObject(
                mapOf(
                  "type" to JsonPrimitive("flag"),
                  "initialValue" to JsonPrimitive(false),
                  "persistence" to JsonPrimitive("preview"),
                )
              ))
        )
    )

  @Test
  fun `both arrive checked and leave a valid document`() {
    listOf("m3/checkbox", "m3/switch").forEach { componentId ->
      val (state, nodeId) = insert(componentId, document)
      val node = state.document.nodes.getValue(nodeId)

      // Material draws an unchecked box as an empty square and an off switch as a grey pill, so an
      // unseeded drop reads as neither control.
      assertEquals("true", node.stringProperty("checked"), componentId)
      assertEquals(emptyList(), CapabilityValidator(catalog).validate(state.document).issues)
    }
  }

  @Test
  fun `both export as the Material call with the design's own checked value`() {
    val checkbox = exportOf("m3/checkbox")
    val switch = exportOf("m3/switch")

    assertTrue(
      "Checkbox(checked = true, onCheckedChange = { Unit }, enabled = true" in checkbox,
      checkbox,
    )
    assertTrue(
      "Switch(checked = true, onCheckedChange = { Unit }, enabled = true" in switch,
      switch,
    )
  }

  /**
   * The bound case, which is the one that makes a control a control rather than a picture of one.
   *
   * The renderer resolves `checked` through the live state and the exporter emits the comparison; a
   * control whose two projections disagreed about a binding would tick on the canvas and generate a
   * screen that never changes.
   */
  @Test
  fun `a checkbox bound to a flag toggles it in the export and reads it on the canvas`() {
    val initial = reducer.initial(withFlag, selectedNodeId = "main-background")
    val target = requireNotNull(reducer.dropTarget(initial, "m3/checkbox"))
    val inserted =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.InsertComponentWithAction(
          componentId = "m3/checkbox",
          target = target,
          action = EditorStateAction.Toggle("notify"),
        ),
      )
    assertIs<CommandOutcome.Accepted>(inserted.lastOutcome, inserted.lastOutcome.toString())
    val nodeId = assertNotNull(inserted.selectedNodeId)

    val bound =
      reducer.reduce(
        inserted,
        // A comparison rather than a bare read: the flag is a boolean and `checked` takes one, so
        // either shape type-checks, and `stateEquals` is the one the renderer and the exporter both
        // resolve for a control's checked state.
        UiBuilderEditorEvent.BindPropertyToState(nodeId, "checked", "notify", equalsValue = "true"),
      )
    val node = bound.document.nodes.getValue(nodeId)
    assertEquals(
      "stateEquals",
      (node.properties.getValue("checked") as JsonObject).getValue("type").jsonPrimitive.content,
    )

    val source = CapabilityComposeCodeExporter.export(bound.document, catalog).requireSource()
    assertTrue("Checkbox(checked = notify ==" in source, source)
    assertTrue("onCheckedChange = { notify = !notify }" in source, source)
    // And the editor is allowed to offer that wiring in the first place, which is derived from the
    // exporter's own field table rather than listed twice.
    assertTrue("m3/checkbox" in COMPOSE_EMITTED_CLICK_COMPONENTS)
    assertTrue("m3/switch" in COMPOSE_EMITTED_CLICK_COMPONENTS)
  }

  private fun exportOf(componentId: String): String {
    val (state, _) = insert(componentId, document)
    return CapabilityComposeCodeExporter.export(state.document, catalog).requireSource()
  }

  private fun insert(
    componentId: String,
    source: UiBuilderDocument,
  ): Pair<UiBuilderEditorState, String> {
    val initial = reducer.initial(source, selectedNodeId = "main-background")
    val target = requireNotNull(reducer.dropTarget(initial, componentId)) { componentId }
    val inserted =
      reducer.reduce(initial, UiBuilderEditorEvent.InsertComponent(componentId, target))
    assertIs<CommandOutcome.Accepted>(inserted.lastOutcome, "$componentId: ${inserted.lastOutcome}")
    return inserted to assertNotNull(inserted.selectedNodeId)
  }

  private fun UiBuilderNode.stringProperty(name: String): String =
    (properties.getValue(name) as JsonObject).getValue("value").jsonPrimitive.content

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
