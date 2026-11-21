package ee.schimke.composeai.uibuilder.capability

import ee.schimke.composeai.uibuilder.SyntheticCatalogs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * A catalog in a declared document format is handed the builder's vocabulary like every published
 * catalog, and keeps it, so a stored design holding a drawing still validates and opens. Its insert
 * panel offers only what the format writes: Kobento component JSON refuses a drawing.
 */
class DocumentFormatPaletteTest {
  private val template = SyntheticCatalogs.remote.components.first()

  private fun catalog(statusSemantics: String) =
    SyntheticCatalogs.remote.copy(
      statusSemantics = Json.parseToJsonElement(statusSemantics).jsonObject,
      components =
        listOf("kobento/button", "layout/column", "draw/rect", "asset/image").map {
          template.copy(componentId = it)
        },
    )

  @Test
  fun `a declared format's palette offers what it writes, and the catalog keeps the rest`() {
    val kobento = catalog("""{"kobento":{}}""")
    assertEquals(setOf("draw/rect", "asset/image"), kobento.paletteHiddenComponentIds)
    assertEquals(
      listOf("kobento/button", "layout/column"),
      kobento.paletteComponents.map { it.componentId },
    )
    assertTrue("draw/rect" in kobento.componentsById)
  }

  @Test
  fun `a catalog with no document format hides nothing for one`() {
    val compose = catalog("""{"platform":"mobile"}""")
    assertTrue(compose.paletteComponents.any { it.componentId == "draw/rect" })
  }
}
