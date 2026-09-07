package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The canvas gives the design the frame its environment names, whatever density the host is at.
 *
 * The workspace is measured at the host's density and the design at its own, and the editor's
 * canvas is the one place the two meet: it sizes the frame, and the renderer inside reads that size
 * back as dp. While the canvas used the host's density for it, a design whose environment named a
 * different one was handed a frame off by the ratio — in the browser, where the host is at 1.0, a
 * 240dp watch at 2.0 became 120dp of room. A 216dp Wear widget does not fit in 120dp, so it was
 * clamped to the frame across while staying its authored height down the unbounded extent, and drew
 * as the square with the text column crushed out of it that #521 reports.
 *
 * Measured through [PinnedDesignCanvas] rather than through a copy of its modifier chain: the
 * question is what the editor's canvas hands the design, and a replica answers it about the
 * replica. The width is the assertion that matters — it is the axis the frame bounds, where the
 * height is free to grow into the extent.
 */
@OptIn(ExperimentalComposeUiApi::class)
class CanvasFrameDensityTest {

  /** A watch: the design's density is twice the browser's, which is where the bug lives. */
  @Test
  fun `a widget on a watch frame is drawn at its authored size, not the host's`() {
    val widget = measureWidget(hostDensity = 1f, designDensity = 2.0)

    assertClose(216f, widget.first, "widget width")
    assertClose(124f, widget.second, "widget height")
  }

  /** A phone, whose 2.625 is not a whole multiple of anything the host is likely to be at. */
  @Test
  fun `the frame survives a density that is not a whole multiple of the host's`() {
    val widget = measureWidget(hostDensity = 1f, designDensity = 2.625)

    assertClose(216f, widget.first, "widget width")
  }

  /** The case the harness already drives, pinned so the fix stays a no-op for it. */
  @Test
  fun `a design at the host's own density is unchanged`() {
    val widget = measureWidget(hostDensity = 2f, designDensity = 2.0)

    assertClose(216f, widget.first, "widget width")
    assertClose(124f, widget.second, "widget height")
  }

  /**
   * The Large widget's own box, in the design's dp, as the canvas draws it on a 240dp frame.
   *
   * Read off the inspection snapshot the canvas already publishes — the same boxes the selection
   * outline and the drop hit-test use — and divided back out of root pixels by the scale the frame
   * is drawn at, which is what makes the answer a number the document can be compared against.
   */
  private fun measureWidget(hostDensity: Float, designDensity: Double): Pair<Float, Float> {
    val document =
      wearWidgetUiBuilderDocument(
        designId = "frame-density",
        catalogPin = catalogPin,
        environment = watchEnvironment(designDensity),
        size = WearWidgetScaffoldSize.Large,
      )
    var snapshot: UiBuilderInspectionSnapshot? = null
    renderComposeScene(WORKSPACE_PX, WORKSPACE_PX, Density(hostDensity)) {
      PinnedDesignCanvas(
        document = document,
        selectedNodeId = null,
        onNodeSelected = {},
        onCanvasMetrics = { _, _, _ -> },
        onCanvasBounds = {},
        dropHovered = false,
        showSelectionOverlay = true,
        reference = ReferenceOverlayState(),
        onMarkDrawn = { _, _ -> },
        onPieceMoved = { _, _, _ -> },
        collaborators = emptyList(),
        commentThreads = emptyList(),
        selectedThreadId = null,
        onCommentThreadSelected = {},
        onInspectionSnapshot = { snapshot = it },
        onInspectionInvalidated = null,
        selectionMenu = {},
        hoverEditor = null,
        zoom = 1f,
        onZoomChanged = {},
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
      )
    }
    val bounds =
      checkNotNull(snapshot?.nodes?.firstOrNull { it.bounds != null }?.bounds) {
        "the canvas measured no node"
      }
    // Root pixels, which carry the zoom and the host's density and not the design's: the frame is
    // laid out in the design's pixels and drawn back down by exactly that density again. Pinned at
    // 1:1 above, so the host's density is all there is left to divide out.
    return bounds.width / hostDensity to bounds.height / hostDensity
  }

  private fun assertClose(expected: Float, actual: Float, what: String) =
    assertTrue(abs(expected - actual) <= 1f, "expected $what ${expected}dp, measured ${actual}dp")

  private fun watchEnvironment(density: Double): JsonObject =
    Json.parseToJsonElement(
        """
        {
          "widthDp": 240, "heightDp": 240, "density": $density, "theme": "dark",
          "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr", "animations": "settled"
        }
        """
      )
      .jsonObject

  private val catalogPin: JsonObject =
    Json.parseToJsonElement(
        """
        {
          "systemId": "remote-m3", "catalogRevision": "wear-widget-scaffolds-v1",
          "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"
        }
        """
      )
      .jsonObject

  private companion object {
    /** Wide enough that the 240dp frame is nowhere near the fit or the scroll at 1:1. */
    const val WORKSPACE_PX = 1400
  }
}
