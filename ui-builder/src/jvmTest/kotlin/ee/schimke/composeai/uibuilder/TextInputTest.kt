package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.codegen.COMPOSE_EMITTED_CLICK_COMPONENTS
import ee.schimke.composeai.uibuilder.codegen.CapabilityComposeCodeExporter
import ee.schimke.composeai.uibuilder.editor.EditorPropertyControl
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `m3/text-field` and `m3/radio-button`: the form pair. Typed text goes to a declared state
 * variable (like a search input's), never a local `remember` the generated screen couldn't read.
 */
class TextInputTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  /**
   * A bindable property (`["string", "object"]`: text or a state read) is still typeable, as
   * `["boolean", "string"]` already was.
   */
  @Test
  fun `the inspector can type into a value that is also bindable`() {
    val (state, _) = insert("m3/text-field")
    val fields = reducer.propertyFields(state).associateBy { it.name }

    assertEquals(EditorPropertyControl.Text, assertNotNull(fields["value"]).control)
  }

  @Test
  fun `a text field arrives labelled, with a placeholder, and valid`() {
    val (state, nodeId) = insert("m3/text-field")
    val field = state.document.nodes.getValue(nodeId)

    assertEquals("filled", field.stringProperty("variant"))
    assertEquals("Label", text(state, field.slots.getValue("label").single()))
    assertEquals("Placeholder", text(state, field.slots.getValue("placeholder").single()))
    assertEquals(emptyList(), CapabilityValidator(catalog).validate(state.document).issues)
  }

  @Test
  fun `a radio button arrives selected and valid`() {
    val (state, nodeId) = insert("m3/radio-button")

    assertEquals("true", state.document.nodes.getValue(nodeId).stringProperty("selected"))
    assertEquals(emptyList(), CapabilityValidator(catalog).validate(state.document).issues)
  }

  /** One id, two Material composables — the choice `m3/card` already makes for its three. */
  @Test
  fun `the variant picks between TextField and OutlinedTextField`() {
    val filled = exportOf("m3/text-field")
    val outlined = exportOf("m3/text-field", "variant" to "outlined")

    assertTrue("TextField(" in filled, filled)
    assertTrue("OutlinedTextField(" !in filled, filled)
    assertTrue("OutlinedTextField(" in outlined, outlined)
  }

  /**
   * The bound field: `searchQuery` (declared by the frozen Jetcaster design) is read live by the
   * canvas and written back by the export.
   */
  @Test
  fun `a bound text field reads and writes the design's own variable`() {
    val (state, nodeId) = insert("m3/text-field")
    val bound =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.BindPropertyToState(nodeId, "value", "searchQuery"),
      )
    assertIs<CommandOutcome.Accepted>(bound.lastOutcome, bound.lastOutcome.toString())

    val source = CapabilityComposeCodeExporter.export(bound.document, catalog).requireSource()
    assertTrue("value = searchQuery," in source, source)
    assertTrue("onValueChange = { searchQuery = it }," in source, source)
  }

  /**
   * An unbound field writes nowhere, rather than a local `remember` that would make a different
   * screen.
   */
  @Test
  fun `an unbound text field emits a literal and an empty handler`() {
    val source = exportOf("m3/text-field")

    assertTrue("""value = "",""" in source, source)
    assertTrue("onValueChange = { Unit }," in source, source)
    assertTrue("label = {" in source, source)
    assertTrue("placeholder = {" in source, source)
  }

  @Test
  fun `a radio button exports with the click action it was wired to`() {
    val source = exportOf("m3/radio-button")

    assertTrue("RadioButton(selected = true, onClick = {}, enabled = true" in source, source)
    assertTrue("m3/radio-button" in COMPOSE_EMITTED_CLICK_COMPONENTS)
  }

  private fun exportOf(componentId: String, vararg properties: Pair<String, String>): String {
    val (state, nodeId) = insert(componentId)
    val edited =
      properties.fold(state) { acc, (name, value) ->
        reducer.reduce(acc, UiBuilderEditorEvent.CommitProperty(nodeId, name, value))
      }
    return CapabilityComposeCodeExporter.export(edited.document, catalog).requireSource()
  }

  private fun text(state: UiBuilderEditorState, nodeId: String): String =
    state.document.nodes.getValue(nodeId).stringProperty("text")

  private fun insert(componentId: String): Pair<UiBuilderEditorState, String> {
    val initial = reducer.initial(document, selectedNodeId = "main-background")
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
