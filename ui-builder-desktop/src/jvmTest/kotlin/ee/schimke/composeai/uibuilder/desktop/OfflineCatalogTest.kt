package ee.schimke.composeai.uibuilder.desktop

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineCatalogTest {
  @Test
  fun `every packaged offline catalog produces a matching starter design`() {
    OfflineCatalog.entries.forEach { offlineCatalog ->
      val text =
        checkNotNull(javaClass.getResource("/${offlineCatalog.capabilitiesResource}")) {
            "missing ${offlineCatalog.capabilitiesResource}"
          }
          .readText()
      val catalog = CapabilityCatalogParser.parse(text)

      val document =
        offlineCatalog.seed(
          designId = "${offlineCatalog.systemId}-workspace",
          catalogRevision = catalog.benchmark.catalogRevision,
          nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
        )

      assertEquals(offlineCatalog.systemId, catalog.benchmark.catalogSystemId)
      assertEquals(offlineCatalog.systemId, document.catalogPin["systemId"]?.toString()?.trim('"'))
      assertTrue(document.nodes.isNotEmpty(), "${offlineCatalog.systemId} starter must be editable")
    }
  }
}
