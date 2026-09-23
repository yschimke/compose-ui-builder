package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.canvas.UiBuilderRenderStrategy
import ee.schimke.composeai.uibuilder.canvas.uiBuilderRenderStrategy
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins every Material component whose editor and bounded Preview intentionally use different APIs.
 */
class PreviewFidelityInventoryTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  @Test
  fun `bounded preview uses real APIs wherever the pinned dependency provides them`() {
    val expected =
      mapOf(
        "layout/horizontal-carousel" to "HorizontalUncontainedCarousel",
        "m3/search-bar" to "SearchBar",
        "m3/search-input-field" to "SearchBarDefaults.InputField",
        "m3/dialog" to "AlertDialog",
      )

    expected.forEach { (componentId, symbol) ->
      assertEquals(symbol, catalog.componentsById.getValue(componentId).code?.symbol)
      assertEquals(
        UiBuilderRenderStrategy.AUTHORING_ADAPTER,
        uiBuilderRenderStrategy(componentId, unrolled = true),
      )
      assertEquals(
        UiBuilderRenderStrategy.REAL,
        uiBuilderRenderStrategy(componentId, unrolled = false),
      )
    }
  }

  @Test
  fun `floating toolbar remains the one declared compatibility adapter`() {
    assertEquals(
      "HorizontalFloatingToolbar",
      catalog.componentsById.getValue("m3/horizontal-floating-toolbar").code?.symbol,
    )
    assertEquals(
      UiBuilderRenderStrategy.COMPATIBILITY_ADAPTER,
      uiBuilderRenderStrategy("m3/horizontal-floating-toolbar", unrolled = true),
    )
    assertEquals(
      UiBuilderRenderStrategy.COMPATIBILITY_ADAPTER,
      uiBuilderRenderStrategy("m3/horizontal-floating-toolbar", unrolled = false),
    )
    assertEquals(
      UiBuilderPreviewSurfaces.Fidelity.APPROXIMATE,
      catalog.previewSurfaces.wasm.fidelity,
    )
    assertEquals(
      UiBuilderPreviewSurfaces.Fidelity.APPROXIMATE,
      catalog.previewSurfaces.native.fidelity,
    )
  }
}
