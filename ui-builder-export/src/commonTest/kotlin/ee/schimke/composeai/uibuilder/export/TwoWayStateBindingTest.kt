package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class TwoWayStateBindingTest {
  private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

  @Test
  fun `a bare state read is written back`() {
    assertEquals(
      "notify",
      TwoWayStateBinding.writeBackVariable(json("""{"type":"state","variable":"notify"}"""), null),
    )
  }

  @Test
  fun `a comparison or a literal is never written back`() {
    assertNull(
      TwoWayStateBinding.writeBackVariable(
        json("""{"type":"stateEquals","variable":"notify","value":true}"""),
        null,
      )
    )
    assertNull(TwoWayStateBinding.writeBackVariable(json("""{"type":"bool","value":true}"""), null))
    assertNull(TwoWayStateBinding.writeBackVariable(null, null))
  }

  @Test
  fun `an authored write of the same variable wins`() {
    val property = json("""{"type":"state","variable":"notify"}""")
    for (kind in listOf("toggle", "set", "select", "setText", "selectOrClear", "increment")) {
      assertNull(
        TwoWayStateBinding.writeBackVariable(
          property,
          json("""[{"type":"$kind","variable":"notify","value":true}]"""),
        ),
        kind,
      )
    }
  }

  @Test
  fun `an action on another variable does not suppress the write back`() {
    assertEquals(
      "notify",
      TwoWayStateBinding.writeBackVariable(
        json("""{"type":"state","variable":"notify"}"""),
        json("""[{"type":"toggle","variable":"other"},{"type":"navigatePage","pageKey":"next"}]"""),
      ),
    )
  }

  @Test
  fun `a control's change event is its callback without on`() {
    assertEquals("checkedChange", TwoWayStateBinding.changeEvent("m3/checkbox", setOf("checked")))
    assertEquals(
      "checkedChange",
      TwoWayStateBinding.changeEvent("wear-m3/switch-button", setOf("checked", "label")),
    )
    assertEquals(
      "select",
      TwoWayStateBinding.changeEvent("wear-m3/radio-button", setOf("selected")),
    )
    assertEquals(
      "selectionClick",
      TwoWayStateBinding.changeEvent("remote-m3/remote-split-radio-button", setOf("selected")),
    )
    // An m3 RadioButton's callback is `onClick`, so it keeps `click`.
    assertNull(TwoWayStateBinding.changeEvent("m3/radio-button", setOf("selected")))
    assertNull(TwoWayStateBinding.changeEvent("m3/button", emptySet()))
  }

  @Test
  fun `the change event is read first, a legacy click second`() {
    val toggle = json("""[{"type":"toggle","variable":"notify"}]""")
    assertEquals("checkedChange", TwoWayStateBinding.eventFor("checked", emptyMap()))
    assertEquals(
      "checkedChange",
      TwoWayStateBinding.eventFor("checked", mapOf("checkedChange" to toggle)),
    )
    assertEquals("click", TwoWayStateBinding.eventFor("checked", mapOf("click" to toggle)))
    assertEquals(
      "checkedChange",
      TwoWayStateBinding.eventFor("checked", mapOf("click" to toggle, "checkedChange" to toggle)),
    )
    assertEquals("select", TwoWayStateBinding.eventFor("selected", emptyMap()))
    assertEquals(
      "selectionClick",
      TwoWayStateBinding.eventFor("selected", mapOf("selectionClick" to toggle)),
    )
    assertEquals("click", TwoWayStateBinding.eventFor("label", emptyMap()))
  }
}
