package ee.schimke.composeai.uibuilder.capability

import ee.schimke.composeai.uibuilder.UiBuilderBuildFeatures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A widget palette offers a repetition only in a build that can export one.
 *
 * `RemoteContentEmitter` refuses `layout/for-each` unless the build enables Remote Compose
 * authoring, and the default build — the one `:ui-builder-desktop:run` and the released desktop app
 * are — does not. Offered anyway, every widget that used it was a design with no export.
 */
class RemoteComposeBuildGateTest {
  @Test
  fun `a widget palette offers For each exactly when the build can export it`() {
    val remote = catalog("/remote-m3-capabilities-v1.json")

    assertEquals(
      UiBuilderBuildFeatures.remoteCompose,
      remote.components.any { it.componentId == "layout/for-each" },
    )
  }

  @Test
  fun `a Compose screen keeps For each in every build`() {
    val mobile = catalog("/m3-catalog-capabilities-v1.json")

    assertTrue(mobile.components.any { it.componentId == "layout/for-each" })
  }

  private fun catalog(path: String): CapabilityCatalog =
    CapabilityCatalogParser.parse(checkNotNull(javaClass.getResource(path)).readText())
}
