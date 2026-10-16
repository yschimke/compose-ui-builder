@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import ee.schimke.composeai.uibuilder.LocalUiBuilderAssetBitmaps
import ee.schimke.composeai.uibuilder.LocalUiBuilderAssetBytes
import ee.schimke.composeai.uibuilder.LocalUiBuilderFontFamilies
import ee.schimke.composeai.uibuilder.LocalUiBuilderFontRegistry
import ee.schimke.composeai.uibuilder.LocalUiBuilderFontVariants
import ee.schimke.composeai.uibuilder.canvas.CanvasExtentLayout
import ee.schimke.composeai.uibuilder.canvas.DeviceSceneHost
import ee.schimke.composeai.uibuilder.canvas.LocalRemoteComposeDocuments
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapterMappings
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapters
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogComponentIds
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderFrameGeometry
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderNativeOnly
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderOverlayPassesInput
import ee.schimke.composeai.uibuilder.canvas.LocalWearWidgetHostShape
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.canvas.renderDensity
import ee.schimke.composeai.uibuilder.canvasAdapterMappings
import ee.schimke.composeai.uibuilder.capability.LocalUiBuilderCatalogScrollers
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.frameGeometry
import ee.schimke.composeai.uibuilder.inspector.CommentPinOverlay
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSurfaceModeV2
import ee.schimke.composeai.uibuilder.reference.ReferenceFacts
import ee.schimke.composeai.uibuilder.reference.ReferenceFindings
import ee.schimke.composeai.uibuilder.reference.ReferenceMarkupKind
import ee.schimke.composeai.uibuilder.reference.ReferenceOverlayCanvas
import ee.schimke.composeai.uibuilder.reference.ReferenceOverlayState
import ee.schimke.composeai.uibuilder.renderer.sdk.CATALOG_RUNTIME_CAPABILITY_HORIZONTAL_UNROLL
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionCollector
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderPixelBounds
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import ee.schimke.composeai.uibuilder.renderer.sdk.right
import kotlin.math.roundToInt
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The design pinned in the workspace: framed or zoomed, scrolled, and hit-tested. Internal so a
 * test can measure the frame, which depends on the rendered density.
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
   * Where the hovering drag would land, or null while no legal slot is under the pointer; the
   * marker is drawn from it.
   */
  dropPlan: UiBuilderDropPlan? = null,
  /**
   * Empty recommended slots drawn as dashed placeholders; see
   * [UiBuilderEditorReducer.slotPlaceholders].
   */
  slotPlaceholders: List<UiBuilderSlotPlaceholder> = emptyList(),
  /** The dragged component carried beside the pointer, at the size it would land. */
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
   * Where the moved node still sits, in root pixels, drawn dashed to tell it apart from the ghost.
   */
  moveOrigin: UiBuilderPixelBounds? = null,
  /** The canvas pane's own box — the workspace a beside-drop's empty ground is measured against. */
  onWorkspaceBounds: (Rect) -> Unit = {},
  /**
   * The workspace's scroll offset, so the drag hit-test can use the same unshifted space as the
   * inspection's boxes.
   */
  onCanvasScroll: (Offset) -> Unit = {},
  showSelectionOverlay: Boolean,
  /**
   * Whether the canvas move gesture is on; off where no handlers are wired so press-and-hold does
   * not eat a scroll.
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
  /** The base picture measured against this frame, for the fits that need its size in dp. */
  referenceFacts: ReferenceFacts? = null,
  /** The last measurement against the reference, outlined over the frame. */
  referenceFindings: ReferenceFindings? = null,
  /**
   * The frame's unclipped box in the editor root on every placement; converts between root pixels
   * and frame dp.
   */
  onFrameRootBounds: (Rect) -> Unit = {},
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
   * The selection's quick editor, or null while closed. Positioned here because only the canvas
   * knows where there is room; [dragHandle] makes part of it a grip.
   */
  hoverEditor: (@Composable (dragHandle: Modifier) -> Unit)?,
  /** A press anywhere else on the canvas closes the quick editor. */
  onHoverEditorDismiss: () -> Unit = {},
  /**
   * Whether [hoverEditor] takes the keyboard: false for the quick editor, true for a typing-only
   * card (Comment), where an outside press closes it.
   */
  hoverEditorFocusable: Boolean = false,
  /** A double-click on the design, with the node under it — the deepest one, as a click picks. */
  onNodeDoubleClicked: (String) -> Unit = {},
  /** The text being typed over in place, drawn over its node; null when none is. */
  inlineTextEdit: CanvasInlineTextEdit? = null,
  /** The in-place text is done: the text to commit, or null to leave the node as it was. */
  onInlineTextDone: (text: String?, focusMovedAway: Boolean) -> Unit = { _, _ -> },
  onTextInputFocusChanged: (Boolean) -> Unit = {},
  /**
   * How the selected node is sized, for its resize handles, or null for none; see
   * [UiBuilderEditorReducer.sizing].
   */
  sizing: EditorNodeSizing? = null,
  /** A handle released or double-clicked: the node and the new sizing of each axis it touched. */
  onResize: (String, EditorSizing?, EditorSizing?) -> Unit = { _, _, _ -> },
  /** The scale the design is drawn at, or null to frame it in whatever room the workspace has. */
  zoom: Float?,
  onZoomChanged: (Float?) -> Unit,
  /**
   * The scale the design was just drawn at ([zoom], or the fit), for the zoom chords to step from.
   */
  onScaleDrawn: (Float) -> Unit = {},
  /**
   * Whether the frame companion is drawn beside the extent when content outgrows the frame. Off
   * while the preview or native pane is open (they already draw the frame) and for Wear or m3
   * designs.
   */
  frameCompanion: Boolean =
    LocalUiBuilderCatalogPlatform.current != UiBuilderCatalogPlatform.WEAR.wireValue &&
      LocalUiBuilderCatalogPlatform.current != UiBuilderCatalogPlatform.MOBILE.wireValue,
  /**
   * The extent, or the device frame with its scroller popped out; see [EditorCanvasView]. Only
   * honoured in-process and not for Wear widgets.
   */
  canvasView: EditorCanvasView = EditorCanvasView.Extent,
  /** Switches the view from the zoom bar, or null to leave the switch out of it. */
  onCanvasViewChanged: ((EditorCanvasView) -> Unit)? = null,
  /** What the pop-out's header calls a node. */
  nodeLabel: (String) -> String = { it },
  contentAlignment: Alignment = Alignment.TopStart,
  modifier: Modifier = Modifier,
) {
  val (sourceWidth, sourceHeight) = document.canvasFrameDp(LocalWearWidgetHostShape.current)
  val density = LocalDensity.current
  // One design pixel in workspace pixels. The workspace uses the host density (usually 1) while the
  // design uses its environment's (2.0 watch, 2.625 phone), so the frame is sized in design pixels
  // and drawn back down by [drawScale]. Sizing it in workspace dp clamped wide designs.
  val densityRatio = document.renderDensity(density).density / density.density
  var inspection by
    remember(document.id, document.revision) { mutableStateOf<UiBuilderInspectionSnapshot?>(null) }
  // A widget's device is the launcher's host shape, which the preview pane draws at every shape
  // there is; a widget has no list to pop out either.
  val deviceViewOffered = document.wearWidgetScaffoldSize() == null
  val deviceView = deviceViewOffered && canvasView == EditorCanvasView.Device
  // A watch at its frame is a round screen, and the square frame's corners are not part of it: the
  // scaffold clips itself, and without this the editor's surface showed in the four corners.
  val roundFrame =
    deviceView && LocalUiBuilderCatalogPlatform.current == UiBuilderCatalogPlatform.WEAR.wireValue
  // The container the selection scrolls inside, which is what comes out beside the frame. It
  // follows the selection and nothing else: select outside it and it goes back in.
  val scrollers = LocalUiBuilderCatalogScrollers.current
  val popOut =
    remember(document, selectedNodeId, deviceView, showSelectionOverlay, scrollers) {
      if (deviceView && showSelectionOverlay) {
        document.scrollingContainerOf(selectedNodeId, scrollers)
      } else null
    }
  BoxWithConstraints(modifier.clipToBounds(), contentAlignment = contentAlignment) {
    // "Fit" is the largest scale showing the whole frame, uncapped at 1:1. Named here because the
    // scrolling box below cannot see these constraints.
    val workspaceWidth = maxWidth
    val workspaceHeight = maxHeight
    // The design's measured content height, which may exceed the frame: the canvas draws the extent
    // (frame width, content height). Until measured, the frame height.
    var expandedHeightDp by remember(document.id) { mutableStateOf(sourceHeight) }
    LaunchedEffect(document.revision, canvasRenderer) {
      if (canvasRenderer != null) expandedHeightDp = sourceHeight
    }
    // Only a design that outgrows its frame gets the second pane. One that fits would be drawn
    // twice identically, and two identical pictures side by side say nothing the one said.
    val overflowsFrame = !deviceView && expandedHeightDp > sourceHeight + 0.5f
    // The device view draws the frame and never grows it: what does not fit is what scrolls.
    val frameHeightDp = if (deviceView) sourceHeight else expandedHeightDp
    // The frame's unclipped root box. Pop-out size and offset are read as a share of it, since it
    // and the inspection come from the same layout pass and stay consistent mid-zoom.
    var frameRootBounds by remember(document.id) { mutableStateOf(Rect.Zero) }
    var popOutRootBounds by remember(document.id) { mutableStateOf<Rect?>(null) }
    val popOutBounds = popOut?.let { container ->
      inspection?.nodes?.firstOrNull { it.nodeId == container.nodeId }?.bounds
    }
    // Kept per container, so a container scrolled out of the frame — and forgotten by the
    // inspection with it — keeps its pop-out rather than collapsing it to nothing.
    val popOutCross = remember(document.id) { mutableMapOf<String, Float>() }
    if (popOut != null && popOutBounds != null && frameRootBounds.width > 0f) {
      popOutCross[popOut.nodeId] =
        if (popOut.horizontal) popOutBounds.height / frameRootBounds.height * sourceHeight
        else popOutBounds.width / frameRootBounds.width * sourceWidth
    }
    val popOutCrossDp = popOut?.let { popOutCross[it.nodeId] }
    // The unrolled length, from the pop-out's own last measurement; until then, one frame's worth.
    var popOutExtent by remember(document.id) { mutableStateOf<Pair<String, Float>?>(null) }
    // What the catalog runtime announced it honours, from the frame's last inspection. Empty
    // in-process, where nothing is asked.
    var runtimeCapabilities by remember(document.id) { mutableStateOf(emptySet<String>()) }
    // The last wheel over one of the runtime's lists, handed on; see the runtime frame below.
    var runtimeScroll by remember(document.id) { mutableStateOf<UiBuilderCanvasScroll?>(null) }
    val popOutExtentDp =
      popOut?.let { container ->
        popOutExtent?.takeIf { it.first == container.nodeId }?.second
          ?: if (canvasRenderer != null) {
            // Runtime surfaces are handed a size, so they first draw into a generous budget.
            RUNTIME_POP_OUT_BUDGET_DP
          } else if (container.horizontal) sourceWidth else sourceHeight
      } ?: 0f
    // Level with the container in the frame, so the connectors run across rather than up.
    val popOutTopDp =
      if (popOutBounds != null && frameRootBounds.height > 0f) {
        ((popOutBounds.y - frameRootBounds.top) / frameRootBounds.height * sourceHeight).coerceIn(
          0f,
          sourceHeight,
        )
      } else 0f
    // A runtime draws a horizontal container whole only if its catalog says it does: a lazy row
    // that ignores the signal is still a scroller, clipped at whatever width it is handed.
    val showPopOut =
      popOut != null &&
        popOutCrossDp != null &&
        (canvasRenderer == null ||
          !popOut.horizontal ||
          CATALOG_RUNTIME_CAPABILITY_HORIZONTAL_UNROLL in runtimeCapabilities)
    val popOutWidthDp =
      if (!showPopOut) 0f else if (popOut!!.horizontal) popOutExtentDp else popOutCrossDp!!
    val popOutHeightDp =
      if (!showPopOut) 0f else if (popOut!!.horizontal) popOutCrossDp!! else popOutExtentDp
    // The frame plus the companion or pop-out: fit frames everything drawn.
    val pairWidth =
      sourceWidth +
        (if (overflowsFrame) sourceWidth + CANVAS_PANE_GAP_DP.value else 0f) +
        (if (showPopOut) popOutWidthDp + CANVAS_PANE_GAP_DP.value else 0f)
    // Fit frames the design above the zoom controls rather than under them: they sit over the
    // workspace's bottom edge, and a fitted frame reaching it had its corner covered.
    val fitHeight = (workspaceHeight.value - ZOOM_CONTROLS_ROOM_DP).coerceAtLeast(1f)
    // The width always fits; the height only down to a readable floor, then the canvas scrolls.
    val fitScale =
      minOf(
          workspaceWidth.value / pairWidth,
          maxOf(
            minOf(
              fitHeight / frameHeightDp,
              if (showPopOut) {
                (fitHeight - VARIANT_LABEL_ROOM_DP) / (popOutTopDp + popOutHeightDp)
              } else Float.MAX_VALUE,
            ),
            FIT_HEIGHT_FLOOR,
          ),
        )
        .coerceIn(MIN_CANVAS_ZOOM, MAX_CANVAS_ZOOM)
    val scale = zoom ?: fitScale
    SideEffect { onScaleDrawn(scale) }
    // Undo the density ratio the frame was sized up by. Equals [scale] when the densities agree.
    val drawScale = scale / densityRatio
    // In dp, as the metrics callback reports.
    var measuredDp by remember(document.id) { mutableStateOf(0 to 0) }
    // Reported on every change of either, not just on a resize: the frame's own size does not move
    // when somebody zooms, and the drop hit-test reads this scale.
    LaunchedEffect(measuredDp, scale) {
      if (measuredDp != 0 to 0) onCanvasMetrics(measuredDp.first, measuredDp.second, scale)
    }
    // The frame's own rectangle in the window, kept because the inspection answers in that space
    // and a press on the canvas arrives in the frame's.
    var frameBounds by remember(document.id) { mutableStateOf(Rect.Zero) }
    // The frame's unclipped top-left in root space. `frameBounds` is clipped to the viewport;
    // frame-local conversions need the unclipped `positionInRoot`, which is current after a scroll.
    var frameOrigin by remember(document.id) { mutableStateOf(Offset.Zero) }
    // The mouse over the frame, for the Alt measure overlay; never written by a touch.
    val measureHover = remember(document.id) { MeasureHoverState() }
    // The workspace's own rectangle, so a node's root-space box can be turned into an offset in
    // this box — which is where the hover editor is placed.
    var workspaceBounds by remember(document.id) { mutableStateOf(Rect.Zero) }
    // Where a right-click landed, in frame pixels, or null while no menu is open. Out here because
    // resize handles over the frame also take secondary clicks.
    var menuAt by remember(document.id) { mutableStateOf<Offset?>(null) }
    // The last menu position, used while the menu animates closed after `menuAt` clears.
    var menuShownAt by remember(document.id) { mutableStateOf(Offset.Zero) }
    // A secondary click at [rootPoint]: select the node under it and open its menu there.
    fun openNodeMenu(rootPoint: Offset) {
      if (!showSelectionOverlay) return
      // Smallest reported node box containing the point: the deepest node under the pointer.
      val hit =
        inspection
          ?.nodes
          .orEmpty()
          .mapNotNull { node -> node.bounds?.let { node.nodeId to it } }
          .filter { (_, bounds) ->
            rootPoint.x >= bounds.x &&
              rootPoint.x <= bounds.x + bounds.width &&
              rootPoint.y >= bounds.y &&
              rootPoint.y <= bounds.y + bounds.height
          }
          .minByOrNull { (_, bounds) -> bounds.width * bounds.height }
          ?.first
      // Before the first inspection arrives, fall back to the selection so right-click is not dead.
      val menuNode = hit ?: selectedNodeId ?: return
      if (menuNode != selectedNodeId) onNodeSelected(menuNode)
      // Back into the frame's own pixels, where the menu is anchored.
      val local =
        if (drawScale > 0f) (rootPoint - frameOrigin) / drawScale else rootPoint - frameOrigin
      menuAt = local
      menuShownAt = local
    }
    // What is drawn of the design — the frame and its extent companion — so the quick editor can
    // open in the empty workspace beside it rather than over it.
    var designsBounds by remember(document.id) { mutableStateOf(Rect.Zero) }
    // Where the author dragged the quick editor to, in the workspace's dp; null until they do. Kept
    // across selections and reopenings: a card somebody put somewhere should stay put.
    var hoverEditorMoved by remember { mutableStateOf<DpOffset?>(null) }
    val dismissHoverEditor = rememberUpdatedState(onHoverEditorDismiss)
    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberScrollState()
    // Auto-scroll while dragging near an edge, so off-screen slots are reachable. Restarts on every
    // pointer move and steps a frame at a time until the pointer leaves the band or the edge is
    // exhausted (keeping the test clock idle).
    val currentDragPosition = rememberUpdatedState(dragPosition)
    val currentWorkspaceBounds = rememberUpdatedState(workspaceBounds)
    // Report the scroll offset: node boxes are in unshifted layout space while pointers arrive
    // shifted, and scrolling does not re-fire position callbacks.
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
    // A requested zoom and the design point to keep under the hand, anchored on the frame's real
    // origin (a centred small design's margin is not scroll). Applied after the new scale is laid
    // out.
    var zoomAnchor by remember(document.id) { mutableStateOf<ZoomAnchor?>(null) }
    LaunchedEffect(scale) {
      val anchor = zoomAnchor ?: return@LaunchedEffect
      if (anchor.scale != scale) return@LaunchedEffect
      // One frame to compose and lay out the new scale, one more for its position to land.
      withFrameNanos {}
      withFrameNanos {}
      val drift = anchorDrift(frameOrigin, anchor.designPoint, scale, anchor.focus)
      horizontalScrollState.dispatchRawDelta(drift.x)
      verticalScrollState.dispatchRawDelta(drift.y)
      zoomAnchor = null
    }
    // The node under a press, asked at press time (see [onDoubleClick]). Through updated state
    // because the gesture outlives the composition.
    val nodeUnderPress = rememberUpdatedState { position: Offset ->
      if (!showSelectionOverlay) return@rememberUpdatedState null
      val point = workspaceBounds.topLeft + position
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
    }
    val doubleClicked = rememberUpdatedState(onNodeDoubleClicked)
    Box(
      Modifier.fillMaxSize()
        .onGloballyPositioned {
          workspaceBounds = it.boundsInRoot()
          onWorkspaceBounds(workspaceBounds)
        }
        // Here rather than on the frame, because the frame moves between the two clicks when the
        // first opens a panel; the workspace does not.
        .onDoubleClick(
          document.id,
          capture = { position -> nodeUnderPress.value(position) },
          onDoubleClick = { nodeId -> doubleClicked.value(nodeId) },
        )
        // Clicking elsewhere closes the quick editor. Observed in the initial pass, never consumed;
        // the card is a sibling, so presses on it never arrive here.
        .pointerInput(hoverEditor != null) {
          if (hoverEditor == null) return@pointerInput
          awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            dismissHoverEditor.value()
          }
        }
        // Outside the scrolls so it reads workspace positions and sees a pinch before the scroll
        // takes a finger.
        .canvasZoomGestures(
          scale = zoomAnchor?.scale ?: scale,
          onZoom = { request ->
            val focus = workspaceBounds.topLeft + request.focus
            zoomAnchor =
              ZoomAnchor(
                scale = request.scale,
                focus = focus,
                // A burst keeps the point it started on: the frame has not moved yet, so reading
                // it again would anchor on where the design was, not where it is going.
                designPoint = zoomAnchor?.designPoint ?: ((focus - frameOrigin) / scale),
              )
            onZoomChanged(request.scale)
          },
          onPan = { pan ->
            // Mid-zoom the anchor owns the scroll, so the pan moves the point it has to land under;
            // otherwise the pan is the scroll.
            val pending = zoomAnchor
            if (pending != null) {
              zoomAnchor = pending.copy(focus = pending.focus + pan)
            } else {
              horizontalScrollState.dispatchRawDelta(-pan.x)
              verticalScrollState.dispatchRawDelta(-pan.y)
            }
          },
        )
        .horizontalScroll(horizontalScrollState)
        .verticalScroll(verticalScrollState)
    ) {
      // The scaled frame takes its drawn size (graphicsLayer scales painting, not layout), so
      // zooming past the workspace scrolls rather than clips.
      Box(
        // The band the zoom controls sit in is left out of where the frame is placed, and is
        // scroll room below a zoomed-in design, so its last row can be brought out from under them.
        Modifier.widthIn(min = workspaceWidth)
          .heightIn(min = workspaceHeight)
          .padding(bottom = ZOOM_CONTROLS_ROOM_DP.dp),
        contentAlignment = contentAlignment,
      ) {
        Row(
          Modifier.onGloballyPositioned { designsBounds = it.boundsInRoot() },
          horizontalArrangement = Arrangement.spacedBy((CANVAS_PANE_GAP_DP.value * scale).dp),
        ) {
          Box(Modifier.size((sourceWidth * scale).dp, (frameHeightDp * scale).dp)) {
            Surface(
              Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                // The extent: frame width, content height, never shorter than the frame. The device
                // view uses the frame size and the pop-out for the rest.
                .requiredWidth((sourceWidth * densityRatio).dp)
                .then(
                  if (deviceView) Modifier.requiredHeight((sourceHeight * densityRatio).dp)
                  else Modifier.requiredHeightIn(min = (sourceHeight * densityRatio).dp)
                )
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
                  // A runtime renderer's pixels show through a `BlendMode.Clear` hole into a DOM
                  // layer below; an offscreen layer would clear its own buffer instead.
                  compositingStrategy =
                    if (canvasRenderer == null) CompositingStrategy.Offscreen
                    else CompositingStrategy.Auto
                }
                .onGloballyPositioned {
                  frameBounds = it.boundsInRoot()
                  frameOrigin = it.positionInRoot()
                  frameRootBounds = it.unclippedRootBounds()
                  onFrameRootBounds(frameRootBounds)
                  onCanvasBounds(frameBounds)
                }
                .then(
                  if (dropHovered) Modifier.border(1.dp, MaterialTheme.colorScheme.primary)
                  else Modifier
                ),
              shape = if (roundFrame) CircleShape else RoundedCornerShape(0.dp),
              color =
                if (canvasRenderer == null) MaterialTheme.colorScheme.surface
                else Color.Transparent,
              shadowElevation = 0.dp,
            ) {
              CanvasExtentLayout(
                Modifier.fillMaxSize()
                  .measureHover(measureHover)
                  .canvasNodeDrag(
                    key = document.id,
                    enabled = moveDragEnabled && showSelectionOverlay,
                    // Presses arrive in frame space; convert like the secondary click and drop
                    // hit-test.
                    rootPoint = { position ->
                      Offset(
                        frameOrigin.x + position.x * drawScale,
                        frameOrigin.y + position.y * drawScale,
                      )
                    },
                    // Inside the selection, drag the selection, so a container can be moved by more
                    // than its padding.
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
                    // Otherwise the smallest containing box, the same answer tap and context menu
                    // give.
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
                    // Convert from frame pixels via [drawScale] from the unclipped origin.
                    openNodeMenu(
                      Offset(
                        frameOrigin.x + position.x * drawScale,
                        frameOrigin.y + position.y * drawScale,
                      )
                    )
                  },
                // A runtime surface is as tall as its last measurement; the extent follows it.
                probeKey = if (canvasRenderer != null) frameHeightDp else null,
              ) {
                Box {
                  LocalUiBuilderChrome.current.PopupMenu(
                    expanded = menuAt != null,
                    onDismissRequest = { menuAt = null },
                    entries = selectionMenu { menuAt = null },
                    offset =
                      with(density) {
                        DpOffset(
                          (menuShownAt.x * drawScale).toDp(),
                          (menuShownAt.y * drawScale).toDp(),
                        )
                      },
                  )
                }
                if (canvasRenderer == null) {
                  CompositionLocalProvider(LocalUiBuilderOverlayPassesInput provides deviceView) {
                    UiBuilderSurface(
                      document = document,
                      editorOverlay = showSelectionOverlay,
                      selectedNodeId = selectedNodeId,
                      onNodeSelected = onNodeSelected,
                      // The extent is a proxy: lists unrolled and scrolling dropped so long lists
                      // are drawn and edited whole (Compose cannot measure a scrollable against
                      // unbounded height). The device view is the real composition.
                      unrolled = !deviceView,
                      onInspectionSnapshot = { snapshot ->
                        inspection = snapshot
                        onInspectionSnapshot?.invoke(snapshot)
                      },
                      onInspectionInvalidated = onInspectionInvalidated,
                    )
                  }
                } else {
                  // The runtime's device view: the real composition at the frame, under the editor
                  // so clicks select; the selection is sent to reveal, and list wheels are
                  // forwarded as `scrollBy`.
                  Box(
                    Modifier.requiredSize(
                        (sourceWidth * densityRatio).dp,
                        (frameHeightDp * densityRatio).dp,
                      )
                      .then(
                        if (!deviceView) Modifier
                        else
                          Modifier.pointerInput(document, inspection) {
                            awaitPointerEventScope {
                              while (true) {
                                val event = awaitPointerEvent()
                                if (event.type != PointerEventType.Scroll) continue
                                val change = event.changes.firstOrNull() ?: continue
                                if (change.isConsumed || change.scrollDelta.y == 0f) continue
                                val point =
                                  Offset(
                                    frameOrigin.x + change.position.x * drawScale,
                                    frameOrigin.y + change.position.y * drawScale,
                                  )
                                val target =
                                  inspection
                                    ?.nodes
                                    .orEmpty()
                                    .filter { document.isVerticalScroller(it.nodeId, scrollers) }
                                    .mapNotNull { node -> node.bounds?.let { node.nodeId to it } }
                                    .filter { (_, bounds) ->
                                      point.x >= bounds.x &&
                                        point.x <= bounds.right &&
                                        point.y >= bounds.y &&
                                        point.y <= bounds.bottom
                                    }
                                    .minByOrNull { (_, bounds) -> bounds.width * bounds.height }
                                    ?.first ?: continue
                                change.consume()
                                runtimeScroll =
                                  UiBuilderCanvasScroll(
                                    nodeId = target,
                                    // On the web the wheel delta is already CSS pixels (one editor
                                    // dp), so it is only converted, never multiplied by a per-notch
                                    // step.
                                    deltaY =
                                      change.scrollDelta.y *
                                        document.renderDensity(density).density,
                                    sequence = (runtimeScroll?.sequence ?: 0) + 1,
                                  )
                              }
                            }
                          }
                      )
                  ) {
                    canvasRenderer(
                      document.withWearWidgetHostShape(LocalWearWidgetHostShape.current),
                      UiBuilderCanvasSurface(
                        sourceWidth,
                        frameHeightDp,
                        document.renderDensity(density).density,
                        if (deviceView) UiBuilderRendererSurfaceModeV2.DEVICE
                        else UiBuilderRendererSurfaceModeV2.AUTHORING_UNROLLED,
                        horizontalScrollState.value * 31 + verticalScrollState.value,
                        interactive = !deviceView,
                        revealNodeId = if (deviceView) selectedNodeId else null,
                        scroll = if (deviceView) runtimeScroll else null,
                      ),
                      selectedNodeId,
                      showSelectionOverlay,
                      onNodeSelected,
                    ) { snapshots ->
                      val snapshot = snapshots.editor
                      inspection = snapshot
                      runtimeCapabilities = snapshots.capabilities
                      if (!deviceView) {
                        val measuredBottom =
                          snapshots.renderer.nodes.mapNotNull { it.bounds?.bottom }.maxOrNull()
                            ?: 0f
                        val measuredHeightDp =
                          measuredBottom / document.renderDensity(density).density
                        if (measuredHeightDp > expandedHeightDp) {
                          expandedHeightDp = measuredHeightDp
                        }
                      }
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
                // Over the document (the reference is compared against it) and under collaborators'
                // selections.
                ReferenceOverlayCanvas(
                  reference,
                  onMarkDrawn,
                  onPieceMoved,
                  facts = referenceFacts,
                  findings = referenceFindings,
                )
                RemotePresenceOverlay(collaborators, inspection, frameOrigin, drawScale)
                // Over the design and collaborators, under the comment pins.
                GuidelineFindingsOverlay(inspection, frameOrigin, drawScale, onNodeSelected)
                // Alt-hover redlines: over the design and its markers, under the pins. Not while a
                // drag is in hand — Alt there means nothing, and the drop marker is what to read.
                if (showSelectionOverlay && dragPosition == null) {
                  SpacingMeasureOverlay(
                    inspection = inspection,
                    selectedNodeId = selectedNodeId,
                    parentNodeId = selectedNodeId?.let { document.location(it)?.nodeId },
                    hover = measureHover,
                    frameOrigin = frameOrigin,
                    drawScale = drawScale,
                    // Root pixels per design dp, as the resize handles count them.
                    pxPerDp = density.density * scale,
                  )
                }
                // Above everything: pins are the clickable non-design element and must not hide
                // under a mock or a selection outline.
                CommentPinOverlay(
                  threads = commentThreads,
                  marks = reference.marks,
                  selectedThreadId = selectedThreadId,
                  onSelect = onCommentThreadSelected,
                )
              }
            }
          }
          // The companion shows the frame view of a design that outgrows it; skipped when another
          // pane already shows the frame.
          if (overflowsFrame && frameCompanion) {
            ConstrainedFramePane(
              document = document,
              widthDp = sourceWidth,
              heightDp = sourceHeight,
              scale = scale,
              densityRatio = densityRatio,
            )
          }
          if (showPopOut) {
            if (canvasRenderer == null) {
              UnrolledContainerPopOut(
                document = document,
                container = popOut!!,
                label = nodeLabel(popOut.nodeId),
                crossDp = popOutCrossDp!!,
                extentDp = popOutExtentDp,
                scale = scale,
                densityRatio = densityRatio,
                // The label sits above the pop-out, so it is lifted by the label's room to keep the
                // drawn box itself level with the container.
                topOffset = (popOutTopDp * scale - VARIANT_LABEL_ROOM_DP).coerceAtLeast(0f).dp,
                selectedNodeId = selectedNodeId,
                onNodeSelected = onNodeSelected,
                onExtentMeasured = { popOutExtent = popOut.nodeId to it },
                onRootBounds = { popOutRootBounds = it },
              )
            } else {
              RuntimeContainerPopOut(
                document = document,
                container = popOut!!,
                label = nodeLabel(popOut.nodeId),
                crossDp = popOutCrossDp!!,
                extentDp = popOutExtentDp,
                scale = scale,
                densityRatio = densityRatio,
                // The label sits above the pop-out, so it is lifted by the label's room to keep the
                // drawn box itself level with the container.
                topOffset = (popOutTopDp * scale - VARIANT_LABEL_ROOM_DP).coerceAtLeast(0f).dp,
                selectedNodeId = selectedNodeId,
                onNodeSelected = onNodeSelected,
                onExtentMeasured = { popOutExtent = popOut.nodeId to it },
                onRootBounds = { popOutRootBounds = it },
                canvasRenderer = canvasRenderer!!,
              )
            }
          }
        }
      }
    }
    if (showPopOut) {
      PopOutConnectors(
        container = popOutBounds?.let { Rect(it.x, it.y, it.right, it.bottom) },
        frame = frameRootBounds,
        popOut = popOutRootBounds,
        modifier = Modifier.matchParentSize(),
      )
    }
    // Beside the design rather than over it — see [hoverEditorPlacement] — and outside the scaled
    // frame so the type stays the size it was designed at however far the design is zoomed out.
    val selectedBounds = selectedNodeId?.let { id ->
      inspection?.nodes?.firstOrNull { it.nodeId == id }?.bounds
    }
    // A handle in hand hides the hover editor for the reason a drag does: it answers the previous
    // question while the pointer is asking the next one.
    var resizing by remember(document.id) { mutableStateOf(false) }
    // Not while dragging: the quick editor follows the selection, while a drag is about the target.
    if (
      hoverEditor != null &&
        showSelectionOverlay &&
        selectedBounds != null &&
        dragPosition == null &&
        !resizing
    ) {
      // The window either side of this pane, which the card may use when the pane cannot hold it
      // beside the design.
      val windowWidthPx = LocalWindowInfo.current.containerSize.width.toFloat()
      val roomLeft = with(density) { workspaceBounds.left.coerceAtLeast(0f).toDp() }
      val roomRight =
        with(density) { (windowWidthPx - workspaceBounds.right).coerceAtLeast(0f).toDp() }
      val placed =
        hoverEditorPlacement(
          workspace = workspaceBounds,
          designs = designsBounds,
          selected = selectedBounds,
          density = density,
          workspaceWidth = workspaceWidth,
          workspaceHeight = workspaceHeight,
          roomLeft = roomLeft,
          roomRight = roomRight,
        )
      val minX = -roomLeft
      val maxX = (workspaceWidth + roomRight - HOVER_EDITOR_WIDTH).coerceAtLeast(minX)
      val maxY = (workspaceHeight - HOVER_EDITOR_GRIP).coerceAtLeast(0.dp)
      val position =
        (hoverEditorMoved ?: placed).let {
          DpOffset(it.x.coerceIn(minX, maxX), it.y.coerceIn(0.dp, maxY))
        }
      val currentPosition = rememberUpdatedState(position)
      // A popup so the pane's clip does not cut the card off. Not focusable: editor keys stay the
      // editor's, though fields still take the caret on click.
      Box(Modifier.align(Alignment.TopStart)) {
        Popup(
          offset = with(density) { IntOffset(position.x.roundToPx(), position.y.roundToPx()) },
          onDismissRequest = if (hoverEditorFocusable) dismissHoverEditor.value else null,
          properties = PopupProperties(focusable = hoverEditorFocusable, clippingEnabled = false),
        ) {
          Box(Modifier.width(HOVER_EDITOR_WIDTH)) {
            hoverEditor(
              Modifier.pointerHoverIcon(PointerIcon.Hand).pointerInput(Unit) {
                detectDragGestures { change, drag ->
                  change.consume()
                  val from = currentPosition.value
                  hoverEditorMoved =
                    DpOffset(
                      (from.x + drag.x.toDp()).coerceIn(minX, maxX),
                      (from.y + drag.y.toDp()).coerceIn(0.dp, maxY),
                    )
                }
              }
            )
          }
        }
      }
    }
    // Over the node it edits, at least wide enough to type in: a one-letter label is a narrow box.
    val inlineBounds = inlineTextEdit?.let { edit ->
      inspection?.nodes?.firstOrNull { it.nodeId == edit.nodeId }?.bounds
    }
    if (inlineTextEdit != null && inlineBounds != null) {
      with(density) {
        InlineTextEditor(
          edit = inlineTextEdit,
          onDone = onInlineTextDone,
          onTextInputFocusChanged = onTextInputFocusChanged,
          modifier =
            Modifier.align(Alignment.TopStart)
              .offset(
                x = (inlineBounds.x - workspaceBounds.left - 4.dp.toPx()).coerceAtLeast(0f).toDp(),
                y = (inlineBounds.y - workspaceBounds.top - 4.dp.toPx()).coerceAtLeast(0f).toDp(),
              )
              .widthIn(min = maxOf(inlineBounds.width.toDp() + 8.dp, INLINE_TEXT_MIN_WIDTH)),
        )
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
      // Fill reaches the parent's box minus its padding.
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
        // Its own scale and every ancestor's.
        nodeScale =
          document.nodes[sizing.nodeId]?.modifiers.orEmpty().let { chain ->
            drawnScale(chain, EditorAxis.Width) *
              document.ancestorScale(sizing.nodeId, EditorAxis.Width) to
              drawnScale(chain, EditorAxis.Height) *
                document.ancestorScale(sizing.nodeId, EditorAxis.Height)
          },
        onResizing = { resizing = it },
        onResize = { width, height -> onResize(sizing.nodeId, width, height) },
        // A handle's target is wider than the dot, and on a short node it covers the middle: a
        // right-click there is still a right-click on the node, not on the handle.
        onSecondaryClick = ::openNodeMenu,
        modifier = Modifier.matchParentSize().clipToBounds(),
      )
    }
    if (dragPosition != null) {
      // The ghost always follows the pointer; the marker alone answers whether it can land. This
      // state resets per drag, not per pointer move.
      var ghostContentBounds by
        remember(document.id) { mutableStateOf<UiBuilderPixelBounds?>(null) }
      // Capped by the landing slot, in the ghost's pixels via the design density (which the ghost
      // does not carry; see [dragGhostDocument]).
      val landing = dropPlan?.bounds
      val ghostConstraints = landing?.let {
        Modifier.sizeIn(
          maxWidth = with(density) { (it.width / densityRatio).toDp() },
          maxHeight = with(density) { (it.height / densityRatio).toDp() },
        )
      }
      // Anchored on the ghost's content, drawn empty until measured once. Uses the content's size
      // only: its reported position includes this offset and would make the layout oscillate.
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
      deviceView = if (deviceViewOffered && onCanvasViewChanged != null) deviceView else null,
      onDeviceViewChanged = { device ->
        onCanvasViewChanged?.invoke(
          if (device) EditorCanvasView.Device else EditorCanvasView.Extent
        )
      },
      modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
    )
  }
}

/**
 * The empty recommended slots, drawn as dashed "a component goes here" regions. The reducer
 * computes one region per slot, which both the drawing and the drop hit-test read, so what is seen
 * is what a drop lands in. Shown only while dragging.
 *
 * The slot name is drawn only where it fits. Strokes and type are screen-sized, scaled back up by
 * the frame's scale factor, so they stay legible when zoomed out.
 */
@Composable
private fun SlotPlaceholderOverlay(
  placeholders: List<UiBuilderSlotPlaceholder>,
  frameOrigin: Offset,
  drawScale: Float,
) {
  if (placeholders.isEmpty()) return
  val color = MaterialTheme.colorScheme.primary
  val labelStyle = MaterialTheme.typography.labelSmall
  val textMeasurer = rememberTextMeasurer()
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
        color = color.copy(alpha = 0.12f),
        topLeft = Offset(local.x, local.y),
        size = Size(local.width, local.height),
        cornerRadius = CornerRadius(stroke * 4f),
      )
      drawRoundRect(
        color = color.copy(alpha = 0.8f),
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
    // The chip's screen size against the region's: the chip is scaled back to screen size below,
    // and the region's bounds are already in screen pixels.
    val text = textMeasurer.measure(placeholder.target.slot, labelStyle, maxLines = 1).size
    val chip = with(density) { Size(text.width + 12.dp.toPx(), text.height + 4.dp.toPx()) }
    if (chip.width > bounds.width || chip.height > bounds.height) return@forEach
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
 * Where a drag would land: the entered slot is tinted, and the seam between the children it lands
 * between is drawn as a bar. Empty slots keep the full highlight.
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
  // Screen-width strokes, divided by the draw scale, so the marker stays visible when zoomed out.
  val hairline = screenStroke(3.5f, drawScale)
  val glow = screenStroke(10f, drawScale)
  Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
    // Dim tint so the seam reads as figure and the slot as ground.
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
 * Where the node a canvas move picked up still sits, dashed, so the original and its ghost are
 * distinguishable. Dropping there is a no-op.
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
 * The dragged thing itself, travelling with the pointer at the size it would land: the component
 * (or moved subtree) in an unconstrained cell, scaled by the zoom and capped by the landing slot.
 *
 * [onContentBounds] reports where the root drew inside the surface, so the canvas can anchor the
 * content (not the cell) on the pointer; until measured the ghost is drawn empty.
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
      // In-process, never the pinned runtime: that is a sandboxed iframe booting its own Wasm,
      // and a drag would have started one on every pick-up just to draw the thing in the air.
      UiBuilderSurface(
        document = document,
        editorOverlay = false,
        // The same answer the editing surface gives: a list in the air is drawn unrolled, which
        // is what will land on the extent — not a clipped scroll nobody is dropping.
        unrolled = true,
        onInspectionSnapshot = inspection,
      )
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
 * The design at its frame beside the extent: read-only, scrollable, showing what fits on the device
 * (e.g. the fold), while the extent is the editing surface.
 *
 * Has its own [renderSessionId] because [UiBuilderSurface] keys bounds, overlays and inspection on
 * it; sharing the editing pane's would mix their geometry.
 */
@Composable
internal fun ConstrainedFramePane(
  document: UiBuilderDocument,
  widthDp: Float,
  heightDp: Float,
  scale: Float,
  /** The design's pixels per workspace pixel — see the same value in [PinnedDesignCanvas]. */
  densityRatio: Float,
  /** Distinct per pane, for the reason the function doc gives. */
  renderSessionId: String = FRAME_COMPANION_SESSION,
  wearWidgetHostShape: WearWidgetHostShape? = null,
  /**
   * The catalog's pinned runtime, for a device preview pane only. Otherwise catalogs the in-process
   * renderer cannot draw (remote-m3) show labelled stand-ins.
   */
  deviceRenderer: UiBuilderCanvasRenderer? = null,
  /** Bumped when whatever scrolls this pane moves, so a runtime frame is placed again. */
  positionVersion: Int = 0,
) {
  // **Read in the editor's composition, never inside the scene**: a scene starts with no
  // `CompositionLocal`s, so reading `.current` there yields defaults (frame geometry, ids,
  // adapters, assets were all lost that way).
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
  // The design's typefaces. Loaded names re-key the scene, since a family arriving after it settles
  // would not be picked up.
  val fontRegistry = LocalUiBuilderFontRegistry.current
  val fontFamilies = LocalUiBuilderFontFamilies.current
  val loadedFontNames = fontFamilies.keys.toSet()
  Box(Modifier.size((widthDp * scale).dp, (heightDp * scale).dp)) {
    Surface(
      Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
        // The device's frame in the design's own pixels, like the extent beside it: this pane
        // exists to say what a device shows, and it can only say it at the density the device has.
        .requiredSize((widthDp * densityRatio).dp, (heightDp * densityRatio).dp)
        .graphicsLayer {
          scaleX = scale / densityRatio
          scaleY = scale / densityRatio
          transformOrigin = TransformOrigin(0f, 0f)
          // Clip at the device edge, inside the layer so the clip scales with the frame.
          clip = true
          // Not offscreen under the runtime, for the editing canvas's reason: the `BlendMode.Clear`
          // hole would be cleared in the buffer instead.
          compositingStrategy =
            if (deviceRenderer == null) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        },
      shape = RoundedCornerShape(0.dp),
      color = if (deviceRenderer == null) MaterialTheme.colorScheme.surface else Color.Transparent,
      shadowElevation = 0.dp,
    ) {
      // The real composition: lazy lists and the design's own scrolling, at a live position. No
      // outer scroll, since that would measure against unbounded height, and content that overflows
      // without its own scrollable overflows on the device too.
      // **In its own scene** so the design can be driven (a wheel arrives as a rotary side button;
      // see [DeviceSceneHost]); locals are carried by value. In-process unless the pane gets the
      // catalog's pinned runtime, which a device pane uses because in-process remote-m3 draws only
      // stand-ins. One frame per device, kept alive across edits.
      if (deviceRenderer != null) {
        deviceRenderer(
          document.withWearWidgetHostShape(wearWidgetHostShape ?: ambientWidgetHostShape),
          UiBuilderCanvasSurface(
            widthDp,
            heightDp,
            document.renderDensity(LocalDensity.current).density,
            UiBuilderRendererSurfaceModeV2.DEVICE,
            positionVersion,
          ),
          null,
          false,
          {},
          {},
        )
      } else
        DeviceSceneHost(
          key = "$renderSessionId:${document.id}:$widthDp:$heightDp",
          contentKey = document to loadedFontNames,
          // The Surface is `widthDp * densityRatio` dp wide, so the scene is that times the host
          // density in pixels.
          sizePx =
            IntSize(
              (widthDp * densityRatio * LocalDensity.current.density).roundToInt(),
              (heightDp * densityRatio * LocalDensity.current.density).roundToInt(),
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
              LocalUiBuilderFontRegistry provides fontRegistry,
              LocalUiBuilderFontFamilies provides fontFamilies,
              LocalUiBuilderFontVariants provides fontRegistry,
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

/**
 * The first length a runtime pop-out is drawn at before measurement. Longer content is found by
 * filling and doubling; shorter is trimmed.
 */
internal const val RUNTIME_POP_OUT_BUDGET_DP = 2000f

/** Keeps the companion's remembered geometry out of the editing pane's. */
private const val FRAME_COMPANION_SESSION = "frame-companion"

/** Canvas dp between the extent and the frame beside it. */
private val CANVAS_PANE_GAP_DP = 24.dp

/**
 * One read-only pane of the variant strip: its label and the design under that frame. The label
 * sits outside the scaled frame so it stays legible at any zoom.
 */
@Composable
internal fun VariantPane(
  pane: UiBuilderVariantPane,
  scale: Float,
  hostDensity: Density,
  deviceRenderer: UiBuilderCanvasRenderer? = null,
  positionVersion: Int = 0,
) {
  if (pane.launcherGridResizable) {
    LauncherWidgetResizablePane(pane, scale, hostDensity, deviceRenderer, positionVersion)
    return
  }
  val host = pane.wearHostFrame
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
      pane.label,
      Modifier.height(VARIANT_LABEL_ROOM_DP.dp).widthIn(max = (pane.widthDp * scale).dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (host != null) {
      Box(
        Modifier.size((pane.widthDp * scale).dp, (pane.heightDp * scale).dp)
          .wearHostFrame(host, scale)
      ) {
        ConstrainedFramePane(
          document = pane.document,
          widthDp = host.contentWidthDp.toFloat(),
          heightDp = host.contentHeightDp.toFloat(),
          scale = scale,
          densityRatio = pane.document.renderDensity(hostDensity).density / hostDensity.density,
          renderSessionId = pane.id,
          wearWidgetHostShape = pane.wearWidgetHostShape,
          deviceRenderer = deviceRenderer,
          positionVersion = positionVersion,
        )
      }
      return@Column
    }
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
      deviceRenderer = deviceRenderer,
      positionVersion = positionVersion,
    )
  }
}

/** What a Wear widget host paints behind a widget that declares no background of its own. */

/** Room above a variant pane for its label, in canvas dp. */
internal const val VARIANT_LABEL_ROOM_DP = 18f

/** The workspace's bottom band the zoom controls are drawn over: their 12dp inset and their row. */
internal const val ZOOM_CONTROLS_ROOM_DP = 64f

/** How wide the editor that follows the selection is, and how much room it needs under a node. */
internal val HOVER_EDITOR_WIDTH = 268.dp

private val HOVER_EDITOR_ROOM = 148.dp

/** How much of a dragged quick editor has to stay in the workspace: its title row, the grip. */
private val HOVER_EDITOR_GRIP = 32.dp

/** Space between the design and a quick editor opened beside it. */
private val HOVER_EDITOR_GAP = 16.dp

/** The narrowest an in-place text field gets, so a short label still leaves room to type. */
private val INLINE_TEXT_MIN_WIDTH = 160.dp

/**
 * Where the quick editor opens, in workspace dp: beside the design, never over it. Right of the
 * drawing, then left, level with the selected node; then the same places over neighbouring panes;
 * only as a last resort below or above the node, where it can be dragged aside.
 */
internal fun hoverEditorPlacement(
  workspace: Rect,
  designs: Rect,
  selected: UiBuilderPixelBounds,
  density: Density,
  workspaceWidth: Dp,
  workspaceHeight: Dp,
  /**
   * How far the window reaches past the workspace's left and right edges; the card is a popup so it
   * may sit over a neighbouring pane.
   */
  roomLeft: Dp = 0.dp,
  roomRight: Dp = 0.dp,
): DpOffset =
  with(density) {
    val top =
      (selected.y - workspace.top)
        .toDp()
        .coerceIn(0.dp, (workspaceHeight - HOVER_EDITOR_ROOM).coerceAtLeast(0.dp))
    if (designs != Rect.Zero) {
      val right = (designs.right - workspace.left).toDp() + HOVER_EDITOR_GAP
      val left = (designs.left - workspace.left).toDp() - HOVER_EDITOR_GAP - HOVER_EDITOR_WIDTH
      // Inside the canvas first, either side; only then out over whatever is beside it.
      if (right + HOVER_EDITOR_WIDTH <= workspaceWidth) return DpOffset(right, top)
      if (left >= 0.dp) return DpOffset(left, top)
      if (right + HOVER_EDITOR_WIDTH <= workspaceWidth + roomRight) return DpOffset(right, top)
      if (left >= -roomLeft) return DpOffset(left, top)
    }
    val x = (selected.x - workspace.left).coerceAtLeast(0f).toDp()
    val below = selected.y + selected.height - workspace.top + 8f
    val above = selected.y - workspace.top - 8f
    val roomBelow = (workspace.height - below).toDp() > HOVER_EDITOR_ROOM
    DpOffset(
      x,
      if (roomBelow) below.toDp() else (above.toDp() - HOVER_EDITOR_ROOM).coerceAtLeast(0.dp),
    )
  }

/**
 * The field a just-added modifier focuses. Only modifiers whose menu row picks a starting value
 * (`padding` 16, `weight` 1); fills and alignments have nothing to type.
 */
internal val MODIFIER_FOCUS_FIELDS = mapOf("padding" to "startDp", "weight" to "weight")

/** The zoom ladder the two step controls walk, in the order a designer expects to land on. */
private val CANVAS_ZOOM_STOPS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

internal const val MIN_CANVAS_ZOOM = 0.1f

/**
 * The least Fit shrinks a design to fit its height; below it the frame scrolls vertically. The
 * width always fits.
 */
internal const val FIT_HEIGHT_FLOOR = 0.75f

internal const val MAX_CANVAS_ZOOM = 4f

/**
 * The next stop above or below [from], stepping from the current drawn scale rather than the last
 * button press.
 */
internal fun canvasZoomStep(from: Float, zoomIn: Boolean): Float =
  if (zoomIn) CANVAS_ZOOM_STOPS.firstOrNull { it > from + 0.001f } ?: MAX_CANVAS_ZOOM
  else CANVAS_ZOOM_STOPS.lastOrNull { it < from - 0.001f } ?: MIN_CANVAS_ZOOM

/** A zoom the keyboard asks for, in the words of the zoom bar's own controls. */
internal enum class CanvasZoomCommand {
  In,
  Out,
  Fit,
  ActualSize,
}

/**
 * The pinned zoom after [command], or null to frame the design — what `canvasZoom` becomes.
 *
 * [drawn] is the scale the canvas is drawn at now, fitted or pinned, for the reason
 * [canvasZoomStep] gives: Ctrl/Cmd+= on a design framed at 62% lands on 75%, the same stop the zoom
 * bar's + would, rather than on a step from some scale nobody can see.
 */
internal fun canvasZoomAfter(command: CanvasZoomCommand, drawn: Float): Float? =
  when (command) {
    CanvasZoomCommand.In -> canvasZoomStep(drawn, zoomIn = true)
    CanvasZoomCommand.Out -> canvasZoomStep(drawn, zoomIn = false)
    CanvasZoomCommand.Fit -> null
    CanvasZoomCommand.ActualSize -> 1f
  }

/** How the design is scaled, said out loud: `Fit · 62%`, or `125%` once somebody has pinned one. */
internal fun canvasZoomLabel(scale: Float, fitting: Boolean): String {
  val percent = "${(scale * 100).roundToInt()}%"
  return if (fitting) "Fit · $percent" else percent
}

/**
 * Zoom out, current scale, zoom in, and fit. Pressing the percentage pins 100%; the fit toggle
 * returns.
 */
@Composable
private fun CanvasZoomControls(
  scale: Float,
  fitting: Boolean,
  onZoomChanged: (Float?) -> Unit,
  /** Whether the device view is on, or null where it is not offered. */
  deviceView: Boolean? = null,
  onDeviceViewChanged: (Boolean) -> Unit = {},
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
      // Beside the zoom because it is the same kind of question — how the design is framed — and
      // like the zoom it changes nothing anybody else sees.
      if (deviceView != null) {
        ToolbarToggleAction("Device view", UiBuilderChromeIcon.Screen, deviceView) {
          onDeviceViewChanged(!deviceView)
        }
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
        // Convert the collaborator's root-space box into the frame, like the drop marker.
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
