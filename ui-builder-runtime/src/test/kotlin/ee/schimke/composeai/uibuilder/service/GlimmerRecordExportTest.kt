package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Glimmer's published seeds export as Glimmer Kotlin through the record-driven gate. Every seed
 * draws `glimmer/icon`, whose `iconKey` must be recognised like `m3/icon`'s.
 */
class GlimmerRecordExportTest {

  private val id = "glimmer-catalog"

  @Test
  fun `every glimmer seed exports through its records, as Glimmer and not Material 3`() {
    val ownership = CatalogOwnership.of(setOf(id))
    val catalog = CatalogCutoverFixtures.catalog(id)
    val templates = CatalogCutoverFixtures.templates(id)
    val record = CatalogCutoverFixtures.exportRecord(id)
    val ids = UiBuilderNewDesignSeed.templateIds(id, ownership, templates)
    assertTrue(ids.isNotEmpty(), "glimmer publishes seed templates")
    for (templateId in ids) {
      val document =
        UiBuilderNewDesignSeed.document(
            designId = "glimmer-export",
            catalogSystemId = id,
            templateId = templateId,
            catalogRevision = catalog.benchmark.catalogRevision,
            nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
            fixture = CatalogCutoverProbe.fixture,
            ownership = ownership,
            published = templates,
          )
          .toDesignDocumentV1()
      val source =
        when (val outcome = ScreenExportGate.export(document, record)) {
          is ScreenExportGate.Outcome.Emitted -> outcome.source
          is ScreenExportGate.Outcome.Refused -> fail("$templateId refused: ${outcome.reasons}")
        }
      assertTrue("import androidx.xr.glimmer.Icon" in source, "$templateId: $source")
      assertTrue("Icon(imageVector = Icons.Filled." in source, "$templateId: $source")
      assertFalse(
        "androidx.compose.material3" in source,
        "$templateId draws no Material 3: $source",
      )
    }
  }
}
