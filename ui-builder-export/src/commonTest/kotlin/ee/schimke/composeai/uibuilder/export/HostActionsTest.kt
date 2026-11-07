package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class HostActionsTest {
  private fun hostAction(value: JsonPrimitive) =
    JsonObject(
      mapOf(
        "type" to JsonPrimitive(HostActions.TYPE),
        "name" to JsonPrimitive("volume"),
        "value" to value,
      ),
    )

  /**
   * A decimal is read to `Float` precision once, so the canvas, the Compose export and the Remote
   * document (`RemoteFloat`) all hand the host the same number.
   */
  @Test
  fun `a decimal is the Float the Remote document sends`() {
    val action = hostAction(JsonPrimitive(16777217.0))

    assertNull(HostActions.issue(action))
    assertEquals(HostActions.Value.Decimal(16777216f), HostActions.value(action))
    assertEquals(JsonPrimitive(16777216f), HostActions.value(action)?.json)
  }

  @Test
  fun `a whole number stays an Int and text stays text`() {
    assertEquals(HostActions.Value.Integer(7), HostActions.value(hostAction(JsonPrimitive(7))))
    assertEquals(HostActions.Value.Text("7"), HostActions.value(hostAction(JsonPrimitive("7"))))
  }
}

class HostActionsAuthoredValueTest {
  @Test
  fun `typed text reads as JSON would, and only a number spelled as one is a number`() {
    assertEquals(HostActions.Value.Integer(7), HostActions.authoredValue("7"))
    assertEquals(HostActions.Value.Integer(-2), HostActions.authoredValue(" -2 "))
    assertEquals(HostActions.Value.Decimal(0.5f), HostActions.authoredValue("0.5"))
    assertEquals(HostActions.Value.Text("007"), HostActions.authoredValue("007"))
    assertEquals(HostActions.Value.Text("+1"), HostActions.authoredValue("+1"))
    assertEquals(HostActions.Value.Text("NaN"), HostActions.authoredValue("NaN"))
    assertEquals(HostActions.Value.Text("7"), HostActions.authoredValue("\"7\""))
    assertEquals(HostActions.Value.Text("×"), HostActions.authoredValue("×"))
    assertEquals(null, HostActions.authoredValue("  "))
  }
}
