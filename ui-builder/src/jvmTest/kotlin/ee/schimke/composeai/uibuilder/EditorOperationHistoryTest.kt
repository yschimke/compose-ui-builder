package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The history says what each change did, and which change the toolbar is aimed at.
 *
 * The editor has always had undo, and never had a way to find out what it would take back. Every
 * fact this panel shows was already being kept — an accepted command carries its own operations and
 * the before/after of everything it moved — so these cases are about the reading, not the record:
 * that the reading names the right change, in the right order, and moves its markers where undo and
 * redo actually move.
 */
class EditorOperationHistoryTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private val titleId = "main-episode-title"
  private val originalTitle = "Episode 140: Lorem ipsum dolor"

  @Test
  fun `an untouched design has no history to show`() {
    assertTrue(reducer.operationHistory(reducer.initial(document)).isEmpty())
  }

  @Test
  fun `a property edit is named, and carries the value it moved from and to`() {
    val state = edit(reducer.initial(document, selectedNodeId = titleId), "Nightcall")

    val entry = reducer.operationHistory(state).single()
    // Named for what the layers panel calls the node *now*, which is how you find it on the canvas.
    // For a text node's own text that is the new value, and the line below carries the old one.
    assertEquals("Set text on Nightcall", entry.summary)
    assertEquals(titleId, entry.nodeId)
    assertTrue(entry.mine)
    assertEquals(
      EditorOperationChange("text", before = originalTitle, after = "Nightcall"),
      entry.changes.single(),
    )
  }

  @Test
  fun `the newest change is the one undo would take back, and the rest are just applied`() {
    var state = reducer.initial(document, selectedNodeId = titleId)
    listOf("one", "two", "three").forEach { state = edit(state, it) }

    val history = reducer.operationHistory(state)
    assertEquals(listOf("three", "two", "one"), history.map { it.changes.single().after })
    assertEquals(EditorOperationStanding.NextUndo, history.first().standing)
    assertTrue(history.drop(1).all { it.standing == EditorOperationStanding.Applied })
  }

  /** The marker follows the buttons: what undo just took back is what redo would put back. */
  @Test
  fun `an undo moves the undo marker down and leaves a redo marker behind`() {
    var state = reducer.initial(document, selectedNodeId = titleId)
    listOf("one", "two").forEach { state = edit(state, it) }
    state = reducer.reduce(state, UiBuilderEditorEvent.Undo)

    val history = reducer.operationHistory(state)
    assertEquals(EditorOperationStanding.NextRedo, history.first().standing)
    assertEquals(EditorOperationStanding.NextUndo, history[1].standing)
    // And what it says lines up with what the buttons will actually do.
    assertTrue(reducer.canUndo(state) && reducer.canRedo(state))
  }

  @Test
  fun `a redone change is back to being the one undo would take`() {
    var state = reducer.initial(document, selectedNodeId = titleId)
    state = edit(state, "one")
    state = reducer.reduce(state, UiBuilderEditorEvent.Undo)
    state = reducer.reduce(state, UiBuilderEditorEvent.Redo)

    assertEquals(
      EditorOperationStanding.NextUndo,
      reducer.operationHistory(state).first().standing,
    )
  }

  @Test
  fun `an insert names the component and where it went`() {
    val state = reducer.initial(document, selectedNodeId = "discover-grid")
    val target = assertNotNull(reducer.dropTarget(state, "m3/text"))
    val inserted =
      reducer.reduce(state, UiBuilderEditorEvent.InsertComponent("m3/text", target, null))

    val entry = reducer.operationHistory(inserted).first()
    assertTrue(entry.summary.startsWith("Added "), entry.summary)
    assertTrue(entry.summary.contains(reducer.nodeName("discover-grid")), entry.summary)
    assertTrue(entry.changes.any { it.label == "added" }, entry.changes.toString())
  }

  /** A screen change belongs to no node, so the row has nothing to select and says so. */
  @Test
  fun `an environment change names the field and points at no node`() {
    val initial = reducer.initial(document)
    val state =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.UpdateEnvironment(
          initial.document.screenEnvironmentSettings().copy(density = 2.0)
        ),
      )

    val entry = reducer.operationHistory(state).single()
    assertEquals("Set density on the screen", entry.summary)
    assertNull(entry.nodeId)
    assertEquals("density", entry.changes.single().label)
    assertEquals("2.0", entry.changes.single().after)
  }

  /** A chain is summarised at both ends, so "what was the padding before" has an answer. */
  @Test
  fun `a layout change shows the chain either side of it`() {
    val state =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = titleId),
        UiBuilderEditorEvent.ToggleModifier(titleId, "padding"),
      )

    val change = reducer.operationHistory(state).first().changes.single { it.label == "layout" }
    assertEquals("none", change.before)
    assertTrue(change.after.orEmpty().startsWith("padding"), change.after.orEmpty())
  }

  /**
   * Somebody else's change is in the list and is not yours to take back.
   *
   * The case the panel is for: undo walks this editor's own commands, so a collaborator's edit sits
   * above the entry undo is aimed at, and without the list there is nothing saying so.
   */
  @Test
  fun `a collaborator's change is listed, and never the one undo is aimed at`() {
    val other = UiBuilderEditorReducer(catalog, actorId = "someone-else", clientId = "their-tab")
    var state = edit(reducer.initial(document, selectedNodeId = titleId), "mine")
    state = other.reduce(state, UiBuilderEditorEvent.CommitProperty(titleId, "text", "theirs"))

    val history = reducer.operationHistory(state)
    assertEquals(2, history.size)
    val theirs = history.first()
    assertEquals("someone-else", theirs.actorId)
    assertTrue(!theirs.mine)
    assertEquals(EditorOperationStanding.Applied, theirs.standing)
    // Undo is still aimed at your own change, one row further down.
    assertEquals(EditorOperationStanding.NextUndo, history[1].standing)
    assertTrue(history[1].mine)
  }

  /**
   * The line a row shows, in characters the browser build has glyphs for.
   *
   * Pinned because the first render of this panel put an arrow between the two ends and the Wasm
   * font drew a box: the rule is that this line stays inside what the editor can actually draw.
   */
  @Test
  fun `a change reads as what it is now and what it was, without an arrow`() {
    assertEquals(
      "text  Nightcall  \u00b7  was Kavinsky",
      EditorOperationChange("text", before = "Kavinsky", after = "Nightcall").readable(),
    )
    assertEquals(
      "added  discover-grid.items",
      EditorOperationChange("added", before = null, after = "discover-grid.items").readable(),
    )
    assertEquals(
      "text  was Kavinsky",
      EditorOperationChange("text", before = "Kavinsky", after = null).readable(),
    )
    assertTrue(
      EditorOperationChange("text", "a", "b").readable().none { it.code > 0xFF },
      "the line has to stay inside the glyphs the browser build ships",
    )
  }

  private fun edit(state: UiBuilderEditorState, value: String): UiBuilderEditorState =
    reducer.reduce(state, UiBuilderEditorEvent.CommitProperty(titleId, "text", value))

  /** The layers panel's name for a node, which is what the history calls it too. */
  private fun UiBuilderEditorReducer.nodeName(nodeId: String): String =
    treeRows(document).first { it.nodeId == nodeId }.label

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
