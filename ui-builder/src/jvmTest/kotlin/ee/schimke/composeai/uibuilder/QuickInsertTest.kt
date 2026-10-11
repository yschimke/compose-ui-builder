package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorChord
import ee.schimke.composeai.uibuilder.editor.EditorViewCommand
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.editor.editorShortcutFor
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Quick insert: `/` with a layer selected opens a search beside it; typing narrows the catalog as
 * the insert panel's search does, Up and Down move, Enter inserts through the palette's own Add,
 * and Esc closes.
 */
@OptIn(ExperimentalTestApi::class)
class QuickInsertTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  /** A grid that takes any composable, so every component has somewhere to land. */
  private val grid = reducer.initial(document, selectedNodeId = "discover-grid")

  private fun ids(state: UiBuilderEditorState, query: String) =
    reducer.quickInsertItems(state, query).map { it.componentId }

  private fun inserted(state: UiBuilderEditorState, componentId: String): UiBuilderEditorState =
    reducer.reduce(
      state,
      UiBuilderEditorEvent.InsertComponent(
        componentId,
        checkNotNull(reducer.dropTarget(grid, componentId)),
      ),
    )

  @Test
  fun `slash opens it, but not with the canvas closed or off screen`() {
    val slash = EditorChord(Key.Slash, command = false, shift = false)
    assertEquals(EditorViewCommand.QuickInsert, editorShortcutFor(slash)?.view)
    assertNull(editorShortcutFor(slash, editing = false))
    assertNull(editorShortcutFor(slash, editing = true, canvasShown = false))
  }

  @Test
  fun `a search ranks exactly as the insert panel's does`() {
    // The panel's component rows for the same query, in the panel's order: one ranking, two views.
    val panel =
      reducer
        .catalogRows(grid.copy(catalogQuery = "button"))
        .filterIsInstance<ee.schimke.composeai.uibuilder.editor.EditorCatalogRow.Component>()
        .map { it.item.componentId }
        .distinct()
    val quick = ids(grid, "  Button ")
    assertTrue(quick.isNotEmpty())
    assertEquals("m3/button", quick.first(), "an exact name leads: $quick")
    // The panel groups by shelf, so compare membership; the order within quick insert is the rank.
    assertEquals(panel.toSet(), quick.toSet())
  }

  @Test
  fun `with no query the recently used components lead, most recent first`() {
    val used = inserted(inserted(grid, "m3/text"), "m3/button")
    // Back on the grid: an insert selects what it placed.
    val state = used.copy(selection = grid.selection)
    val all = ids(state, "")
    assertEquals(listOf("m3/button", "m3/text"), all.take(2))
    // Listed once, not again further down.
    assertEquals(all.distinct(), all)
    // A search ranks by match, not by recency.
    assertEquals(ids(grid, "text"), ids(state, "text"))
  }

  @Test
  fun `only components the selection can take are offered`() {
    // A Text holds nothing, so nothing inserts into it; every row Enter could pick must land.
    val label = reducer.initial(document, selectedNodeId = "main-episode-title")
    ids(label, "").forEach { id ->
      assertTrue(reducer.dropTarget(label, id) != null, "$id has nowhere to go")
    }
    ids(grid, "").forEach { id ->
      assertTrue(reducer.dropTarget(grid, id) != null, "$id has nowhere to go")
    }
  }

  @Test
  fun `the real editor inserts the highlighted match on Enter and records it as recent`() =
    runDesktopComposeUiTest(width = 1600, height = 1050) {
      var latest: UiBuilderEditorState? = null
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document,
            catalog,
            chrome = PointerTestUiBuilderChrome,
            onStateChanged = { latest = it },
          )
        }
      }
      waitForIdle()
      // A press on the design selects what is under it and gives the editor the keys.
      window().performMouseInput { click(DESIGN) }
      waitForIdle()
      val before = checkNotNull(latest)
      val selected = checkNotNull(before.selectedNodeId)
      val expected = reducer.quickInsertItems(before, "text").first().componentId

      window().performKeyInput { pressKey(Key.Slash) }
      waitForIdle()
      val search = onNodeWithContentDescription("Quick insert search")
      search.assertIsFocused()
      // A `/` typed into the search is a character, not another quick insert.
      search.performTextInput("tex")
      search.performTextInput("t")
      waitForIdle()
      assertEquals(
        "text",
        search.fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
      )
      // Down then Up lands back on the best match.
      search.performKeyInput {
        pressKey(Key.DirectionDown)
        pressKey(Key.DirectionUp)
        pressKey(Key.Enter)
      }
      waitForIdle()

      onNodeWithContentDescription("Quick insert").assertDoesNotExist()
      val after = checkNotNull(latest)
      assertEquals(before.document.nodes.size + 1, after.document.nodes.size)
      assertEquals(expected, after.recentComponents.first())
      val placed = after.document.nodes.keys - before.document.nodes.keys
      assertEquals(expected, after.document.nodes.getValue(placed.single()).componentId)
      assertTrue(selected in after.document.nodes)
    }

  @Test
  fun `Esc closes it without inserting, and slash opens it again`() =
    runDesktopComposeUiTest(width = 1600, height = 1050) {
      var latest: UiBuilderEditorState? = null
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document,
            catalog,
            chrome = PointerTestUiBuilderChrome,
            onStateChanged = { latest = it },
          )
        }
      }
      waitForIdle()
      window().performMouseInput { click(DESIGN) }
      waitForIdle()
      val before = checkNotNull(latest)

      window().performKeyInput { pressKey(Key.Slash) }
      waitForIdle()
      onNodeWithContentDescription("Quick insert").assertExists()
      // Nothing used yet, so no shelf heading.
      onNodeWithText("Recently used").assertDoesNotExist()
      onNodeWithContentDescription("Quick insert search").performKeyInput { pressKey(Key.Escape) }
      waitForIdle()
      onNodeWithContentDescription("Quick insert").assertDoesNotExist()
      assertEquals(before.document, checkNotNull(latest).document)

      // The editor has the keys back: `/` reopens it straight away.
      window().performKeyInput { pressKey(Key.Slash) }
      waitForIdle()
      onNodeWithContentDescription("Quick insert").assertExists()
    }

  private companion object {
    /** A point on the Jetcaster design as a 1600 × 1050 window draws it. */
    val DESIGN = Offset(420f, 600f)
  }

  /** The editor's own root: the card is a popup, which is a second one. */
  private fun ComposeUiTest.window() = onAllNodes(isRoot()).onFirst()

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
