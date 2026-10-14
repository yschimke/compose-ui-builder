package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import java.io.File
import kotlinx.serialization.json.Json

/**
 * `wear-m3` as a committed capability fixture, and `remote-m3` and `a2ui-catalog` as their own
 * published files, handed to the executor as published catalogs.
 *
 * Wear uses its composed fixture (`docs/design/fixtures/ui-builder/<id>-capabilities-v1.json`,
 * written by `SynthesisedCatalogGoldenTest`). The other catalogs compose the published policy and
 * record, so tests follow their owned definitions.
 */
internal object PublishedCatalogFixtures {

  private val json = Json { ignoreUnknownKeys = true }

  /** The catalogs whose definition this build no longer wants to own. */
  val servedIds: Set<String> =
    setOf(
      CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID,
      CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID,
      CurrentM3UiBuilderCatalogExecutor.A2UI_CATALOG_SYSTEM_ID,
    )

  fun catalog(systemId: String): CatalogCapabilityV1 =
    if (
      systemId == CurrentM3UiBuilderCatalogExecutor.A2UI_CATALOG_SYSTEM_ID ||
        systemId == CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID
    )
      // No Kotlin generator: compose the catalog's published policy and record.
      CatalogCutoverFixtures.catalog(systemId)
    else json.decodeFromString(file(systemId).readText())

  // A unit test runs with the module directory as its working directory, so `..` reaches the
  // repository root -- resolved by the DIRECTORY, the way `SynthesisedCatalogGoldenTest` does it.
  private fun file(systemId: String): File {
    val fixtures = "docs/design/fixtures/ui-builder"
    val dir = File("../$fixtures").takeIf { it.isDirectory } ?: File(fixtures)
    return File(dir, "$systemId-capabilities-v1.json")
  }

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
