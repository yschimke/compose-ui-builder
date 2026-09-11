package ee.schimke.composeai.uibuilder

import kotlin.test.*
import kotlinx.serialization.json.*

class StateSelectionTest {
  private fun declarations(kind: String = "int") = buildJsonObject {
    putJsonObject("page") {
      put("valueType", kind)
      put("initialValue", 10)
    }
  }

  private fun selection(vararg values: JsonPrimitive, fallback: String? = "other") =
    StateSelection(
      buildJsonObject {
        put("type", "state")
        put("variable", "page")
      },
      values.mapIndexed { i, v -> "case$i" to v }.toMap(),
      fallback,
    )

  private fun node(selection: StateSelection) =
    UiBuilderNode(
      "switch",
      "layout/box",
      properties = buildJsonObject { put(SHOW_BY_STATE, selection.encode()) },
      slots =
        mapOf("children" to (selection.cases.keys + listOfNotNull(selection.fallback)).toList()),
    )

  @Test
  fun `values remain authored values and unknown state chooses the fallback`() {
    val selection = selection(JsonPrimitive(10), JsonPrimitive(20))
    val node = node(selection)
    assertNull(stateSelectionIssue(node, declarations()))
    assertEquals(selection, node.stateSelection())
    assertEquals("case0", selection.selectedNode(mapOf("page" to "10"), declarations()))
    assertEquals("case1", selection.selectedNode(mapOf("page" to "20"), declarations()))
    assertEquals("other", selection.selectedNode(mapOf("page" to "30"), declarations()))
    assertEquals("other", selection.selectedNode(mapOf("page" to null), declarations()))
    assertNull(selection.copy(fallback = null).selectedNode(mapOf("page" to "30"), declarations()))
  }

  @Test
  fun `all children must be assigned and selector must exist`() {
    val node = node(selection(JsonPrimitive(10), JsonPrimitive(20)))
    assertNotNull(
      stateSelectionIssue(
        node.copy(slots = mapOf("children" to listOf("case0", "case1", "other", "extra"))),
        declarations(),
      )
    )
    assertNotNull(stateSelectionIssue(node, JsonObject(emptyMap())))
    assertNotNull(stateSelectionIssue(node.copy(componentId = "layout/row"), declarations()))
    assertNotNull(
      stateSelectionIssue(node(selection(JsonPrimitive(10), fallback = "case0")), declarations())
    )
  }

  @Test
  fun `case types and float canonical values are checked before rendering`() {
    assertNotNull(stateSelectionIssue(node(selection(JsonPrimitive("10"))), declarations()))
    assertNotNull(
      stateSelectionIssue(node(selection(JsonPrimitive(10), JsonPrimitive(10))), declarations())
    )
    assertNotNull(
      stateSelectionIssue(
        node(selection(JsonPrimitive(16777216.0), JsonPrimitive(16777217.0))),
        declarations("float"),
      )
    )
    assertNotNull(
      stateSelectionIssue(
        node(selection(JsonPrimitive(-0.0), JsonPrimitive(0.0))),
        declarations("float"),
      )
    )
    assertNotNull(stateSelectionIssue(node(selection(JsonPrimitive(1e100))), declarations("float")))
    val decimal = selection(JsonPrimitive(16777217.0))
    assertEquals(
      "case0",
      decimal.selectedNode(mapOf("page" to "16777216.0"), declarations("float")),
    )
    val integer = selection(JsonPrimitive(16777217))
    assertEquals("other", integer.selectedNode(mapOf("page" to "16777216"), declarations()))
  }

  @Test
  fun `malformed literal wrappers cannot be silently discarded`() {
    val good = node(selection(JsonPrimitive(10)))
    val encoded = good.properties.getValue(SHOW_BY_STATE).jsonObject
    fun malformed(transform: (JsonObject) -> JsonObject): UiBuilderNode =
      good.copy(properties = buildJsonObject { put(SHOW_BY_STATE, transform(encoded)) })
    assertNull(malformed { JsonObject(it + ("unknown" to JsonPrimitive(true))) }.stateSelection())
    val wrongSelector = malformed {
      JsonObject(
        it +
          ("fields" to
            JsonObject(
              it.getValue("fields").jsonObject +
                ("selector" to
                  buildJsonObject {
                    put("type", "int")
                    put("value", "ten")
                  })
            ))
      )
    }
    assertNull(wrongSelector.stateSelection())
    assertNotNull(stateSelectionIssue(wrongSelector, declarations()))
  }
}
