package ee.schimke.composeai.uibuilder.host

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineCatalogTest {
  @Test
  fun `every offline catalog, read from what it publishes, produces a matching starter design`() {
    OfflineCatalog.entries.forEach { offlineCatalog ->
      val catalog = CapabilityCatalogParser.parse(offlineCatalog.capabilityJson())

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

  @Test
  fun `every template a catalog offers seeds a design in that catalog`() {
    OfflineCatalog.entries.forEach { offlineCatalog ->
      val catalog = CapabilityCatalogParser.parse(offlineCatalog.capabilityJson())
      offlineCatalog.templateIds.forEach { template ->
        val document =
          offlineCatalog.seed(
            designId = "$template-workspace",
            catalogRevision = catalog.benchmark.catalogRevision,
            nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
            templateId = template,
          )

        assertEquals(
          offlineCatalog.systemId,
          document.catalogPin["systemId"]?.toString()?.trim('"'),
          "${offlineCatalog.systemId}/$template",
        )
        assertTrue(document.nodes.isNotEmpty(), "${offlineCatalog.systemId}/$template is empty")
      }
    }
  }
}
