package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.EditorCatalogRow
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.export.RecordFreeExport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A root-only component (e.g. a Wear widget container, exported as the whole design) is listed only
 * where it can land: on an empty design, not once a root exists.
 */
class RootOnlyPaletteTest {
  private fun resource(path: String) = checkNotNull(javaClass.getResource(path)).readText()

  private val catalog = SyntheticCatalogs.remote
  private val reducer = UiBuilderEditorReducer(catalog)
  private val widget =
    publishedTestSeed(
      systemId = "remote-m3",
      templateId = "wear-widget-small",
      designId = "widget",
      catalogRevision = "test",
      nativeRuntimeId = "test",
    )

  private fun listed(state: UiBuilderEditorState): Set<String> =
    reducer
      .catalogRows(state)
      .filterIsInstance<EditorCatalogRow.Component>()
      .map { it.item.componentId }
      .toSet()

  private val rootOnlyInCatalog =
    catalog.paletteComponents
      .map { it.componentId }
      .filter { it in RecordFreeExport.ROOT_ONLY_COMPONENT_IDS }

  @Test
  fun `a widget design does not list the widget containers it cannot take`() {
    assertTrue(rootOnlyInCatalog.isNotEmpty(), "remote-m3 lists root-only containers")
    val state = reducer.initial(widget)
    val shown = listed(state)
    assertTrue(rootOnlyInCatalog.none { it in shown }, "hidden: ${rootOnlyInCatalog - shown}")
    // Searched for by name, still not offered: a search that surfaced them would offer an Add
    // that refuses.
    val searched = listed(state.copy(catalogQuery = "widget"))
    assertTrue(rootOnlyInCatalog.none { it in searched })
    // The All row counts what is listed, not the whole catalog.
    assertEquals(
      catalog.paletteComponents.size - rootOnlyInCatalog.size,
      reducer.listedComponentCount(state),
    )
  }

  @Test
  fun `an empty design still offers them as its first pick`() {
    val empty = reducer.initial(widget.copy(roots = emptyList(), nodes = emptyMap()))
    assertTrue(rootOnlyInCatalog.all { it in listed(empty) })
    assertEquals(catalog.paletteComponents.size, reducer.listedComponentCount(empty))
  }
}
