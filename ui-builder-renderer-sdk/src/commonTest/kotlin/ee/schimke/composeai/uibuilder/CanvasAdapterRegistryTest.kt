package ee.schimke.composeai.uibuilder

import androidx.compose.ui.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CanvasAdapterRegistryTest {
  @Test
  fun `a registry rejects duplicate adapter ids`() {
    assertFailsWith<IllegalArgumentException> {
      canvasAdapterRegistry {
        register("catalog/text") {}
        register("catalog/text") {}
      }
    }
  }

  @Test
  fun `independent donor registries compose without copying adapters`() {
    val catalog = canvasAdapterRegistry { register("catalog/button") {} }
    val foundation = canvasAdapterRegistry { register("foundation/column") {} }

    val combined = catalog + foundation

    assertNotNull(combined["catalog/button"])
    assertNotNull(combined["foundation/column"])
  }

  @Test
  fun `composed registries reject an ambiguous adapter id`() {
    val first = canvasAdapterRegistry { register("shared/text") {} }
    val second = canvasAdapterRegistry { register("shared/text") {} }

    assertFailsWith<IllegalArgumentException> { first + second }
  }

  @Test
  fun `an adapter updates only the state variable bound to its property`() {
    val updates = mutableListOf<Pair<String, String?>>()
    val scope =
      CanvasNodeScope(
        node =
          UiBuilderNode(
            id = "slider",
            componentId = "material3/Slider",
            properties =
              JsonObject(
                mapOf(
                  "value" to
                    JsonObject(
                      mapOf(
                        "type" to JsonPrimitive("state"),
                        "variable" to JsonPrimitive("progress"),
                        "value" to JsonPrimitive("0.25"),
                      )
                    ),
                  "literal" to
                    JsonObject(
                      mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(0.5))
                    ),
                )
              ),
          ),
        modifier = Modifier,
        mode = CanvasMode.Device,
        renderSlot = { _, _ -> },
        renderItems = { _, _ -> },
        countItems = { 0 },
        renderItem = { _, _, _ -> },
        dispatchEvent = {},
        updateState = { variable, value -> updates += variable to value },
        recordText = {},
      )

    scope.updateBoundState("value", "0.75")
    scope.updateBoundState("literal", "1.0")
    scope.updateBoundState("missing", null)

    assertEquals(listOf<Pair<String, String?>>("progress" to "0.75"), updates)
  }
}
