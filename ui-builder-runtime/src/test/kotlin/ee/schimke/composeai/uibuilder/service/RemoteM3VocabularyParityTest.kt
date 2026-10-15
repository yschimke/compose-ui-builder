package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.REMOTE_CONTENT_COMPONENT_IDS
import ee.schimke.composeai.uibuilder.export.REMOTE_CONTENT_MODIFIERS
import ee.schimke.composeai.uibuilder.export.RemoteMaterial3
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The published `remote-m3` palette against the export's modifier vocabulary. The catalog owns its
 * component list; the emitter owns the component and modifier operations it can write.
 */
class RemoteM3VocabularyParityTest {
  private val catalog =
    PublishedCatalogFixtures.executor(
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
  fun `every Remote Material 3 component the export writes is in its vocabulary`() {
    assertEquals(
      emptyList(),
      RemoteMaterial3.components
        .map { it.componentId }
        .filterNot { it in REMOTE_CONTENT_COMPONENT_IDS },
    )
  }

  @Test
  fun `no component advertises a modifier the export cannot write`() {
    assertEquals(
      emptyMap(),
      authoringComponents
        .associate {
          it.componentId to it.modifierCapabilities.filterNot { m -> m in REMOTE_CONTENT_MODIFIERS }
        }
        .filterValues { it.isNotEmpty() },
    )
  }
}
