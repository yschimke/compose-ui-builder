package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.uibuilder.canvas.LocalWearWidgetHostShape
import ee.schimke.composeai.uibuilder.editor.PinnedDesignCanvas
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import ee.schimke.composeai.uibuilder.export.wearWidgetUiBuilderDocument
import ee.schimke.composeai.uibuilder.reference.ReferenceOverlayState
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The canvas gives the design the frame its environment names, whatever density the host is at. A
 * design at 2.0 in a 1.0 host must get its full dp frame, not one shrunk by the density ratio (see
 * #521).
 *
 * The widget is measured in the **squircle**, stated explicitly since the host frame is a view
 * choice (`WearWidgetHostShape`), not a document property. Measured through [PinnedDesignCanvas]
 * itself; width is the bounded axis that matters.
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
   * The Large widget's own box, in the design's dp, as the canvas draws it on a 240dp frame, read
   * from the inspection snapshot and divided back out of root pixels by the frame's scale.
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
      CompositionLocalProvider(LocalWearWidgetHostShape provides WearWidgetHostShape.Squircle) {
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
          selectionMenu = { emptyList() },
          hoverEditor = null,
          zoom = 1f,
          onZoomChanged = {},
          contentAlignment = Alignment.Center,
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
    val bounds =
      checkNotNull(snapshot?.nodes?.firstOrNull { it.bounds != null }?.bounds) {
        "the canvas measured no node"
      }
    // Root pixels carry zoom and host density; zoom is pinned at 1:1, so dividing by the host
    // density leaves design dp.
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
