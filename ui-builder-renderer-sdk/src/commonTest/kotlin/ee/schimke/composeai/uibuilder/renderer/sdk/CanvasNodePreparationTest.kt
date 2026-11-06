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
                      ),
                    ),
                    action("toggle", "expanded"),
                  ),
                ),
            ),
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
            mapOf("click" to JsonArray(listOf(JsonPrimitive("bad"), JsonObject(emptyMap())))),
          ),
      )
    val writes = mutableListOf<Pair<String, String?>>()

    dispatchCanvasEvent(node, "missing", emptyMap(), { name, value -> writes += name to value }, {})
    dispatchCanvasEvent(node, "click", emptyMap(), { name, value -> writes += name to value }, {})

    assertEquals(emptyList(), writes)
  }

  /** `wear-m3/button.onClickAction` runs as one more click action, after the bound ones. */
  @Test
  fun `a click runs the bound actions and then onClickAction`() {
    val node =
      UiBuilderNode(
        id = "start",
        componentId = "wear-m3/button",
        properties =
          JsonObject(
            mapOf(
              "onClickAction" to
                JsonObject(
                  mapOf(
                    "type" to JsonPrimitive("object"),
                    "fields" to
                      JsonObject(
                        mapOf(
                          "type" to literal("toggle"),
                          "variable" to literal("running"),
                        ),
                      ),
                  ),
                ),
            ),
          ),
        eventBindings =
          JsonObject(mapOf("click" to JsonArray(listOf(action("set", "label", "Stop"))))),
      )
    val writes = mutableListOf<Pair<String, String?>>()

    dispatchCanvasEvent(
      node,
      "click",
      mapOf("running" to "false", "label" to "Start"),
      { name, value -> writes += name to value },
      {},
    )

    assertEquals(listOf<Pair<String, String?>>("label" to "Stop", "running" to "true"), writes)
  }

  @Test
  fun `onClickAction alone is a click and a null wrapper is none`() {
    val toggle =
      UiBuilderNode(
        id = "card",
        componentId = "wear-m3/card",
        properties =
          JsonObject(
            mapOf(
              "onClickAction" to
                JsonObject(
                  mapOf(
                    "type" to JsonPrimitive("object"),
                    "fields" to
                      JsonObject(mapOf("type" to literal("toggle"), "variable" to literal("open"))),
                  ),
                ),
            ),
          ),
      )
    val cleared =
      toggle.copy(
        properties =
          JsonObject(mapOf("onClickAction" to JsonObject(mapOf("type" to JsonPrimitive("null"))))),
      )
    val writes = mutableListOf<Pair<String, String?>>()

    dispatchCanvasEvent(toggle, "click", mapOf("open" to "false"), { n, v -> writes += n to v }, {})
    dispatchCanvasEvent(
      cleared,
      "click",
      mapOf("open" to "false"),
      { n, v -> writes += n to v },
      {},
    )

    assertEquals(listOf<Pair<String, String?>>("open" to "true"), writes)
  }

  private fun literal(value: String): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(value)))

  private fun action(type: String, variable: String, value: String? = null): JsonObject =
    JsonObject(
      buildMap {
        put("type", JsonPrimitive(type))
        put("variable", JsonPrimitive(variable))
        if (value != null) put("value", JsonPrimitive(value))
      },
    )
}
