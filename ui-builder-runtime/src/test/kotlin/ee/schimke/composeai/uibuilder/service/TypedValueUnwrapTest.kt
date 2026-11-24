package ee.schimke.composeai.uibuilder.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The JSON a property literal stands for, as the catalog's `jsonType` check reads it. */
class TypedValueUnwrapTest {
  private fun parse(text: String) = Json.parseToJsonElement(text)

  @Test
  fun `a scalar literal is its value`() {
    assertEquals(JsonPrimitive(3.0), parse("""{"type":"float","value":3.0}""").unwrapTypedValue())
  }

  @Test
  fun `a list literal is an array, an empty one included`() {
    assertEquals(
      parse("""[{"type":"float","value":1.0}]"""),
      parse("""{"type":"list","values":[{"type":"float","value":1.0}]}""").unwrapTypedValue(),
    )
    assertEquals(
      JsonArray(emptyList()),
      parse("""{"type":"list","values":[]}""").unwrapTypedValue(),
    )
  }

  @Test
  fun `an object literal is an object`() {
    assertEquals(
      parse("""{"label":{"type":"string","value":"Heating"}}"""),
      parse("""{"type":"object","fields":{"label":{"type":"string","value":"Heating"}}}""")
        .unwrapTypedValue(),
    )
  }

  @Test
  fun `anything else is left as it is`() {
    val binding = parse("""{"type":"state","name":"page"}""")
    assertEquals(binding, binding.unwrapTypedValue())
    assertEquals(JsonObject(emptyMap()), JsonObject(emptyMap()).unwrapTypedValue())
  }
}
