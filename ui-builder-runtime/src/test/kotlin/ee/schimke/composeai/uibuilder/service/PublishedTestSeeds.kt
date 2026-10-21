package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.NewDesignState
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1

/**
 * Design [designId] from [systemId]'s captured published [templateId], seeded exactly as Create
 * seeds it and pinned to [catalogRevision] and [nativeRuntimeId] (the served catalog's, by
 * default).
 */
internal fun publishedTestSeed(
  systemId: String,
  templateId: String,
  designId: String = "$systemId-$templateId",
  catalogRevision: String? = null,
  nativeRuntimeId: String? = null,
  state: List<NewDesignState> = emptyList(),
): UiBuilderDocument {
  val served by lazy { CatalogCutoverFixtures.catalog(systemId).benchmark }
  return UiBuilderNewDesignSeed.document(
    designId = designId,
    templateId = templateId,
    catalogPin =
      CatalogReferenceV1(
        systemId,
        catalogRevision ?: served.catalogRevision,
        "candidate",
        nativeRuntimeId ?: served.nativeRuntimeId,
      ),
    published = CatalogCutoverFixtures.templates(systemId),
    state = state,
  )
}
