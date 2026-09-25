@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.LocalUiBuilderAssetBitmaps
import ee.schimke.composeai.uibuilder.LocalUiBuilderAssetBytes
import ee.schimke.composeai.uibuilder.canvas.CanvasExtentLayout
import ee.schimke.composeai.uibuilder.canvas.DeviceSceneHost
import ee.schimke.composeai.uibuilder.canvas.LocalRemoteComposeDocuments
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapterMappings
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapters
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogComponentIds
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderFrameGeometry
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderNativeOnly
import ee.schimke.composeai.uibuilder.canvas.LocalWearWidgetHostShape
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.canvas.renderDensity
import ee.schimke.composeai.uibuilder.canvasAdapterMappings
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.frameGeometry
import ee.schimke.composeai.uibuilder.inspector.CommentPinOverlay
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSurfaceModeV2
import ee.schimke.composeai.uibuilder.reference.ReferenceMarkupKind
import ee.schimke.composeai.uibuilder.reference.ReferenceOverlayCanvas
import ee.schimke.composeai.uibuilder.reference.ReferenceOverlayState
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionCollector
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderPixelBounds
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import ee.schimke.composeai.uibuilder.renderer.sdk.right
import kotlin.math.roundToInt
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The design pinned in the workspace: framed or zoomed, scrolled, and hit-tested.
 *
 * Internal rather than private so a test can measure what the frame hands the design. How big that
 * frame is depends on the density the design is drawn at, which is a fact about a *rendered*
 * composition and not one any amount of reading the arithmetic below settles.
 */
