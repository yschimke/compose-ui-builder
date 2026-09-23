package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorCatalogRow
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The palette's pins, and the catalog defaults behind them.
 *
 * Two halves of one bargain: the catalog knows what its typical app is made of, so it declares the
 * components worth keeping at the top and the slot each component considers its main one; the
 * reader can overrule the pins, and the moment they do, the panel is theirs — including the
 * defaults they take off.
 */
class PalettePinsTest {
  private val baseCatalog =
    CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(baseCatalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  /** The fixture catalog with editor metadata declared, as a catalog would declare its own. */
  private fun catalogWith(
    pins: List<String> = emptyList(),
    recommended: Map<String, List<String>> = emptyMap(),
  ): CapabilityCatalog {
    val base = Json.parseToJsonElement(resource("/m3-catalog-capabilities-v1.json")).jsonObject
    val semantics =
      JsonObject(
        base["statusSemantics"]?.jsonObject.orEmpty() +
          mapOf(
            "pinnedComponents" to JsonArray(pins.map(::JsonPrimitive)),
            "recommendedSlots" to
              JsonObject(
                recommended.mapValues { (_, names) -> JsonArray(names.map(::JsonPrimitive)) }
              ),
          )
      )
    return CapabilityCatalogParser.parse(JsonObject(base + ("statusSemantics" to semantics)))
  }

  private fun shelfNames(rows: List<EditorCatalogRow>): List<String> =
    rows.filterIsInstance<EditorCatalogRow.Group>().map(EditorCatalogRow.Group::name)

  @Test
  fun `a catalog that declares nothing pins nothing and the panel is unchanged`() {
    assertTrue(baseCatalog.pinnedComponents.isEmpty())
    val state = reducer.initial(document)

    assertTrue(reducer.pinnedComponents(state).isEmpty())
    assertTrue("Pinned" !in shelfNames(reducer.catalogRows(state)))
  }

  @Test
  fun `the catalog's pins are the defaults, and ids it does not offer are dropped`() {
    val catalog = catalogWith(pins = listOf("m3/button", "m3/text", "not/a-component"))
    val reducer = UiBuilderEditorReducer(catalog)

    assertEquals(
      setOf("m3/button", "m3/text"),
      reducer.pinnedComponents(reducer.initial(document)),
    )
  }

  @Test
  fun `pinning a component keeps it at the top and in its own shelf`() {
    val pinned =
      reducer.reduce(
        reducer.initial(document),
        UiBuilderEditorEvent.TogglePinnedComponent("m3/button"),
      )

    val rows = reducer.catalogRows(pinned)
    // The shelf line comes first and says how many are in it.
    assertEquals("Pinned", shelfNames(rows).first())
    assertEquals(1, (rows.first() as EditorCatalogRow.Group).count)
    // A pin is a shortcut, not a move: the component's row is drawn twice — once under Pinned,
    // once on its real shelf — and both copies still carry the shelf the catalog put it on.
    val buttonRows =
      rows.filterIsInstance<EditorCatalogRow.Component>().filter {
        it.item.componentId == "m3/button"
      }
    assertEquals(2, buttonRows.size)
    assertEquals(listOf("Actions", "Actions"), buttonRows.map { it.item.group })
  }

  @Test
  fun `a catalog default can be taken off, because the first press materialises the defaults`() {
    val catalog = catalogWith(pins = listOf("m3/button", "m3/text"))
    val reducer = UiBuilderEditorReducer(catalog)
    val unpinned =
      reducer.reduce(
        reducer.initial(document),
        UiBuilderEditorEvent.TogglePinnedComponent("m3/text"),
      )

    assertEquals(setOf("m3/button"), reducer.pinnedComponents(unpinned))
    val pinnedShelf =
      reducer
        .catalogRows(unpinned)
        .takeWhile { it !is EditorCatalogRow.Group || it.name == "Pinned" }
        .filterIsInstance<EditorCatalogRow.Component>()
        .map { it.item.componentId }
    assertEquals(listOf("m3/button"), pinnedShelf)
  }

  @Test
  fun `searching hides the pinned shelf, because the match already answers`() {
    val pinned =
      reducer.reduce(
        reducer.initial(document),
        UiBuilderEditorEvent.TogglePinnedComponent("m3/button"),
      )
    val searching = reducer.reduce(pinned, UiBuilderEditorEvent.SearchCatalog("but"))

    assertTrue(
      "Pinned" !in shelfNames(reducer.catalogRows(searching)),
      "a search is a question about the whole catalog, not about the pins",
    )
  }

  @Test
  fun `the derived recommendation is the slot that takes the most of the catalog`() {
    // No declaration: the widest acceptance wins, which for a Scaffold is its content and for a
    // container its children.
    assertEquals("content", baseCatalog.recommendedSlot("layout/scaffold"))
    assertEquals("content", baseCatalog.recommendedSlot("m3/card"))
    assertEquals("children", baseCatalog.recommendedSlot("layout/column"))
    // A leaf declares no slots, so there is nothing to recommend.
    assertNull(baseCatalog.recommendedSlot("m3/text"))
  }

  @Test
  fun `a declared recommendation wins, and a stale one falls back to the derivation`() {
    val declared = catalogWith(recommended = mapOf("layout/scaffold" to listOf("topBar")))
    assertEquals("topBar", declared.recommendedSlot("layout/scaffold"))

    // A slot the component does not declare must not invent a destination.
    val stale = catalogWith(recommended = mapOf("layout/scaffold" to listOf("sidebar")))
    assertEquals("content", stale.recommendedSlot("layout/scaffold"))
  }
}
