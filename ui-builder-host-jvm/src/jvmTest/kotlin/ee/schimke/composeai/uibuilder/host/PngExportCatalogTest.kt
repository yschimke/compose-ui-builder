package ee.schimke.composeai.uibuilder.host

import ee.schimke.composeai.uibuilder.svg.JvmDocumentRasterizer
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * A PNG is drawn in a scene of its own, which inherits nothing from the editor. For a Wear design
 * that difference is the frame and the platform the canvas draws with, so a PNG drawn without the
 * catalog was a different picture from the design on screen.
 */
class PngExportCatalogTest {
  @Test
  fun `a Wear design rasterizes with its catalog, not as the default one`() {
    val catalog = OfflineCatalog.WEAR_M3.capabilityCatalog()
    val document =
      OfflineCatalog.WEAR_M3.seed(
        designId = "png-wear",
        catalogRevision = catalog.benchmark.catalogRevision,
        nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
      )

    val withCatalog = JvmDocumentRasterizer.renderPng(document, catalog)
    val without = JvmDocumentRasterizer.renderPng(document)

    assertFalse(withCatalog.contentEquals(without), "the catalog changed nothing in the PNG")
  }
}