@Composable
internal fun PinnedDesignCanvas(
  document: UiBuilderDocument,
  selectedNodeId: String?,
  onNodeSelected: (String) -> Unit,
  onCanvasMetrics: (Int, Int, Float) -> Unit,
  onCanvasBounds: (Rect) -> Unit,
  dropHovered: Boolean,
  /**
   * Where the drag hovering over the canvas would land, or null while no legal slot is under the
   * pointer. Both a palette drag and a canvas move resolve one, and the marker is drawn from it.
   */
  dropPlan: UiBuilderDropPlan? = null,
  /**
   * The empty recommended slots, drawn as dashed "a component goes here" regions until they are
   * populated — see [UiBuilderEditorReducer.slotPlaceholders].
   */
  slotPlaceholders: List<UiBuilderSlotPlaceholder> = emptyList(),
  /**
   * The dragged component carried beside the pointer, at the size it would land — a ghost of the
   * component itself, not of the thumbnail frame it was pictured in.
   */
  dragPreview: UiBuilderDocument? = null,
  /** The published Remote Compose capture carried while its document bytes are still remote. */
  dragPreviewBitmap: ImageBitmap? = null,
  /** The subtree a canvas move is carrying, at the size it would land. */
  moveDragPreview: UiBuilderDocument? = null,
  /** What the ghost names when no picture of the dragged component can be drawn. */
  dragGhostLabel: String? = null,
  /** Pointer position in the editor root coordinate space. */
  dragPosition: Offset? = null,
  /**
   * Where the node a canvas move picked up still sits, in root pixels — drawn as a dashed outline
   * so the original and the ghost are two things the eye can tell apart.
   */
  moveOrigin: UiBuilderPixelBounds? = null,
  /** The canvas pane's own box — the workspace a beside-drop's empty ground is measured against. */
  onWorkspaceBounds: (Rect) -> Unit = {},
  /**
   * The workspace's current scroll offset, reported so the drag hit-test can read the pointer in
   * the same (unshifted) layout space the inspection's node boxes answer in.
   */
  onCanvasScroll: (Offset) -> Unit = {},
  showSelectionOverlay: Boolean,
  /**
   * Whether the canvas move gesture is on. The editor wires the handlers below; a canvas composed
   * without them — the tests, the read-only panes — must not let a press-and-hold eat a scroll.
   */
  moveDragEnabled: Boolean = false,
  /** A press-and-hold that becomes a drag of the node under the pointer. */
  onNodeDragStarted: (String, Offset) -> Unit = { _, _ -> },
  /** Positions while a canvas move is in flight, in the editor root coordinate space. */
  onNodeDragged: (Offset) -> Unit = {},
  /** The pointer position a move lands at, or null when the drag was cancelled. */
  onNodeDragEnded: (Offset?) -> Unit = {},
  reference: ReferenceOverlayState,
  onMarkDrawn: (ReferenceMarkupKind, List<Float>) -> Unit,
  onPieceMoved: (String, Float, Float) -> Unit,
  collaborators: List<UiBuilderCollaborator>,
  /** Threads with somewhere to sit on the frame; see [DesignCommentBoard.pinned]. */
  commentThreads: List<DesignCommentThread>,
  selectedThreadId: String?,
  onCommentThreadSelected: (String) -> Unit,
  onInspectionSnapshot: ((UiBuilderInspectionSnapshot) -> Unit)?,
  onInspectionInvalidated: ((UiBuilderInspectionCollector) -> Unit)?,
  canvasRenderer: UiBuilderCanvasRenderer? = null,
  /** The verbs a layer answers to, for the canvas's own context menu. */
  selectionMenu: (() -> Unit) -> List<UiBuilderMenuEntry>,
  /**
   * The tight editor that follows the selection over the design, or null where there is nothing to
   * follow. Positioned here, because only the canvas knows where the selected node is drawn.
   */
  hoverEditor: (@Composable () -> Unit)?,
  /**
   * How the selected node is sized, for its resize handles, or null where it gets none — see
   * [UiBuilderEditorReducer.sizing].
   */
  sizing: EditorNodeSizing? = null,
  /** A handle released or double-clicked: the node and the new sizing of each axis it touched. */
  onResize: (String, EditorSizing?, EditorSizing?) -> Unit = { _, _, _ -> },
  /** The scale the design is drawn at, or null to frame it in whatever room the workspace has. */
  zoom: Float?,
  onZoomChanged: (Float?) -> Unit,
  /**
   * Whether the frame companion is drawn beside the extent when the content outgrows the frame.
   *
   * False while the preview or native pane is open, because both of those draw the design at its
   * frame — see the call site. The companion exists to answer "what does someone see on the
   * device?" for a design being edited at its whole extent, and it is the wrong place to answer it
   * twice.
   */
  frameCompanion: Boolean = true,
  contentAlignment: Alignment = Alignment.TopStart,
  modifier: Modifier = Modifier,
) {
  val (sourceWidth, sourceHeight) = document.canvasFrameDp(LocalWearWidgetHostShape.current)
  val density = LocalDensity.current
  // What one of the design's pixels is worth in the workspace's.
  //
  // The workspace is measured at the host's density — the browser's `devicePixelRatio`, which is
  // usually 1 — while the design inside the frame is measured at the one its environment names: 2.0
  // for a watch, 2.625 for a phone. So `240.dp` written here and `240.dp` written inside the design
  // are not the same width, and sizing the frame with the workspace's dp handed a 240dp watch 240
  // of the *workspace's* pixels, which the design then read as 120dp. Everything authored wider
  // than that was clamped to it: a 216x124dp Wear widget came out 120dp wide against an unclamped
  // 124dp tall, which is the square frame with the text column crushed out of it in #521.
  //
  // So the frame is sized in the design's pixels — every dp below multiplied through this — and
  // drawn back down to the workspace by [drawScale], which leaves what is on screen exactly where
  // the zoom says. It is 1 wherever the two densities agree, which is why the 1280x800-at-1.0
  // fixture the harness drives never showed any of this.
  val densityRatio = document.renderDensity(density).density / density.density
  var inspection by
    remember(document.id, document.revision) { mutableStateOf<UiBuilderInspectionSnapshot?>(null) }
  BoxWithConstraints(modifier.clipToBounds(), contentAlignment = contentAlignment) {
    // What "fit" means: the largest scale at which the whole frame is on screen. It is no longer
    // capped at 1:1, which is the whole of "autozoom": a 411 x 891 dp phone opened in a desktop
    // workspace was drawn as a stamp in the middle of an empty page, and a 1280 x 800 dp design in
    // a narrow window was drawn shrunk with room to spare beside it. Neither is the design framed.
    // Named, because the scrolling box below is a different receiver and cannot see the
    // constraints scope these come from.
    val workspaceWidth = maxWidth
    val workspaceHeight = maxHeight
    // How tall the design actually is, which is not how tall its frame is.
    //
    // A screen is drawn at one frame and is usually longer than it: a list of twelve rows on a
    // 914dp phone is a design whose author is working on rows nine to twelve as much as on the
    // first four. So the canvas draws the *extent* — the frame's width, the content's height — and
    // that is the surface edits land on, the way the Wear stadium already works. Until the content
    // has been measured this is the frame's own height, which is what a design that fits stays at.
    var expandedHeightDp by remember(document.id) { mutableStateOf(sourceHeight) }
    LaunchedEffect(document.revision, canvasRenderer) {
      if (canvasRenderer != null) expandedHeightDp = sourceHeight
    }
    // Only a design that outgrows its frame gets the second pane. One that fits would be drawn
    // twice identically, and two identical pictures side by side say nothing the one said.
    val overflowsFrame = expandedHeightDp > sourceHeight + 0.5f
    // The frame, plus the extent companion when the content outgrows it. Fit frames what is
    // actually drawn rather than the frame alone: zooming to fit a design whose companion is off
    // the right edge is not fitting the design.
    val pairWidth =
      sourceWidth + (if (overflowsFrame) sourceWidth + CANVAS_PANE_GAP_DP.value else 0f)
    val fitScale =
      minOf(workspaceWidth.value / pairWidth, workspaceHeight.value / expandedHeightDp)
        .coerceIn(MIN_CANVAS_ZOOM, MAX_CANVAS_ZOOM)
    val scale = zoom ?: fitScale
    // The frame is laid out in the design's pixels, so it is drawn back down by the same ratio it
    // was sized up by. Equal to [scale] whenever the design's density is the host's, which is what
    // keeps the zoom readout and the fit above honest: the frame still covers `sourceWidth * scale`
    // of the workspace's dp.
    val drawScale = scale / densityRatio
    // In dp, because that is what the metrics callback reports and what the frame is measured in.
    var measuredDp by remember(document.id) { mutableStateOf(0 to 0) }
    // Reported on every change of either, not just on a resize: the frame's own size does not move
    // when somebody zooms, and the drop hit-test reads this scale.
    LaunchedEffect(measuredDp, scale) {
      if (measuredDp != 0 to 0) onCanvasMetrics(measuredDp.first, measuredDp.second, scale)
    }
    // The frame's own rectangle in the window, kept because the inspection answers in that space
    // and a press on the canvas arrives in the frame's.
    var frameBounds by remember(document.id) { mutableStateOf(Rect.Zero) }
    // Where the frame's own top-left is in root space, *unclipped*. `frameBounds` above is the
    // frame's visible box — `boundsInRoot` is clipped to the viewport — which is what "is the
    // pointer over the design" means, but not what a frame-local point has to be added to: once
    // the canvas is scrolled, the frame's origin is above the viewport and its visible top is the
    // viewport's. `positionInRoot` is the placement without that clipping, and it is current after
    // a scroll, which is what every conversion below needs.
    var frameOrigin by remember(document.id) { mutableStateOf(Offset.Zero) }
    // The workspace's own rectangle, so a node's root-space box can be turned into an offset in
    // this box — which is where the hover editor is placed.
    var workspaceBounds by remember(document.id) { mutableStateOf(Rect.Zero) }
    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberScrollState()
    // While a drag is in the air, the pointer near an edge scrolls the workspace under it.
    //
    // A long design's lower slots are off-screen, and without this the only way to reach them was
    // to let go, scroll, and start the drag again — which the seam marker made more painful, not
    // less, since the plan it promised is lost on the way. The effect restarts on every pointer
    // move (so a moving drag re-arms continuously) and runs one frame-step at a time until the
    // pointer is out of the band or the edge has nothing left to give, which is also what keeps
    // the test clock idle once the content is exhausted.
    val currentDragPosition = rememberUpdatedState(dragPosition)
    val currentWorkspaceBounds = rememberUpdatedState(workspaceBounds)
    // The scroll offset, reported upward: the inspection's node boxes are in the content's own
    // (unshifted) layout space, while a pointer arrives in the drawn space the scroll shifted, and
    // the resolver that answers "what is under the pointer" needs both in one space. Scrolling
    // does not re-fire position callbacks — it translates a layer — so this is the one place the
    // two spaces can be reconciled from.
    LaunchedEffect(horizontalScrollState.value, verticalScrollState.value) {
      onCanvasScroll(
        Offset(horizontalScrollState.value.toFloat(), verticalScrollState.value.toFloat())
      )
    }
    LaunchedEffect(dragPosition) {
      if (dragPosition == null) return@LaunchedEffect
      val band = with(density) { DRAG_AUTO_SCROLL_BAND_DP.dp.toPx() }
      val speed = with(density) { DRAG_AUTO_SCROLL_SPEED_DP.dp.toPx() }
      var lastNanos = withFrameNanos { it }
      while (true) {
        val nanos = withFrameNanos { it }
        val seconds = ((nanos - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastNanos = nanos
        val pointer = currentDragPosition.value ?: break
        val box = currentWorkspaceBounds.value
        val step = seconds * speed
        val dx =
          edgeAutoScrollDelta(pointer.x - box.left, box.width, band, step, horizontalScrollState)
        val dy =
          edgeAutoScrollDelta(pointer.y - box.top, box.height, band, step, verticalScrollState)
        if (dx == 0f && dy == 0f) break
        if (dx != 0f) horizontalScrollState.dispatchRawDelta(dx)
        if (dy != 0f) verticalScrollState.dispatchRawDelta(dy)
      }
    }
    // Where the scroll has to be once the zoom a pinch or Ctrl+wheel just asked for is laid out,
    // so the point under the hand stays under it. Applied a frame later, because until the new
    // scale has been measured the scroll range is the old one's and the offset would be clamped
    // away; kept until then, so a burst of wheel events chains from the last target rather than
    // from a scroll that has not moved yet.
    var zoomTarget by remember(document.id) { mutableStateOf<Triple<Float, Float, Float>?>(null) }
    LaunchedEffect(scale) {
      val (targetScale, x, y) = zoomTarget ?: return@LaunchedEffect
      if (targetScale != scale) return@LaunchedEffect
      withFrameNanos {}
      horizontalScrollState.scrollTo(x.roundToInt())
      verticalScrollState.scrollTo(y.roundToInt())
      zoomTarget = null
    }
    Box(
      Modifier.fillMaxSize()
        .onGloballyPositioned {
          workspaceBounds = it.boundsInRoot()
          onWorkspaceBounds(workspaceBounds)
        }
        // Outside the scrolls, so the positions it reads are the workspace's own and it sees a
        // pinch before the scroll can take one finger of it.
        .canvasZoomGestures(
          scale = zoomTarget?.first ?: scale,
          onZoom = { request ->
            val (fromScale, fromX, fromY) =
              zoomTarget
                ?: Triple(
                  scale,
                  horizontalScrollState.value.toFloat(),
                  verticalScrollState.value.toFloat(),
                )
            zoomTarget =
              Triple(
                request.scale,
                anchoredScroll(fromX, request.focus.x, fromScale, request.scale),
                anchoredScroll(fromY, request.focus.y, fromScale, request.scale),
              )
            onZoomChanged(request.scale)
          },
          onPan = { pan ->
            horizontalScrollState.dispatchRawDelta(-pan.x)
            verticalScrollState.dispatchRawDelta(-pan.y)
          },
        )
        .horizontalScroll(horizontalScrollState)
        .verticalScroll(verticalScrollState)
    ) {
      // The scaled frame takes the room it is drawn in — the graphicsLayer below scales the
      // painting, not the layout — so that zooming past the workspace scrolls rather than clips,
      // and a frame smaller than the workspace still sits where [contentAlignment] says.
      Box(
        Modifier.widthIn(min = workspaceWidth).heightIn(min = workspaceHeight),
        contentAlignment = contentAlignment,
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy((CANVAS_PANE_GAP_DP.value * scale).dp)) {
          Box(Modifier.size((sourceWidth * scale).dp, (expandedHeightDp * scale).dp)) {
            Surface(
              Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                // The frame's width, the content's height, never shorter than the frame — the
                // extent. `requiredSize` here is what used to cut a long list off at the frame and
                // leave the rest of it somewhere nobody could edit.
                .requiredWidth((sourceWidth * densityRatio).dp)
                .requiredHeightIn(min = (sourceHeight * densityRatio).dp)
                // Back into the design's own dp — the unit the environment states the frame in and
                // the one the extent is compared against above — rather than the workspace's.
                .onSizeChanged { size ->
                  val designDensity = document.renderDensity(density).density
                  measuredDp =
                    (size.width / designDensity).roundToInt() to
                      (size.height / designDensity).roundToInt()
                  expandedHeightDp = size.height / designDensity
                }
                .graphicsLayer {
                  scaleX = drawScale
                  scaleY = drawScale
                  transformOrigin = TransformOrigin(0f, 0f)
                  // A runtime renderer's pixels are in a DOM layer under the editor, reached
                  // through a `BlendMode.Clear` hole it punches in this frame. An offscreen layer
                  // would take that hole into its own buffer and composite the buffer back over
                  // the workspace, so the design never showed through: the frame drew blank.
                  compositingStrategy =
                    if (canvasRenderer == null) CompositingStrategy.Offscreen
                    else CompositingStrategy.Auto
                }
                .onGloballyPositioned {
                  frameBounds = it.boundsInRoot()
                  frameOrigin = it.positionInRoot()
                  onCanvasBounds(frameBounds)
                }
                .then(
                  if (dropHovered) Modifier.border(1.dp, MaterialTheme.colorScheme.primary)
                  else Modifier
                ),
              shape = RoundedCornerShape(0.dp),
              color =
                if (canvasRenderer == null) MaterialTheme.colorScheme.surface
                else Color.Transparent,
              shadowElevation = 0.dp,
            ) {
              // Where a right-click landed on the design, in the frame's own pixels, and null
              // while no menu is open.
              var menuAt by remember(document.id) { mutableStateOf<Offset?>(null) }
              CanvasExtentLayout(
                Modifier.fillMaxSize()
                  .canvasNodeDrag(
                    key = document.id,
                    enabled = moveDragEnabled && showSelectionOverlay,
                    // The frame's own pixels reach the screen through [drawScale]; a press arrives
                    // in the frame's space, so the same conversion the secondary click and the
                    // drop hit-test use answers for this gesture too.
                    rootPoint = { position ->
                      Offset(
                        frameOrigin.x + position.x * drawScale,
                        frameOrigin.y + position.y * drawScale,
                      )
                    },
                    // Inside the selection, the selection: having chosen the card is how somebody
                    // says "move the card", and picking up the text under the pointer instead made
                    // a container impossible to drag by anything but its padding.
                    insideSelection = { point ->
                      selectedNodeId != null &&
                        inspection
                          ?.nodes
                          ?.firstOrNull { it.nodeId == selectedNodeId }
                          ?.bounds
                          ?.let {
                            point.x >= it.x &&
                              point.x <= it.right &&
                              point.y >= it.y &&
                              point.y <= it.bottom
                          } == true
                    },
                    // Otherwise the design already reports every node's box; the smallest
                    // containing one is the deepest node under the point — the same answer the tap
                    // and the context menu give, so a drag picks up what a click would select.
                    hitTest = { point ->
                      val selected = selectedNodeId?.let { id ->
                        inspection?.nodes?.firstOrNull { it.nodeId == id }?.bounds
                      }
                      if (
                        selected != null &&
                          point.x >= selected.x &&
                          point.x <= selected.right &&
                          point.y >= selected.y &&
                          point.y <= selected.bottom
                      ) {
                        return@canvasNodeDrag selectedNodeId
                      }
                      inspection
                        ?.nodes
                        .orEmpty()
                        .mapNotNull { node -> node.bounds?.let { node.nodeId to it } }
                        .filter { (_, bounds) ->
                          point.x >= bounds.x &&
                            point.x <= bounds.right &&
                            point.y >= bounds.y &&
                            point.y <= bounds.bottom
                        }
                        .minByOrNull { (_, bounds) -> bounds.width * bounds.height }
                        ?.first
                    },
                    onStarted = onNodeDragStarted,
                    onDragged = onNodeDragged,
                    onEnded = onNodeDragEnded,
                  )
                  .then(
                    if (canvasRenderer != null && showSelectionOverlay) {
                      Modifier.pointerInput(document.revision, inspection) {
                        detectTapGestures { position ->
                          val point =
                            Offset(
                              frameOrigin.x + position.x * drawScale,
                              frameOrigin.y + position.y * drawScale,
                            )
                          inspection
                            ?.nodes
                            .orEmpty()
                            .mapNotNull { node -> node.bounds?.let { node.nodeId to it } }
                            .filter { (_, bounds) ->
                              point.x >= bounds.x &&
                                point.x <= bounds.right &&
                                point.y >= bounds.y &&
                                point.y <= bounds.bottom
                            }
                            .minByOrNull { (_, bounds) -> bounds.width * bounds.height }
                            ?.first
                            ?.let(onNodeSelected)
                        }
                      }
                    } else Modifier
                  )
                  .onSecondaryClick(document.id) { position ->
                    if (!showSelectionOverlay) return@onSecondaryClick
                    // The inspection reports each box in root pixels, which is the space this press
                    // has to be asked in: the frame's own pixels reach the screen through
                    // [drawScale], and its origin is the *unclipped* one — a scrolled frame's
                    // visible top is the viewport's, not the frame's.
                    val point =
                      Offset(
                        frameOrigin.x + position.x * drawScale,
                        frameOrigin.y + position.y * drawScale,
                      )
                    // The design already reports every node's box, which is what the presence
                    // overlay and the catalog drop both hit-test against. Smallest box wins: the
                    // deepest node containing the point is the one under the pointer.
                    val hit =
                      inspection
                        ?.nodes
                        .orEmpty()
                        .mapNotNull { node -> node.bounds?.let { node.nodeId to it } }
                        .filter { (_, bounds) ->
                          point.x >= bounds.x &&
                            point.x <= bounds.x + bounds.width &&
                            point.y >= bounds.y &&
                            point.y <= bounds.y + bounds.height
                        }
                        .minByOrNull { (_, bounds) -> bounds.width * bounds.height }
                        ?.first
                    // The inspection callback follows the first rendered frame. A right-click can
                    // arrive before it, especially immediately after opening a design; in that
                    // interval retain the current selection as the menu subject rather than making
                    // the secondary button appear dead. Once bounds are available, the node under
                    // the pointer remains authoritative.
                    val menuNode = hit ?: selectedNodeId
                    if (menuNode != null) {
                      if (menuNode != selectedNodeId) onNodeSelected(menuNode)
                      menuAt = position
                    }
                  }
              ) {
                Box {
                  LocalUiBuilderChrome.current.PopupMenu(
                    expanded = menuAt != null,
                    onDismissRequest = { menuAt = null },
                    entries = selectionMenu { menuAt = null },
                    offset =
                      with(density) {
                        DpOffset(
                          ((menuAt?.x ?: 0f) * drawScale).toDp(),
                          ((menuAt?.y ?: 0f) * drawScale).toDp(),
                        )
                      },
                  )
                }
                if (canvasRenderer == null) {
                  UiBuilderSurface(
                    document = document,
                    editorOverlay = showSelectionOverlay,
                    selectedNodeId = selectedNodeId,
                    onNodeSelected = onNodeSelected,
                    // The extent is a proxy: lists unrolled, scrolling dropped, sized by content.
                    // Compose will not measure a real scrollable against an unbounded height, so
                    // this is what lets a long list be drawn — and edited — whole.
                    unrolled = true,
                    onInspectionSnapshot = { snapshot ->
                      inspection = snapshot
                      onInspectionSnapshot?.invoke(snapshot)
                    },
                    onInspectionInvalidated = onInspectionInvalidated,
                  )
                } else {
                  Box(
                    Modifier.requiredSize(
                      (sourceWidth * densityRatio).dp,
                      (expandedHeightDp * densityRatio).dp,
                    )
                  ) {
                    canvasRenderer(
                      document.withWearWidgetHostShape(LocalWearWidgetHostShape.current),
                      UiBuilderCanvasSurface(
                        sourceWidth,
                        expandedHeightDp,
                        document.renderDensity(density).density,
                        UiBuilderRendererSurfaceModeV2.AUTHORING_UNROLLED,
                        horizontalScrollState.value * 31 + verticalScrollState.value,
                      ),
                      selectedNodeId,
                      showSelectionOverlay,
                      onNodeSelected,
                    ) { snapshots ->
                      val snapshot = snapshots.editor
                      inspection = snapshot
                      val measuredBottom =
                        snapshots.renderer.nodes.mapNotNull { it.bounds?.bottom }.maxOrNull() ?: 0f
                      val measuredHeightDp =
                        measuredBottom / document.renderDensity(density).density
                      if (measuredHeightDp > expandedHeightDp) expandedHeightDp = measuredHeightDp
                      onInspectionSnapshot?.invoke(snapshot)
                    }
                  }
                }
                SlotPlaceholderOverlay(
                  placeholders = slotPlaceholders,
                  frameOrigin = frameOrigin,
                  drawScale = drawScale,
                )
                DropTargetOverlay(
                  dropPlan = dropPlan,
                  frameOrigin = frameOrigin,
                  drawScale = drawScale,
                )
                MoveOriginOverlay(
                  moveOrigin = moveOrigin,
                  frameOrigin = frameOrigin,
                  drawScale = drawScale,
                )
                // Over the document and under the collaborators: the reference is being compared
                // against
                // what the document draws, so it goes on top of that; another person's selection is
                // a
                // fact
                // about this session and must not be hidden by a mock.
                ReferenceOverlayCanvas(reference, onMarkDrawn, onPieceMoved)
                RemotePresenceOverlay(collaborators, inspection, frameOrigin, drawScale)
                // Above everything, because a pin is the one thing on this canvas a person clicks
                // that is
                // not part of the design: it must not end up under a mock somebody just turned up
                // the
                // opacity of, and it must not be what a selection outline is drawn over.
                CommentPinOverlay(
                  threads = commentThreads,
                  marks = reference.marks,
                  selectedThreadId = selectedThreadId,
                  onSelect = onCommentThreadSelected,
                )
              }
            }
          }
          // The companion is the *frame* view of a design that outgrows it — what someone sees on
          // the device, beside the extent they edit. It is not drawn when another pane is already
          // showing the frame: the preview and native panes both do, at the design's own size and
          // at every device it claims, so a third copy inside the editor costs the editing surface
          // a third of its width to say what the pane next door says better.
          if (overflowsFrame && frameCompanion) {
            ConstrainedFramePane(
              document = document,
              widthDp = sourceWidth,
              heightDp = sourceHeight,
              scale = scale,
              densityRatio = densityRatio,
            )
          }
        }
      }
    }
    // Beside the selected node rather than over it, and outside the scaled frame so the type stays
    // the size it was designed at however far the design is zoomed out.
    val selectedBounds = selectedNodeId?.let { id ->
      inspection?.nodes?.firstOrNull { it.nodeId == id }?.bounds
    }
    // A handle in hand hides the hover editor for the reason a drag does: it answers the previous
    // question while the pointer is asking the next one.
    var resizing by remember(document.id) { mutableStateOf(false) }
    // Not while a drag is in the air: the tight editor follows the *selection*, and a drag is a
    // question about the target — a panel of the selected node's fields floating over the canvas
    // is answering the previous question while the pointer asks the next one.
    if (
      hoverEditor != null &&
        showSelectionOverlay &&
        selectedBounds != null &&
        dragPosition == null &&
        !resizing
    ) {
      val left = (selectedBounds.x - workspaceBounds.left).coerceAtLeast(0f)
      val below = selectedBounds.y + selectedBounds.height - workspaceBounds.top + 8f
      val above = selectedBounds.y - workspaceBounds.top - 8f
      val roomBelow = with(density) { (workspaceBounds.height - below).toDp() } > HOVER_EDITOR_ROOM
      Box(
        Modifier.align(Alignment.TopStart)
          .offset(
            x =
              with(density) { left.toDp() }
                .coerceIn(0.dp, (workspaceWidth - HOVER_EDITOR_WIDTH).coerceAtLeast(0.dp)),
            y =
              with(density) { (if (roomBelow) below else above).toDp() }
                .coerceIn(0.dp, workspaceHeight)
                .let { if (roomBelow) it else (it - HOVER_EDITOR_ROOM).coerceAtLeast(0.dp) },
          )
          .width(HOVER_EDITOR_WIDTH)
      ) {
        hoverEditor()
      }
    }
    if (
      sizing != null &&
        sizing.nodeId == selectedNodeId &&
        showSelectionOverlay &&
        selectedBounds != null &&
        dragPosition == null
    ) {
      val pxPerDp = density.density * scale
      val rtl = document.environment["layoutDirection"]?.jsonPrimitive?.contentOrNull == "rtl"
      // Where Fill actually reaches: the parent's box less its own padding, which is the room it
      // offers its children. Snapping to the outer box made a padded parent's Fill land short of
      // where the handle was let go.
      val parentBounds =
        document.location(sizing.nodeId)?.nodeId?.let { parentId ->
          inspection
            ?.nodes
            ?.firstOrNull { it.nodeId == parentId }
            ?.bounds
            ?.let { outer ->
              // In the parent's drawn pixels: its ancestors' scales and its own, in chain order.
              val sx = pxPerDp * document.ancestorScale(parentId, EditorAxis.Width)
              val sy = pxPerDp * document.ancestorScale(parentId, EditorAxis.Height)
              val (left, top, right, bottom) =
                paddingInsets(document.nodes[parentId]?.modifiers.orEmpty(), rtl)
              UiBuilderPixelBounds(
                x = outer.x + left * sx,
                y = outer.y + top * sy,
                width = (outer.width - (left + right) * sx).coerceAtLeast(0f),
                height = (outer.height - (top + bottom) * sy).coerceAtLeast(0f),
              )
            }
        }
      ResizeHandles(
        sizing = sizing,
        bounds = selectedBounds,
        parentBounds = parentBounds,
        origin = workspaceBounds.topLeft,
        // A design dp is `scale` workspace dp — see [drawScale] — and the root counts in the
        // workspace's pixels.
        pxPerDp = pxPerDp,
        // Its own scale and every ancestor's: the canvas measured the node after all of them.
        nodeScale =
          document.nodes[sizing.nodeId]?.modifiers.orEmpty().let { chain ->
            drawnScale(chain, EditorAxis.Width) *
              document.ancestorScale(sizing.nodeId, EditorAxis.Width) to
              drawnScale(chain, EditorAxis.Height) *
                document.ancestorScale(sizing.nodeId, EditorAxis.Height)
          },
        onResizing = { resizing = it },
        onResize = { width, height -> onResize(sizing.nodeId, width, height) },
        modifier = Modifier.matchParentSize().clipToBounds(),
      )
    }
    if (dragPosition != null) {
      // The ghost follows the pointer wherever it goes. Vanishing over an illegal region would
      // answer "can it land here" twice — once with the marker, once by taking the preview away —
      // and only one of those answers says anything.
      //
      // This state leaves the composition when the drag ends, so consecutive drags cannot inherit
      // each other's measurement. Keying on the pointer position would instead reset it on every
      // move and keep the ghost permanently empty.
      var ghostContentBounds by
        remember(document.id) { mutableStateOf<UiBuilderPixelBounds?>(null) }
      // Capped by the slot under the pointer, in the ghost's own pixels: a component that will
      // fill its landing slot is drawn filling it while still in the air. The conversion is the
      // design's density, which the ghost deliberately does not carry — see [dragGhostDocument].
      val landing = dropPlan?.bounds
      val ghostConstraints = landing?.let {
        Modifier.sizeIn(
          maxWidth = with(density) { (it.width / densityRatio).toDp() },
          maxHeight = with(density) { (it.height / densityRatio).toDp() },
        )
      }
      // Anchored on the ghost's content, not its cell: the component itself rides under the
      // pointer, the way the part rides under the cursor in every canvas tool. Until the ghost has
      // been measured once it is drawn empty rather than one frame in the wrong place.
      //
      // The anchor is the content's *size*, never its reported position: the position comes back
      // from the ghost's own inspection in root space — which includes the very offset this is
      // computing — and reading it would make the offset chase itself, a loop that oscillates every
      // layout and leaves the scene never idle. The content sits at the cell's top-start, so the
      // size is all the anchor needs.
      val ghostModifier =
        Modifier.align(Alignment.TopStart)
          .offset(
            x =
              with(density) {
                (dragPosition.x -
                    workspaceBounds.left -
                    (ghostContentBounds?.width ?: 0f) / 2f * scale)
                  .toDp()
              },
            y =
              with(density) {
                (dragPosition.y -
                    workspaceBounds.top -
                    (ghostContentBounds?.height ?: 0f) / 2f * scale)
                  .toDp()
              },
          )
      when {
        moveDragPreview != null ->
          DragLivePreviewGhost(
            document = moveDragPreview,
            scale = scale,
            ghostConstraints = ghostConstraints,
            hidden = ghostContentBounds == null,
            onContentBounds = { ghostContentBounds = it },
            modifier = ghostModifier,
          )
        dragPreview != null ->
          DragLivePreviewGhost(
            document = dragPreview,
            scale = scale,
            ghostConstraints = ghostConstraints,
            hidden = ghostContentBounds == null,
            onContentBounds = { ghostContentBounds = it },
            modifier = ghostModifier,
          )
        dragPreviewBitmap != null ->
          DragBitmapPreviewGhost(bitmap = dragPreviewBitmap, modifier = ghostModifier)
        dragGhostLabel != null -> DragPlaceholderGhost(dragGhostLabel, ghostModifier)
      }
    }
    // Over the workspace rather than in the status bar, where every canvas tool puts it, and
    // outside the scrolling box so it stays put while the design under it moves.
    CanvasZoomControls(
      scale = scale,
      fitting = zoom == null,
      onZoomChanged = onZoomChanged,
      modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
    )
  }
}

/**
 * The empty recommended slots, drawn as dashed regions that say "a component goes here".
 *
 * The alternative to a placeholder is the panel's destination line — "Adds into
 * root-surface.content" — which answers the same question in a sentence about ids. This answers it
 * where the answer belongs, and it is the region a drop hits: the reducer computes one region per
 * empty slot and both the drawing and the hit test read it, so what a reader sees is what a drop
 * lands in. It is drawn under the drag marker and gone the moment the slot is populated, because
 * the reducer only reports empty slots.
 *
 * Strokes and type are screen-sized, not design-sized: a hint that thins with the zoom is a hint
 * lost exactly when the design is too small to read. The label is scaled back up through the same
 * factor the frame is scaled down by.
 */
@Composable
private fun SlotPlaceholderOverlay(
  placeholders: List<UiBuilderSlotPlaceholder>,
  frameOrigin: Offset,
  drawScale: Float,
) {
  if (placeholders.isEmpty()) return
  val color = MaterialTheme.colorScheme.primary
  val stroke = screenStroke(2f, drawScale)
  val dashOn = screenStroke(8f, drawScale)
  val dashOff = screenStroke(6f, drawScale)
  Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
    placeholders.forEach { placeholder ->
      val bounds = placeholder.bounds
      val local =
        UiBuilderPixelBounds(
          x = (bounds.x - frameOrigin.x) / drawScale,
          y = (bounds.y - frameOrigin.y) / drawScale,
          width = bounds.width / drawScale,
          height = bounds.height / drawScale,
        )
      drawRoundRect(
        color = color.copy(alpha = 0.06f),
        topLeft = Offset(local.x, local.y),
        size = Size(local.width, local.height),
        cornerRadius = CornerRadius(stroke * 4f),
      )
      drawRoundRect(
        color = color.copy(alpha = 0.5f),
        topLeft = Offset(local.x, local.y),
        size = Size(local.width, local.height),
        cornerRadius = CornerRadius(stroke * 4f),
        style =
          Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashOn, dashOff), 0f),
          ),
      )
    }
  }
  val density = LocalDensity.current
  placeholders.forEach { placeholder ->
    val bounds = placeholder.bounds
    val local =
      UiBuilderPixelBounds(
        x = (bounds.x - frameOrigin.x) / drawScale,
        y = (bounds.y - frameOrigin.y) / drawScale,
        width = bounds.width / drawScale,
        height = bounds.height / drawScale,
      )
    // Centred, not cornered: the top-left of a selected container is exactly where the tight
    // editor floats, and an invitation hidden under a panel is not an invitation.
    Box(
      Modifier.offset(
          x = with(density) { local.x.toDp() },
          y = with(density) { local.y.toDp() },
        )
        .size(
          width = with(density) { local.width.coerceAtLeast(0f).toDp() },
          height = with(density) { local.height.coerceAtLeast(0f).toDp() },
        ),
      contentAlignment = Alignment.Center,
    ) {
      Box(
        // Back up through the frame's own scale, so the label reads at the size it was written
        // however far the design is zoomed out.
        Modifier.graphicsLayer {
            scaleX = 1f / drawScale
            scaleY = 1f / drawScale
          }
          .clip(RoundedCornerShape(6.dp))
          .background(color.copy(alpha = 0.16f))
          .padding(horizontal = 6.dp, vertical = 2.dp)
          .semantics { contentDescription = "Drop into ${placeholder.target.slot}" }
      ) {
        Text(
          placeholder.target.slot,
          style = MaterialTheme.typography.labelSmall,
          color = color,
          maxLines = 1,
        )
      }
    }
  }
}

