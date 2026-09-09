package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The history bar's two claims, checked where they are actually made.
 *
 * The strip claims that each row is a picture of *that* revision, and the compare pane claims that
 * what it lists is the difference between two of them. Both come out of the same record the History
 * panel reads, so what these cases are about is the rebuilding: that a rewound document is the one
 * that revision held, that a revision the record cannot reach is refused rather than approximated,
 * and that the diff is read off the two documents rather than off the operations between them —
 * which is the difference an undo in the range would otherwise hide.
 */
class RevisionTimelineTest {
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
  fun `an untouched design is one row, the one on the canvas`() {
    val state = reducer.initial(document)
    val entries = revisionTimeline(state, reducer.operationHistory(state))

    val only = entries.single()
    assertTrue(only.current)
    assertTrue(only.origin)
    assertEquals(state.document, only.document)
  }

  @Test
  fun `each edit adds a row, oldest first, and only the newest is current`() {
    var state = reducer.initial(document, selectedNodeId = titleId)
    listOf("one", "two", "three").forEach { state = edit(state, it) }

    val entries = revisionTimeline(state, reducer.operationHistory(state))
    assertEquals(4, entries.size)
    assertEquals(entries.map { it.revision }.sorted(), entries.map { it.revision })
    assertTrue(entries.first().origin)
    assertEquals(listOf(false, false, false, true), entries.map { it.current })
    assertEquals("Set text on three", entries.last().summary)
  }

  /** The claim the pictures rest on: a row's document is what that revision actually held. */
  @Test
  fun `a rewound row carries the value that revision held, not the current one`() {
    var state = reducer.initial(document, selectedNodeId = titleId)
    listOf("one", "two").forEach { state = edit(state, it) }

    val entries = revisionTimeline(state, reducer.operationHistory(state))
    assertEquals(
      listOf(originalTitle, "one", "two"),
      entries.map { assertNotNull(it.document).title(titleId) },
    )
  }

  @Test
  fun `an insert is absent from the revision before it and present after`() {
    val state = reducer.initial(document, selectedNodeId = "discover-grid")
    val target = assertNotNull(reducer.dropTarget(state, "m3/text"))
    val inserted =
      reducer.reduce(state, UiBuilderEditorEvent.InsertComponent("m3/text", target, null))

    val entries = revisionTimeline(inserted, reducer.operationHistory(inserted))
    val before = assertNotNull(entries.first().document)
    val after = assertNotNull(entries.last().document)
    assertEquals(1, after.nodes.size - before.nodes.size)
  }

  /**
   * An undo moves the document and mints a revision of its own, so the strip has to be able to
   * rewind over it — and to name it after the change it took back, because "Undo" is the one thing
   * about such a row a reader already knows.
   */
  @Test
  fun `an undo is a row of its own, named after what it took back`() {
    var state = reducer.initial(document, selectedNodeId = titleId)
    state = edit(state, "one")
    state = reducer.reduce(state, UiBuilderEditorEvent.Undo)

    val entries = revisionTimeline(state, reducer.operationHistory(state))
    val undoRow = entries.last()
    // Named after its target, and its target names the node as the canvas names it *now* — which,
    // the undo having put the old text back, is the old text. The strip borrows the History panel's
    // summaries rather than writing its own, so the two cannot drift apart on this.
    assertEquals("Took back: Set text on $originalTitle", undoRow.summary)
    assertEquals(originalTitle, assertNotNull(undoRow.document).title(titleId))
    // And the revision it took back is still on the strip, still showing what it did.
    assertEquals("one", assertNotNull(entries[entries.lastIndex - 1].document).title(titleId))
  }

  @Test
  fun `a redo rewinds too, and puts the change back`() {
    var state = reducer.initial(document, selectedNodeId = titleId)
    state = edit(state, "one")
    state = reducer.reduce(state, UiBuilderEditorEvent.Undo)
    state = reducer.reduce(state, UiBuilderEditorEvent.Redo)

    val entries = revisionTimeline(state, reducer.operationHistory(state))
    assertEquals("one", assertNotNull(entries.last().document).title(titleId))
    assertEquals(originalTitle, assertNotNull(entries.first().document).title(titleId))
  }

  /**
   * A design reopened from a stored snapshot has history this client never saw. The strip has to
   * refuse those revisions rather than draw a picture of a document it guessed at.
   */
  @Test
  fun `a revision the record does not cover is refused, not approximated`() {
    val opened = reducer.initial(document.copy(revision = 40))
    assertNull(opened.collaboration.documentAtRevision(39))
    assertEquals(40, opened.collaboration.reconstructableFromRevision())
    assertEquals(1, revisionTimeline(opened, reducer.operationHistory(opened)).size)
  }

