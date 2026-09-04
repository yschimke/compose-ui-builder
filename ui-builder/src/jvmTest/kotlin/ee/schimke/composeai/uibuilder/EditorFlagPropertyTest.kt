package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `["boolean", "string"]` is how the catalog spells "a flag, or the name of a state variable".
 *
 * `literalDefault` reads that by membership and unbinds such a property to a real boolean. The
 * inspector read it by equality and called it unsupported, so unbinding a chip's `selected` wrote a
 * value the panel then refused to edit — two rules in one file disagreeing about one declaration.
 */
class EditorFlagPropertyTest {
  private val catalog =
    CapabilityCatalogParser.parse(resource("/jetcaster-discover-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  @Test
  fun `a flag that may also name a state variable is still a flag`() {
    val field =
      assertNotNull(
        reducer
          .propertyFields(reducer.initial(document, selectedNodeId = "chip-crime"))
          .firstOrNull { it.name == "selected" }
      )

    assertEquals(EditorPropertyControl.Boolean, field.control)
  }

  @Test
  fun `unbinding then toggling is a round trip rather than a one-way door`() {
    val bound = reducer.initial(document, selectedNodeId = "chip-crime")
    // The fixture binds this to `stateEquals`, which is what makes the declaration a union.
    assertEquals(
      "stateEquals",
      bound.document.nodes
        .getValue("chip-crime")
        .properties
        .getValue("selected")
        .jsonObject
        .getValue("type")
        .jsonPrimitive
        .content,
    )

    val unbound =
      reducer.reduce(bound, UiBuilderEditorEvent.UnbindProperty("chip-crime", "selected"))
    val literal =
      unbound.document.nodes.getValue("chip-crime").properties.getValue("selected").jsonObject
    assertEquals("bool", literal.getValue("type").jsonPrimitive.content)

    // And the panel can now turn it back on, which before this it could not.
    val toggled =
      reducer.reduce(
        unbound,
        UiBuilderEditorEvent.CommitProperty("chip-crime", "selected", "true"),
      )
    assertEquals(
      "true",
      toggled.document.nodes
        .getValue("chip-crime")
        .properties
        .getValue("selected")
        .jsonObject
        .getValue("value")
        .jsonPrimitive
        .content,
    )
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
