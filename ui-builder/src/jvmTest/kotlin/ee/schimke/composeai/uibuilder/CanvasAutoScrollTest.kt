package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.PinnedDesignCanvas
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.reference.ReferenceOverlayState
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import ee.schimke.composeai.uibuilder.renderer.sdk.right
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * A drag that reaches the edge of the workspace scrolls the design under the pointer.
 *
 * A long design's lower slots are off-screen, and the drag has to be able to reach them without the
 * author letting go, scrolling by hand, and starting again. The scroll is reported upward — the
 * offset is what a hit-test reconciliation will need once the inspection's boxes and the drawn
 * pointer are taught to agree under scroll, which is a separate, pre-existing gap.
 */
@OptIn(ExperimentalTestApi::class)
class CanvasAutoScrollTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document = UiBuilderReducer.replay(FIXTURE.jsonObject).document

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  @Test
  fun `a drag held at the bottom edge scrolls the design upward`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var scroll = Offset.Zero
      var dragPosition by mutableStateOf<Offset?>(null)
      setContent {
        CanvasHost(
          document,
          zoom = 1f,
          dragPosition = dragPosition,
          onScroll = { scroll = it },
        )
      }
      waitForIdle()

      // Held at the bottom edge of a 700px viewport, inside the frame's width.
      mainClock.autoAdvance = false
      dragPosition = Offset(160f, 690f)
      repeat(60) { mainClock.advanceTimeBy(200) }
      dragPosition = null
      mainClock.advanceTimeBy(200)
      waitForIdle()

      assertTrue(scroll.y > 100f, "the content should have scrolled under the pointer ($scroll)")
    }

  @Test
  fun `a drag in the middle of the workspace scrolls nothing`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var scroll = Offset.Zero
      var dragPosition by mutableStateOf<Offset?>(null)
      setContent {
        CanvasHost(
          document,
          zoom = 1f,
          dragPosition = dragPosition,
          onScroll = { scroll = it },
        )
      }
      waitForIdle()

      // Comfortably inside the band-free middle of the workspace.
      mainClock.autoAdvance = false
      dragPosition = Offset(160f, 350f)
      repeat(60) { mainClock.advanceTimeBy(200) }
      dragPosition = null
      mainClock.advanceTimeBy(200)
      waitForIdle()

      assertTrue(scroll == Offset.Zero, "the middle of the workspace does not scroll ($scroll)")
    }

  @Test
  fun `an off-screen node reports its own box, not the viewport's corner`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var scroll = Offset.Zero
      var dragPosition by mutableStateOf<Offset?>(null)
      var snapshot: UiBuilderInspectionSnapshot? = null
      setContent {
        CanvasHost(
          document,
          zoom = 1f,
          dragPosition = dragPosition,
          onScroll = { scroll = it },
          onInspection = { snapshot = it },
        )
      }
      waitForIdle()

      // Scroll to the bottom: the first rows are far above the viewport now.
      mainClock.autoAdvance = false
      dragPosition = Offset(160f, 690f)
      repeat(60) { mainClock.advanceTimeBy(200) }
      dragPosition = null
      mainClock.advanceTimeBy(200)
      waitForIdle()

      val first = snapshot?.nodes?.firstOrNull { it.nodeId == "tall-1" }?.bounds
      // Clipped boxes reported 0x0 at the origin for everything off-screen, which is what made a
      // slot's seam ordering put every scrolled-away child at the top.
      assertTrue(
        first != null && first.width > 0f && first.height > 0f,
        "an off-screen node keeps its own box (tall-1=$first, scroll=$scroll)",
      )
    }

  @Test
  fun `a plan after the scroll names the seam under the drawn pointer`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var scroll = Offset.Zero
      var dragPosition by mutableStateOf<Offset?>(null)
      var snapshot: UiBuilderInspectionSnapshot? = null
      setContent {
        CanvasHost(
          document,
          zoom = 1f,
          dragPosition = dragPosition,
          onScroll = { scroll = it },
          onInspection = { snapshot = it },
        )
      }
      waitForIdle()

      mainClock.autoAdvance = false
      dragPosition = Offset(160f, 690f)
      repeat(60) { mainClock.advanceTimeBy(200) }
      dragPosition = null
      mainClock.advanceTimeBy(200)
      waitForIdle()

      // The pointer is drawn over the deep end of the column: the inspection answers in the same
      // space the pointer arrives in, so the seam is where the eye says it is.
      val state = reducer.initial(document)
      val bounds =
        snapshot
          ?.nodes
          .orEmpty()
          .mapNotNull { node -> node.bounds?.let { node.nodeId to it } }
          .toMap()
      val plan =
        reducer.catalogDropPlan(
          state,
          "m3/text",
          snapshot?.slots.orEmpty(),
          bounds,
          pointX = 30f,
          pointY = 650f,
        )

      assertTrue(
        plan != null && plan.index > 100,
        "the seam should be deep in the column (plan=${plan?.index}, scroll=$scroll)",
      )
    }

  @Test
  fun `a hold after the scroll picks up the node drawn under it`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var scroll = Offset.Zero
      var dragPosition by mutableStateOf<Offset?>(null)
      var snapshot: UiBuilderInspectionSnapshot? = null
      var startedNode: String? = null
      setContent {
        CanvasHost(
          document,
          zoom = 1f,
          dragPosition = dragPosition,
          onScroll = { scroll = it },
          onInspection = { snapshot = it },
          onStarted = { id, _ -> startedNode = id },
        )
      }
      waitForIdle()

      mainClock.autoAdvance = false
      dragPosition = Offset(160f, 690f)
      repeat(60) { mainClock.advanceTimeBy(200) }
      dragPosition = null
      mainClock.advanceTimeBy(200)
      waitForIdle()

      // What the renderer says is drawn at a screen point, pressed at that same point in the
      // root: the gesture's own conversion — frame origin plus the frame-local position — is what
      // turns it into the point the inspection answers in, and that conversion is the thing under
      // test.
      val screenPoint = Offset(30f, 400f)
      val expected =
        snapshot
          ?.nodes
          .orEmpty()
          .mapNotNull { node -> node.bounds?.let { node.nodeId to it } }
          .filter { (_, box) ->
            screenPoint.x >= box.x &&
              screenPoint.x <= box.right &&
              screenPoint.y >= box.y &&
              screenPoint.y <= box.bottom
          }
          .minByOrNull { (_, box) -> box.width * box.height }
          ?.first
      onRoot().performTouchInput {
        down(screenPoint)
        advanceEventTime(1_000)
        moveTo(Offset(screenPoint.x, screenPoint.y + 40f))
        up()
      }
      waitForIdle()

      assertTrue(expected != null, "the fixture drew something at $screenPoint")
      assertEquals(expected, startedNode, "the hold should pick up what is drawn under the press")
    }

  @Composable
  private fun CanvasHost(
    document: UiBuilderDocument,
    zoom: Float,
    dragPosition: Offset?,
    onScroll: (Offset) -> Unit,
    onInspection: (UiBuilderInspectionSnapshot) -> Unit = {},
    onStarted: (String, Offset) -> Unit = { _, _ -> },
    onEnded: (Offset?) -> Unit = {},
  ) {
    PinnedDesignCanvas(
      document = document,
      selectedNodeId = null,
      onNodeSelected = {},
      onCanvasMetrics = { _, _, _ -> },
      onCanvasBounds = {},
      dropHovered = false,
      showSelectionOverlay = true,
      dragPosition = dragPosition,
      onCanvasScroll = onScroll,
      moveDragEnabled = true,
      onNodeDragStarted = onStarted,
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
      selectionMenu = { emptyList() },
      hoverEditor = null,
      zoom = zoom,
      onZoomChanged = {},
    )
  }

  private companion object {
    /**
     * A design whose content is far taller than the 700px viewport: a column of 150 texts, so the
     * extent scrolls and the bottom edge has somewhere to take the drag.
     */
    private val FIXTURE: kotlinx.serialization.json.JsonObject
      get() {
        val rows =
          (1..150).joinToString(",") { index ->
            val after = if (index == 1) "" else "\"afterNodeId\": \"tall-${index - 1}\","
            """
          {
            "operationId": "t$index",
            "type": "insertNode",
            "parent": {"nodeId": "tall-column", "slot": "children"},
            $after
            "node": {
              "id": "tall-$index",
              "componentId": "m3/text",
              "properties": {"text": {"type": "string", "value": "Row $index"}}
            }
          }
          """
          }
        return Json.parseToJsonElement(
            """
          {
            "documentSchema": "compose-ui-builder-document/v1-candidate",
            "designId": "canvas-auto-scroll-fixture",
            "operations": [
              {
                "operationId": "create",
                "type": "createDesign",
                "title": "Auto scroll fixture",
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
                "node": {"id": "tall-scaffold", "componentId": "layout/scaffold"}
              },
              {
                "operationId": "column",
                "type": "insertNode",
                "parent": {"nodeId": "tall-scaffold", "slot": "content"},
                "node": {"id": "tall-column", "componentId": "layout/column"}
              },
              ${rows}
            ]
          }
          """
              .trimIndent()
          )
          .jsonObject
      }
  }
}
