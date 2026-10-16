package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import kotlinx.serialization.json.Json

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
   * Explicit catalog inputs for tests. M3 uses the historical benchmark fixture so validation tests
   * continue to exercise the Jetcaster document; other catalogs use captured publications.
   */
  fun publishedFor(catalogSystemIds: Set<String>): Map<String, CatalogCapabilityV1> =
    catalogSystemIds.filter { it in servedIds }.associateWith(::catalog) +
      if ("m3-catalog" in catalogSystemIds) mapOf("m3-catalog" to benchmarkCatalog())
      else emptyMap()

  /** An executor with every catalog supplied explicitly. */
  fun executor(
    catalogSystemIds: Set<String>,
    packs: List<UiBuilderComponentPackSource> = emptyList(),
  ): CurrentM3UiBuilderCatalogExecutor =
    fixtureCatalogExecutor(
      catalogSystemIds = catalogSystemIds,
      packs = packs,
      published = publishedFor(catalogSystemIds),
    )
}

/** Historical benchmark input, supplied by tests rather than implicitly served by production. */
internal fun benchmarkCatalog(): CatalogCapabilityV1 =
  Json.decodeFromString(
    checkNotNull(
        CurrentM3UiBuilderCatalogExecutor::class
          .java
          .getResourceAsStream("/ee/schimke/composeai/uibuilder/catalogs/m3-catalog-v1.json")
      )
      .bufferedReader()
      .use { it.readText() }
  )

/** Explicit benchmark catalog for tests that validate the Jetcaster operations fixture. */
internal fun fixtureCatalogExecutor(
  source: String? = null,
  catalogSystemIds: Set<String> = setOf("m3-catalog"),
  exportCapabilities: ExportCapabilitiesV1 =
    ExportCapabilitiesV1.Builder()
      .apply {
        composeCode = true
        svg = false
        png = false
      }
      .build(),
  composeExportFor: (String) -> Boolean = { exportCapabilities.composeCode },
  packs: List<UiBuilderComponentPackSource> = emptyList(),
  published: Map<String, CatalogCapabilityV1> = emptyMap(),
): CurrentM3UiBuilderCatalogExecutor {
  val benchmark =
    source?.let { Json.decodeFromString<CatalogCapabilityV1>(it) } ?: benchmarkCatalog()
  return CurrentM3UiBuilderCatalogExecutor.Builder()
    .also {
      source?.let { source -> it.source = source }
      it.catalogSystemIds = catalogSystemIds
      it.exportCapabilities = exportCapabilities
      it.composeExportFor = composeExportFor
      it.packs = packs
      it.published =
        (if ("m3-catalog" in catalogSystemIds)
          mapOf(
            "m3-catalog" to
              benchmark.newBuilder().also { it.exportCapabilities = exportCapabilities }.build()
          )
        else emptyMap()) + published
    }
    .build()
}
