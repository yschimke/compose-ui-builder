package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorCatalogRow
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The palette says which components the Compose export can write, from the export's own embedded
 * record (as the problems panel reads it), so they can't disagree and no separate `exportStatus`
 * field is needed (see compose-preview-server#477).
 */
class PaletteExportStatusTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val state =
    reducer.initial(
      UiBuilderReducer.replay(
          Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
        )
        .document,
      selectedNodeId = "discover-grid",
    )

  /**
   * The capability ids the embedded record can print a call site for, from BOTH files: the
   * builder's `layout/`, `shape/` and `asset/` record and the m3 one, merged by
   * `:ui-builder:embedComponentRecord`.
   */
  private val covered: Set<String> =
    listOf("m3-catalog-components-v1.json", "compose-foundation-components-v1.json")
      .flatMap { name ->
        Json.parseToJsonElement(resource("/$name"))
          .jsonObject
          .getValue("components")
          .jsonArray
          .map { it.jsonObject }
          .filter { it["code"]?.jsonObject?.get("call") != null }
          .flatMap { it["componentIds"]?.jsonArray.orEmpty() }
          .map { it.jsonPrimitive.content }
      }
      .toSet()

  private fun items() =
    reducer
      .catalogRows(state.copy(collapsedCatalogGroups = emptySet()))
      .filterIsInstance<EditorCatalogRow.Component>()
      .map { it.item }

  @Test
  fun `every row's export marker is the record's own answer`() {
    val items = items()
    assertTrue(items.isNotEmpty())
    for (item in items) {
      assertEquals(
        item.componentId in covered,
        assertNotNull(item.exportsToCompose, "${item.componentId} has no answer"),
        item.componentId,
      )
      item.variants.forEach { assertEquals(item.exportsToCompose, it.exportsToCompose, it.value) }
    }
  }

  @Test
  fun `the marker names the gap #477 was filed about, and not the components it closed`() {
    // The five components #477 named must read as writable (including `asset/image`, via the asset
    // registry).
    val byId = items().associateBy { it.componentId }
    for (id in
      listOf(
        "asset/image",
        "m3/list-item",
        "m3/center-aligned-top-app-bar",
        "m3/slider",
        "m3/text",
      )) {
      assertEquals(true, byId.getValue(id).exportsToCompose, id)
    }
    // And rows the record can't print are still marked, so the marker isn't a constant.
    assertEquals(false, byId.getValue("m3/date-picker").exportsToCompose)
  }

  @Test
  fun `a catalog the record was not authored for gets no marker at all`() {
    // A Wear or Remote Compose catalog exports through its own emitter, so `m3-catalog`'s record
    // says nothing: every row is null (no answer) rather than wrong.
    val other = catalog.copy(benchmark = catalog.benchmark.copy(catalogSystemId = "wear-m3"))
    val items =
      UiBuilderEditorReducer(other)
        .catalogRows(state.copy(collapsedCatalogGroups = emptySet()))
        .filterIsInstance<EditorCatalogRow.Component>()
        .map { it.item }
    assertTrue(items.isNotEmpty())
    assertTrue(items.all { it.exportsToCompose == null }, items.map { it.componentId }.toString())
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