/**
 * Where a drag would land, drawn so the eye never has to ask.
 *
 * The slot it is entering is tinted, the way it always was; the seam between the children the drop
 * lands between is drawn as a bar across the slot — the honest answer to "where in here", which a
 * slot tint alone never gave. An empty slot has no seams, so it keeps the full highlight: its whole
 * box is the landing region, and saying so is the highlight's job.
 */
@Composable
private fun DropTargetOverlay(
  dropPlan: UiBuilderDropPlan?,
  frameOrigin: Offset,
  drawScale: Float,
) {
  val plan = dropPlan ?: return
  val bounds = plan.bounds
  val local =
    UiBuilderPixelBounds(
      x = (bounds.x - frameOrigin.x) / drawScale,
      y = (bounds.y - frameOrigin.y) / drawScale,
      width = bounds.width / drawScale,
      height = bounds.height / drawScale,
    )
  val color = MaterialTheme.colorScheme.primary
  // Stroke widths are screen widths, not design widths: the marker is an affordance, and an
  // affordance that thins with the zoom is one the reader loses exactly when the design is too
  // small to read — which is when "where will it land" matters most. The canvas is inside the
  // scaled layer, so the conversion back is dividing by the scale it was drawn at.
  val hairline = screenStroke(3.5f, drawScale)
  val glow = screenStroke(10f, drawScale)
  Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
    // The seam is the message; the tint is the container it sits in. Dimmer than it used to be,
    // so the two read as background and figure rather than as two boxes.
    drawRect(
      color = color.copy(alpha = 0.10f),
      topLeft = Offset(local.x, local.y),
      size = Size(local.width, local.height),
    )
    if (plan.children.isEmpty()) {
      drawRect(
        color = color,
        topLeft = Offset(local.x, local.y),
        size = Size(local.width, local.height),
        style = Stroke(width = screenStroke(4f, drawScale)),
      )
      return@Canvas
    }
    drawRect(
      color = color.copy(alpha = 0.55f),
      topLeft = Offset(local.x, local.y),
      size = Size(local.width, local.height),
      style = Stroke(width = screenStroke(2f, drawScale)),
    )
    val before = plan.children.getOrNull(plan.index - 1)?.second
    val after = plan.children.getOrNull(plan.index)?.second
    if (plan.axis == UiBuilderDropAxis.Horizontal) {
      val seamX =
        when {
          before != null && after != null -> (before.right + after.x) / 2f
          before != null -> before.right
          after != null -> after.x
          else -> bounds.x
        }
      val x = (seamX - frameOrigin.x) / drawScale
      drawLine(
        color = color.copy(alpha = 0.25f),
        start = Offset(x, local.y),
        end = Offset(x, local.y + local.height),
        strokeWidth = glow,
        cap = StrokeCap.Round,
      )
      drawLine(
        color = color,
        start = Offset(x, local.y),
        end = Offset(x, local.y + local.height),
        strokeWidth = hairline,
        cap = StrokeCap.Round,
      )
    } else {
      val seamY =
        when {
          before != null && after != null -> (before.bottom + after.y) / 2f
          before != null -> before.bottom
          after != null -> after.y
          else -> bounds.y
        }
      val y = (seamY - frameOrigin.y) / drawScale
      drawLine(
        color = color.copy(alpha = 0.25f),
        start = Offset(local.x, y),
        end = Offset(local.x + local.width, y),
        strokeWidth = glow,
        cap = StrokeCap.Round,
      )
      drawLine(
        color = color,
        start = Offset(local.x, y),
        end = Offset(local.x + local.width, y),
        strokeWidth = hairline,
        cap = StrokeCap.Round,
      )
    }
  }
}

