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
   *
   * [published] is what each catalog publishes, by id; a test that asks "what if this catalog also
   * declared X" passes the captured catalog with X added, which is exactly the change the catalog
   * repository would make.
   */
  fun findings(
    published: Map<String, CatalogCapabilityV1> =
      CatalogCutoverFixtures.catalogIds.associateWith(CatalogCutoverFixtures::catalog),
    templates: (String) -> CatalogSeedTemplates = CatalogCutoverFixtures::templates,
  ): List<String> =
    // The same check a deployment runs in shadow, so the ledger and a box's report agree.
    published.flatMap { (id, catalog) ->
      CatalogCutoverShadow.ownedFindings(
        catalogId = id,
        published = catalog,
        templates = templates(id),
        fixture = fixture,
        packComponents = CatalogCutoverFixtures.composed(id).records,
        exportRecord = CatalogCutoverFixtures.exportRecord(id),
        nativeRuntimeId = CatalogCutoverFixtures.rendererRuntimeId(id),
      )
    }

  /**
   * One line per catalog whose repository has written a policy but publishes no delivery branch: it
   * cannot be served at all, flag or no flag, until it does.
   */
  fun unpublishedFindings(): List<String> =
    CatalogCutoverFixtures.unpublishedIds.map { id ->
      "$id: has no delivery branch, so there is no ui-builder.json to serve it from"
    }
}
