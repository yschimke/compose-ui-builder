package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorCatalogRow
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.export.RecordFreeExport
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * A root-only component is listed only where it can land.
 *
 * A Wear widget container is exported as the whole design, so once a design has a root both kinds
 * of Add refuse it. A widget design's list opened on three more widget containers it could never
 * take; an empty design is where one is the right first pick, and there it stays.
 */
class RootOnlyPaletteTest {
  private fun resource(path: String) = checkNotNull(javaClass.getResource(path)).readText()

  private val catalog = CapabilityCatalogParser.parse(resource("/remote-m3-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val widget =
    UiBuilderNewDesignSeed.document(
      designId = "widget",
      catalogSystemId = "remote-m3",
      templateId = "wear-widget-small",
      catalogRevision = "test",
      nativeRuntimeId = "test",
      fixture =
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject,
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
