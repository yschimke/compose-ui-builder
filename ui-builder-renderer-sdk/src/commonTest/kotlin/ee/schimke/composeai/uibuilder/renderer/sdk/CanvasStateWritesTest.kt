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
         {"type":"increment","variable":"x","amount":1}]"""
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
