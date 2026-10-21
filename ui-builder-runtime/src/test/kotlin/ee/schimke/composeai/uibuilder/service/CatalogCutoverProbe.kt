package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.CatalogSeedTemplates
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * What happens to each catalog when the catalog-owned flag is on: the measurement
 * [CatalogCutoverReadinessTest] holds to its gap ledger. One line per finding, so the ledger reads
 * as the list of things a catalog repository has to publish before its cutover is a no-op.
 */
internal object CatalogCutoverProbe {

  val fixture by lazy {
    Json.parseToJsonElement(
        File("../docs/design/fixtures/ui-builder/jetcaster-discover-operations-v1.json").readText()
      )
      .jsonObject
  }

  /**
   * `<catalog>: <finding>` for everything that is not clean under [CatalogOwnership.ALL].
   * [published] maps id to what each catalog publishes; tests may add a declaration to see its
   * effect.
   */
  fun findings(
    published: Map<String, CatalogCapabilityV1> =
      CatalogCutoverFixtures.catalogIds.associateWith(CatalogCutoverFixtures::catalog),
    templates: (String) -> CatalogSeedTemplates = CatalogCutoverFixtures::templates,
  ): List<String> =
    // The same check that decides whether a catalog can be served owned.
    published.flatMap { (id, catalog) ->
      OwnedCatalogCheck.findings(
        catalogId = id,
        published = catalog,
        templates = templates(id),
        packComponents = CatalogCutoverFixtures.composed(id).records,
        exportRecord = CatalogCutoverFixtures.exportRecord(id),
        nativeRuntimeId = CatalogCutoverFixtures.rendererRuntimeId(id),
      )
    }
}
