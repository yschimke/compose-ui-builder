package ee.schimke.composeai.uibuilder.renderer.sdk

import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean

class CanvasNodeResolutionTest {
  @Test
  fun `component arguments and state resolve before an adapter reads properties`() {
    val node =
      UiBuilderNode(
        id = "body",
        componentId = "catalog/label",
        properties =
          JsonObject(
            mapOf(
              "text" to wrapper("binding", "headline"),
              "selected" to
                JsonObject(
                  mapOf(
                    "type" to JsonPrimitive("stateEquals"),
                    "variable" to JsonPrimitive("selection"),
                    "value" to JsonPrimitive("body"),
                  )
                ),
            )
          ),
      )

    val resolved =
      resolveCanvasNode(
        node = node,
        arguments = JsonObject(mapOf("headline" to wrapper("string", "Resolved"))),
        state = mapOf("selection" to "body"),
        mapping = null,
      )

    assertEquals("Resolved", resolved.value("text").content)
    assertEquals(true, resolved.value("selected").boolean)
  }

  private fun wrapper(type: String, value: String) =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))

  private fun UiBuilderNode.value(name: String) =
    (properties.getValue(name) as JsonObject).getValue("value") as JsonPrimitive
}
