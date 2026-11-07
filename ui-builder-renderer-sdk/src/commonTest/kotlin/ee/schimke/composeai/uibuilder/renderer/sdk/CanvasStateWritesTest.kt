package ee.schimke.composeai.uibuilder.renderer.sdk

import ee.schimke.composeai.uibuilder.export.UiExpressions
import ee.schimke.composeai.uibuilder.export.UiValueKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/** A batch of actions computes each formula over the state the actions before it left. */
class CanvasStateWritesTest {
  private val actions =
    Json.parseToJsonElement(
      """[{"type":"set","variable":"x","value":{"type":"expr","op":"add","args":[
           {"type":"state","variable":"x"},{"type":"int","value":40}]}},
         {"type":"increment","variable":"x","amount":1}]""",
    ) as JsonArray

  @Test
  fun `a formula in a batch is computed over the earlier writes`() {
    val writes =
      canvasStateWrites(
        actions,
        mapOf("x" to "40"),
        UiExpressions.Scope(mapOf("x" to UiValueKind.FLOAT)),
      )

    assertEquals(listOf("x", "x"), writes.map { it.first })
    assertEquals(listOf(80.0, 81.0), writes.map { it.second!!.toDouble() })
  }
}

class ApplyHostValuesTest {
  private val declarations =
    kotlinx.serialization.json.Json.parseToJsonElement(
        """{"calc.display":{"valueType":"string","initialValue":"0","persistence":"host"},
            "pressed":{"valueType":"bool","initialValue":false,"persistence":"session"}}""",
      )
      .let { it as kotlinx.serialization.json.JsonObject }

  @Test
  fun `host values are drawn, and an input a host stops pushing goes back to its initial value`() {
    val state = mutableMapOf<String, String?>("calc.display" to "0", "pressed" to "true")
    val applied = mutableMapOf<String, String?>()

    applyHostValues(
      state,
      applied,
      mapOf("calc.display" to "3", "pressed" to "false"),
      declarations,
    )
    // The design's own state is the design's: a host value naming it is not drawn.
    assertEquals(mapOf<String, String?>("calc.display" to "3", "pressed" to "true"), state)

    applyHostValues(state, applied, emptyMap(), declarations)
    assertEquals(mapOf<String, String?>("calc.display" to "0", "pressed" to "true"), state)
  }
}
