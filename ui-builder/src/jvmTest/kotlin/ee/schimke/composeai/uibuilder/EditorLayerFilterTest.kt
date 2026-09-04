package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Finding a node in a hundred-row tree by scrolling is the tax on every other thing the layers
 * panel can do, and it fell hardest on the multi-node inspector: restyling every text on a screen
 * meant spotting each of them by eye.
 */
class EditorLayerFilterTest {
  private val catalog =
    CapabilityCatalogParser.parse(resource("/jetcaster-discover-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private fun filtered(query: String) =
    reducer.visibleTreeRows(
      reducer.reduce(
        reducer.initial(document, selectedNodeId = "root-surface"),
        UiBuilderEditorEvent.SearchLayers(query),
      )
    )

  @Test
  fun `a match keeps exactly its own ancestors as context`() {
    // Ancestors now come from parent pointers rather than a backward scan over every preceding
    // row, so this is the property that had to survive the rewrite: the rows kept for a single
    // match are that row plus its chain to the root, and nothing else.
    val rows = filtered("chip-crime-label")
    val all = reducer.treeRows(document)
    assertEquals(
      listOf("chip-crime-label"),
      rows.filter { it.matched }.map { it.nodeId },
      "the query must match exactly one row for this to be about ancestors",
    )

    val chain =
      generateSequence(all.first { it.nodeId == "chip-crime-label" }) { row ->
        row.parent?.nodeId?.let { parentId -> all.first { it.nodeId == parentId } }
      }

    assertEquals(
      chain.map { it.nodeId }.toList().reversed(),
      rows.map { it.nodeId },
      "kept rows should be the match's own ancestor chain, root first",
    )
  }

  @Test
  fun `two matches under one parent keep that parent once`() {
    // The early stop — walk up only until an ancestor is already kept — is what keeps the pass
    // linear. It must not drop an ancestor the second match still needs.
    val rows = filtered("chip-")
    val ids = rows.map { it.nodeId }

    assertEquals(ids.distinct(), ids, "no row appears twice")
    assertTrue(ids.contains("chip-crime") && ids.contains("chip-news"), ids.toString())
    rows
      .filterNot { it.matched }
      .forEach { context ->
        assertTrue(
          rows.any { it.parent?.nodeId == context.nodeId },
          "context row ${context.nodeId} keeps nothing beneath it",
        )
      }
  }

  @Test
  fun `an empty query is the whole tree`() {
    assertEquals(
      reducer.treeRows(document).map { it.nodeId },
      filtered("").map { it.nodeId },
    )
    assertTrue(filtered("").all { it.matched })
  }

  @Test
  fun `a filtered row keeps the ancestors that make its indentation mean something`() {
    val rows = filtered("search-placeholder")

    // The match itself, plus the chain down to it. A tree filtered to bare matches would show one
    // row three levels deep sitting flush against nothing.
    assertEquals(listOf("search-placeholder"), rows.filter { it.matched }.map { it.nodeId })
    assertEquals(
      listOf(
        "root-surface",
        "pane-scaffold",
        "main-background",
        "main-scaffold",
        "search-bar",
        "search-input",
        "search-placeholder",
      ),
      rows.map { it.nodeId },
    )
    // Ancestors are context and say so, so nothing selects them by accident.
    assertTrue(rows.dropLast(1).none { it.matched })
  }

  @Test
  fun `the four names a person would type all match`() {
    // What it says, what its component is called, its own id, its component id.
    assertTrue(filtered("Crime").any { it.matched }, "content")
    assertTrue(filtered("Adaptive lazy").any { it.matched }, "component display name")
    assertTrue(filtered("discover-grid").any { it.matched }, "node id")
    assertTrue(filtered("m3/filter-chip").any { it.matched }, "component id")
  }

  @Test
  fun `select all matches takes the matches and not the ancestors carrying them`() {
    val filteredState =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = "root-surface"),
        // Not "m3/icon": that is a prefix of "m3/icon-button", and a substring match over
        // component ids rightly takes both. The chip id is its own.
        UiBuilderEditorEvent.SearchLayers("m3/filter-chip"),
      )
    val matches = reducer.visibleTreeRows(filteredState).filter { it.matched }.map { it.nodeId }
    assertEquals(4, matches.size, "expected the fixture's four chips, found $matches")

    val selected = reducer.reduce(filteredState, UiBuilderEditorEvent.SelectAllMatches)

    assertEquals(matches, selected.selection)
    // Every selected node is a chip: the ancestors shown as context are not in the selection.
    assertTrue(
      selected.selection.all { document.nodes.getValue(it).componentId == "m3/filter-chip" }
    )
  }

  @Test
  fun `a query nothing answers leaves the selection alone rather than clearing it`() {
    val filteredState =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = "root-surface"),
        UiBuilderEditorEvent.SearchLayers("nothing-here-matches-this"),
      )
    assertEquals(emptyList(), reducer.visibleTreeRows(filteredState).map { it.nodeId })

    val after = reducer.reduce(filteredState, UiBuilderEditorEvent.SelectAllMatches)
    assertEquals(listOf("root-surface"), after.selection)
  }

  @Test
  fun `arrow navigation walks what the panel shows, not what the filter hid`() {
    // An arrow press that landed on a hidden row would move the selection somewhere the person
    // cannot see it.
    val filteredState =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = "root-surface"),
        UiBuilderEditorEvent.SearchLayers("search-placeholder"),
      )
    val visible = reducer.visibleTreeRows(filteredState).map { it.nodeId }

    val next =
      reducer.reduce(
        filteredState,
        UiBuilderEditorEvent.SelectRelative(EditorSelectionMove.Next),
      )

    assertEquals(visible[1], next.selectedNodeId)
  }

  @Test
  fun `an authoritative update does not clear the filter`() {
    // `authoritativeGeneration` advances for every snapshot and verified delta — including the
    // confirmation of the user's own saved edit — and reconciliation rebuilt the editor without
    // carrying this. Typing a filter and then saving cleared what you were looking at.
    val searching =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = "root-surface"),
        UiBuilderEditorEvent.SearchLayers("chip"),
      )
    assertEquals("chip", searching.layerQuery)

    val reconciled = reducer.reconciled(searching, searching.document.copy(revision = 99))

    assertEquals("chip", reconciled.layerQuery)
    assertEquals(
      reducer.visibleTreeRows(searching).map { it.nodeId },
      reducer.visibleTreeRows(reconciled).map { it.nodeId },
    )
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
