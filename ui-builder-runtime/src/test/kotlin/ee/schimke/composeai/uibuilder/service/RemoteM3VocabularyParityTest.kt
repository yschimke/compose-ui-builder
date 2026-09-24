package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.REMOTE_CONTENT_COMPONENT_IDS
import ee.schimke.composeai.uibuilder.export.REMOTE_CONTENT_MODIFIERS
import ee.schimke.composeai.uibuilder.export.RemoteMaterial3
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `remote-m3` palette against the generator's vocabulary, in the repository that owns both.
 *
 * compose-preview-server's `:server` has the same join (`RemoteM3VocabularyParityTest`), and it was
 * the only one: the palette grew the 21 Remote Material 3 components here (#207), the export learned
 * to write them (#202), and `REMOTE_CONTENT_COMPONENT_IDS` — the list the join reads — did not, so
 * the break surfaced as a red `server-against-checkout` on every pull request instead of a red test
 * in the change that caused it. This module sees the catalog and the export, so the check lives here.
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
      RemoteMaterial3.components.map { it.componentId }.filterNot { it in REMOTE_CONTENT_COMPONENT_IDS },
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
}
