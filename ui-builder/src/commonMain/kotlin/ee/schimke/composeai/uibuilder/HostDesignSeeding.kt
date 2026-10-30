package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.local.catalogTemplateDocuments
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The document an embedding host's empty design file starts as: design [designId] from [templateId]
 * of the catalog it is opened against.
 *
 * The host sends the catalog's published templates ([templates], the `CatalogTemplateDocumentsV1`
 * the catalog's `/templates` route serves) and the design is seeded from them, exactly as the web
 * host's New design form is; [templateId] must be one they publish. The one template no catalog
 * publishes, the Jetcaster benchmark ([UiBuilderNewDesignSeed.FIXTURE_TEMPLATE]), is seeded from
 * [fixture] instead.
 */
internal fun hostSeedDocument(
  catalog: CapabilityCatalog,
  designId: String,
  templateId: String,
  fixture: String,
  templates: String,
): UiBuilderDocument {
  val systemId = catalog.benchmark.catalogSystemId
  // The capability carries no digest; the serving executor accepts `candidate`.
  val pin =
    CatalogReferenceV1(
      systemId,
      catalog.benchmark.catalogRevision,
      "candidate",
      catalog.benchmark.nativeRuntimeId,
    )
  if (templates.isNotBlank()) {
    return UiBuilderNewDesignSeed.document(
      designId = designId,
      templateId = templateId,
      catalogPin = pin,
      published = catalogTemplateDocuments(templates, pin),
    )
  }
  require(templateId == UiBuilderNewDesignSeed.FIXTURE_TEMPLATE && fixture.isNotBlank()) {
    "the host sent no templates for catalog '$systemId', so it cannot seed '$templateId'"
  }
  return UiBuilderNewDesignSeed.fixtureDocument(
    designId,
    pin,
    Json.parseToJsonElement(fixture).jsonObject,
  )
}