/**
 * Where the node a canvas move picked up still sits, dashed.
 *
 * A carried button and its own ghost are two pictures of the same thing; without this outline the
 * eye has to work out which one follows the pointer. It is drawn for as long as the move is in
 * flight and vanishes with it — the origin is the one place the drop cannot land, because the
 * release there is the no-op it should be.
 */
@Composable
private fun MoveOriginOverlay(
  moveOrigin: UiBuilderPixelBounds?,
  frameOrigin: Offset,
  drawScale: Float,
) {
  val bounds = moveOrigin ?: return
  val color = MaterialTheme.colorScheme.primary
  Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
    drawRect(
      color = color.copy(alpha = 0.6f),
      topLeft =
        Offset((bounds.x - frameOrigin.x) / drawScale, (bounds.y - frameOrigin.y) / drawScale),
      size = Size(bounds.width / drawScale, bounds.height / drawScale),
      style =
        Stroke(
          width = screenStroke(2.5f, drawScale),
          // The dash lengths are screen lengths for the same reason the width is: a dash pattern
          // that scales with the zoom becomes a solid line when the design is small.
          pathEffect =
            PathEffect.dashPathEffect(
              floatArrayOf(screenStroke(10f, drawScale), screenStroke(7f, drawScale)),
              0f,
            ),
        ),
    )
  }
}

