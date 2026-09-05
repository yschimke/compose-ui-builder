package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The layers panel showed a tree with the slots taken out of it, and could only move a node one
 * step among the children it already sat with.
 *
 * Those two are one bug. A screen whose Scaffold holds an app bar in `topBar` and a Box in
 * `content` draws them as two rows at the same indent — they read as siblings — and every drag
 * between them was a no-op, because they are the only child of their own slot and there is no step
 * to take. Nothing said so, so the panel looked broken rather than strict.
 */
class EditorLayerDragTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document
  private val state = reducer.initial(document, selectedNodeId = "root-surface")

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private fun slotsOf(rows: List<EditorLayerRow>, nodeId: String) =
    rows.filterIsInstance<EditorLayerRow.Slot>().filter { it.parent.nodeId == nodeId }

  private fun nodeRow(rows: List<EditorLayerRow>, nodeId: String) =
    rows.filterIsInstance<EditorLayerRow.Node>().single { it.nodeId == nodeId }

  @Test
  fun `a multi-slot parent names each of its slots, in the catalog's order`() {
    val rows = reducer.layerRows(state)

    assertEquals(
      listOf("topBar", "snackbarHost", "content"),
      slotsOf(rows, "main-scaffold").map { it.parent.slot },
    )
    // The children keep the depth they had — a tree that indented twice per level runs out of a
    // 300dp panel by the fourth container — and each group now sits under the line naming its
    // slot, which is the difference between "siblings" and "the only child of two different slots"
    // that the panel could not draw before.
    assertEquals(nodeRow(rows, "main-scaffold").indent + 1, nodeRow(rows, "search-bar").indent)
    assertEquals(nodeRow(rows, "search-bar").indent, nodeRow(rows, "main-content").indent)
    // Each slot line comes immediately before the children it heads, subtrees and all.
    assertEquals(
      listOf("topBar", "search-bar", "snackbarHost", "snackbar-host", "content", "main-content"),
      rows
        .filter {
          when (it) {
            is EditorLayerRow.Slot -> it.parent.nodeId == "main-scaffold"
            is EditorLayerRow.Node -> it.row.parent?.nodeId == "main-scaffold"
          }
        }
        .map {
          when (it) {
            is EditorLayerRow.Slot -> it.parent.slot
            is EditorLayerRow.Node -> it.nodeId
          }
        },
    )
  }

  @Test
  fun `a slot line carries what is in it and what it will take`() {
    val rows = reducer.layerRows(state)
    val topBar = slotsOf(rows, "main-scaffold").single { it.parent.slot == "topBar" }

    assertEquals(1, topBar.childCount)
    assertEquals(1, topBar.maxChildren)
    assertTrue(topBar.full, "topBar holds one of one and did not say it was full")
  }

  @Test
  fun `one slot with something in it draws no line of its own`() {
    // Indentation already says everything the slot name would, and a line per Column would double
    // the length of the panel for no information at all.
    val rows = reducer.layerRows(state)

    assertEquals(emptyList(), slotsOf(rows, "main-episode-copy"))
    assertEquals(
      nodeRow(rows, "main-episode-copy").indent + 1,
      nodeRow(rows, "main-episode-title").indent,
    )
  }

  @Test
  fun `an empty slot draws a line, because a drop has nothing else to aim at`() {
    val emptied =
      document.copy(
        nodes =
          document.nodes +
            ("main-episode-copy" to
              document.nodes.getValue("main-episode-copy").copy(slots = emptyMap()))
      )
    val rows = reducer.layerRows(reducer.initial(emptied, selectedNodeId = "root-surface"))

    val slot = slotsOf(rows, "main-episode-copy").single()
    assertEquals("children", slot.parent.slot)
    assertEquals(0, slot.childCount)
  }

  @Test
  fun `a node that is the only child of its slot has no step to take, and can still be moved`() {
    // The regression this fixes: `snackbar-host` is the whole of `main-scaffold.snackbarHost`, so
    // the in-slot step the panel used to send is null in both directions and the drag did nothing,
    // however far it was dragged and whatever it was dropped on.
    assertNull(reducer.moveTarget(state, "snackbar-host", EditorMoveDirection.Before))
    assertNull(reducer.moveTarget(state, "snackbar-host", EditorMoveDirection.After))

    val target = ParentSlot("detail-scaffold", "snackbarHost")
    assertNull(reducer.moveRefusal(state, "snackbar-host", target))

    val moved = reducer.reduce(state, UiBuilderEditorEvent.MoveNodeInto("snackbar-host", target))

    assertIs<CommandOutcome.Accepted>(moved.lastOutcome)
    assertEquals(
      listOf("snackbar-host"),
      moved.document.nodes.getValue("detail-scaffold").slots.getValue("snackbarHost"),
    )
    assertEquals(
      emptyList(),
      moved.document.nodes.getValue("main-scaffold").slots["snackbarHost"].orEmpty(),
    )
  }

  @Test
  fun `a slot the document requires cannot be emptied by a drag`() {
    // A Scaffold holds exactly one `content`, and the document validator refuses a document
    // without it. The panel asks the same question while the row is in the air, so the drop reads
    // as refused before the release rather than after it.
    val refusal =
      assertNotNull(
        reducer.moveRefusal(state, "main-content", ParentSlot("main-background", "children"))
      )

    assertEquals(RejectionCode.INVALID_LOCATION, refusal.code)
    assertTrue("main-scaffold.content" in refusal.message, refusal.message)
  }

  @Test
  fun `a null afterNodeId lands first in the slot`() {
    val moved =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.MoveNodeInto(
          "main-episode-title",
          ParentSlot("main-episode-footer", "children"),
        ),
      )

    assertIs<CommandOutcome.Accepted>(moved.lastOutcome)
    assertEquals(
      "main-episode-title",
      moved.document.nodes.getValue("main-episode-footer").slots.getValue("children").first(),
    )
  }

  @Test
  fun `a slot that will not have the node says why, rather than swallowing the drag`() {
    val target = ParentSlot("main-scaffold", "topBar")
    val refusal = assertNotNull(reducer.moveRefusal(state, "main-episode-column", target))

    assertEquals(RejectionCode.INVALID_LOCATION, refusal.code)
    val moved =
      reducer.reduce(state, UiBuilderEditorEvent.MoveNodeInto("main-episode-column", target))
    val rejected = assertIs<CommandOutcome.Rejected>(moved.lastOutcome)
    assertEquals(refusal.message, rejected.message)
    assertEquals(document, moved.document)
  }

  @Test
  fun `a node cannot be dropped into its own subtree`() {
    val target = ParentSlot("main-episode-copy", "children")
    val refusal = assertNotNull(reducer.moveRefusal(state, "main-episode-column", target))

    assertEquals(RejectionCode.CYCLE, refusal.code)
    val moved =
      reducer.reduce(state, UiBuilderEditorEvent.MoveNodeInto("main-episode-column", target))
    assertIs<CommandOutcome.Rejected>(moved.lastOutcome)
  }

  @Test
  fun `a full slot counts the node itself out, so reordering inside it stays legal`() {
    // `detail-list.items` is unbounded; `main-scaffold.content` holds one of one. Moving the child
    // it already holds back into it is not "the slot is full".
    assertNull(reducer.moveRefusal(state, "main-content", ParentSlot("main-scaffold", "content")))
    assertNotNull(reducer.moveRefusal(state, "detail-list", ParentSlot("main-scaffold", "content")))
  }

  @Test
  fun `a drop that changes nothing costs nothing`() {
    // A row picked up and released where it was must not spend an operation, an undo step and a
    // revision every collaborator has to take.
    val settled =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.MoveNodeInto(
          "main-episode-podcast",
          ParentSlot("main-episode-copy", "children"),
          afterNodeId = "main-episode-title",
        ),
      )

    assertSame(state, settled)
  }
}
