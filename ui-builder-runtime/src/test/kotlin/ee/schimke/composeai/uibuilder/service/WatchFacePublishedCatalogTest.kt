package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The `wff-watchface` published catalog composes, is served as a watch face and exports no Kotlin.
 */
class WatchFacePublishedCatalogTest {
  private val published: String =
    generateSequence(File("").absoluteFile) { it.parentFile }
      .map { File(it, "docs/design/fixtures/ui-builder/wff-watchface-published-v1.json") }
      .first { it.isFile }
      .readText()

  private fun composed() =
    assertIs<PublishedUiBuilderCatalog.Result.Composed>(
        PublishedUiBuilderCatalog.compose(
          published,
          null,
          ExportCapabilitiesV1.Builder().apply { composeCode = true }.build(),
        ),
      )
      .catalog

  @Test
  fun `the published file composes into the WFF elements`() {
    val catalog = composed()
    assertEquals("wff-watchface", catalog.benchmark.catalogSystemId)
    assertEquals(
      UiBuilderCatalogPlatform.WATCH_FACE,
      UiBuilderCatalogPlatform.from(catalog.statusSemantics),
    )
    assertEquals(29, catalog.components.size, catalog.components.map { it.componentId }.toString())
    assertTrue(catalog.components.all { it.componentId.startsWith("wff/") })
  }

  @Test
  fun `a served watch face takes no builder vocabulary and offers no Compose export`() {
    val catalog = composed()
    val served =
      fixtureCatalogExecutor(
          catalogSystemIds = setOf("wff-watchface"),
          published = mapOf("wff-watchface" to catalog),
        )
        .listCatalogs()
        .single { it.benchmark.catalogSystemId == "wff-watchface" }
    assertEquals(
      catalog.components.map { it.componentId }.toSet(),
      served.components.map { it.componentId }.toSet(),
    )
    assertFalse(served.exportCapabilities.composeCode)
  }
}
