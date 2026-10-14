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

  @Test
  fun `a colour expression may read a theme-colour argument`() {
    val node =
      UiBuilderNode(
        id = "button",
        componentId = "catalog/button",
        properties =
          JsonObject(
            mapOf(
              "containerColor" to
                JsonObject(
                  mapOf(
                    "type" to JsonPrimitive("expr"),
                    "op" to JsonPrimitive("lerp"),
                    "args" to
                      kotlinx.serialization.json.JsonArray(
                        listOf(
                          wrapper("binding", "base"),
                          wrapper("color", "#FF000000"),
                          JsonObject(
                            mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(0))
                          ),
                        )
                      ),
                  )
                )
            )
          ),
      )

    val resolved =
      resolveCanvasNode(
        node = node,
        arguments = JsonObject(mapOf("base" to wrapper("colorToken", "primary"))),
        state = emptyMap(),
        mapping = null,
        expressions =
          CanvasExpressions(
            emptyMap(),
            ee.schimke.composeai.uibuilder.export.UiExpressions.Clock.DEFAULT,
          ),
      )

    val colour = resolved.properties.getValue("containerColor") as JsonObject
    assertEquals("colorToken", (colour.getValue("type") as JsonPrimitive).content)
    assertEquals("primary", resolved.value("containerColor").content)
  }

  private fun wrapper(type: String, value: String) =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))

  private fun UiBuilderNode.value(name: String) =
    (properties.getValue(name) as JsonObject).getValue("value") as JsonPrimitive
}