/**
 * The dragged thing itself, travelling with the pointer at the size it would land.
 *
 * The ghost used to be a 88x66 stamp of the thumbnail frame — a picture of a picture, half the size
 * of what would land. This renders the component (or, for a canvas move, the subtree being carried)
 * into an unconstrained cell, scales it by the canvas zoom so one of its dp lands as one of the
 * design's dp, and caps it by the landing slot so a component that will fill that slot is drawn
 * filling it while still in the air.
 *
 * [onContentBounds] reports where the document's root drew inside the surface, so the canvas can
 * anchor the **content** on the pointer rather than the cell around it — and until the first
 * measurement lands the ghost is drawn empty rather than one frame in the wrong place.
 */
@Composable
private fun DragLivePreviewGhost(
  document: UiBuilderDocument,
  scale: Float,
  ghostConstraints: Modifier?,
  hidden: Boolean,
  onContentBounds: (UiBuilderPixelBounds?) -> Unit,
  modifier: Modifier = Modifier,
) {
  val rootId = document.roots.firstOrNull() ?: return
  val renderer = LocalUiBuilderCanvasRenderer.current
  val density = LocalDensity.current
  val widthDp =
    document.environment["widthDp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
      ?: PREVIEW_FRAME_WIDTH_DP.toFloat()
  val heightDp =
    document.environment["heightDp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
      ?: PREVIEW_FRAME_HEIGHT_DP.toFloat()
  Box(modifier) {
    Box(
      (ghostConstraints ?: Modifier)
        .alpha(if (hidden) 0f else 0.92f)
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
          transformOrigin = TransformOrigin(0f, 0f)
        }
        // A picture of a Switch is not a Switch — the same rule the palette row keeps.
        .clearAndSetSemantics {}
    ) {
      val inspection: (UiBuilderInspectionSnapshot) -> Unit = { snapshot ->
        onContentBounds(snapshot.nodes.firstOrNull { it.nodeId == rootId }?.bounds)
      }
      if (renderer == null) {
        UiBuilderSurface(
          document = document,
          editorOverlay = false,
          // The same answer the editing surface gives: a list in the air is drawn unrolled, which
          // is what will land on the extent — not a clipped scroll nobody is dropping.
          unrolled = true,
          onInspectionSnapshot = inspection,
        )
      } else {
        renderer(
          document.withWearWidgetHostShape(LocalWearWidgetHostShape.current),
          UiBuilderCanvasSurface(
            widthDp,
            heightDp,
            document.renderDensity(density).density,
            UiBuilderRendererSurfaceModeV2.AUTHORING_UNROLLED,
          ),
          null,
          false,
          {},
          { snapshots -> inspection(snapshots.editor) },
        )
      }
    }
  }
}

/** The named chip that stands in where no picture of the dragged component can be drawn. */
@Composable
private fun DragPlaceholderGhost(label: String, modifier: Modifier = Modifier) {
  Surface(
    modifier.alpha(0.92f),
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHighest,
    border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
    tonalElevation = 6.dp,
  ) {
    Text(
      label,
      Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
    )
  }
}

/** The catalog's real published capture travelling with a Remote Compose document drag. */
@Composable
private fun DragBitmapPreviewGhost(bitmap: ImageBitmap, modifier: Modifier = Modifier) {
  Surface(
    modifier.size(88.dp, 66.dp).alpha(0.88f),
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHighest,
    border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
    tonalElevation = 6.dp,
  ) {
    Image(
      bitmap = bitmap,
      contentDescription = null,
      modifier = Modifier.fillMaxSize().padding(4.dp).clearAndSetSemantics {},
      contentScale = ContentScale.Fit,
    )
  }
}

/**
 * The design at its frame, beside the extent: what fits on the device, scrollable.
 *
 * Read-only, and that is the point of it rather than a limitation. The extent beside it is the
 * editing surface — one live coordinate space, one hit-test, one place a drop or a comment pin can
 * land — and this pane answers the other question that space cannot: *what does someone actually
 * see when they open the screen?* A list edited at full height hides the thing a phone shows first,
 * which is the fold; a frame that clips and scrolls puts it back without asking anybody to switch
 * between two views of their own design.
 *
 * Its own [renderSessionId] because [UiBuilderSurface] keys its bounds, overlay boxes and
 * inspection collector on that: sharing the editing pane's id would have the two panes' geometry
 * overwrite each other, and the inspection the editor hit-tests against would be whichever composed
 * last.
 */
@Composable
private fun ConstrainedFramePane(
  document: UiBuilderDocument,
  widthDp: Float,
  heightDp: Float,
  scale: Float,
  /** The design's pixels per workspace pixel — see the same value in [PinnedDesignCanvas]. */
  densityRatio: Float,
  /** Distinct per pane, for the reason the function doc gives. */
  renderSessionId: String = FRAME_COMPANION_SESSION,
  wearWidgetHostShape: WearWidgetHostShape? = null,
) {
  val renderer = LocalUiBuilderCanvasRenderer.current
  // **Read in the editor's composition, never inside the scene.** A scene starts with no
  // `CompositionLocal`s, so `provides LocalX.current` written in the content lambda below resolves
  // against the scene's empty context and yields each local's default. That is what silently cost
  // this pane the catalog's frame geometry — and with it the scaffold's content padding, so its
  // rows ran to the bezel and were clipped — along with the catalog's component ids, its canvas
  // adapters, its platform word, the widget host shape and the asset registry. Reading them here
  // captures the values the editor is actually running with.
  val nativeOnlyIds = LocalUiBuilderNativeOnly.current
  val catalogComponentIds = LocalUiBuilderCatalogComponentIds.current
  val canvasAdapters = LocalUiBuilderCanvasAdapters.current
  val canvasAdapterMappings = LocalUiBuilderCanvasAdapterMappings.current
  val frameGeometry = LocalUiBuilderFrameGeometry.current
  val catalogPlatform = LocalUiBuilderCatalogPlatform.current
  val ambientWidgetHostShape = LocalWearWidgetHostShape.current
  val remoteDocuments = LocalRemoteComposeDocuments.current
  val assetBitmaps = LocalUiBuilderAssetBitmaps.current
  val assetBytes = LocalUiBuilderAssetBytes.current
  Box(Modifier.size((widthDp * scale).dp, (heightDp * scale).dp)) {
    Surface(
      Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
        // The device's frame in the design's own pixels, like the extent beside it: this pane
        // exists to say what a device shows, and it can only say it at the density the device has.
        .requiredSize((widthDp * densityRatio).dp, (heightDp * densityRatio).dp)
        // Clipped before it is scrolled: the frame is the device's edge, and content past it is
        // what the person scrolls to rather than something that spills onto the canvas.
        .clip(RoundedCornerShape(0.dp))
        .graphicsLayer {
          scaleX = scale / densityRatio
          scaleY = scale / densityRatio
          transformOrigin = TransformOrigin(0f, 0f)
          compositingStrategy = CompositingStrategy.Offscreen
        },
      shape = RoundedCornerShape(0.dp),
      shadowElevation = 0.dp,
    ) {
      // The real composition, deliberately: this pane is the one that answers what a device
      // actually shows, so its list is the lazy one, and the scrolling is the design's own —
      // the `LazyColumn` or the `verticalScroll` the author put there, at a live position.
      //
      // No outer scroll wrapped around it, for two reasons that happen to agree. It would measure
      // the design against an unbounded height, which is the thing this whole pane exists to avoid.
      // And a design that overflows *without* a scrollable of its own is a design that overflows on
      // the device too: clipping it here is not a gap in the pane, it is the answer to the question
      // the pane is asking. The extent beside it is where the rest of that content is legible.
      //
      // **In its own scene**, so the design can be *driven*: a wheel over the pane arrives as a
      // rotating side button and a Wear list turns through its own snap behaviour — see
      // [DeviceSceneHost] for why that needs a scene rather than a subtree. The locals are carried
      // by value because a scene starts with none of them, and the list is the pane's own: a
      // design's components, its assets and the host shape it is drawn in are the same ones the
      // canvas beside it uses.
      if (renderer != null) {
        renderer(
          document.withWearWidgetHostShape(wearWidgetHostShape ?: ambientWidgetHostShape),
          UiBuilderCanvasSurface(
            widthDp,
            heightDp,
            document.renderDensity(LocalDensity.current).density,
            UiBuilderRendererSurfaceModeV2.DEVICE,
          ),
          null,
          false,
          {},
          {},
        )
      } else
        DeviceSceneHost(
          key = "$renderSessionId:${document.id}:$widthDp:$heightDp",
          contentKey = document,
          sizePx =
            IntSize(
              (widthDp * densityRatio).roundToInt(),
              (heightDp * densityRatio).roundToInt(),
            ),
          density = LocalDensity.current,
          content = {
            CompositionLocalProvider(
              LocalUiBuilderNativeOnly provides nativeOnlyIds,
              LocalUiBuilderCatalogComponentIds provides catalogComponentIds,
              LocalUiBuilderCanvasAdapters provides canvasAdapters,
              LocalUiBuilderCanvasAdapterMappings provides canvasAdapterMappings,
              LocalUiBuilderFrameGeometry provides frameGeometry,
              LocalUiBuilderCatalogPlatform provides catalogPlatform,
              LocalWearWidgetHostShape provides (wearWidgetHostShape ?: ambientWidgetHostShape),
              LocalRemoteComposeDocuments provides remoteDocuments,
              LocalUiBuilderAssetBitmaps provides assetBitmaps,
              LocalUiBuilderAssetBytes provides assetBytes,
            ) {
              UiBuilderSurface(
                document = document,
                editorOverlay = false,
                renderSessionId = renderSessionId,
                unrolled = false,
              )
            }
          },
        )
    }
  }
}

/** Keeps the companion's remembered geometry out of the editing pane's. */
private const val FRAME_COMPANION_SESSION = "frame-companion"

/** Canvas dp between the extent and the frame beside it. */
private val CANVAS_PANE_GAP_DP = 24.dp

/**
 * One read-only pane of the variant strip: what it is called, and the design under that frame.
 *
 * The label sits outside the scaled frame, like the hover editor and for its reason: it names a
 * picture rather than being part of one, so it stays legible however far the design is zoomed out.
 * Which is also why it is the pane's own [Column] rather than an overlay — a name drawn on top of a
 * variant would be the one thing in the strip that is not the design.
 */
@Composable
internal fun VariantPane(pane: UiBuilderVariantPane, scale: Float, hostDensity: Density) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
      pane.label,
      Modifier.height(VARIANT_LABEL_ROOM_DP.dp).widthIn(max = (pane.widthDp * scale).dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    ConstrainedFramePane(
      document = pane.document,
      widthDp = pane.widthDp,
      heightDp = pane.heightDp,
      scale = scale,
      // The variant's own, not the design's: a preset carries a density as well as a size, and a
      // Pixel Fold drawn at the watch's 2.0 would be the right box around the wrong measurements.
      densityRatio = pane.document.renderDensity(hostDensity).density / hostDensity.density,
      renderSessionId = pane.id,
      wearWidgetHostShape = pane.wearWidgetHostShape,
    )
  }
}

