package ee.schimke.composeai.uibuilder.renderer.sdk

import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CanvasNodePreparationTest {
  @Test
  fun `event actions observe earlier writes and retain navigation order`() {
    val node =
      UiBuilderNode(
        id = "toggle",
        componentId = "catalog/toggle",
        eventBindings =
          JsonObject(
            mapOf(
              "click" to
                JsonArray(
                  listOf(
                    action("set", "selected", "news"),
                    action("selectOrClear", "selected", "news"),
                    JsonObject(
                      mapOf(
                        "type" to JsonPrimitive("navigatePage"),
                        "pageKey" to JsonPrimitive("details"),
                      )
                    ),
                    action("toggle", "expanded"),
                  )
                )
            )
          ),
      )
    val writes = mutableListOf<Pair<String, String?>>()
    val navigations = mutableListOf<String>()

    dispatchCanvasEvent(
      node = node,
      event = "click",
      state = mapOf("selected" to "old", "expanded" to "false"),
      onState = { name, value -> writes += name to value },
      onNavigate = navigations::add,
    )

    assertEquals(
      listOf("selected" to "news", "selected" to null, "expanded" to "true"),
      writes,
    )
    assertEquals(listOf("details"), navigations)
  }

  @Test
  fun `unknown event and malformed actions do nothing`() {
    val node =
      UiBuilderNode(
        id = "button",
        componentId = "catalog/button",
        eventBindings =
          JsonObject(
            mapOf("click" to JsonArray(listOf(JsonPrimitive("bad"), JsonObject(emptyMap()))))
          ),
      )
    val writes = mutableListOf<Pair<String, String?>>()

    dispatchCanvasEvent(node, "missing", emptyMap(), { name, value -> writes += name to value }, {})
    dispatchCanvasEvent(node, "click", emptyMap(), { name, value -> writes += name to value }, {})

    assertEquals(emptyList(), writes)
  }

  private fun action(type: String, variable: String, value: String? = null): JsonObject =
    JsonObject(
      buildMap {
        put("type", JsonPrimitive(type))
        put("variable", JsonPrimitive(variable))
        if (value != null) put("value", JsonPrimitive(value))
      }
    )
}
