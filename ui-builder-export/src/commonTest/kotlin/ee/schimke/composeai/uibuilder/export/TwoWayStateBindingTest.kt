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
}