/** Room above a variant pane for its label, in canvas dp. */
internal const val VARIANT_LABEL_ROOM_DP = 18f

/** How wide the editor that follows the selection is, and how much room it needs under a node. */
private val HOVER_EDITOR_WIDTH = 268.dp

private val HOVER_EDITOR_ROOM = 148.dp

/**
 * The number a just-added modifier hands the caret to.
 *
 * Only the ones whose menu row picks a value on the author's behalf: `padding` starts at 16 and
 * `weight` at 1 because something has to be typed in the box, and the box is where the real number
 * is chosen. A fill has no number and takes no caret, and an alignment is a list to pick from
 * rather than a value to type.
 */
internal val MODIFIER_FOCUS_FIELDS = mapOf("padding" to "startDp", "weight" to "weight")

/** The zoom ladder the two step controls walk, in the order a designer expects to land on. */
private val CANVAS_ZOOM_STOPS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

internal const val MIN_CANVAS_ZOOM = 0.1f

internal const val MAX_CANVAS_ZOOM = 4f

/**
 * The next stop above or below [from].
 *
 * Stepped from whatever the canvas is *currently drawn at* rather than from the last button press,
 * so the first zoom out of a design framed at 62% goes to 50% and not to some remembered 100%.
 */
internal fun canvasZoomStep(from: Float, zoomIn: Boolean): Float =
  if (zoomIn) CANVAS_ZOOM_STOPS.firstOrNull { it > from + 0.001f } ?: MAX_CANVAS_ZOOM
  else CANVAS_ZOOM_STOPS.lastOrNull { it < from - 0.001f } ?: MIN_CANVAS_ZOOM

