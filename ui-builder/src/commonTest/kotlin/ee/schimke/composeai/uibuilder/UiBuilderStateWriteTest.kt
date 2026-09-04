package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * What an event action does to a state variable.
 *
 * Tested apart from the renderer because the transition is pure: a rule about what a button does to
 * a variable should not need a composition to verify.
 */
class UiBuilderStateWriteTest {
  private fun action(json: String) = Json.parseToJsonElement(json).jsonObject

  @Test
  fun `select and setText write the value`() {
    assertEquals(
      "Crime" to "Crime",
      uiBuilderStateWrite(
        action("""{"type":"select","variable":"Crime","value":"Crime"}"""),
        emptyMap(),
      ),
    )
    assertEquals(
      "q" to "hello",
      uiBuilderStateWrite(
        action("""{"type":"setText","variable":"q","value":"hello"}"""),
        emptyMap(),
      ),
    )
  }

  @Test
  fun `set is the protocol's own name for an assignment and now works`() {
    // Declared by the protocol and unimplemented here, so a document from another client using it
    // used to crash the renderer instead of writing the value.
    assertEquals(
      "day" to "monday",
      uiBuilderStateWrite(
        action("""{"type":"set","variable":"day","value":"monday"}"""),
        emptyMap(),
      ),
    )
  }

  @Test
  fun `toggle flips a flag, and starts from false when it is unset`() {
    val write = action("""{"type":"toggle","variable":"expanded"}""")

    assertEquals("expanded" to "true", uiBuilderStateWrite(write, emptyMap()))
    assertEquals("expanded" to "false", uiBuilderStateWrite(write, mapOf("expanded" to "true")))
    assertEquals("expanded" to "true", uiBuilderStateWrite(write, mapOf("expanded" to "false")))
    // Anything that is not the string `true` is not selected, which is how `stateEquals` compares.
    assertEquals("expanded" to "true", uiBuilderStateWrite(write, mapOf("expanded" to "nonsense")))
  }

  @Test
  fun `selectOrClear clears only when the value is already selected`() {
    val write = action("""{"type":"selectOrClear","variable":"category","value":"Crime"}""")

    assertEquals("category" to "Crime", uiBuilderStateWrite(write, emptyMap()))
    assertEquals("category" to null, uiBuilderStateWrite(write, mapOf("category" to "Crime")))
    assertEquals("category" to "Crime", uiBuilderStateWrite(write, mapOf("category" to "News")))
  }

  @Test
  fun `an unrecognised action writes nothing rather than killing the preview`() {
    // The protocol declares actions this renderer does not implement, and other clients author
    // them. Losing one interaction beats losing the whole screen, including the parts that work.
    assertNull(
      uiBuilderStateWrite(action("""{"type":"increment","variable":"count"}"""), emptyMap())
    )
    assertNull(
      uiBuilderStateWrite(action("""{"type":"navigatePage","pageKey":"next"}"""), emptyMap())
    )
    assertNull(uiBuilderStateWrite(action("""{"type":"select"}"""), emptyMap()))
  }

  @Test
  fun `a numeric operand compares by value, the way the exported screen compares it`() {
    // The renderer keeps state in its string form, so `1` and `1.0` used to be two different
    // values here while the generated Kotlin — comparing two `Double`s — called them the same.
    // The preview and the exported screen then disagreed about whether a control was selected.
    assertTrue(uiBuilderStateEquals("1", JsonPrimitive(1.0)))
    assertTrue(uiBuilderStateEquals("1.0", JsonPrimitive(1)))
    assertTrue(uiBuilderStateEquals("2", JsonPrimitive(2)))
    assertFalse(uiBuilderStateEquals("1.5", JsonPrimitive(1.0)))
  }

  @Test
  fun `a quoted operand stays a string comparison`() {
    // It exports as a string literal, where `1` and `1.0` are properly unequal. Comparing every
    // pair numerically would make a text variable holding `1` match the label `1.0`.
    assertTrue(uiBuilderStateEquals("Crime", JsonPrimitive("Crime")))
    assertFalse(uiBuilderStateEquals("1", JsonPrimitive("1.0")))
  }

  @Test
  fun `nothing equals an unset variable except an absent operand`() {
    assertFalse(uiBuilderStateEquals(null, JsonPrimitive(1.0)))
    assertFalse(uiBuilderStateEquals("1.0", null))
    assertFalse(uiBuilderStateEquals("1.0", JsonNull))
  }
}
