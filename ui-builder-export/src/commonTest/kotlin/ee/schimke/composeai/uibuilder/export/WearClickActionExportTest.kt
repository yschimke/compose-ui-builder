package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `wear-m3/button.onClickAction` and `wear-m3/card.onClickAction` are click actions, as
 * `eventBindings.click` is (see [ClickActions]): the generated `onClick` runs them, in the same
 * order the canvas does.
 */
class WearClickActionExportTest {
  private val pin = JsonObject(emptyMap())
  private val environment = JsonObject(emptyMap())

  @Test
  fun `a button's onClickAction is written into its onClick with the state it writes`() {
    val source =
      export(
        screenWith(
          state = mapOf("running" to flag(false)),
          button(
            "start",
            properties = mapOf("onClickAction" to objectAction("toggle", "running")),
          ),
          text("start-label", "Start"),
        )
      )

    assertTrue("var running by remember { mutableStateOf(false) }" in source, source)
    assertTrue("onClick = { running = !running }," in source, source)
  }

  @Test
  fun `a card runs its bound click actions, then onClickAction`() {
    val source =
      export(
        screenWith(
          state = mapOf("count" to number(0), "opened" to flag(false)),
          UiBuilderNode(
            id = "row",
            componentId = WearScreenCodeExporter.CARD,
            properties =
              JsonObject(
                mapOf(
                  "variant" to literal("enum", "plain"),
                  "onClickAction" to objectAction("set", "opened", JsonPrimitive(true)),
                )
              ),
            slots = mapOf("content" to listOf("row-label")),
            eventBindings =
              JsonObject(
                mapOf(
                  "click" to
                    JsonArray(
                      listOf(
                        JsonObject(
                          mapOf(
                            "type" to JsonPrimitive("increment"),
                            "variable" to JsonPrimitive("count"),
                          )
                        )
                      )
                    )
                )
              ),
          ),
          text("row-label", "Inbox"),
        )
      )

    assertTrue("var count by remember { mutableStateOf(0) }" in source, source)
    assertTrue("var opened by remember { mutableStateOf(false) }" in source, source)
    assertTrue("onClick = { count += 1; opened = true }," in source, source)
  }

  @Test
  fun `a click with no actions stays an empty lambda`() {
    val source = export(screenWith(emptyMap(), button("plain"), text("plain-label", "Go")))

    assertTrue("onClick = {}," in source, source)
  }

  @Test
  fun `an action the Wear lane cannot write is refused by name, not dropped`() {
    val refused =
      assertIs<WearScreenCodeExporter.Result.Refused>(
        WearScreenCodeExporter.export(
          screenWith(
            emptyMap(),
            button(
              "go",
              properties = mapOf("onClickAction" to objectAction("toggle", "undeclared")),
            ),
            text("go-label", "Go"),
          )
        )
      )

    assertEquals(1, refused.reasons.size, refused.reasons.toString())
    assertTrue("node `go` click action 1" in refused.reasons.single(), refused.reasons.toString())
    assertTrue("`undeclared`" in refused.reasons.single(), refused.reasons.toString())
  }

  private fun export(document: UiBuilderDocument): String =
    assertIs<WearScreenCodeExporter.Result.Emitted>(WearScreenCodeExporter.export(document)).source

  /** The sample screen, its list holding [nodes]' first node, and [state] declared. */
  private fun screenWith(
    state: Map<String, JsonObject>,
    vararg nodes: UiBuilderNode,
  ): UiBuilderDocument {
    val base = wearScreenUiBuilderDocument("activity", pin, environment)
    val list = base.nodes.getValue("wear-list")
    return base.copy(
      stateVariables = JsonObject(state),
      nodes =
        base.nodes +
          ("wear-list" to list.copy(slots = mapOf("items" to listOf(nodes.first().id)))) +
          nodes.associateBy { it.id },
    )
  }

  private fun button(id: String, properties: Map<String, JsonElement> = emptyMap()) =
    UiBuilderNode(
      id = id,
      componentId = WearScreenCodeExporter.BUTTON,
      properties = JsonObject(properties),
      slots = mapOf("content" to listOf("$id-label")),
    )

  private fun text(id: String, value: String) =
    UiBuilderNode(
      id = id,
      componentId = WearScreenCodeExporter.TEXT,
      properties = JsonObject(mapOf("text" to literal("string", value))),
    )

  private fun literal(type: String, value: String): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))

  /** An action as the reducer stores an `object` property: typed members under `fields`. */
  private fun objectAction(type: String, variable: String, value: JsonPrimitive? = null) =
    JsonObject(
      mapOf(
        "type" to JsonPrimitive("object"),
        "fields" to
          JsonObject(
            buildMap {
              put("type", literal("string", type))
              put("variable", literal("string", variable))
              if (value != null) {
                put(
                  "value",
                  JsonObject(mapOf("type" to JsonPrimitive("boolean"), "value" to value)),
                )
              }
            }
          ),
      )
    )

  private fun flag(initial: Boolean) = declaration("bool", JsonPrimitive(initial))

  private fun number(initial: Int) = declaration("int", JsonPrimitive(initial))

  private fun declaration(valueType: String, initial: JsonPrimitive) =
    JsonObject(
      mapOf(
        "type" to JsonPrimitive("value"),
        "valueType" to JsonPrimitive(valueType),
        "nullable" to JsonPrimitive(false),
        "initialValue" to initial,
        "persistence" to JsonPrimitive("preview"),
      )
    )
}
