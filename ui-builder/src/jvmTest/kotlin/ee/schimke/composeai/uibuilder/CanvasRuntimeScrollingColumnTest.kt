package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.CanvasDeviceViewTest.Companion.FRAME_DP
import ee.schimke.composeai.uibuilder.CanvasDeviceViewTest.Companion.children
import ee.schimke.composeai.uibuilder.CanvasDeviceViewTest.Companion.fixture
import ee.schimke.composeai.uibuilder.CanvasDeviceViewTest.Companion.replay
import ee.schimke.composeai.uibuilder.editor.EditorCanvasView
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSurfaceModeV2
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The editor's two views of a design drawn by a catalog runtime, for the plainest scroller there
 * is: a foundation `Column` with `verticalScroll`, several frames tall.
 *
 * The runtime here is [InProcessCatalogRuntime] — the SDK's real document host inside a root sized
 * to the surface it is handed — so these fail the way the hosted editor does when a runtime lays
 * its content out inside the frame it was given rather than the content's own extent, which is what
 * a Wear screen did: the extent never grew past one screenful, and switching the device view off
 * looked like nothing happened.
 */
@OptIn(ExperimentalTestApi::class)
class CanvasRuntimeScrollingColumnTest {
  private val column = replay(scrollingColumnFixture(ROWS))

  @Test
  fun `the device view switch moves between the whole column and the frame`() =
    runDesktopComposeUiTest(width = 900, height = 1400) {
      val runtime = InProcessCatalogRuntime()
      var view by mutableStateOf(EditorCanvasView.Extent)
      // The drawn frame, in dp at this zoom and density. Read from the frame's own positioning
      // rather than the metrics callback, which the editor reports from an effect this test's
      // clock does not run until it ends.
      var frame = Rect.Zero
      setContent {
        DeviceViewCanvasHost(
          column,
          view = view,
          onFrame = { frame = it },
          canvasRenderer = runtime.renderer,
        )
      }
      awaitFrames("the extent grows past the frame") { frame.height > FRAME_DP }
      val extent = frame.height
      val extentSurface = assertNotNull(runtime.surfaceFor(column.roots))

      view = EditorCanvasView.Device
      awaitFrames("the device view is the frame") { frame.height == FRAME_DP.toFloat() }
      val deviceSurface = assertNotNull(runtime.surfaceFor(column.roots))

      view = EditorCanvasView.Extent
      awaitFrames("the extent unrolls again") { frame.height > FRAME_DP }
      val extentAgain = frame.height

      assertEquals(UiBuilderRendererSurfaceModeV2.AUTHORING_UNROLLED, extentSurface.mode)
      assertEquals(UiBuilderRendererSurfaceModeV2.DEVICE, deviceSurface.mode)
      assertEquals(FRAME_DP.toFloat(), deviceSurface.heightDp, "the device view draws the frame")
      assertTrue(
        extent > FRAME_DP * 1.5,
        "the extent holds every row rather than one frame's worth ($extent)",
      )
      assertEquals(extent, extentAgain, 0.5f, "switching back unrolls the column again")
    }

  @Test
  fun `the extent draws the last row below the frame`() =
    runDesktopComposeUiTest(width = 900, height = 1400) {
      val runtime = InProcessCatalogRuntime()
      setContent {
        DeviceViewCanvasHost(
          column,
          view = EditorCanvasView.Extent,
          canvasRenderer = runtime.renderer,
        )
      }
      awaitFrames("the last row is measured below the frame") {
        (bounds(runtime.inspectionFor(column.roots), "row-$ROWS")?.bottom ?: 0f) > FRAME_DP
      }

      val last = assertNotNull(bounds(runtime.inspectionFor(column.roots), "row-$ROWS"))
      val first = assertNotNull(bounds(runtime.inspectionFor(column.roots), "row-1"))
      assertTrue(last.height > 0f, "the last row was squeezed to nothing ($last)")
      assertTrue(last.bottom > FRAME_DP, "the last row is drawn past the frame ($last)")
      assertEquals(first.height, last.height, 0.5f, "every row is drawn at its own height")
    }

  /** Selected from the layers panel: the column itself, not a row in it. */
  @Test
  fun `selecting the scrolling column in the device view pops it out whole`() =
    runDesktopComposeUiTest(width = 1400, height = 1400) {
      val runtime = InProcessCatalogRuntime()
      setContent {
        DeviceViewCanvasHost(
          column,
          view = EditorCanvasView.Device,
          selectedNodeId = "column",
          canvasRenderer = runtime.renderer,
        )
      }
      awaitFrames("the pop-out draws the last row") {
        bounds(runtime.inspectionFor(listOf("column")), "row-$ROWS") != null
      }

      assertPoppedOutWhole(runtime)
    }

  @Test
  fun `selecting a row in the device view pops its column out whole`() =
    runDesktopComposeUiTest(width = 1400, height = 1400) {
      val runtime = InProcessCatalogRuntime()
      setContent {
        DeviceViewCanvasHost(
          column,
          view = EditorCanvasView.Device,
          selectedNodeId = "row-3",
          canvasRenderer = runtime.renderer,
        )
      }
      awaitFrames("the pop-out draws the last row") {
        bounds(runtime.inspectionFor(listOf("column")), "row-$ROWS") != null
      }

      assertPoppedOutWhole(runtime)
    }

  private fun assertPoppedOutWhole(runtime: InProcessCatalogRuntime) {
    val popOut = assertNotNull(runtime.surfaceFor(listOf("column")), "the column came out")
    assertEquals(UiBuilderRendererSurfaceModeV2.AUTHORING_UNROLLED, popOut.mode)
    assertEquals(FRAME_DP.toFloat(), popOut.widthDp, 0.5f, "at the width it has in the frame")
    val inspection = runtime.inspectionFor(listOf("column"))
    (1..ROWS).forEach { row ->
      val box = assertNotNull(bounds(inspection, "row-$row"), "row $row is in the pop-out")
      assertTrue(box.height > 0f, "row $row is drawn in the pop-out ($box)")
    }
    val last = assertNotNull(bounds(inspection, "row-$ROWS"))
    assertTrue(
      popOut.heightDp >= last.bottom - 0.5f,
      "the pop-out settles tall enough for its last row (${popOut.heightDp} vs $last)",
    )
  }

  /**
   * Steps frames until [condition] holds. The editor learns its size in a layout callback and
   * reports it from an effect, and the runtime's surface grows a frame after that, so settling
   * takes several frames that an idle test clock does not produce on its own.
   */
  private fun DesktopComposeUiTest.awaitFrames(
    description: String,
    condition: () -> Boolean,
  ) {
    repeat(MAX_FRAMES) {
      waitForIdle()
      if (condition()) return
      mainClock.advanceTimeByFrame()
    }
    waitForIdle()
    assertTrue(condition(), "$description, within $MAX_FRAMES frames")
  }

  private fun bounds(snapshot: UiBuilderInspectionSnapshot?, nodeId: String) =
    snapshot?.nodes?.firstOrNull { it.nodeId == nodeId }?.bounds

  private companion object {
    const val ROWS = 40
    const val MAX_FRAMES = 60

    /** The frame filled by one column that scrolls, holding [rows] texts. */
    fun scrollingColumnFixture(rows: Int): String =
      fixture(
        "canvas-runtime-scrolling-column",
        """
        {"operationId": "column", "type": "insertNode", "parent": null,
         "node": {"id": "column", "componentId": "layout/column",
                  "modifiers": [{"type": "fillMaxSize"}, {"type": "verticalScroll"}]}},
        ${children("column", "children", "row", "Row", rows)}
        """,
      )
  }
}
