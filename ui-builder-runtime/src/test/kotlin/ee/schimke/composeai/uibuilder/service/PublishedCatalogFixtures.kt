package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import java.io.File
import kotlinx.serialization.json.Json

/**
 * `wear-m3` and `remote-m3` as committed capability fixtures, and `a2ui-catalog` as its own
 * published file, handed to the executor as published catalogs — what production does (no
 * deployment runs the synthesised generators).
 *
 * The composed fixture (`docs/design/fixtures/ui-builder/<id>-capabilities-v1.json`, written by
 * `SynthesisedCatalogGoldenTest`) rather than the policy pair, because composing needs the record
 * reader that `checkUiBuilderRuntimeBoundary` keeps off this module.
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
    if (systemId == CurrentM3UiBuilderCatalogExecutor.A2UI_CATALOG_SYSTEM_ID)
      // No golden: this build has no generator for it, so it is a2ui-catalog's own published file.
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
