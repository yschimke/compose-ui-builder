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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `m3/text-field` and `m3/radio-button`: the form pair.
 *
 * The text field is the first component whose value the operator *types*, so the question it raises
 * is where that text goes. It goes to a declared state variable, the way a search input's does — a
 * field that kept its own `remember` would look like it worked on the canvas and would generate a
 * screen whose text nothing else can read.
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
   * A bindable property is still a property somebody types into.
   *
   * `["string", "object"]` is how the catalog spells "text, or a read of a state variable", and
   * judging the control by the whole declaration made every such property `Unsupported` — the text
   * a field shows could be bound but not typed. The boolean branch beside it had already learned
   * this for `["boolean", "string"]`.
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
   * The bound field, which is what the component is for.
   *
   * `searchQuery` is declared by the frozen Jetcaster design, so this is the real seam a form uses:
   * the canvas reads the variable through the live state and the export writes back to it.
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
   * An unbound field writes nowhere, deliberately.
   *
   * The alternative is a local `remember` in the generated screen, which would type back at you and
   * be a different screen from the one designed.
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

    assertTrue("RadioButton(selected = true, onClick = { Unit }, enabled = true" in source, source)
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
