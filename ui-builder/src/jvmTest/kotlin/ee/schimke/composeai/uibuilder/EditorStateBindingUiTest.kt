package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the inspector may offer for a state binding.
 *
 * The reducer has been able to bind and unbind since the events were added; the panel could not see
 * either, so a bound property drew a control the reducer refuses and an unbound one offered
 * nothing. The question this pins is the one the menu asks before drawing itself.
 */
class EditorStateBindingUiTest {
  private val catalog =
    CapabilityCatalogParser.parse(resource("/jetcaster-discover-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private fun state(nodeId: String) = reducer.initial(document, selectedNodeId = nodeId)

  private fun field(nodeId: String, name: String) =
    reducer.propertyFields(state(nodeId)).firstOrNull { it.name == name }

  @Test
  fun `a bound property reports the variable it is bound to`() {
    // The fixture binds this to `stateEquals(selectedCategory, "Crime")`.
    assertEquals("selectedCategory", assertNotNull(field("chip-crime", "selected")).boundVariable)
  }

  @Test
  fun `a menu is only offered where the reducer would accept the binding`() {
    // Answered by putting the value a bind would write through the validator, rather than by
    // reasoning about catalog types a second time and getting a different answer.
    assertTrue(reducer.canBindToState(state("chip-crime"), "chip-crime", "selected"))

    // `m3/text.text` is `string` and takes a literal, not a reference. Offering a binding here
    // would be a menu that lies.
    assertFalse(reducer.canBindToState(state("search-placeholder"), "search-placeholder", "text"))
    // And a plain boolean has no room for the comparison object either.
    assertFalse(reducer.canBindToState(state("chip-crime"), "chip-crime", "enabled"))
  }

  @Test
  fun `nothing is bindable in a design that declares no state`() {
    val stateless =
      reducer.initial(document.copy(stateVariables = JsonObject(emptyMap())), "chip-crime")

    assertTrue(reducer.stateVariableNames(stateless).isEmpty())
    assertFalse(reducer.canBindToState(stateless, "chip-crime", "selected"))
  }

  @Test
  fun `unbind leaves a literal the inspector can then edit`() {
    val unbound =
      reducer.reduce(
        state("chip-crime"),
        UiBuilderEditorEvent.UnbindProperty("chip-crime", "selected"),
      )

    val field = assertNotNull(reducer.propertyFields(unbound).firstOrNull { it.name == "selected" })
    assertNull(field.boundVariable, "the panel would still show a binding chip")
    assertEquals(EditorPropertyControl.Boolean, field.control)
    assertEquals(
      "bool",
      unbound.document.nodes
        .getValue("chip-crime")
        .properties
        .getValue("selected")
        .jsonObject
        .getValue("type")
        .jsonPrimitive
        .content,
    )
  }

  @Test
  fun `binding back is a comparison where the catalog needs one`() {
    // A boolean property cannot take a string variable's value, so the shape that will be accepted
    // is `stateEquals` — which is what the panel asks for a comparison value before offering.
    assertTrue(reducer.bindingNeedsComparison(state("chip-crime"), "chip-crime", "selected"))

    val unbound =
      reducer.reduce(
        state("chip-crime"),
        UiBuilderEditorEvent.UnbindProperty("chip-crime", "selected"),
      )
    val rebound =
      reducer.reduce(
        unbound,
        UiBuilderEditorEvent.BindPropertyToState(
          "chip-crime",
          "selected",
          "selectedCategory",
          equalsValue = "Crime",
        ),
      )

    assertEquals(
      "selectedCategory",
      assertNotNull(reducer.propertyFields(rebound).firstOrNull { it.name == "selected" })
        .boundVariable,
    )
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
