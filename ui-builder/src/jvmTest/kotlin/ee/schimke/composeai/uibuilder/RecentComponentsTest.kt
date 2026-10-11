package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorCatalogRow
import ee.schimke.composeai.uibuilder.editor.RECENT_COMPONENT_LIMIT
import ee.schimke.composeai.uibuilder.editor.RECENT_SHELF
import ee.schimke.composeai.uibuilder.editor.RECENT_SHELF_ID
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.editor.catalogRowKey
import ee.schimke.composeai.uibuilder.editor.withRecentComponent
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The palette's "Recently used" shelf: what was just placed, first, so the second Text of a screen
 * is one press away instead of a scroll back to the Text shelf.
 */
class RecentComponentsTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private fun shelfNames(rows: List<EditorCatalogRow>): List<String> =
    rows.filterIsInstance<EditorCatalogRow.Group>().map(EditorCatalogRow.Group::name)

  /** The component ids under the "Recently used" heading, in the order the panel draws them. */
  private fun recentShelf(state: UiBuilderEditorState): List<String> =
    reducer
      .catalogRows(state)
      .filterIsInstance<EditorCatalogRow.Component>()
      .filter { it.recent }
      .map { it.item.componentId }

  /** A grid that takes any composable, so every insert below has somewhere to land. */
  private val base = reducer.initial(document, selectedNodeId = "discover-grid")

  /**
   * [componentId] inserted into the grid. The target is asked of [base] rather than [state]: an
   * insert selects what it placed, and a Text holds nothing for the next insert to land in.
   */
  private fun inserted(
    state: UiBuilderEditorState = base,
    componentId: String,
  ): UiBuilderEditorState {
    val target =
      checkNotNull(reducer.dropTarget(base, componentId)) { "no target for $componentId" }
    return reducer.reduce(state, UiBuilderEditorEvent.InsertComponent(componentId, target))
  }

  @Test
  fun `the list is most recent first, each component once, and bounded`() {
    val list = emptyList<String>().withRecentComponent("a").withRecentComponent("b")
    assertEquals(listOf("b", "a"), list)
    // A repeat moves to the front rather than appearing twice.
    assertEquals(listOf("a", "b"), list.withRecentComponent("a"))
    assertEquals(listOf("b", "a"), list.withRecentComponent("b"))

    val full = (1..10).fold(emptyList<String>()) { acc, n -> acc.withRecentComponent("c$n") }
    assertEquals(RECENT_COMPONENT_LIMIT, full.size)
    assertEquals("c10", full.first())
    assertEquals((10 downTo 5).map { "c$it" }, full)
    // The oldest is what falls off, even when the newest was already on the list.
    assertEquals(listOf("c2", "c3"), listOf("c3", "c2", "c1").withRecentComponent("c2", limit = 2))
  }

  @Test
  fun `a fresh editor has no recent shelf`() {
    val state = reducer.initial(document)

    assertTrue(state.recentComponents.isEmpty())
    assertTrue(RECENT_SHELF !in shelfNames(reducer.catalogRows(state)))
  }

  @Test
  fun `an accepted insert lands on the shelf, at the top of the panel`() {
    val afterText = inserted(componentId = "m3/text")
    val afterButton = inserted(afterText, "m3/button")
    val textAgain = inserted(afterButton, "m3/text")

    assertEquals(listOf("m3/text"), afterText.recentComponents)
    assertEquals(listOf("m3/button", "m3/text"), afterButton.recentComponents)
    assertEquals(listOf("m3/text", "m3/button"), textAgain.recentComponents)

    val rows = reducer.catalogRows(textAgain)
    assertEquals(RECENT_SHELF, shelfNames(rows).first())
    assertEquals(2, (rows.first() as EditorCatalogRow.Group).count)
    assertEquals(listOf("m3/text", "m3/button"), recentShelf(textAgain))
    // A shortcut, not a move: the component is still on its own shelf as well.
    assertTrue(
      rows.filterIsInstance<EditorCatalogRow.Component>().any {
        it.item.componentId == "m3/text" && !it.recent
      }
    )
  }

  @Test
  fun `an insert beside counts too`() {
    val state = reducer.initial(document)
    val beside =
      reducer.reduce(state, UiBuilderEditorEvent.InsertComponentBeside("m3/button", variant = null))

    assertEquals(listOf("m3/button"), beside.recentComponents)
  }

  @Test
  fun `a refused insert placed nothing, so it is not recorded`() {
    val state = reducer.initial(document)
    val refused =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.InsertComponent("m3/text", ParentSlot("no-such-node", "children")),
      )

    assertTrue(refused.recentComponents.isEmpty())
  }

  @Test
  fun `searching hides the shelf, because the match already answers`() {
    val used = inserted(componentId = "m3/text")
    val searching = reducer.reduce(used, UiBuilderEditorEvent.SearchCatalog("te"))

    assertTrue(RECENT_SHELF !in shelfNames(reducer.catalogRows(searching)))
  }

  @Test
  fun `only components the catalog still offers are shown`() {
    val state =
      reducer.initial(document).copy(recentComponents = listOf("not/a-component", "m3/text"))

    assertEquals(listOf("m3/text"), recentShelf(state))
    assertTrue(
      RECENT_SHELF !in
        shelfNames(reducer.catalogRows(state.copy(recentComponents = listOf("not/a-component"))))
    )
  }

  @Test
  fun `the shelf survives the authoritative document that answers the insert`() {
    val used = inserted(componentId = "m3/text")

    assertEquals(listOf("m3/text"), reducer.reconciled(used, used.document).recentComponents)
  }

  @Test
  fun `the shelf collapses like any other`() {
    val used = inserted(componentId = "m3/text")
    val collapsed = reducer.reduce(used, UiBuilderEditorEvent.ToggleCatalogGroup(RECENT_SHELF_ID))

    val rows = reducer.catalogRows(collapsed)
    assertEquals(RECENT_SHELF, shelfNames(rows).first())
    assertTrue(recentShelf(collapsed).isEmpty())
  }

  @Test
  fun `a catalog shelf called Recently used is a different shelf from the editor's`() {
    // The fixture catalog with one shelf of its own, labelled exactly as the editor's shelf is.
    val base = Json.parseToJsonElement(resource("/m3-catalog-capabilities-v1.json")).jsonObject
    val menu =
      JsonObject(
        mapOf(
          "groupOrder" to JsonArray(listOf(JsonPrimitive(RECENT_SHELF))),
          "components" to
            JsonObject(
              mapOf("m3/button" to JsonObject(mapOf("group" to JsonPrimitive(RECENT_SHELF))))
            ),
        )
      )
    val semantics =
      JsonObject(base["statusSemantics"]?.jsonObject.orEmpty() + ("componentMenu" to menu))
    val reducer =
      UiBuilderEditorReducer(
        CapabilityCatalogParser.parse(JsonObject(base + ("statusSemantics" to semantics)))
      )
    val state =
      reducer
        .initial(document, selectedNodeId = "discover-grid")
        .copy(recentComponents = listOf("m3/text"))

    val headings = reducer.catalogRows(state).filterIsInstance<EditorCatalogRow.Group>()
    val sameLabel = headings.filter { it.name == RECENT_SHELF }
    assertEquals(2, sameLabel.size, "both shelves are drawn")
    // Distinct lazy keys across the whole panel: two rows under one key is a crash in a lazy grid.
    val keys = reducer.catalogRows(state).map { it.catalogRowKey() }
    assertEquals(keys.size, keys.toSet().size, "duplicate keys: ${keys - keys.toSet()}")

    // Collapsing the editor's shelf leaves the catalog's open, and the other way round.
    val editorCollapsed =
      reducer.reduce(state, UiBuilderEditorEvent.ToggleCatalogGroup(RECENT_SHELF_ID))
    assertEquals(
      listOf(false, true),
      reducer
        .catalogRows(editorCollapsed)
        .filterIsInstance<EditorCatalogRow.Group>()
        .filter { it.name == RECENT_SHELF }
        .map { it.expanded },
    )
    val catalogCollapsed =
      reducer.reduce(state, UiBuilderEditorEvent.ToggleCatalogGroup(RECENT_SHELF))
    assertEquals(
      listOf(true, false),
      reducer
        .catalogRows(catalogCollapsed)
        .filterIsInstance<EditorCatalogRow.Group>()
        .filter { it.name == RECENT_SHELF }
        .map { it.expanded },
    )
  }
}
