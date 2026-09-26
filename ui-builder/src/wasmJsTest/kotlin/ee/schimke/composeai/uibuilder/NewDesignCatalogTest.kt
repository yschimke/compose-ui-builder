package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NewDesignCatalogTest {
  @Test
  fun `the browser offers only published Remote templates the seed accepts`() {
    val catalog =
      CatalogCapabilityV1.Builder(
          "compose-catalog-capabilities/v1",
          CatalogBenchmarkV1.Builder(
              "remote-m3",
              "ui-builder.json",
              "remote-m3",
              "sha256:test",
              "remote-m3-test",
            )
            .build(),
          emptyList(),
        )
        .build()

    val templates = assertNotNull(newDesignCatalog(catalog)).templates.map { it.id }.toSet()

    assertEquals(UiBuilderNewDesignSeed.templateIds("remote-m3"), templates)
    assertTrue(AdaptiveWearWidget.TEMPLATE_ID !in templates)
  }
}
