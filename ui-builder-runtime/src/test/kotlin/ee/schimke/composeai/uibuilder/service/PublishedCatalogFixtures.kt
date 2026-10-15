package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1

/**
 * Catalog-owned policies and records composed as published catalogs, handed to the executor as
 * production hosts do.
 */
internal object PublishedCatalogFixtures {

  /** The catalogs whose definition this build no longer wants to own. */
  val servedIds: Set<String> =
    setOf(
      CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID,
      CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID,
      CurrentM3UiBuilderCatalogExecutor.A2UI_CATALOG_SYSTEM_ID,
    )

  fun catalog(systemId: String): CatalogCapabilityV1 = CatalogCutoverFixtures.catalog(systemId)

  /**
   * The `published` map for whichever of [catalogSystemIds] an executor serves. `m3-catalog` is
   * absent: it is the packaged catalog the foundation derives from.
   */
  fun publishedFor(catalogSystemIds: Set<String>): Map<String, CatalogCapabilityV1> =
    catalogSystemIds.filter { it in servedIds }.associateWith(::catalog)

  /** An executor serving [catalogSystemIds], with the non-packaged ones published. */
  fun executor(
    catalogSystemIds: Set<String>,
    packs: List<UiBuilderComponentPackSource> = emptyList(),
  ): CurrentM3UiBuilderCatalogExecutor =
    CurrentM3UiBuilderCatalogExecutor(
      catalogSystemIds = catalogSystemIds,
      packs = packs,
      published = publishedFor(catalogSystemIds),
    )
}
