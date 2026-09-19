package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The press that becomes a canvas move, driven through the real gesture on the real canvas.
 *
 * The plan and the land are pinned by [CanvasDropPlanTest] against hand-built geometry; this one
 * asks the two things only a live pointer stream can answer: does a press-and-carry past the slop
 * pick up the node under the press, and does a still press stay a tap that selects. Where the nodes
 * actually drew is read from the inspection the canvas itself published, not assumed.
 */
@OptIn(ExperimentalTestApi::class)
class CanvasMoveDragTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document = UiBuilderReducer.replay(FIXTURE.jsonObject).document

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  @Test
  fun `a held press carried to a seam picks up the node and reports where it went`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var startedNode: String? = null
      var endedAt: Offset? = null
      var snapshot: UiBuilderInspectionSnapshot? = null
      setContent {
        MaterialTheme {
          CanvasHost(
            document,
            zoom = 1f,
            onStarted = { id, _ -> startedNode = id },
            onEnded = { endedAt = it },
            onInspection = { snapshot = it },
          )
        }
      }
      waitForIdle()

      val carried = assertNotNull(nodeBounds(snapshot, "plan-a"), "the renderer measured plan-a")
      val column = assertNotNull(slotBounds(snapshot, "plan-column", "children"))
      val pressAt = Offset(carried.x + carried.width / 2f, carried.y + carried.height / 2f)
      // Out of the box it started in, into the column's own seam below the last child.
      val dropAt = Offset(column.x + column.width / 2f, column.bottom - 5f)

      // The hold arms the drag; the pointer then travels without the slop cancelling it, because
      // once armed the gesture owns the pointer.
      onRoot().performTouchInput {
        down(pressAt)
        advanceEventTime(1_000)
        moveTo(Offset(pressAt.x, pressAt.y + (dropAt.y - pressAt.y) / 2))
        moveTo(dropAt)
        up()
      }
      waitForIdle()

      assertEquals("plan-a", startedNode)
      val end = assertNotNull(endedAt, "the drag landed where the pointer was")
      assertEquals(dropAt.x, end.x, 2f)
      assertEquals(dropAt.y, end.y, 2f)

      // The editor's own release wiring, driven by the position the gesture reported: the text
      // lands in the column, after the child the seam was drawn at.
      val state = reducer.initial(document)
      val plan =
        assertNotNull(
          reducer.canvasMovePlan(
            state,
            assertNotNull(startedNode),
            assertNotNull(snapshot).slots,
            nodeBounds(snapshot),
            end.x,
            end.y,
          )
        )
      val landed =
        reducer.reduce(
          state,
          UiBuilderEditorEvent.MoveNodeInto(
            assertNotNull(startedNode),
            plan.target,
            plan.afterNodeId,
          ),
        )
      val siblings =
        landed.document.nodes.getValue(plan.target.nodeId).slots.getValue(plan.target.slot)
      // The landed order is the order the slot had without the carried node, with it inserted at
      // the seam the plan named — not merely "somewhere in the slot".
      val before = siblings.filterNot { it == "plan-a" }
      assertEquals(
        before.toMutableList().apply { add(plan.index.coerceAtMost(before.size), "plan-a") },
        siblings,
      )
      assertTrue("plan-a" in siblings, "the carried node landed in the slot the plan named")
    }

  @Test
  fun `a still press stays a tap and selects`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var selected: String? = null
      var dragStarted = false
      var snapshot: UiBuilderInspectionSnapshot? = null
      setContent {
        MaterialTheme {
          CanvasHost(
            document,
            zoom = 1f,
            onStarted = { _, _ -> dragStarted = true },
            onSelected = { selected = it },
            onInspection = { snapshot = it },
          )
        }
      }
      waitForIdle()

      val target = assertNotNull(nodeBounds(snapshot, "plan-a"), "the renderer measured plan-a")
      onRoot().performTouchInput {
        down(Offset(target.x + target.width / 2f, target.y + target.height / 2f))
        up()
      }
      waitForIdle()

      assertEquals("plan-a", selected)
      assertTrue(!dragStarted, "a click is not a pick-up")
    }

  /**
   * The friction the slot-first model asks for: a hurried press-and-move is a scroll or a swipe,
   * not a rearrangement. Nothing is picked up and nothing lands.
   */
  @Test
  fun `a quick press and move picks nothing up`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var dragStarted = false
      var dragEnded = false
      var snapshot: UiBuilderInspectionSnapshot? = null
      setContent {
        MaterialTheme {
          CanvasHost(
            document,
            zoom = 1f,
            onStarted = { _, _ -> dragStarted = true },
            onEnded = { dragEnded = true },
            onInspection = { snapshot = it },
          )
        }
      }
      waitForIdle()

      val target = assertNotNull(nodeBounds(snapshot, "plan-a"), "the renderer measured plan-a")
      val pressAt = Offset(target.x + target.width / 2f, target.y + target.height / 2f)
      onRoot().performTouchInput {
        down(pressAt)
        moveTo(Offset(pressAt.x, pressAt.y + 120f))
        up()
      }
      waitForIdle()

      assertTrue(!dragStarted, "a move without the hold is not a pick-up")
      assertTrue(!dragEnded, "nothing was carried, so nothing landed")
    }

  /** The node's measured box, in the frame's own pixels — where the canvas says it drew it. */
  private fun nodeBounds(
    snapshot: UiBuilderInspectionSnapshot?,
    nodeId: String,
  ): UiBuilderPixelBounds? = snapshot?.nodes?.firstOrNull { it.nodeId == nodeId }?.bounds

  private fun nodeBounds(
    snapshot: UiBuilderInspectionSnapshot?
  ): Map<String, UiBuilderPixelBounds> =
    snapshot?.nodes.orEmpty().mapNotNull { node -> node.bounds?.let { node.nodeId to it } }.toMap()

  private fun slotBounds(
    snapshot: UiBuilderInspectionSnapshot?,
    parentNodeId: String,
    slotName: String,
  ): UiBuilderPixelBounds? =
    snapshot
      ?.slots
      ?.firstOrNull { it.parentNodeId == parentNodeId && it.slotName == slotName }
      ?.bounds

  /** The canvas at the fixture's own scale: zoom pinned, so design px are window px. */
  @Composable
  private fun CanvasHost(
    document: UiBuilderDocument,
    zoom: Float,
    onStarted: (String, Offset) -> Unit = { _, _ -> },
    onDragged: (Offset) -> Unit = {},
    onEnded: (Offset?) -> Unit = {},
    onSelected: (String) -> Unit = {},
    onInspection: (UiBuilderInspectionSnapshot) -> Unit = {},
  ) {
    PinnedDesignCanvas(
      document = document,
      selectedNodeId = null,
      onNodeSelected = onSelected,
      onCanvasMetrics = { _, _, _ -> },
      onCanvasBounds = {},
      dropHovered = false,
      showSelectionOverlay = true,
      moveDragEnabled = true,
      onNodeDragStarted = onStarted,
      onNodeDragged = onDragged,
      onNodeDragEnded = onEnded,
      reference = ReferenceOverlayState(mintedIds = 0),
      onMarkDrawn = { _, _ -> },
      onPieceMoved = { _, _, _ -> },
      collaborators = emptyList(),
      commentThreads = emptyList(),
      selectedThreadId = null,
      onCommentThreadSelected = {},
      onInspectionSnapshot = onInspection,
      onInspectionInvalidated = null,
      selectionMenu = {},
      hoverEditor = null,
      zoom = zoom,
      onZoomChanged = {},
    )
  }

  private companion object {
    private val FIXTURE =
      Json.parseToJsonElement(
          """
          {
            "documentSchema": "compose-ui-builder-document/v1-candidate",
            "designId": "canvas-move-drag-fixture",
            "operations": [
              {
                "operationId": "create",
                "type": "createDesign",
                "title": "Move drag fixture",
                "catalogPin": {
                  "systemId": "m3-catalog",
                  "catalogRevision": "candidate",
                  "capabilityDigest": "candidate",
                  "nativeRuntimeId": "candidate"
                },
                "environment": {
                  "widthDp": 320, "heightDp": 320, "density": 1.0, "theme": "dark",
                  "dynamicColor": false, "locale": "en-US", "fontScale": 1.0,
                  "layoutDirection": "ltr", "windowPosture": "flat",
                  "browserZoomPercent": 100, "fixedTime": "2024-05-16T12:00:00Z",
                  "animations": "settled", "networkAccess": false
                },
                "stateVariables": {}
              },
              {
                "operationId": "scaffold",
                "type": "insertNode",
                "parent": null,
                "node": {"id": "plan-scaffold", "componentId": "layout/scaffold"}
              },
              {
                "operationId": "column",
                "type": "insertNode",
                "parent": {"nodeId": "plan-scaffold", "slot": "content"},
                "node": {"id": "plan-column", "componentId": "layout/column"}
              },
              {
                "operationId": "a",
                "type": "insertNode",
                "parent": {"nodeId": "plan-column", "slot": "children"},
                "node": {
                  "id": "plan-a",
                  "componentId": "m3/text",
                  "properties": {"text": {"type": "string", "value": "A"}}
                }
              },
              {
                "operationId": "box",
                "type": "insertNode",
                "parent": {"nodeId": "plan-column", "slot": "children"},
                "afterNodeId": "plan-a",
                "node": {"id": "plan-box", "componentId": "layout/box"}
              },
              {
                "operationId": "n1",
                "type": "insertNode",
                "parent": {"nodeId": "plan-box", "slot": "children"},
                "node": {
                  "id": "plan-n1",
                  "componentId": "m3/text",
                  "properties": {"text": {"type": "string", "value": "N1"}}
                }
              },
              {
                "operationId": "c",
                "type": "insertNode",
                "parent": {"nodeId": "plan-column", "slot": "children"},
                "afterNodeId": "plan-box",
                "node": {
                  "id": "plan-c",
                  "componentId": "m3/text",
                  "properties": {"text": {"type": "string", "value": "C"}}
                }
              }
            ]
          }
          """
            .trimIndent()
        )
        .jsonObject
  }
}
