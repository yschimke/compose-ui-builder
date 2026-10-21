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
 * A host that sends the catalog's published templates ([templates], the
 * `CatalogTemplateDocumentsV1` the catalog's `/templates` route serves) is seeded from them,
 * exactly as the web host's New design form is, and [templateId] must be one they publish. A host
 * that sends none still gets the built-in seed for the catalog id, read against [fixture]; that
 * path goes once every host sends templates.
 */
internal fun hostSeedDocument(
  catalog: CapabilityCatalog,
  designId: String,
  templateId: String,
  fixture: String,
  templates: String,
): UiBuilderDocument {
  val systemId = catalog.benchmark.catalogSystemId
  if (templates.isNotBlank()) {
    // The capability carries no digest; the serving executor accepts `candidate`.
    val pin =
      CatalogReferenceV1(
        systemId,
        catalog.benchmark.catalogRevision,
        "candidate",
        catalog.benchmark.nativeRuntimeId,
      )
    return UiBuilderNewDesignSeed.document(
      designId = designId,
      templateId = templateId,
      catalogPin = pin,
      published = catalogTemplateDocuments(templates, pin),
    )
  }
  require(fixture.isNotBlank()) { "the host sent neither the catalog's templates nor the fixture" }
  require(templateId in UiBuilderNewDesignSeed.templateIds(systemId)) {
    "catalog '$systemId' has no template '$templateId'"
  }
  return UiBuilderNewDesignSeed.document(
    designId = designId,
    catalogSystemId = systemId,
    templateId = templateId,
    catalogRevision = catalog.benchmark.catalogRevision,
    nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
    fixture = Json.parseToJsonElement(fixture).jsonObject,
  )
}
