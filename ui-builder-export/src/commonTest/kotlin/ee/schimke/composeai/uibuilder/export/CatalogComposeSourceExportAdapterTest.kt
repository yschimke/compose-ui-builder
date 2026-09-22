package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComposeSourceExportCapabilityV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CatalogComposeSourceExportAdapterTest {
  @Test
  fun `Compose Material 3 resolves from a catalog declaration`() {
    val catalog =
      catalog()
        .newBuilder()
        .also {
          it.composeSourceExport =
            ComposeSourceExportCapabilityV1.Builder(
                CatalogComposeSourceExportAdapters.COMPOSE_MATERIAL3,
                CatalogComposeSourceExportAdapters.COMPOSE_MATERIAL3_V1,
              )
              .build()
        }
        .build()

    val resolution =
      assertIs<CatalogComposeSourceExportAdapters.Resolution.Supported>(
        CatalogComposeSourceExportAdapters.resolve(catalog)
      )

    assertEquals(
      CatalogComposeSourceExportAdapter.Strategy.COMPONENT_RECORDS,
      resolution.adapter.strategy,
    )
  }

  @Test
  fun `undeclared and unknown adapters do not fall back`() {
    assertIs<CatalogComposeSourceExportAdapters.Resolution.NotDeclared>(
      CatalogComposeSourceExportAdapters.resolve(catalog())
    )

    val unknown = ComposeSourceExportCapabilityV1.Builder("other-compose", 7).build()
    val resolution =
      assertIs<CatalogComposeSourceExportAdapters.Resolution.Unsupported>(
        CatalogComposeSourceExportAdapters.resolve(unknown)
      )

    assertEquals("other-compose", resolution.adapter)
    assertEquals(7, resolution.version)
  }

  private fun catalog(): CatalogCapabilityV1 =
    CatalogCapabilityV1.Builder(
        schema = "compose-ui-builder-capability/v1",
        benchmark =
          CatalogBenchmarkV1.Builder(
              id = "m3",
              sourceRevision = "source",
              catalogSystemId = "m3-catalog",
              catalogRevision = "revision",
              nativeRuntimeId = "runtime",
            )
            .build(),
        components = emptyList(),
      )
      .build()
}
