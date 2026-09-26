package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import ee.schimke.composeai.uibuilder.editor.EditorCanvasView
import ee.schimke.composeai.uibuilder.editor.PinnedDesignCanvas
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.reference.ReferenceOverlayState
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot

/** The editing canvas with nothing wired but what the device-view tests read. */
@Composable
internal fun DeviceViewCanvasHost(
  document: UiBuilderDocument,
  view: EditorCanvasView,
  selectedNodeId: String? = null,
  onSelected: (String) -> Unit = {},
  onInspection: (UiBuilderInspectionSnapshot) -> Unit = {},
  onMetrics: (Int, Int) -> Unit = { _, _ -> },
  onFrame: (Rect) -> Unit = {},
) {
  PinnedDesignCanvas(
    document = document,
    selectedNodeId = selectedNodeId,
    onNodeSelected = onSelected,
    onCanvasMetrics = { width, height, _ -> onMetrics(width, height) },
    onCanvasBounds = onFrame,
    dropHovered = false,
    showSelectionOverlay = true,
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
    // Pinned, so the pop-out opening does not re-fit the frame under the assertions.
    zoom = 1f,
    onZoomChanged = {},
    canvasView = view,
  )
}
