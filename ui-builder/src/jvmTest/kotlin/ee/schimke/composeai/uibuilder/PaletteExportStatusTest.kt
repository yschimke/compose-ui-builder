package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The palette says which components the Compose export can write, from the export's own record.
 *
 * compose-preview-server#477 found the palette teaching a shape the exporter refused: three of the
 * first five components a person reaches for rendered perfectly and exported as comments, and the
 * way to a working design was knowing which third of the palette to avoid. The record the export
 * reads was already embedded in the editor for the problems panel; this is the palette reading the
 * same record, so the two cannot disagree — and so a second, parallel `exportStatus` field in the
 * catalog was never needed.
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

  /** The capability ids the embedded record can print a call site for. */
  private val covered: Set<String> =
    Json.parseToJsonElement(resource("/m3-catalog-components-v1.json"))
      .jsonObject
      .getValue("components")
      .jsonArray
      .map { it.jsonObject }
      .filter { it["code"]?.jsonObject?.get("call") != null }
      .flatMap { it["componentIds"]?.jsonArray.orEmpty() }
      .map { it.jsonPrimitive.content }
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
    // The one the record still cannot carry — a `Painter` no value expresses — is marked; the
    // four #477 named alongside it are recorded now and must read as writable, or the palette
    // would be greying out exactly the components that were just made to export.
    val byId = items().associateBy { it.componentId }
    assertEquals(false, byId.getValue("asset/image").exportsToCompose)
    for (id in listOf("m3/list-item", "m3/center-aligned-top-app-bar", "m3/slider", "m3/text")) {
      assertEquals(true, byId.getValue(id).exportsToCompose, id)
    }
  }

  @Test
  fun `a catalog the record was not authored for gets no marker at all`() {
    // A Wear or Remote Compose screen generates through its own emitter, so `m3-catalog`'s record
    // says nothing about it — and a palette greyed against the wrong record would be lying in the
    // other direction. Null on every row is the panel declining to answer rather than answering
    // wrongly.
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