  @Test
  fun `the strip is bounded, and the bound is the length of the strip not of the history`() {
    var state = reducer.initial(document, selectedNodeId = titleId)
    repeat(6) { state = edit(state, "value $it") }

    val entries = revisionTimeline(state, reducer.operationHistory(state), limit = 3)
    assertEquals(4, entries.size)
    assertEquals(state.document.revision, entries.last().revision)
    // Everything is still in the record: the strip is short, the history is not.
    assertEquals(6, reducer.operationHistory(state).size)
  }

  @Test
  fun `a diff names the property that moved, at both ends`() {
    var state = reducer.initial(document, selectedNodeId = titleId)
    val from = state.document.revision
    state = edit(state, "one")
    val to = state.document.revision

    val diff = assertNotNull(revisionDiff(state, catalog, from, to))
    assertEquals(from, diff.from)
    assertEquals(to, diff.to)
    assertFalse(diff.identical)
    val node = diff.nodes.single()
    assertEquals(titleId, node.nodeId)
    assertEquals(EditorNodeDiffKind.Changed, node.kind)
    assertEquals(
      EditorOperationChange("text", before = originalTitle, after = "one"),
      node.changes.single(),
    )
  }

  @Test
  fun `the diff reads the same whichever end is picked first`() {
    var state = reducer.initial(document, selectedNodeId = titleId)
    val from = state.document.revision
    state = edit(state, "one")
    val to = state.document.revision

    assertEquals(revisionDiff(state, catalog, from, to), revisionDiff(state, catalog, to, from))
  }

  /**
   * The reason the diff is computed from the documents rather than from the operations between
   * them: a change and its own reversal are two operations and no difference at all.
   */
  @Test
  fun `an edit and its undo are no difference`() {
    var state = reducer.initial(document, selectedNodeId = titleId)
    val from = state.document.revision
    state = edit(state, "one")
    state = reducer.reduce(state, UiBuilderEditorEvent.Undo)

    val diff = assertNotNull(revisionDiff(state, catalog, from, state.document.revision))
    assertTrue(diff.identical, diff.nodes.toString())
    // Two operations happened, and the History panel is where they are read.
    assertEquals(1, reducer.operationHistory(state).size)
  }

  @Test
  fun `a screen change is reported apart from the nodes`() {
    val initial = reducer.initial(document)
    val from = initial.document.revision
    val state =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.UpdateEnvironment(
          initial.document.screenEnvironmentSettings().copy(density = 2.0)
        ),
      )

    val diff = assertNotNull(revisionDiff(state, catalog, from, state.document.revision))
    assertTrue(diff.nodes.isEmpty())
    assertEquals("density", diff.environment.single().label)
    assertEquals("2.0", diff.environment.single().after)
  }

  /**
   * Looking at an old revision is not being at one — the design does not move, and edits still
   * land.
   */
  @Test
  fun `an edit made while peeking brings the canvas back to the design`() {
    var state = reducer.initial(document, selectedNodeId = titleId)
    state = edit(state, "one")
    state = reducer.reduce(state, UiBuilderEditorEvent.ShowRevision(0))
    assertEquals(0, state.revisionPeek)

    state = edit(state, "two")
    assertNull(state.revisionPeek)
    assertEquals("two", state.document.title(titleId))
  }

  @Test
  fun `shutting the strip ends what it was showing`() {
    var state = reducer.initial(document, selectedNodeId = titleId)
    state = edit(state, "one")
    state = reducer.reduce(state, UiBuilderEditorEvent.ToggleHistoryBar)
    state = reducer.reduce(state, UiBuilderEditorEvent.ShowRevision(0))
    state = reducer.reduce(state, UiBuilderEditorEvent.CompareRevision(state.document.revision))
    assertEquals(0, state.revisionPeek)
    assertNotNull(state.revisionCompare)

    state = reducer.reduce(state, UiBuilderEditorEvent.ToggleHistoryBar)
    assertFalse(state.historyBarVisible)
    assertNull(state.revisionPeek)
    assertNull(state.revisionCompare)
  }

  /** There is no comparison to add an end to until one end has been picked. */
  @Test
  fun `a second end is ignored while nothing is being looked at`() {
    var state = reducer.initial(document, selectedNodeId = titleId)
    state = edit(state, "one")

    state = reducer.reduce(state, UiBuilderEditorEvent.CompareRevision(0))
    assertNull(state.revisionCompare)
  }

  private fun edit(state: UiBuilderEditorState, value: String): UiBuilderEditorState =
    reducer.reduce(state, UiBuilderEditorEvent.CommitProperty(titleId, "text", value))

  private fun UiBuilderDocument.title(nodeId: String): String? =
    nodes[nodeId]?.properties?.get("text")?.displayValue()

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
