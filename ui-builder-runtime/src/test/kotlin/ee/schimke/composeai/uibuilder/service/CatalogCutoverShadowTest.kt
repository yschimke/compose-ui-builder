package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CatalogCutoverShadow]: the check a deployment runs for a catalog it shadows before flipping it.
 * Its owned half is the readiness probe itself (`CatalogCutoverProbe` delegates to it, so the gap
 * ledger in [CatalogCutoverReadinessTest] is its golden); this pins the catalog comparison and the
 * executor's on-demand synthesis it depends on.
 */
class CatalogCutoverShadowTest {

  private val executor =
    CurrentM3UiBuilderCatalogExecutor.Builder()
      .also { it.catalogOwnership = CatalogOwnership.NONE }
      .build()

  @Test
  fun `a catalog compared with itself has no differences`() {
    val wear = assertNotNull(executor.synthesisedCatalog("wear-m3"))
    assertEquals(emptyList(), CatalogCutoverShadow.catalogDifferences(wear, wear))
  }

  @Test
  fun `the executor synthesises on demand, and nothing for an id it does not know`() {
    assertNotNull(executor.synthesisedCatalog("remote-m3"))
    assertNull(executor.synthesisedCatalog("remote-widgets"))
  }

  @Test
  fun `a component or property only one side has is reported, and by which side`() {
    val kotlin = assertNotNull(executor.synthesisedCatalog("wear-m3"))
    val dropped = kotlin.components.first { it.properties.isNotEmpty() }
    val property = dropped.properties.first().name
    val published =
      kotlin
        .newBuilder()
        .also { builder ->
          builder.components =
            kotlin.components.drop(0).map { component ->
              if (component.componentId != dropped.componentId) component
              else
                component
                  .newBuilder()
                  .also {
                    it.properties = component.properties.filterNot { p -> p.name == property }
                  }
                  .build()
            } - kotlin.components.last()
        }
        .build()

    val differences = CatalogCutoverShadow.catalogDifferences(kotlin, published)
    assertContains(
      differences,
      "wear-m3/${kotlin.components.last().componentId}: only the Kotlin catalog has it",
    )
    assertContains(differences, "wear-m3/${dropped.componentId}: properties: loses $property")
  }

  /**
   * The comparison a box would print for Wear today, measured against the captured delivery branch:
   * not asserted line by line (the catalogs move independently), but it must name components rather
   * than fail, and every line must be addressed to the catalog.
   */
  @Test
  fun `the live Wear catalog compares against the Kotlin one it would replace`() {
    val differences =
      CatalogCutoverShadow.catalogDifferences(
        assertNotNull(executor.synthesisedCatalog("wear-m3")),
        CatalogCutoverFixtures.catalog("wear-m3"),
      )
    assertTrue(differences.all { it.startsWith("wear-m3/") }, differences.joinToString("\n"))
  }

  /**
   * The report compares served catalogs, so the builder vocabulary both sides are given is never
   * reported as lost, and its findings are the readiness ledger's lines for the catalog.
   */
  @Test
  fun `the report compares what an editor is served, and agrees with the ledger`() {
    val report =
      CatalogCutoverShadow.report(
        catalogId = "wear-m3",
        published = CatalogCutoverFixtures.catalog("wear-m3"),
        templates = CatalogCutoverFixtures.templates("wear-m3"),
        fixture = CatalogCutoverProbe.fixture,
        packComponents = CatalogCutoverFixtures.composed("wear-m3").records,
        exportRecord = CatalogCutoverFixtures.exportRecord("wear-m3"),
        nativeRuntimeId = CatalogCutoverFixtures.rendererRuntimeId("wear-m3"),
      )
    val differences = assertNotNull(report.differences)
    assertTrue(
      differences.none { it.startsWith("wear-m3/layout/") },
      differences.joinToString("\n"),
    )
    assertEquals(
      CatalogCutoverProbe.findings(
        published = mapOf("wear-m3" to CatalogCutoverFixtures.catalog("wear-m3"))
      ),
      report.findings,
    )
  }

  @Test
  fun `a catalog the build synthesises nothing for has nothing to lose`() {
    val report =
      CatalogCutoverShadow.report(
        catalogId = "glimmer-catalog",
        published = CatalogCutoverFixtures.catalog("glimmer-catalog"),
        templates = CatalogCutoverFixtures.templates("glimmer-catalog"),
        fixture = CatalogCutoverProbe.fixture,
      )
    assertNull(report.differences)
  }
}
