package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.DRAG_GHOST_CELL_ID
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The ghost a drag carries is the thing itself, not a picture of a picture.
 *
 * The thumbnail's document answers "what is this" at 44 dp inside a fixed frame; the ghost's
 * answers "what will land", which means the component at its own size in the design's own theme, a
 * moved subtree as it is, and an honest null for the components that cannot stand alone.
 */
class DragGhostDocumentTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document
  private val state = reducer.initial(document, selectedNodeId = null)

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  @Test
  fun `a palette ghost carries the component unconstrained, in the design's own theme`() {
    val ghost = assertNotNull(reducer.dragGhostDocument(state, "m3/button"))

    val cell = ghost.nodes.getValue(DRAG_GHOST_CELL_ID)
    // No size modifier: the cell wraps its child, so the ghost is the component's size and the
    // canvas's to cap — not the thumbnail frame's fixed 176x128.
    assertEquals(emptyList(), cell.modifiers)
    assertEquals(listOf(DRAG_GHOST_CELL_ID), ghost.roots)
    val button = cell.slots.getValue("children").singleOrNull()
    assertEquals("m3/button", ghost.nodes.getValue(assertNotNull(button)).componentId)
    // The design's own theme, so a ghost is drawn in the colours it would land in.
    assertEquals(
      "dark",
      (ghost.environment["theme"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
    )
    // The density is deliberately absent: the ghost is drawn into the workspace's density and
    // scaled by the canvas, which is what makes one of its dp land as one of the design's dp.
    assertNull(ghost.environment["density"])
  }

  @Test
  fun `every component the palette offers carries a ghost`() {
    // The same list `CatalogThumbnailTest` keeps empty: a component that stops producing a ghost
    // loses its preview in the air and nothing else complains, so this is the thing that
    // complains. The canvas still has a fallback for a null — a named chip — but it is a guard,
    // not a live case.
    val ghostless =
      catalog.components
        .map { it.componentId }
        .filter { reducer.dragGhostDocument(state, it) == null }

    assertEquals(emptyList(), ghostless.sorted(), "components whose ghost could not be built")
  }

  /**
   * A catalog framed by its own container (A2UI's `a2ui/Column`) must not carry that container in
   * the air: the canvas draws it as a dashed placeholder, and the drop never places it.
   */
  @Test
  fun `an A2UI ghost is rooted at the component, not the column it was validated in`() {
    val a2ui = CapabilityCatalogParser.parse(resource("/a2ui-catalog-capabilities-v1.json"))
    val a2uiReducer = UiBuilderEditorReducer(a2ui)
    val a2uiState = a2uiReducer.initial(document, selectedNodeId = null)
    a2ui.components.forEach { component ->
      val ghost =
        assertNotNull(
          a2uiReducer.dragGhostDocument(a2uiState, component.componentId),
          component.componentId,
        )
      val root = ghost.nodes.getValue(ghost.roots.single())
      assertEquals(component.componentId, root.componentId)
      assertNull(ghost.nodes[DRAG_GHOST_CELL_ID], "${component.componentId} kept its frame cell")
    }
  }

  @Test
  fun `a move ghost is the subtree being carried, rooted at the node`() {
    val ghost = assertNotNull(reducer.nodeGhostDocument(state, "main-episode-copy"))

    assertEquals(listOf("main-episode-copy"), ghost.roots)
    assertTrue("main-episode-title" in ghost.nodes, "the subtree travels with its root")
    assertTrue("root-surface" !in ghost.nodes, "the rest of the design does not")
  }

  @Test
  fun `a ghost names no node the document does not hold`() {
    assertNull(reducer.nodeGhostDocument(state, "not-a-node"))
  }
}
