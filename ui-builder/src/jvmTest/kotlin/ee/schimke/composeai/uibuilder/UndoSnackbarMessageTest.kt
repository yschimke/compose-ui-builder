package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.editor.undoSnackbarInvalidatedBy
import ee.schimke.composeai.uibuilder.editor.undoSnackbarMessage
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The undo snackbar's sentence, read from real reducer transitions over the Jetcaster fixture, so
 * what it names is what the reducer actually removed rather than what the test assumed it would.
 */
class UndoSnackbarMessageTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private fun message(
    event: UiBuilderEditorEvent,
    before: UiBuilderEditorState,
  ): Pair<String?, UiBuilderEditorState> {
    val after = reducer.reduce(before, event)
    return undoSnackbarMessage(event, before, after) { reducer.nodeKindLabel(before, it) } to after
  }

  @Test
  fun `deleting one node names its component`() {
    val before = reducer.initial(document, selectedNodeId = "main-episode-play")
    assertEquals("Deleted Icon button", message(UiBuilderEditorEvent.DeleteSelected, before).first)
  }

  @Test
  fun `a text is named by its kind, not quoted`() {
    // The layers panel calls this node "Episode 140: Lorem ipsum dolor"; a snackbar that quoted it
    // would read like the deletion of a sentence.
    val before = reducer.initial(document, selectedNodeId = "main-episode-title")
    assertEquals("Cut Text", message(UiBuilderEditorEvent.CutSelected, before).first)
  }

  @Test
  fun `a multi-selection is counted`() {
    var before = reducer.initial(document, selectedNodeId = "main-episode-play")
    before = reducer.reduce(before, UiBuilderEditorEvent.ToggleNode("main-episode-queue"))
    before = reducer.reduce(before, UiBuilderEditorEvent.ToggleNode("main-episode-more"))
    assertEquals("Deleted 3 items", message(UiBuilderEditorEvent.DeleteSelected, before).first)
  }

  @Test
  fun `a descendant selected with its ancestor is not counted twice`() {
    var before = reducer.initial(document, selectedNodeId = "main-episode-play")
    before = reducer.reduce(before, UiBuilderEditorEvent.ToggleNode("main-episode-play-icon"))
    assertEquals("Deleted Icon button", message(UiBuilderEditorEvent.DeleteSelected, before).first)
  }

  @Test
  fun `unwrapping names the container that went`() {
    val before = reducer.initial(document, selectedNodeId = "main-episode-copy")
    val (sentence, after) = message(UiBuilderEditorEvent.UnwrapSelection, before)
    assertEquals("Unwrapped Column", sentence)
    // Its children stayed, which is why unwrap counts only the container.
    assertTrue("main-episode-title" in after.document.nodes)
  }

  @Test
  fun `non-destructive and refused edits offer nothing`() {
    val selected = reducer.initial(document, selectedNodeId = "main-episode-play")
    assertNull(message(UiBuilderEditorEvent.DuplicateSelected, selected).first)
    assertNull(message(UiBuilderEditorEvent.CopySelected, selected).first)
    // The root has nowhere to go: the reducer refuses, and there is nothing to undo.
    val root = reducer.initial(document, selectedNodeId = "root-surface")
    val (sentence, after) = message(UiBuilderEditorEvent.DeleteSelected, root)
    assertEquals(root.document, after.document)
    assertNull(sentence)
  }

  @Test
  fun `any later edit, an undo included, takes the offer down`() {
    val before = reducer.initial(document, selectedNodeId = "main-episode-play")
    val deleted = reducer.reduce(before, UiBuilderEditorEvent.DeleteSelected)
    val undone = reducer.reduce(deleted, UiBuilderEditorEvent.Undo)
    assertTrue(undoSnackbarInvalidatedBy(deleted, undone))
    val edited =
      reducer.reduce(
        deleted,
        UiBuilderEditorEvent.CommitProperty("main-episode-title", "text", "Renamed"),
      )
    assertTrue(undoSnackbarInvalidatedBy(deleted, edited))
    // Moving the selection is not an edit, and the action still undoes the delete it names.
    val moved = reducer.reduce(deleted, UiBuilderEditorEvent.SelectNode("main-episode-title"))
    assertFalse(undoSnackbarInvalidatedBy(deleted, moved))
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
