package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/**
 * State declarations have to survive a page navigation, because that is how a new design is
 * created: the dialog navigates, and everything it collected has to arrive as query parameters.
 */
class NewDesignStateCodecTest {
  private val declared =
    listOf(
      NewDesignState("expanded", NewDesignStateType.Flag, JsonPrimitive(false)),
      NewDesignState("caption", NewDesignStateType.Text, JsonPrimitive("Hello, world")),
      NewDesignState("count", NewDesignStateType.Number, JsonPrimitive(3)),
    )

  @Test
  fun `declarations round trip`() {
    assertEquals(declared, decodeNewDesignStates(encodeNewDesignStates(declared)))
  }

  @Test
  fun `a text value carrying separators survives`() {
    // The reason this is JSON and not a separator scheme: a Text variable's initial value is free
    // text, and every separator worth choosing can appear inside one.
    val awkward =
      listOf(NewDesignState("caption", NewDesignStateType.Text, JsonPrimitive("a,b:c;d\"e")))

    assertEquals(awkward, decodeNewDesignStates(encodeNewDesignStates(awkward)))
  }

  @Test
  fun `a query parameter that is not declarations opens an empty design rather than failing`() {
    // The input is whatever the address bar contained. A mistyped URL should open an empty design,
    // not a blank page.
    assertEquals(emptyList(), decodeNewDesignStates("not json at all"))
    assertEquals(emptyList(), decodeNewDesignStates("{}"))
    assertEquals(emptyList(), decodeNewDesignStates(""))
  }

  @Test
  fun `entries that could not become a Kotlin property are dropped`() {
    // The name becomes a property in the exported Compose, and the generator refuses one it cannot
    // write. Refusing here means the dialog can say so while someone types.
    val encoded =
      """[{"name":"9lives","kind":"Flag","initial":false},""" +
        """{"name":"ok","kind":"Flag","initial":false},""" +
        """{"name":"unknown","kind":"Quantum","initial":false}]"""

    assertEquals(listOf("ok"), decodeNewDesignStates(encoded).map { it.name })
  }

  @Test
  fun `a repeated name is taken once`() {
    val encoded =
      """[{"name":"x","kind":"Flag","initial":false},{"name":"x","kind":"Text","initial":"b"}]"""

    val decoded = decodeNewDesignStates(encoded)
    assertEquals(1, decoded.size)
    assertEquals(NewDesignStateType.Flag, decoded.single().type)
  }

  @Test
  fun `every kind has a total reading of what someone typed`() {
    // A dialog that refuses to create a design over a typo in a default is worse than one that
    // starts the flag off.
    assertEquals(JsonPrimitive(true), NewDesignStateType.Flag.parse(" true "))
    assertEquals(JsonPrimitive(false), NewDesignStateType.Flag.parse("yes"))
    assertEquals(JsonPrimitive(0L), NewDesignStateType.Number.parse("three"))
    assertEquals(JsonPrimitive(12L), NewDesignStateType.Number.parse(" 12 "))
    assertEquals(JsonPrimitive("  spaces kept  "), NewDesignStateType.Text.parse("  spaces kept  "))
  }

  @Test
  fun `declarations reach the document the dialog creates`() {
    val document =
      blankUiBuilderDocument(
        designId = "with-state",
        catalogPin = kotlinx.serialization.json.JsonObject(emptyMap()),
        environment = kotlinx.serialization.json.JsonObject(emptyMap()),
        state = decodeNewDesignStates(encodeNewDesignStates(declared)),
      )

    assertEquals(setOf("expanded", "caption", "count"), document.stateVariables.keys)
    assertTrue(document.stateVariables.getValue("caption").toString().contains("Hello, world"))
  }
}
