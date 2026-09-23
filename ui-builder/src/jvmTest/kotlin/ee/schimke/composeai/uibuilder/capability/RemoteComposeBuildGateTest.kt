package ee.schimke.composeai.uibuilder.capability

import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import ee.schimke.composeai.uibuilder.export.wearWidgetUiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

/**
 * A widget palette offers a repetition only in a build that can export one — and a design that
 * already holds one still opens in every build.
 *
 * `RemoteContentEmitter` refuses `layout/for-each` unless the build enables Remote Compose
 * authoring, and the default build — the one `:ui-builder-desktop:run` and the released desktop app
 * are — does not. So the insert panel hides it there. The catalog keeps it: removing the capability
 * made a design saved by an enabled build fail validation as an unknown component, and every
 * unrelated edit to it with it.
 */
class RemoteComposeBuildGateTest {
  private val remote = catalog("/remote-m3-capabilities-v1.json")

  @Test
  fun `a widget palette offers For each exactly when the build can export it`() {
    assertEquals(
      !UiBuilderBuildFeatures.remoteCompose,
      "layout/for-each" in remote.paletteHiddenComponentIds,
    )
  }

  @Test
  fun `a widget that already holds For each is still one the catalog knows`() {
    val blank =
      wearWidgetUiBuilderDocument(
        "loop",
        JsonObject(emptyMap()),
        JsonObject(emptyMap()),
        WearWidgetScaffoldSize.Small,
      )
    val root = blank.roots.single()
    val loop = UiBuilderNode(id = "loop", componentId = "layout/for-each")
    val document =
      blank.copy(
        nodes =
          blank.nodes +
            (root to blank.nodes.getValue(root).copy(slots = mapOf("content" to listOf("loop")))) +
            ("loop" to loop)
      )

    assertTrue("layout/for-each" in remote.componentsById)
    assertEquals(emptySet(), CapabilityValidator(remote).coverage(document).missingComponentIds)
  }

  @Test
  fun `a Compose screen keeps For each in its palette in every build`() {
    val mobile = catalog("/m3-catalog-capabilities-v1.json")

    assertTrue("layout/for-each" in mobile.componentsById)
    assertTrue(mobile.paletteHiddenComponentIds.isEmpty())
  }

  private fun catalog(path: String): CapabilityCatalog =
    CapabilityCatalogParser.parse(checkNotNull(javaClass.getResource(path)).readText())
}
