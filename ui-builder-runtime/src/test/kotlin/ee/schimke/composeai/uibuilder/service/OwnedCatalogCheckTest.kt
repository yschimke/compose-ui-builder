package ee.schimke.composeai.uibuilder.service

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

/**
 * [OwnedCatalogCheck]; the per-catalog answers are the gap ledger in [CatalogCutoverReadinessTest].
 */
class OwnedCatalogCheckTest {

  /**
   * Owning a catalog with no platform is refused, so the check reports that rather than throwing.
   */
  @Test
  fun `a published catalog that declares no platform is a finding, not a crash`() {
    val wear = CatalogCutoverFixtures.catalog("wear-m3")
    val unplatformed =
      wear
        .newBuilder()
        .also {
          it.statusSemantics =
            JsonObject(wear.statusSemantics - CurrentM3UiBuilderCatalogExecutor.PLATFORM_KEY)
        }
        .build()
    val findings =
      OwnedCatalogCheck.findings(
        catalogId = "wear-m3",
        published = unplatformed,
        templates = CatalogCutoverFixtures.templates("wear-m3"),
        fixture = CatalogCutoverProbe.fixture,
      )
    assertTrue(findings.single().contains("declares no platform"), findings.toString())
  }
}
