package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.REMOTE_CONTENT_COMPONENT_IDS
import ee.schimke.composeai.uibuilder.export.REMOTE_CONTENT_MODIFIERS
import ee.schimke.composeai.uibuilder.export.REMOTE_ICON_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.REMOTE_ICON_DEFAULT_KEY
import ee.schimke.composeai.uibuilder.export.RemoteMaterial3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `remote-m3` palette against the generator's vocabulary (`REMOTE_CONTENT_COMPONENT_IDS`),
 * checked here, where both live, so a break fails in the change that caused it.
 */
class RemoteM3VocabularyParityTest {
  private val catalog =
    CurrentM3UiBuilderCatalogExecutor(
        catalogSystemIds =
          linkedSetOf("m3-catalog", CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID)
      )
      .listCatalogs()
      .single {
        it.benchmark.catalogSystemId ==
          CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID
      }

  /** The widget containers are the launcher's, not body content, so no emitter case is owed. */
  private val authoringComponents =
    catalog.components.filterNot { it.componentId.startsWith("remote-m3/widget-container-") }

  @Test
  fun `every component the palette offers has an authored answer in the generator`() {
    assertEquals(
      emptyList(),
      authoringComponents.map { it.componentId }.filterNot { it in REMOTE_CONTENT_COMPONENT_IDS },
    )
  }

  @Test
  fun `every Remote Material 3 component the export writes is in its vocabulary`() {
    assertEquals(
      emptyList(),
      RemoteMaterial3.components
        .map { it.componentId }
        .filterNot { it in REMOTE_CONTENT_COMPONENT_IDS },
    )
  }

  @Test
  fun `no component advertises a modifier the generator cannot write`() {
    assertEquals(
      emptyMap(),
      authoringComponents
        .associate {
          it.componentId to it.modifierCapabilities.filterNot { m -> m in REMOTE_CONTENT_MODIFIERS }
        }
        .filterValues { it.isNotEmpty() },
    )
  }

  /**
   * `RemoteIcon`'s vector cannot be derived from its signature, so the palette states it — keyed by
   * the icon table `m3/icon` publishes, which is the only spelling the export can write.
   */
  @Test
  fun `the icon names its vector by the icon keys the export resolves`() {
    val icon = catalog.components.single { it.componentId == REMOTE_ICON_COMPONENT_ID }
    val property = icon.properties.single { it.name == "imageVector" }
    val keys = property.allowedValues.orEmpty().map { it.jsonPrimitive.content }

    assertTrue("home" in keys && REMOTE_ICON_DEFAULT_KEY in keys, keys.toString())
  }
}