/** How the design is scaled, said out loud: `Fit · 62%`, or `125%` once somebody has pinned one. */
internal fun canvasZoomLabel(scale: Float, fitting: Boolean): String {
  val percent = "${(scale * 100).roundToInt()}%"
  return if (fitting) "Fit · $percent" else percent
}

/**
 * Zoom out, the current scale, zoom in, and back to framing the design.
 *
 * The percentage is a button as well as a readout: pressing it pins 100%, which is the one scale
 * worth a control of its own, and the fit toggle is how you get back from it.
 */
@Composable
private fun CanvasZoomControls(
  scale: Float,
  fitting: Boolean,
  onZoomChanged: (Float?) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier,
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 3.dp,
  ) {
    Row(
      Modifier.padding(horizontal = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      ToolbarIconAction("Zoom out", "", UiBuilderChromeIcon.Remove, scale > MIN_CANVAS_ZOOM) {
        onZoomChanged(canvasZoomStep(scale, zoomIn = false))
      }
      TextButton(
        onClick = { onZoomChanged(1f) },
        modifier = Modifier.semantics { contentDescription = "Zoom to 100%" },
      ) {
        Text(canvasZoomLabel(scale, fitting), style = MaterialTheme.typography.labelLarge)
      }
      ToolbarIconAction("Zoom in", "", UiBuilderChromeIcon.Add, scale < MAX_CANVAS_ZOOM) {
        onZoomChanged(canvasZoomStep(scale, zoomIn = true))
      }
      ToolbarToggleAction("Fit to window", UiBuilderChromeIcon.Fit, fitting) {
        onZoomChanged(if (fitting) scale else null)
      }
    }
  }
}

