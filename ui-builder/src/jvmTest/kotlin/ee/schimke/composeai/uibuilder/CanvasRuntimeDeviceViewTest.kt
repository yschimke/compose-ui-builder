package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.uibuilder.CanvasDeviceViewTest.Companion.CHIPS
import ee.schimke.composeai.uibuilder.CanvasDeviceViewTest.Companion.FRAME_DP
import ee.schimke.composeai.uibuilder.CanvasDeviceViewTest.Companion.listFixture
import ee.schimke.composeai.uibuilder.CanvasDeviceViewTest.Companion.replay
import ee.schimke.composeai.uibuilder.CanvasDeviceViewTest.Companion.rowFixture
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.editor.EditorCanvasView
import ee.schimke.composeai.uibuilder.editor.ScrollingContainer
import ee.schimke.composeai.uibuilder.editor.UiBuilderCanvasInspection
import ee.schimke.composeai.uibuilder.editor.UiBuilderCanvasRenderer
import ee.schimke.composeai.uibuilder.editor.UiBuilderCanvasSurface
import ee.schimke.composeai.uibuilder.editor.detachedAt
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSurfaceModeV2
import ee.schimke.composeai.uibuilder.renderer.sdk.CATALOG_RUNTIME_CAPABILITY_HORIZONTAL_UNROLL
import ee.schimke.composeai.uibuilder.renderer.sdk.CatalogRuntimeAction
import ee.schimke.composeai.uibuilder.renderer.sdk.REVEAL_NODE_ACTION
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionCollector
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderSemanticActionController
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderSemanticActionResult
import ee.schimke.composeai.uibuilder.renderer.sdk.canvasAdapterRegistry
import ee.schimke.composeai.uibuilder.renderer.sdk.unrolledHorizontally
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The device view where production draws it: through the catalog's pinned runtime.
 *
 * Every hosted catalog whose pin names a delivered runtime is drawn by that runtime, in a sandboxed
 * frame, so a device view that only worked in-process was invisible in production. These drive the
 * editor against a stand-in runtime that reports what it was asked to draw and a measured box for
 * each container, and pin what the editor asks of it: the frame at the device's size and under the
 * editor, the selection to reveal, the wheel over a list handed on, and the pop-out as a second,
 * unrolled surface — sideways only for a runtime that says it can.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class CanvasRuntimeDeviceViewTest {
  private val list = replay(listFixture(rows = 30))
  private val row = replay(rowFixture(chips = CHIPS))

  @Test
  fun `the runtime draws the device view at the frame, under the editor, told the selection`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      val runtime = FakeRuntime()
      setContent {
        DeviceViewCanvasHost(
          list,
          view = EditorCanvasView.Device,
          selectedNodeId = "row-5",
          canvasRenderer = runtime.renderer,
        )
      }
      waitForIdle()

      val frame = assertNotNull(runtime.surfaceFor(list.roots), "the frame was drawn")
      assertEquals(UiBuilderRendererSurfaceModeV2.DEVICE, frame.mode)
      assertEquals(FRAME_DP.toFloat() to FRAME_DP.toFloat(), frame.widthDp to frame.heightDp)
      assertEquals(false, frame.interactive, "a click on the frame selects rather than presses")
      assertEquals("row-5", frame.revealNodeId)
    }

  @Test
  fun `a row selected on the runtime path pops its list out as an unrolled runtime surface`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      val runtime = FakeRuntime()
      setContent {
        DeviceViewCanvasHost(
          list,
          view = EditorCanvasView.Device,
          selectedNodeId = "row-5",
          canvasRenderer = runtime.renderer,
        )
      }
      waitForIdle()

      val popOut = assertNotNull(runtime.surfaceFor(listOf("list")), "the list came out")
      assertEquals(UiBuilderRendererSurfaceModeV2.AUTHORING_UNROLLED, popOut.mode)
      assertEquals(FRAME_DP.toFloat(), popOut.widthDp, 0.5f, "at the width it has in the frame")
    }

  @Test
  fun `a lazy row pops out on the runtime path only when the runtime unrolls sideways`() {
    fun popOut(capabilities: Set<String>): Pair<UiBuilderDocument, UiBuilderCanvasSurface>? {
      var drawn: Pair<UiBuilderDocument, UiBuilderCanvasSurface>? = null
      runDesktopComposeUiTest(width = 900, height = 700) {
        val runtime = FakeRuntime(capabilities)
        setContent {
          DeviceViewCanvasHost(
            row,
            view = EditorCanvasView.Device,
            selectedNodeId = "chip-$CHIPS",
            canvasRenderer = runtime.renderer,
          )
        }
        waitForIdle()
        drawn = runtime.drawn.lastOrNull { it.first.roots == listOf("chips") }
      }
      return drawn
    }

    assertNull(popOut(emptySet()), "a runtime that predates the signal gets no sideways pop-out")
    val sideways =
      assertNotNull(
        popOut(setOf(CATALOG_RUNTIME_CAPABILITY_HORIZONTAL_UNROLL)),
        "a runtime that unrolls sideways does",
      )
    assertTrue(sideways.first.unrolledHorizontally, "and is told to, in the document")
  }

  @Test
  fun `the wheel over a list in the runtime's frame is handed to that list`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      val runtime = FakeRuntime()
      setContent {
        DeviceViewCanvasHost(
          list,
          view = EditorCanvasView.Device,
          canvasRenderer = runtime.renderer,
        )
      }
      waitForIdle()

      onRoot().performMouseInput {
        moveTo(Offset(FRAME_DP / 2f, FRAME_DP / 2f))
        scroll(3f)
      }
      waitForIdle()

      val scroll = assertNotNull(runtime.surfaceFor(list.roots)?.scroll, "the wheel was handed on")
      assertEquals("list", scroll.nodeId)
      assertTrue(scroll.deltaY > 0f, "downward ($scroll)")
    }

  /**
   * The runtime half, end to end through the SDK a runtime is built on: a `revealNode` naming a row
   * no list has composed reaches the list through the controller the host installed, and the row is
   * measured on screen after it. A runtime's own lazy adapters register the same way
   * (`CanvasNodeScope.registerScrolling`); this checkout's in-process lists stand in for them.
   */
  @Test
  fun `a revealNode dispatched to a surface scrolls an uncomposed row into view`() =
    runDesktopComposeUiTest(width = FRAME_DP, height = FRAME_DP) {
      val controller = UiBuilderSemanticActionController()
      var snapshot: UiBuilderInspectionSnapshot? = null
      setContent {
        UiBuilderSurface(
          list,
          runtimeActionController = controller,
          onInspectionSnapshot = { snapshot = it },
        )
      }
      waitForIdle()
      assertNull(
        snapshot?.nodes?.first { it.nodeId == "row-27" }?.bounds,
        "row 27 starts unmeasured",
      )

      val result = runOnIdle {
        controller.dispatch(
          CatalogRuntimeAction(list.id, list.revision, "row-27", REVEAL_NODE_ACTION),
          snapshot,
        )
      }
      waitForIdle()

      assertEquals(UiBuilderSemanticActionResult.Applied, result)
      assertNotNull(snapshot?.nodes?.first { it.nodeId == "row-27" }?.bounds, "row 27 is on screen")
    }

  /**
   * The sideways signal travels in the document, because the renderer protocol's surface modes are
   * a closed set; it has to reach the catalog adapter that draws the lazy row, which is the only
   * code that can lay its items out whole.
   */
  @Test
  fun `a document marked sideways tells the catalog adapter drawing it`() {
    val seen = mutableListOf<Boolean>()
    val registry = canvasAdapterRegistry {
      register("layout/lazy-row") { seen += unrolledHorizontally }
    }
    val sideways = row.detachedAt(ScrollingContainer("chips", horizontal = true))
    renderComposeScene(FRAME_DP, FRAME_DP, Density(1f)) {
      UiBuilderSurface(sideways, canvasAdapterRegistry = registry, unrolled = true)
    }
    renderComposeScene(FRAME_DP, FRAME_DP, Density(1f)) {
      UiBuilderSurface(row, canvasAdapterRegistry = registry, unrolled = true)
    }

    assertEquals(listOf(true, false), seen.distinct())
  }

  /**
   * A runtime stand-in: it records every surface it is asked for, and reports each container's box
   * as the whole of the surface it drew — enough for the editor to size a pop-out by.
   */
  private class FakeRuntime(val capabilities: Set<String> = emptySet()) {
    val drawn = mutableListOf<Pair<UiBuilderDocument, UiBuilderCanvasSurface>>()

    fun surfaceFor(roots: List<String>) = drawn.lastOrNull { it.first.roots == roots }?.second

    val renderer: UiBuilderCanvasRenderer = { document, surface, _, _, _, onInspection ->
      drawn += document to surface
      var reported by mutableStateOf(false)
      Box(
        Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
          if (reported) return@onGloballyPositioned
          reported = true
          val origin = coordinates.positionInRoot()
          val size = coordinates.size
          val editor = UiBuilderInspectionCollector(document)
          val native = UiBuilderInspectionCollector(document)
          document.nodes.values
            .filter { it.componentId.startsWith("layout/lazy") }
            .forEach {
              editor.recordNodeBounds(
                it.id,
                origin.x,
                origin.y,
                origin.x + size.width,
                origin.y + size.height,
              )
              native.recordNodeBounds(it.id, 0f, 0f, size.width.toFloat(), size.height.toFloat())
            }
          onInspection(
            UiBuilderCanvasInspection(native.snapshot(), editor.snapshot(), capabilities)
          )
        }
      )
    }
  }
}
