package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import java.io.File
import kotlinx.serialization.json.Json

/**
 * `wear-m3` and `remote-m3` as this repository's committed capability fixtures, handed to the
 * executor the way a deployment hands it a published catalog.
 *
 * ## Why a test builds them this way
 *
 * Because it is what production does. All three catalogs have served from their published files
 * since 3.27.0; nothing reads the synthesised Kotlin generators except these tests. A test that
 * constructs `wear-m3` by naming it and letting the generator answer is exercising a path no
 * deployment takes, and it is the last thing holding those 460 lines in the build
 * ([#819](https://github.com/yschimke/compose-preview-server/issues/819) step 3).
 *
 * ## Why the fixture rather than the published policy file
 *
 * Composing a catalog from the pair a repository publishes — a policy file plus a component record
 * — is `:server`'s job: it needs the record reader, which `checkUiBuilderRuntimeBoundary` keeps off
 * this module's classpath on purpose. This module's seam is `published`, which takes catalogs
 * already composed. So the fixture is the composed form, and
 * `docs/design/fixtures/ui-builder/<id>-capabilities-v1.json` already is exactly that: written by
 * `SynthesisedCatalogGoldenTest` as `encodeToString(catalog)` of a `CatalogCapabilityV1`, so it
 * decodes straight back into one.
 *
 * That the fixture is currently a golden OF the generator is the point of the sequencing, not an
 * accident: it is the description the generator produced, frozen while both existed, and it goes on
 * describing the catalog after the generator is gone.
 */
internal object PublishedCatalogFixtures {

  private val json = Json { ignoreUnknownKeys = true }

  /** The catalogs whose definition this build no longer wants to own. */
  val servedIds: Set<String> =
    setOf(
      CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID,
      CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID,
    )

  fun catalog(systemId: String): CatalogCapabilityV1 =
    json.decodeFromString(file(systemId).readText())

  // A unit test runs with the module directory as its working directory, so `..` reaches the
  // repository root -- resolved by the DIRECTORY, the way `SynthesisedCatalogGoldenTest` does it.
  private fun file(systemId: String): File {
    val fixtures = "docs/design/fixtures/ui-builder"
    val dir = File("../$fixtures").takeIf { it.isDirectory } ?: File(fixtures)
    return File(dir, "$systemId-capabilities-v1.json")
  }

  /**
   * The `published` map for whichever of [servedIds] an executor is being asked to serve.
   *
   * `m3-catalog` is deliberately absent: it is the PACKAGED catalog this build still owns, the one
   * a standalone builder opens on, and the foundation is derived from it.
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