@Composable
private fun RemotePresenceOverlay(
  collaborators: List<UiBuilderCollaborator>,
  inspection: UiBuilderInspectionSnapshot?,
  /** Where the frame's own top-left is in root space, unclipped — see [PinnedDesignCanvas]. */
  frameOrigin: Offset,
  drawScale: Float,
) {
  if (collaborators.isEmpty()) return
  val boundsByNode = inspection?.nodes?.associate { it.nodeId to it.bounds }.orEmpty()
  Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
    collaborators.forEach { collaborator ->
      val color = collaborator.colorArgbHex.toPresenceColor()
      collaborator.selectedNodeIds.forEach { nodeId ->
        val bounds = boundsByNode[nodeId] ?: return@forEach
        // The inspection answers in root space and this canvas draws inside the frame, so a
        // collaborator's outline needs the same conversion the drop marker's does — without it
        // their selection sits wherever the frame's origin happens to be.
        drawRect(
          color = color,
          topLeft =
            Offset(
              (bounds.x - frameOrigin.x) / drawScale,
              (bounds.y - frameOrigin.y) / drawScale,
            ),
          size =
            androidx.compose.ui.geometry.Size(bounds.width / drawScale, bounds.height / drawScale),
          style =
            androidx.compose.ui.graphics.drawscope.Stroke(width = screenStroke(3f, drawScale)),
        )
      }
    }
  }
}
