package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

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
}
