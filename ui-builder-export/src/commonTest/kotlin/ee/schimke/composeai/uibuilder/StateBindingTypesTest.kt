package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.*

class StateBindingTypesTest {
  private val declarations =
    Json.parseToJsonElement(
        """{
    "name":{"valueType":"string","initialValue":"Ready"},
    "flag":{"valueType":"bool","initialValue":false},
    "count":{"valueType":"int","initialValue":1},
    "fraction":{"valueType":"float","initialValue":1},
    "optional":{"valueType":"string","nullable":true,"initialValue":"Present"}
  }"""
      )
      .jsonObject

  private fun matches(
    variable: String,
    type: String,
    property: String = "text",
    comparison: Boolean = false,
  ): Boolean? =
    stateBindingMatchesCatalog(
      buildJsonObject {
        put("type", if (comparison) "stateEquals" else "state")
        put("variable", variable)
        if (comparison) put("value", "Ready")
      },
      JsonPrimitive(type),
      emptyList(),
      declarations,
      property,
    )

  @Test
  fun `bindings are checked by their result type rather than their JSON wrapper`() {
    assertEquals(true, matches("name", "string"))
    assertEquals(false, matches("count", "string"))
    assertEquals(true, matches("count", "integer", "minLines"))
    assertEquals(false, matches("fraction", "integer", "minLines"))
    assertEquals(true, matches("flag", "boolean", "enabled"))
    assertEquals(false, matches("name", "boolean", "enabled"))
    assertEquals(true, matches("name", "boolean", "enabled", comparison = true))
    assertEquals(false, matches("optional", "string"))
    assertEquals(false, matches("missing", "string"))
  }

  @Test
  fun `a string state is not a colour, asset or constrained enum`() {
    assertEquals(false, matches("name", "string", "color"))
    assertEquals(false, matches("name", "string", "assetKey"))
    assertEquals(
      false,
      stateBindingMatchesCatalog(
        Json.parseToJsonElement("""{"type":"state","variable":"name"}"""),
        JsonPrimitive("string"),
        listOf(JsonPrimitive("Ready")),
        declarations,
        "style",
      ),
    )
  }
}
