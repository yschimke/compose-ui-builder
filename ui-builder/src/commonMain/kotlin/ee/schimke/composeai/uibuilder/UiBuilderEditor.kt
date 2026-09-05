@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import kotlin.math.roundToInt
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The inspector's width, named because two places have to agree on it.
 *
 * A default on [PropertyInspector] alone does nothing: the desktop layout passes its own modifier,
 * so widening the default for a fourth tab widened the preview and left every real editor at the
 * three-tab width. Four tabs share it, and the widest label has to stay legible.
 */
private val INSPECTOR_WIDTH = 360.dp

/**
 * How many pixels a flattened reference gets per dp of frame.
 *
 * Two, so a mark drawn against a 400 dp screen survives being drawn back over one — a flatten at 1×
 * loses a hairline stroke to rounding the first time it is re-fitted, and every round after that
 * loses a little more.
 */
private const val FLATTEN_SCALE = 2

private val EditorColors =
  darkColorScheme(
    background = Color(0xff121316),
    surface = Color(0xff1b1c20),
    surfaceVariant = Color(0xff282a30),
    primary = Color(0xffb9c3ff),
    onPrimary = Color(0xff17215b),
    outline = Color(0xff454750),
  )

private enum class MobileEditorPanel {
  None,
  Components,
  Properties,
  Code,
}

data class UiBuilderNewDesignTemplate(
  val id: String,
  val label: String,
  val supportingText: String,
)

data class UiBuilderNewDesignCatalog(
  val systemId: String,
  val label: String,
  val templates: List<UiBuilderNewDesignTemplate>,
)

/**
 * One render of the current design by real Compose on the host, as the editor needs it.
 *
 * An [ImageBitmap] rather than the bytes the route returns: decoding is the host's job, because
 * `wasmJs` and the JVM decode differently and neither belongs in an editor. [refusals] is not an
 * error state — a design the generator cannot express has no native render and the reasons are the
 * actionable half, exactly as in the code pane. [failure] is the transport failing, which is a
 * different sentence: try again versus fix the design.
 */
data class UiBuilderNativeRender(
  val image: ImageBitmap? = null,
  val refusals: List<String> = emptyList(),
  val failure: String? = null,
  /**
   * Design node id → the box it drew, in [image]'s own pixels.
   *
   * What turns the frame from a picture into a surface: the selected node is outlined in it, and a
   * click resolves to the smallest box containing the point. A node the host reports no box for —
   * one the render never placed — is simply not selectable there, which is the same answer the
   * inspection snapshot gives for a lazy slot that never composed.
   */
  val nodeBounds: Map<String, UiBuilderNativeNodeBounds> = emptyMap(),
)

/** One node's rectangle on a native frame, in that frame's pixels, origin at its top-left. */
data class UiBuilderNativeNodeBounds(
  val x: Int,
  val y: Int,
  val width: Int,
  val height: Int,
) {
  internal fun contains(px: Float, py: Float): Boolean =
    px >= x && py >= y && px < x + width && py < y + height

  internal val area: Long
    get() = width.toLong() * height.toLong()
}

@Composable
fun UiBuilderEditor(
  document: UiBuilderDocument,
  catalog: CapabilityCatalog,
  onStateChanged: (UiBuilderEditorState) -> Unit = {},
  onCanvasMetrics: (Int, Int, Float) -> Unit = { _, _, _ -> },
  onCanvasBoundsChanged: (Rect) -> Unit = {},
  onDropTargetChanged: (Boolean, String) -> Unit = { _, _ -> },
  onInspectionSnapshot: ((UiBuilderInspectionSnapshot) -> Unit)? = null,
  onInspectionInvalidated: ((UiBuilderInspectionCollector) -> Unit)? = null,
  showSelectionOverlay: Boolean = true,
  actorId: String = EDITOR_ACTOR_ID,
  clientId: String = EDITOR_CLIENT_ID,
  operationIdPrefix: String = clientId,
  sessionLabel: String = "Local session",
  onReconnect: (() -> Unit)? = null,
  onSubmission: ((EditorSubmission) -> Unit)? = null,
  authoritativeGeneration: Int = 0,
  initialSelectedNodeId: String? = null,
  initialCatalogQuery: String = "",
  initialLayerQuery: String = "",
  initialInspectorMode: EditorInspectorMode = EditorInspectorMode.Properties,
  initialPreviewMode: Boolean = false,
  initialCodePaneVisible: Boolean = false,
  /**
   * Asks the host to compile and render this design with real Compose, or null where it cannot.
   *
   * Null on a box with no compile lane, and in every preview and test — so the control is absent
   * rather than present and failing, which is the same rule the server applies to the route.
   */
  onRequestNativeRender: (suspend () -> UiBuilderNativeRender)? = null,
  /** A render already in hand, for the previews that draw this pane without a host. */
  initialNativeRender: UiBuilderNativeRender? = null,
  initialPreviewSurface: EditorPreviewSurface = EditorPreviewSurface.Wasm,
  collaborators: List<UiBuilderCollaborator> = emptyList(),
  /**
   * Device frames the Screen inspector offers, supplied by the host because `wasmJs` cannot resolve
   * the JVM-only render catalog they come from. Empty (the default) simply hides the menu and
   * leaves the raw fields, so a host that has no catalog to hand still gets a working inspector.
   */
  devicePresets: List<UiBuilderDevicePreset> = emptyList(),
  /**
   * A reference the host has loaded back from storage, or null while there is none.
   *
   * Applied through the reducer's own attach path rather than dropped into the state, so a stored
   * SVG gets its layout boxes read by the same code a fresh import goes through. Watched by
   * identity: the host may deliver it late (it arrives over HTTP, after the editor has mounted) and
   * may replace it, and neither should disturb an alignment the operator is in the middle of.
   */
  restoredReference: RestoredReference? = null,
  /**
   * Asks the host for a picture, or null where it cannot supply one.
   *
   * Everything unportable about importing lives behind this: opening a file picker, reading a
   * paste, sniffing the bytes, refusing what may not be attached, and minting the identity the
   * editor caches the decode against. Null in every preview and test, where the panel then offers
   * no import rather than an action that cannot work.
   */
  onPickReference: (suspend () -> ReferenceImportOutcome)? = null,
  /**
   * Renders the design as it stands and hands the pixels back, or null where the host cannot.
   *
   * The first move of the markup loop: snapshot what is there, mark up what is wrong, and build
   * against the annotated result. A host answers this from its export lane, which is the same
   * renderer the design's PNG export uses — so the snapshot is the design, not the editor chrome
   * around it.
   */
  onSnapshotDesign: (suspend () -> ReferenceImportOutcome)? = null,
  /**
   * A picture the host caught on the clipboard, or null when none has arrived.
   *
   * Pasting is the gesture a design tool leaves you holding — Figma's "copy as PNG" puts a frame or
   * a component straight onto the clipboard — so it lands without a menu. Where it lands depends on
   * what is already there: with nothing attached it becomes the reference, and over an existing
   * reference it becomes a piece to position, which is the only reading of "paste this component"
   * that does not throw away the mock it was going to be compared against.
   */
  pastedReference: ReferenceImage? = null,
  /** A sentence from the host — a refused paste, a store that would not keep it. */
  referenceStatus: String? = null,
  newDesignCatalogs: List<UiBuilderNewDesignCatalog> = emptyList(),
  onCreateDesign:
    ((
      catalogSystemId: String,
      designId: String,
      templateId: String,
      state: List<NewDesignState>,
    ) -> Unit)? =
    null,
  onHelp: (() -> Unit)? = null,
  /**
   * The published Remote Compose documents the pinned catalog offers as content, if any.
   *
   * Supplied by the host rather than read off [catalog], because they are the *serving* catalog's
   * previews rather than the authoring catalog's components — see [RemoteComposeSource]. Empty (the
   * default) simply leaves the palette out, which is the right answer for a catalog whose previews
   * are Jetpack Compose.
   */
  remoteComposeSources: List<RemoteComposeSource> = emptyList(),
  /**
   * Fetches one source's document, Base64-encoded, or throws.
   *
   * Suspending and host-owned: the bytes arrive over the network and neither the reducer nor this
   * composable can reach it. Null with a non-empty [remoteComposeSources] would be a palette that
   * cannot add anything, so the panel requires both.
   */
  resolveRemoteComposeDocument: (suspend (RemoteComposeSource) -> String)? = null,
) {
  val reducer =
    remember(catalog, actorId, clientId, operationIdPrefix) {
      UiBuilderEditorReducer(catalog, actorId, clientId, operationIdPrefix)
    }
  var state by
    remember(document.id) {
      mutableStateOf(
        reducer
          .initial(
            document,
            selectedNodeId =
              initialSelectedNodeId?.takeIf(document.nodes::containsKey)
                ?: document.roots.firstOrNull(),
          )
          .copy(
            catalogQuery = initialCatalogQuery,
            layerQuery = initialLayerQuery,
            inspectorMode = initialInspectorMode,
            previewMode = initialPreviewMode,
            codePaneVisible = initialCodePaneVisible,
            previewSurface = initialPreviewSurface,
          )
      )
    }
  LaunchedEffect(document.revision, authoritativeGeneration) {
    if (state.document != document) {
      state = reducer.reconciled(state, document, initialSelectedNodeId)
    }
  }
  // Applied once, and only over an editor that has nothing of its own: the host delivers this
  // late (it arrives over HTTP, after the editor has mounted) and may deliver it again, and
  // neither should overwrite marks the operator has drawn since.
  var referenceRestored by remember(document.id) { mutableStateOf(false) }
  LaunchedEffect(restoredReference) {
    val restored = restoredReference ?: return@LaunchedEffect
    if (referenceRestored || state.reference.hasContent) return@LaunchedEffect
    referenceRestored = true
    val attached =
      restored.image?.let { reducer.reduce(state, UiBuilderEditorEvent.AttachReference(it)) }
        ?: state
    state =
      reducer
        .reduce(attached, UiBuilderEditorEvent.UpdateReferenceSettings(restored.settings))
        .let {
          it.withReference(
            it.reference.copy(
              pieces = restored.pieces,
              marks = restored.marks,
              // Past every id that came back, so a stroke drawn now cannot collide with a stroke
              // drawn in a previous session.
              mintedIds = restored.pieces.size + restored.marks.size,
            )
          )
        }
  }
  var catalogDragPosition by remember { mutableStateOf<Offset?>(null) }
  var draggedComponentId by remember { mutableStateOf<String?>(null) }
  var canvasBounds by remember { mutableStateOf(Rect.Zero) }
  // The factor between the frame's own pixels and the pane it is drawn in, kept so a drop landing
  // at a window coordinate can be asked about in the space the renderer reports its slots in.
  var canvasScale by remember { mutableFloatStateOf(1f) }
  var textInputFocused by remember { mutableStateOf(false) }
  var mobilePanel by remember(document.id) { mutableStateOf(MobileEditorPanel.None) }
  var showNewDesign by remember(document.id) { mutableStateOf(false) }
  // The source whose document is being fetched, or null. One at a time on purpose: the palette is a
  // list of 476 rows on the Remote M3 catalog, and a double-click that started two fetches would
  // insert the same component twice — the second insert lands against a document the first already
  // changed, and neither the author nor their collaborators asked for it.
  var pendingRemoteSource by remember(document.id) { mutableStateOf<RemoteComposeSource?>(null) }
  // Only a transport failure. A document that fetched and did not decode is refused by the reducer,
  // which reports it through the same rejection channel as every other refused edit rather than a
  // second status line saying a different thing about the same click.
  var remoteSourceFailure by remember(document.id) { mutableStateOf<String?>(null) }
  val editorFocusRequester = remember { FocusRequester() }
  // Held here rather than inside the flatten, which is not a composable: a text mark has to be set
  // in the same font when it is baked in as when it was drawn.
  val flattenTextMeasurer = rememberTextMeasurer()
  // The canvas's own layout, kept here as well as handed to the host: promoting a piece asks which
  // slot is under it, and that question is answered by the layout the renderer actually produced
  // rather than by anything the document says.
  var canvasInspection by
    remember(document.id) { mutableStateOf<UiBuilderInspectionSnapshot?>(null) }
  var captureRequest by remember(document.id) { mutableStateOf<ReferenceCaptureRequest?>(null) }
  var captureSequence by remember(document.id) { mutableStateOf(0) }
  var captureFailure by remember(document.id) { mutableStateOf<String?>(null) }
  val draggedTarget = draggedComponentId?.let { reducer.dropTarget(state, it) }
  val canvasDropHovered =
    catalogDragPosition?.let(canvasBounds::contains) == true && draggedTarget != null
  fun dispatch(event: UiBuilderEditorEvent) {
    val previous = state
    val current = reducer.reduce(previous, event)
    state = current
    reducer.acceptedSubmission(previous, current)?.let { onSubmission?.invoke(it) }
  }
  fun focusEditor() {
    textInputFocused = false
    editorFocusRequester.requestFocus()
  }
  /**
   * Bake the reference stack into one picture and make it the base.
   *
   * Sized from the *document's* frame rather than from the canvas on screen, at twice its dp, so
   * the flattened picture does not inherit whatever zoom the window happened to be at — two people
   * flattening the same stack on different monitors get the same bytes.
   */
  fun flattenCurrentReference() {
    val environment = state.document.screenEnvironmentSettings()
    val flattened =
      flattenReference(
        reference = state.reference,
        widthPx = environment.widthDp * FLATTEN_SCALE,
        heightPx = environment.heightDp * FLATTEN_SCALE,
        id = "flattened-${document.id}-${state.reference.mintedIds}-${state.document.revision}",
        textMeasurer = flattenTextMeasurer,
      )
    if (flattened != null) dispatch(UiBuilderEditorEvent.FlattenReference(flattened))
  }
  LaunchedEffect(pastedReference?.id) {
    val pasted = pastedReference ?: return@LaunchedEffect
    dispatch(
      if (state.reference.attached) UiBuilderEditorEvent.PlaceReferencePiece(pasted)
      else UiBuilderEditorEvent.AttachReference(pasted)
    )
  }
  LaunchedEffect(state) { onStateChanged(state) }
  LaunchedEffect(Unit) { editorFocusRequester.requestFocus() }
  LaunchedEffect(canvasDropHovered, draggedTarget) {
    onDropTargetChanged(
      canvasDropHovered,
      draggedTarget?.let { "${it.nodeId}.${it.slot}" } ?: "No compatible slot",
    )
  }
  // Cached for the same reason as the issues scan further down, at a smaller scale: the filter
  // lowercases and scans four strings for every node in the document, and the panel recomposes far
  // more often than either the document or the query changes.
  val layerRows = remember(reducer, state.document, state.layerQuery) { reducer.layerRows(state) }
  val navigator: @Composable (Modifier, Boolean) -> Unit = { modifier, closeAfterDrop ->
    EditorNavigator(
      state = state,
      catalogSystemId = catalog.benchmark.catalogSystemId,
      catalogItems = reducer.catalogItems(state.catalogQuery),
      layerRows = layerRows,
      collaborators = collaborators,
      dropTarget = reducer.dropTarget(state, draggedComponentId ?: "m3/text"),
      onCatalogDrag = { componentId, position ->
        if (position != null) focusEditor()
        draggedComponentId = componentId
        catalogDragPosition = position
      },
      onCatalogDrop = { componentId, position ->
        // Where it was dropped, not where the selection happens to be. The palette's own legend
        // says a drag inserts the component "where it is dropped", and it did not: every drop
        // landed in the selected node's slot, so dragging onto a card put the component wherever
        // the last click had been. The renderer already reports each slot's box, and the reference
        // overlay already promotes a piece into the slot under it — this asks the same question.
        val target =
          canvasInspection?.let { snapshot ->
            reducer.promotionTarget(
              state,
              componentId,
              snapshot.slots,
              (position.x - canvasBounds.left) / canvasScale,
              (position.y - canvasBounds.top) / canvasScale,
            )
          } ?: reducer.dropTarget(state, componentId)
        if (canvasBounds.contains(position) && target != null) {
          dispatch(UiBuilderEditorEvent.InsertComponent(componentId, target))
          if (closeAfterDrop) mobilePanel = MobileEditorPanel.None
        }
        draggedComponentId = null
        catalogDragPosition = null
      },
      canAddCatalogComponent = { reducer.dropTarget(state, it) != null },
      onCatalogAdd = { componentId ->
        focusEditor()
        reducer.dropTarget(state, componentId)?.let { target ->
          dispatch(UiBuilderEditorEvent.InsertComponent(componentId, target))
          if (closeAfterDrop) mobilePanel = MobileEditorPanel.None
        }
      },
      remoteComposeSources =
        if (resolveRemoteComposeDocument == null) emptyList() else remoteComposeSources,
      pendingRemoteComposeSource = pendingRemoteSource,
      remoteComposeFailure = remoteSourceFailure,
      onAddRemoteComposeSource = { source ->
        focusEditor()
        if (pendingRemoteSource == null) pendingRemoteSource = source
        if (closeAfterDrop) mobilePanel = MobileEditorPanel.None
      },
      moveRefusal = { nodeId, target -> reducer.moveRefusal(state, nodeId, target) },
      onEditorInteraction = ::focusEditor,
      onTextInputFocusChanged = { textInputFocused = it },
      dispatch = ::dispatch,
      modifier = modifier,
    )
  }
  val canvas: @Composable (Modifier, Alignment) -> Unit = { modifier, alignment ->
    PinnedDesignCanvas(
      document = state.document,
      selectedNodeId = state.selectedNodeId,
      onNodeSelected = {
        focusEditor()
        dispatch(UiBuilderEditorEvent.SelectNode(it))
      },
      onCanvasMetrics = { width, height, scale ->
        canvasScale = scale
        onCanvasMetrics(width, height, scale)
      },
      onCanvasBounds = {
        canvasBounds = it
        onCanvasBoundsChanged(it)
      },
      dropHovered = canvasDropHovered,
      showSelectionOverlay = showSelectionOverlay && !state.previewMode,
      reference = state.reference,
      onMarkDrawn = { kind, points ->
        dispatch(UiBuilderEditorEvent.AddReferenceMark(kind, points))
      },
      onPieceMoved = { pieceId, dx, dy ->
        dispatch(UiBuilderEditorEvent.MoveReferencePiece(pieceId, dx, dy))
      },
      collaborators = collaborators,
      onInspectionSnapshot = { snapshot ->
        canvasInspection = snapshot
        onInspectionSnapshot?.invoke(snapshot)
      },
      onInspectionInvalidated = onInspectionInvalidated,
      contentAlignment = alignment,
      modifier = modifier,
    )
  }
  // Cached against the document, because it is not cheap and depends on nothing else: it walks
  // every node and every property against the catalog, traverses the graph and looks for cycles.
  // Called inline it would run all of that on every recomposition of the inspector — which is
  // every keystroke in a property field and every frame of a drag.
  val problems = remember(reducer, state.document) { reducer.problems(state.document) }
  /**
   * The slot a piece would be built into, hit-tested at its own centre.
   *
   * Fractions become render pixels here rather than in the reducer, because the conversion needs
   * the frame and the density — two facts about how this editor is drawing right now, and neither
   * of them the reducer's business.
   */
  fun promotionTargetFor(piece: ReferencePiece): ParentSlot? {
    val componentId = piece.componentId ?: return null
    val environment = state.document.screenEnvironmentSettings()
    val scale = environment.density.toFloat()
    return reducer.promotionTarget(
      state = state,
      componentId = componentId,
      slots = canvasInspection?.slots.orEmpty(),
      pointX = (piece.left + piece.right) / 2f * environment.widthDp * scale,
      pointY = (piece.top + piece.bottom) / 2f * environment.heightDp * scale,
    )
  }
  // Cached the same way and for the same reason, and only while the pane is open: generating is a
  // projection plus a full generator run, which nobody should pay for on every recomposition — or
  // at all, with the pane closed.
  var nativeRender by remember(document.id) { mutableStateOf(initialNativeRender) }
  // Whether the chosen surface needs the host to draw anything. Derived rather than stored: the
  // surface is the setting, and a second flag that could disagree with it is a bug waiting.
  val nativeRequested =
    onRequestNativeRender != null && state.previewSurface != EditorPreviewSurface.Wasm
  var nativePending by remember(document.id) { mutableStateOf(false) }
  // Keyed on the revision as well as the request, so asking again after an edit re-renders rather
  // than showing the frame the design used to have — a stale native render beside a live canvas is
  // the exact disagreement this pane exists to expose.
  LaunchedEffect(nativeRequested, state.document.revision) {
    if (!nativeRequested || onRequestNativeRender == null) return@LaunchedEffect
    nativePending = true
    nativeRender =
      try {
        onRequestNativeRender()
      } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
        throw cancelled
      } catch (failure: Throwable) {
        UiBuilderNativeRender(failure = failure.message ?: "the native render request failed")
      }
    nativePending = false
  }
  LaunchedEffect(pendingRemoteSource) {
    val source = pendingRemoteSource ?: return@LaunchedEffect
    val resolve = resolveRemoteComposeDocument ?: return@LaunchedEffect
    val encoded =
      try {
        resolve(source)
      } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
        throw cancelled
      } catch (failure: Throwable) {
        remoteSourceFailure = "${source.label}: ${failure.message ?: "could not be fetched"}"
        pendingRemoteSource = null
        return@LaunchedEffect
      }
    // Resolved against the selection as it stands NOW, not as it stood when the row was pressed: a
    // fetch takes a round trip, and the reducer would refuse a target the author has since moved
    // away from. Asking again is what makes the insert land where the canvas says it will.
    val target = reducer.dropTarget(state, REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID)
    if (target == null) {
      remoteSourceFailure = "${source.label}: no compatible slot is selected"
    } else {
      remoteSourceFailure = null
      dispatch(UiBuilderEditorEvent.InsertRemoteComposeDocument(source, encoded, target))
    }
    pendingRemoteSource = null
  }
  val generatedCode =
    if (state.codePaneVisible || mobilePanel == MobileEditorPanel.Code) {
      remember(reducer, state.document) { reducer.generatedCode(state.document) }
    } else null
  // Named where the document is, not inside the pane: the pane is handed source and has no way to
  // tell which generator wrote it.
  val generatedCodeCaption =
    if (state.document.isWearWidget()) "Wear widget · Remote Compose"
    else "Compose export · ${ScreenExportGate.PACKAGE_NAME}"
  val propertyFields = reducer.propertyFields(state)
  // Which of those a binding must reach as a comparison rather than a bare read. Computed beside
  // the fields so the inspector is not asking the reducer the same question twice per frame.
  val comparisonBindingProperties =
    state.selectedNodeId?.let { nodeId ->
      propertyFields
        .filter { reducer.bindingNeedsComparison(state, nodeId, it.name) }
        .map { it.name }
        .toSet()
    } ?: emptySet()
  // Only the properties a binding would actually be accepted on. A menu that offers one the
  // reducer will refuse is a menu that lies, which is the rule the wrap menu already follows.
  val bindableProperties =
    state.selectedNodeId?.let { nodeId ->
      propertyFields
        .filter { reducer.canBindToState(state, nodeId, it.name) }
        .map { it.name }
        .toSet()
    } ?: emptySet()
  val inspector: @Composable (Modifier) -> Unit = { modifier ->
    PropertyInspector(
      state = state,
      fields = propertyFields,
      stateVariables = reducer.stateVariableNames(state),
      comparisonBindingProperties = comparisonBindingProperties,
      bindableProperties = bindableProperties,
      problems = problems,
      themeSettings = reducer.themeSettings(state),
      devicePresets = devicePresets,
      onPickReference = onPickReference,
      onSnapshotDesign = onSnapshotDesign,
      onFlatten = ::flattenCurrentReference,
      catalogItems = reducer.catalogItems(""),
      onPlaceComponent = { componentId ->
        captureSequence += 1
        captureRequest = ReferenceCaptureRequest(componentId, captureSequence)
      },
      onPromotePiece = { piece ->
        promotionTargetFor(piece)?.let {
          dispatch(UiBuilderEditorEvent.PromoteReferencePiece(piece.id, it))
        }
      },
      canPromotePiece = { piece -> promotionTargetFor(piece) != null },
      referenceStatus = captureFailure ?: referenceStatus,
      onTextInputFocusChanged = { textInputFocused = it },
      dispatch = ::dispatch,
      modifier = modifier,
    )
  }

  // Composed but never shown: it photographs a component and hands back the pixels. Mounted here
  // rather than inside the panel so that a capture survives the inspector switching tabs.
  val pendingCapture = captureRequest
  ReferenceComponentCapture(
    request = pendingCapture,
    catalog = catalog,
    document = state.document,
    onCaptured = { captured ->
      captureRequest = null
      captureFailure =
        if (captured == null) "That component could not be captured from this catalog." else null
      if (captured != null && pendingCapture != null) {
        dispatch(
          UiBuilderEditorEvent.PlaceReferencePiece(
            captured,
            componentId = pendingCapture.componentId,
          )
        )
      }
    },
  )

  MaterialTheme(colorScheme = EditorColors) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val compact = maxWidth < 840.dp
      Column(
        Modifier.fillMaxSize()
          .background(MaterialTheme.colorScheme.background)
          .focusRequester(editorFocusRequester)
          .focusable()
          .onPreviewKeyEvent { event ->
            editorShortcut(
              event,
              enabled = !textInputFocused,
              previewing = state.previewMode,
              dispatch = ::dispatch,
            )
          }
      ) {
        if (compact) {
          MobileEditorToolbar(
            state = state,
            canDelete = reducer.canDeleteSelected(state),
            canDuplicate = reducer.canDuplicateSelected(state),
            canCopy = reducer.canCopySelected(state),
            canCut = reducer.canCutSelected(state),
            canPaste = reducer.canPaste(state),
            wrapCandidates = reducer.wrapCandidates(state),
            canUnwrap = reducer.canUnwrapSelected(state),
            canUndo = reducer.canUndo(state),
            canRedo = reducer.canRedo(state),
            onNewDesign =
              if (newDesignCatalogs.isNotEmpty() && onCreateDesign != null) {
                { showNewDesign = true }
              } else null,
            onReconnect = onReconnect,
            onHelp = onHelp,
            dispatch = ::dispatch,
          )
        } else {
          EditorToolbar(
            state = state,
            canDelete = reducer.canDeleteSelected(state),
            canDuplicate = reducer.canDuplicateSelected(state),
            canCopy = reducer.canCopySelected(state),
            canCut = reducer.canCutSelected(state),
            canPaste = reducer.canPaste(state),
            wrapCandidates = reducer.wrapCandidates(state),
            canUnwrap = reducer.canUnwrapSelected(state),
            canUndo = reducer.canUndo(state),
            canRedo = reducer.canRedo(state),
            sessionLabel = sessionLabel,
            collaborators = collaborators,
            onNewDesign =
              if (newDesignCatalogs.isNotEmpty() && onCreateDesign != null) {
                { showNewDesign = true }
              } else null,
            onReconnect = onReconnect,
            onHelp = onHelp,
            // Absent where the host cannot draw: a project with no compile lane has exactly one
            // renderer, and offering a choice between it and nothing is not a choice.
            previewSurface = if (onRequestNativeRender == null) null else state.previewSurface,
            dispatch = ::dispatch,
          )
        }
        Box(Modifier.fillMaxSize()) {
          if (!compact) {
            Row(Modifier.fillMaxSize()) {
              navigator(Modifier.width(300.dp).fillMaxHeight(), false)
              Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                  // One renderer or the other, normally. A CMP project that targets Wasm is best
                  // previewed in the browser; a project that targets only Android or desktop has
                  // no browser renderer at all, and the host's is not an extra pane but the whole
                  // preview. `Both` is the deliberate third case — comparing them — rather than
                  // the layout everything else is squeezed into.
                  if (state.previewSurface != EditorPreviewSurface.Native || !nativeRequested) {
                    canvas(
                      Modifier.weight(1f)
                        .fillMaxHeight()
                        .background(Color(0xff0d0e11))
                        .padding(20.dp),
                      Alignment.TopStart,
                    )
                  }
                  if (nativeRequested) {
                    NativeRenderPane(
                      render = nativeRender,
                      pending = nativePending,
                      selectedNodeId = state.selectedNodeId,
                      onNodeSelected = {
                        focusEditor()
                        dispatch(UiBuilderEditorEvent.SelectNode(it))
                      },
                      modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                  }
                }
                // Under the canvas rather than beside it: generated Kotlin is long lines and the
                // canvas is a phone-shaped thing, so height is what the code has to spare and
                // width is what the canvas cannot.
                if (generatedCode != null) {
                  GeneratedCodePane(
                    generatedCode,
                    generatedCodeCaption,
                    Modifier.fillMaxWidth().weight(0.8f),
                  )
                }
              }
              inspector(Modifier.width(INSPECTOR_WIDTH).fillMaxHeight())
            }
          } else {
            canvas(
              Modifier.fillMaxSize()
                .background(Color(0xff0d0e11))
                .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 64.dp),
              Alignment.Center,
            )
            if (mobilePanel == MobileEditorPanel.Components) {
              navigator(
                Modifier.align(Alignment.BottomCenter)
                  .fillMaxWidth()
                  .fillMaxHeight(0.72f)
                  .padding(bottom = 56.dp),
                true,
              )
            }
            if (mobilePanel == MobileEditorPanel.Properties) {
              inspector(
                Modifier.align(Alignment.BottomCenter)
                  .fillMaxWidth()
                  .fillMaxHeight(0.72f)
                  .padding(bottom = 56.dp)
              )
            }
            if (mobilePanel == MobileEditorPanel.Code && generatedCode != null) {
              GeneratedCodePane(
                generatedCode,
                generatedCodeCaption,
                Modifier.align(Alignment.BottomCenter)
                  .fillMaxWidth()
                  .fillMaxHeight(0.72f)
                  .padding(bottom = 56.dp),
              )
            }
            MobilePanelDock(
              panel = mobilePanel,
              onPanelChanged = {
                mobilePanel = if (mobilePanel == it) MobileEditorPanel.None else it
              },
              modifier = Modifier.align(Alignment.BottomCenter),
            )
          }
        }
      }
      if (showNewDesign && onCreateDesign != null) {
        NewDesignDialog(
          catalogs = newDesignCatalogs,
          initialCatalogSystemId =
            document.catalogPin["systemId"]?.jsonPrimitive?.contentOrNull
              ?: newDesignCatalogs.first().systemId,
          onDismiss = { showNewDesign = false },
          onCreate = onCreateDesign,
        )
      }
    }
  }
}

@Composable
private fun NewDesignDialog(
  catalogs: List<UiBuilderNewDesignCatalog>,
  initialCatalogSystemId: String,
  onDismiss: (() -> Unit)?,
  onCreate:
    (
      catalogSystemId: String,
      designId: String,
      templateId: String,
      state: List<NewDesignState>,
    ) -> Unit,
) {
  val initialCatalog =
    catalogs.firstOrNull { it.systemId == initialCatalogSystemId } ?: catalogs.first()
  var selectedCatalogId by remember { mutableStateOf(initialCatalog.systemId) }
  var selectedTemplateId by remember {
    mutableStateOf(initialCatalog.templates.firstOrNull()?.id.orEmpty())
  }
  var designId by remember { mutableStateOf("") }
  val selectedCatalog = catalogs.first { it.systemId == selectedCatalogId }
  val selectedTemplate =
    selectedCatalog.templates.firstOrNull { it.id == selectedTemplateId }
      ?: selectedCatalog.templates.first()
  val designIdValid = designId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))
  var declared by remember { mutableStateOf(listOf<NewDesignState>()) }
  var variableName by remember { mutableStateOf("") }
  var variableKind by remember { mutableStateOf(NewDesignStateType.Flag) }
  var variableInitial by remember { mutableStateOf("") }
  val variableNameValid =
    NEW_DESIGN_STATE_NAME.matches(variableName) && declared.none { it.name == variableName }

  AlertDialog(
    onDismissRequest = { onDismiss?.invoke() },
    title = { Text("Create a new design") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Catalog", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          catalogs.forEach { catalog ->
            FilterChip(
              selected = catalog.systemId == selectedCatalogId,
              onClick = {
                selectedCatalogId = catalog.systemId
                selectedTemplateId = catalog.templates.first().id
              },
              label = { Text(catalog.label) },
            )
          }
        }
        Text("Starting point", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          selectedCatalog.templates.forEach { template ->
            FilterChip(
              selected = template.id == selectedTemplate.id,
              onClick = { selectedTemplateId = template.id },
              label = { Text(template.label) },
            )
          }
        }
        Text(
          selectedTemplate.supportingText,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        Text("Design ID", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
          value = designId,
          onValueChange = { designId = it },
          modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Design ID" },
          placeholder = { Text("my-widget") },
          supportingText = {
            Text(
              if (designId.isEmpty() || designIdValid) {
                "Letters, numbers, dots, underscores, and hyphens"
              } else {
                "Start with a letter or number and use only path-safe characters"
              }
            )
          },
          isError = designId.isNotEmpty() && !designIdValid,
          singleLine = true,
        )
        // State is declared here because `CreateDesign` carries a whole document and no released
        // mutation reaches `stateVariables` afterwards. Until one does, this is the only moment a
        // design can be given the variables the inspector then binds properties to.
        Text("State", style = MaterialTheme.typography.labelLarge)
        Text(
          "Variables this screen reacts to. A property can be bound to one once the design exists.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          NewDesignStateType.entries.forEach { kind ->
            FilterChip(
              selected = kind == variableKind,
              onClick = { variableKind = kind },
              label = { Text(kind.label) },
            )
          }
        }
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedTextField(
            value = variableName,
            onValueChange = { variableName = it },
            modifier = Modifier.weight(1f).semantics { contentDescription = "State name" },
            placeholder = { Text("expanded") },
            isError = variableName.isNotEmpty() && !variableNameValid,
            singleLine = true,
          )
          OutlinedTextField(
            value = variableInitial,
            onValueChange = { variableInitial = it },
            modifier = Modifier.weight(1f).semantics { contentDescription = "State initial value" },
            placeholder = { Text(variableKind.placeholder) },
            singleLine = true,
          )
          TextButton(
            onClick = {
              declared +=
                NewDesignState(variableName, variableKind, variableKind.parse(variableInitial))
              variableName = ""
              variableInitial = ""
            },
            enabled = variableNameValid,
          ) {
            Text("Add")
          }
        }
        if (declared.isNotEmpty()) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            declared.forEach { variable ->
              FilterChip(
                selected = false,
                onClick = { declared = declared - variable },
                label = { Text("${variable.name} · ${variable.type.label}") },
              )
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onCreate(selectedCatalog.systemId, designId, selectedTemplate.id, declared) },
        enabled = designIdValid,
      ) {
        Text("Create")
      }
    },
    dismissButton = { if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
fun UiBuilderNewDesignScreen(
  catalogs: List<UiBuilderNewDesignCatalog>,
  initialCatalogSystemId: String,
  onCreate:
    (
      catalogSystemId: String,
      designId: String,
      templateId: String,
      state: List<NewDesignState>,
    ) -> Unit,
) {
  require(catalogs.isNotEmpty()) { "new design screen requires at least one catalog" }
  MaterialTheme(colorScheme = EditorColors) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    NewDesignDialog(
      catalogs = catalogs,
      initialCatalogSystemId = initialCatalogSystemId,
      onDismiss = null,
      onCreate = onCreate,
    )
  }
}

@Composable
private fun MobileEditorToolbar(
  state: UiBuilderEditorState,
  canDelete: Boolean,
  canDuplicate: Boolean,
  canCopy: Boolean,
  canCut: Boolean,
  canPaste: Boolean,
  wrapCandidates: List<EditorCatalogItem>,
  canUnwrap: Boolean,
  canUndo: Boolean,
  canRedo: Boolean,
  onNewDesign: (() -> Unit)?,
  onReconnect: (() -> Unit)?,
  onHelp: (() -> Unit)?,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
    Row(
      Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("UI Builder", Modifier.weight(1f), fontWeight = FontWeight.Bold)
      EditorAction("Undo", "Ctrl/⌘+Z", canUndo) { dispatch(UiBuilderEditorEvent.Undo) }
      EditorAction("Redo", "Ctrl/⌘+Shift+Z", canRedo) { dispatch(UiBuilderEditorEvent.Redo) }
      Box {
        TextButton(
          onClick = { expanded = true },
          modifier = Modifier.semantics { contentDescription = "More editor actions" },
        ) {
          Text("More")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
          if (onNewDesign != null) {
            DropdownMenuItem(
              text = { Text("New design") },
              onClick = {
                expanded = false
                onNewDesign()
              },
            )
          }
          DropdownMenuItem(
            text = { Text("Duplicate") },
            enabled = canDuplicate,
            onClick = {
              expanded = false
              dispatch(UiBuilderEditorEvent.DuplicateSelected)
            },
          )
          DropdownMenuItem(
            text = { Text("Copy") },
            enabled = canCopy,
            onClick = {
              expanded = false
              dispatch(UiBuilderEditorEvent.CopySelected)
            },
          )
          DropdownMenuItem(
            text = { Text("Cut") },
            enabled = canCut,
            onClick = {
              expanded = false
              dispatch(UiBuilderEditorEvent.CutSelected)
            },
          )
          DropdownMenuItem(
            text = { Text("Paste") },
            enabled = canPaste,
            onClick = {
              expanded = false
              dispatch(UiBuilderEditorEvent.Paste)
            },
          )
          DropdownMenuItem(
            text = { Text("Delete") },
            enabled = canDelete,
            onClick = {
              expanded = false
              dispatch(UiBuilderEditorEvent.DeleteSelected)
            },
          )
          if (onReconnect != null) {
            DropdownMenuItem(
              text = { Text("Reconnect") },
              onClick = {
                expanded = false
                onReconnect()
              },
            )
          }
          if (onHelp != null) {
            DropdownMenuItem(
              text = { Text("Help") },
              onClick = {
                expanded = false
                onHelp()
              },
            )
          }
        }
      }
      Text("r${state.document.revision}", style = MaterialTheme.typography.labelMedium)
    }
  }
}

@Composable
private fun MobilePanelDock(
  panel: MobileEditorPanel,
  onPanelChanged: (MobileEditorPanel) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(modifier.fillMaxWidth().height(56.dp), tonalElevation = 6.dp) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
      MobilePanelButton("Components", MobileEditorPanel.Components, panel, onPanelChanged)
      MobilePanelButton("Properties", MobileEditorPanel.Properties, panel, onPanelChanged)
      MobilePanelButton("Code", MobileEditorPanel.Code, panel, onPanelChanged)
    }
  }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MobilePanelButton(
  label: String,
  target: MobileEditorPanel,
  selected: MobileEditorPanel,
  onPanelChanged: (MobileEditorPanel) -> Unit,
) {
  TextButton(
    onClick = { onPanelChanged(target) },
    modifier =
      Modifier.weight(1f).fillMaxHeight().semantics {
        contentDescription =
          if (selected == target) "Close ${label.lowercase()} panel"
          else "Open ${label.lowercase()} panel"
      },
  ) {
    Text(label, fontWeight = if (selected == target) FontWeight.Bold else FontWeight.Normal)
  }
}

@Composable
private fun EditorToolbar(
  state: UiBuilderEditorState,
  canDelete: Boolean,
  canDuplicate: Boolean,
  canCopy: Boolean,
  canCut: Boolean,
  canPaste: Boolean,
  wrapCandidates: List<EditorCatalogItem>,
  canUnwrap: Boolean,
  canUndo: Boolean,
  canRedo: Boolean,
  sessionLabel: String,
  collaborators: List<UiBuilderCollaborator>,
  onNewDesign: (() -> Unit)?,
  onReconnect: (() -> Unit)?,
  onHelp: (() -> Unit)?,
  /**
   * The surface in use, or null where the host cannot compile — a project with one renderer is not
   * offered a choice between it and nothing.
   */
  previewSurface: EditorPreviewSurface? = null,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var showShortcuts by remember { mutableStateOf(false) }
  if (showShortcuts) {
    EditorShortcutsDialog(onDismiss = { showShortcuts = false })
  }
  Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
    Row(
      Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 18.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("Compose UI Builder", fontWeight = FontWeight.Bold)
      // Yields rather than pushes. The toolbar has gained a control in most of the last few
      // changes, and an unconstrained title crowds them out of the row on the narrowest window
      // that still calls itself a desktop.
      Text(
        "${state.document.title} · ${state.document.catalogPin["systemId"]?.jsonPrimitive?.contentOrNull.orEmpty()}",
        Modifier.padding(start = 14.dp).weight(1f, fill = false),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.weight(1f))
      collaborators.take(4).forEach { collaborator ->
        Surface(
          Modifier.padding(start = 4.dp).size(28.dp).clearAndSetSemantics {},
          shape = RoundedCornerShape(14.dp),
          color = collaborator.colorArgbHex.toPresenceColor(),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Text(
              collaborator.displayName.firstOrNull()?.uppercase().orEmpty(),
              color = Color.White,
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      }
      if (onNewDesign != null) {
        EditorAction(label = "New design", shortcut = "", enabled = true, onClick = onNewDesign)
      }
      // Only once there is something to hide — a picture, a placed piece or a mark. An
      // always-present control for a feature most designs never use is exactly the toolbar
      // crowding the row above is already fighting.
      if (state.reference.hasContent) {
        EditorAction(
          label = if (state.reference.settings.visible) "Hide reference" else "Show reference",
          shortcut = "",
          enabled = true,
          onClick = { dispatch(UiBuilderEditorEvent.ToggleReference) },
        )
      }
      EditorAction(
        label = "Undo",
        shortcut = "Ctrl/⌘+Z",
        enabled = canUndo,
        onClick = { dispatch(UiBuilderEditorEvent.Undo) },
      )
      EditorAction(
        label = "Redo",
        shortcut = "Ctrl/⌘+Shift+Z",
        enabled = canRedo,
        onClick = { dispatch(UiBuilderEditorEvent.Redo) },
      )
      EditorAction(
        label = "Duplicate",
        shortcut = "Ctrl/⌘+D",
        enabled = canDuplicate,
        onClick = { dispatch(UiBuilderEditorEvent.DuplicateSelected) },
      )
      if (wrapCandidates.isNotEmpty()) {
        var wrapOpen by remember { mutableStateOf(false) }
        Box {
          EditorAction(
            label = "Wrap",
            shortcut = "",
            enabled = true,
            onClick = { wrapOpen = true },
          )
          DropdownMenu(expanded = wrapOpen, onDismissRequest = { wrapOpen = false }) {
            // Only what will work. The list is computed from both ends — the parent slot has to
            // accept the container and the container needs a slot that accepts every selected
            // node — so this menu is a promise rather than a guess.
            wrapCandidates.forEach { candidate ->
              DropdownMenuItem(
                text = { Text(candidate.displayName) },
                onClick = {
                  wrapOpen = false
                  dispatch(UiBuilderEditorEvent.WrapSelection(candidate.componentId))
                },
              )
            }
          }
        }
      }
      EditorAction(
        label = "Unwrap",
        shortcut = "",
        enabled = canUnwrap,
        onClick = { dispatch(UiBuilderEditorEvent.UnwrapSelection) },
      )
      EditorAction(
        label = "Copy",
        shortcut = "Ctrl/⌘+C",
        enabled = canCopy,
        onClick = { dispatch(UiBuilderEditorEvent.CopySelected) },
      )
      EditorAction(
        label = "Cut",
        shortcut = "Ctrl/⌘+X",
        enabled = canCut,
        onClick = { dispatch(UiBuilderEditorEvent.CutSelected) },
      )
      EditorAction(
        label = "Paste",
        shortcut = "Ctrl/⌘+V",
        enabled = canPaste,
        onClick = { dispatch(UiBuilderEditorEvent.Paste) },
      )
      EditorAction(
        label = "Delete",
        shortcut = "Delete/Backspace",
        enabled = canDelete,
        onClick = { dispatch(UiBuilderEditorEvent.DeleteSelected) },
      )
      // The one control that changes what the canvas is for, so it says which side it is on rather
      // than what it will do — a button reading "Preview" while you are previewing is a coin toss.
      EditorAction(
        label = if (state.previewMode) "Previewing · exit" else "Preview",
        shortcut = "Ctrl/\u2318+Enter",
        enabled = true,
        onClick = { dispatch(UiBuilderEditorEvent.TogglePreview) },
      )
      // Beside Preview because it is the same kind of control: both change what the canvas column
      // is showing rather than editing the document.
      EditorAction(
        label = if (state.codePaneVisible) "Code · hide" else "Code",
        shortcut = "",
        enabled = true,
        onClick = { dispatch(UiBuilderEditorEvent.ToggleCodePane) },
      )
      if (previewSurface != null) {
        // Names the surface in use rather than what pressing it will do: this control has three
        // positions, and "Native" on a button while you are looking at the Wasm canvas is a coin
        // toss. "Native", not "Android", because the compile lane renders whatever the host's
        // catalog targets and naming one platform would be wrong on a desktop-only box.
        EditorAction(
          label = "Render · ${previewSurface.label()}",
          shortcut = "",
          enabled = true,
          onClick = { dispatch(UiBuilderEditorEvent.ShowPreviewSurface(previewSurface.next())) },
        )
      }
      EditorAction(
        label = "Shortcuts",
        shortcut = "",
        enabled = true,
        onClick = { showShortcuts = true },
      )
      if (onReconnect != null) {
        EditorAction(label = "Reconnect", shortcut = "", enabled = true, onClick = onReconnect)
      }
      if (onHelp != null) {
        EditorAction(label = "Help", shortcut = "", enabled = true, onClick = onHelp)
      }
      Text(
        "Revision ${state.document.revision}  ·  ${state.document.nodes.size} nodes",
        Modifier.padding(start = 8.dp),
        style = MaterialTheme.typography.labelLarge,
      )
      Surface(
        Modifier.padding(start = 14.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xff214c37),
      ) {
        Text(
          sessionLabel,
          Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
          color = Color(0xffa8f2c6),
          style = MaterialTheme.typography.labelMedium,
        )
      }
    }
  }
}

private fun EditorPreviewSurface.label(): String =
  when (this) {
    EditorPreviewSurface.Wasm -> "Wasm"
    EditorPreviewSurface.Native -> "Native"
    EditorPreviewSurface.Both -> "Both"
  }

/** Wasm → Native → Both → Wasm. Three positions is a cycle; four would need a menu. */
private fun EditorPreviewSurface.next(): EditorPreviewSurface =
  when (this) {
    EditorPreviewSurface.Wasm -> EditorPreviewSurface.Native
    EditorPreviewSurface.Native -> EditorPreviewSurface.Both
    EditorPreviewSurface.Both -> EditorPreviewSurface.Wasm
  }

@Composable
private fun EditorAction(
  label: String,
  shortcut: String,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  TextButton(
    onClick = onClick,
    enabled = enabled,
    modifier = Modifier.semantics { contentDescription = "$label ($shortcut)" },
  ) {
    Text(label)
  }
}

@Composable
private fun EditorShortcutsDialog(onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Keyboard and pointer") },
    text = { EditorShortcutsPanel() },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
  )
}

/**
 * The shortcut table, rendered from [EDITOR_SHORTCUTS] and [EDITOR_GESTURES] rather than retyped.
 *
 * Separate from the dialog so it can be previewed on its own: a help surface that drifts from the
 * handler is worse than no help surface, and the only way to keep it honest is for both to read the
 * same list and for a render to show what the list currently says.
 */
@Composable
internal fun EditorShortcutsPanel(modifier: Modifier = Modifier) {
  Column(modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("Keys", style = MaterialTheme.typography.labelLarge)
    EDITOR_SHORTCUTS.forEach { shortcut -> EditorShortcutRow(shortcut.chord, shortcut.description) }
    Spacer(Modifier.height(10.dp))
    Text("Pointer", style = MaterialTheme.typography.labelLarge)
    EDITOR_GESTURES.forEach { (gesture, description) -> EditorShortcutRow(gesture, description) }
  }
}

@Composable
private fun EditorShortcutRow(chord: String, description: String) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Surface(
      Modifier.width(178.dp),
      shape = RoundedCornerShape(6.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
      Text(
        chord,
        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
      )
    }
    Text(
      description,
      Modifier.padding(start = 12.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

/** How a click on a layer row changes the selection. */
private enum class LayerSelectionGesture {
  Replace,
  Toggle,
  Range,
}

private fun editorShortcut(
  event: KeyEvent,
  enabled: Boolean,
  previewing: Boolean,
  dispatch: (UiBuilderEditorEvent) -> Unit,
): Boolean {
  if (!enabled || event.type != KeyEventType.KeyDown) return false
  val chord =
    EditorChord(
      key = event.key,
      command = event.isCtrlPressed || event.isMetaPressed,
      shift = event.isShiftPressed,
    )
  val match = editorShortcutFor(chord, previewing) ?: return false
  dispatch(match.event)
  return true
}

/**
 * The shortcut a chord resolves to, or null when none does.
 *
 * Pure, so the table's precedence and the preview suppression can be tested without synthesising a
 * key event — which on this target is more machinery than the rule being tested.
 *
 * While the canvas belongs to the screen, only the chord that hands it back is live. With the
 * selection overlay gone there is nothing on screen to show what a Delete or an arrow just did, so
 * those chords would edit invisibly and surprise later.
 */
internal fun editorShortcutFor(chord: EditorChord, previewing: Boolean = false): EditorShortcut? =
  EDITOR_SHORTCUTS.firstOrNull { it.matches(chord) }
    ?.takeIf { !previewing || it.event == UiBuilderEditorEvent.TogglePreview }

/** The part of a key press a shortcut is allowed to look at. */
internal data class EditorChord(val key: Key, val command: Boolean, val shift: Boolean)

/**
 * One chord the editor answers to.
 *
 * [shift] is `null` for "does not care", which is not the same as `false`: `Ctrl/⌘+Z` fires whether
 * or not shift is down, and only reaches the undo entry because the redo entry above it claims the
 * shifted spelling first.
 */
internal data class EditorShortcut(
  val chord: String,
  val description: String,
  val event: UiBuilderEditorEvent,
  val keys: Set<Key>,
  val command: Boolean,
  val shift: Boolean? = null,
) {
  fun matches(pressed: EditorChord): Boolean =
    pressed.command == command && (shift == null || shift == pressed.shift) && pressed.key in keys
}

/**
 * Every key chord the editor answers to, in the order it tries them, and the list the shortcuts
 * panel renders.
 *
 * One table rather than a `when` plus a hand-written help sheet, because the second of those is
 * wrong within two commits. Most of what this editor learned to do — extending a selection,
 * reordering, wrapping, the clipboard — arrived with no visible affordance at all: reorder is a
 * chord and a drag gesture and appears on no button, and arrow-key navigation appears nowhere. A
 * capability nobody can find is one the tool does not have.
 *
 * Order is behaviour: the redo entry has to precede undo, and the reordering arrows have to precede
 * the navigating ones or a modified arrow is eaten by selection. `editorShortcutsAreAllReachable`
 * asserts every entry is the first match for its own chord, so a reordering that shadows one fails
 * rather than quietly dropping a row the panel still advertises.
 */
internal val EDITOR_SHORTCUTS: List<EditorShortcut> =
  listOf(
    EditorShortcut(
      chord = "Ctrl/\u2318+Shift+Z",
      description = "Redo",
      event = UiBuilderEditorEvent.Redo,
      keys = setOf(Key.Z),
      command = true,
      shift = true,
    ),
    EditorShortcut(
      chord = "Ctrl/\u2318+Y",
      description = "Redo",
      event = UiBuilderEditorEvent.Redo,
      keys = setOf(Key.Y),
      command = true,
    ),
    EditorShortcut(
      chord = "Ctrl/\u2318+Z",
      description = "Undo",
      event = UiBuilderEditorEvent.Undo,
      keys = setOf(Key.Z),
      command = true,
    ),
    // Enter rather than P. The builder ships in a browser, and Ctrl/\u2318+P is the print dialog:
    // a chord whose worst case is a print preview over the design is not a chord worth having,
    // and whether Compose consumes it before the browser sees it is not something to find out in
    // production. Ctrl/\u2318+Enter is unclaimed, and "run it" is already what it means everywhere
    // else.
    EditorShortcut(
      chord = "Ctrl/\u2318+Enter",
      description = "Hand the canvas to the screen, and back",
      event = UiBuilderEditorEvent.TogglePreview,
      keys = setOf(Key.Enter, Key.NumPadEnter),
      command = true,
    ),
    EditorShortcut(
      chord = "Ctrl/\u2318+D",
      description = "Duplicate the selection in place",
      event = UiBuilderEditorEvent.DuplicateSelected,
      keys = setOf(Key.D),
      command = true,
    ),
    // Reorder before plain navigation, so the modified arrows are not eaten by selection.
    EditorShortcut(
      chord = "Ctrl/\u2318+\u2191",
      description = "Move the selection earlier in its slot",
      event = UiBuilderEditorEvent.MoveSelected(EditorMoveDirection.Before),
      keys = setOf(Key.DirectionUp),
      command = true,
    ),
    EditorShortcut(
      chord = "Ctrl/\u2318+\u2193",
      description = "Move the selection later in its slot",
      event = UiBuilderEditorEvent.MoveSelected(EditorMoveDirection.After),
      keys = setOf(Key.DirectionDown),
      command = true,
    ),
    EditorShortcut(
      chord = "\u2193",
      description = "Select the next layer",
      event = UiBuilderEditorEvent.SelectRelative(EditorSelectionMove.Next),
      keys = setOf(Key.DirectionDown),
      command = false,
    ),
    EditorShortcut(
      chord = "\u2191",
      description = "Select the previous layer",
      event = UiBuilderEditorEvent.SelectRelative(EditorSelectionMove.Previous),
      keys = setOf(Key.DirectionUp),
      command = false,
    ),
    EditorShortcut(
      chord = "\u2190",
      description = "Select the parent",
      event = UiBuilderEditorEvent.SelectRelative(EditorSelectionMove.Parent),
      keys = setOf(Key.DirectionLeft),
      command = false,
    ),
    EditorShortcut(
      chord = "\u2192",
      description = "Select the first child",
      event = UiBuilderEditorEvent.SelectRelative(EditorSelectionMove.FirstChild),
      keys = setOf(Key.DirectionRight),
      command = false,
    ),
    EditorShortcut(
      chord = "Ctrl/\u2318+C",
      description = "Copy the selection",
      event = UiBuilderEditorEvent.CopySelected,
      keys = setOf(Key.C),
      command = true,
    ),
    EditorShortcut(
      chord = "Ctrl/\u2318+X",
      description = "Cut the selection",
      event = UiBuilderEditorEvent.CutSelected,
      keys = setOf(Key.X),
      command = true,
    ),
    EditorShortcut(
      chord = "Ctrl/\u2318+V",
      description = "Paste into the selected container",
      event = UiBuilderEditorEvent.Paste,
      keys = setOf(Key.V),
      command = true,
    ),
    EditorShortcut(
      chord = "Delete / Backspace",
      description = "Delete the selection",
      event = UiBuilderEditorEvent.DeleteSelected,
      keys = setOf(Key.Delete, Key.Backspace),
      command = false,
    ),
  )

/**
 * The pointer gestures, which no chord and no button can advertise.
 *
 * They are the least discoverable thing in the editor and the most load-bearing: without them a
 * selection is one node, and every batch operation this editor gained is unreachable.
 */
internal val EDITOR_GESTURES: List<Pair<String, String>> =
  listOf(
    "Ctrl/\u2318 + click a layer" to "Add one layer to the selection, or take it out",
    "Shift + click a layer" to "Extend the selection to that layer",
    "Drag a layer row" to "Drop it on the layer or the slot it should join",
    "Drag a catalog component" to "Insert it where it is dropped",
  )

@Composable
private fun EditorNavigator(
  state: UiBuilderEditorState,
  catalogSystemId: String,
  catalogItems: List<EditorCatalogItem>,
  layerRows: List<EditorLayerRow>,
  collaborators: List<UiBuilderCollaborator>,
  dropTarget: ParentSlot?,
  onCatalogDrag: (String, Offset?) -> Unit,
  onCatalogDrop: (String, Offset) -> Unit,
  canAddCatalogComponent: (String) -> Boolean,
  onCatalogAdd: (String) -> Unit,
  remoteComposeSources: List<RemoteComposeSource>,
  pendingRemoteComposeSource: RemoteComposeSource?,
  remoteComposeFailure: String?,
  onAddRemoteComposeSource: (RemoteComposeSource) -> Unit,
  moveRefusal: (String, ParentSlot) -> EditorMoveRefusal?,
  onEditorInteraction: () -> Unit,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
  modifier: Modifier = Modifier.width(300.dp).fillMaxHeight(),
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column {
      PanelHeading(
        "$catalogSystemId component catalog",
        "Drop target: " + (dropTarget?.let { "${it.nodeId}.${it.slot}" } ?: "No compatible slot"),
      )
      SearchField(
        state.catalogQuery,
        placeholder = "Search components",
        onFocusChanged = onTextInputFocusChanged,
      ) {
        dispatch(UiBuilderEditorEvent.SearchCatalog(it))
      }
      LazyColumn(Modifier.fillMaxWidth().height(240.dp)) {
        itemsIndexed(catalogItems, key = { _, item -> item.componentId }) { index, item ->
          if (index == 0 || catalogItems[index - 1].kind != item.kind) KindHeading(item.kind)
          CatalogRow(
            item = item,
            onDrag = { onCatalogDrag(item.componentId, it) },
            onDrop = { onCatalogDrop(item.componentId, it) },
            canAdd = canAddCatalogComponent(item.componentId),
            onAdd = { onCatalogAdd(item.componentId) },
          )
        }
      }
      if (remoteComposeSources.isNotEmpty()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        // Filtered by the SAME field as the components above rather than a third search box. The
        // two lists answer one question — "what can I put here?" — and the palette is long enough
        // that a typed name has to narrow both or it narrows neither.
        val visibleSources =
          remember(remoteComposeSources, state.catalogQuery) {
            filterRemoteComposeSources(remoteComposeSources, state.catalogQuery)
          }
        PanelHeading(
          "Remote Compose documents",
          remoteComposeFailure
            ?: pendingRemoteComposeSource?.let { "Fetching ${it.label}\u2026" }
            ?: "${visibleSources.size} of ${remoteComposeSources.size} published",
        )
        LazyColumn(Modifier.fillMaxWidth().height(180.dp)) {
          itemsIndexed(visibleSources, key = { _, source -> source.id }) { index, source ->
            if (index == 0 || visibleSources[index - 1].group != source.group) {
              GroupHeading(source.group)
            }
            RemoteComposeSourceRow(
              source = source,
              // Enabled off the same question the insert will ask, so a row that cannot land is
              // visibly unavailable rather than pressable and then refused.
              canAdd =
                pendingRemoteComposeSource == null &&
                  canAddCatalogComponent(REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID),
              onAdd = { onAddRemoteComposeSource(source) },
            )
          }
        }
      }
      HorizontalDivider(color = MaterialTheme.colorScheme.outline)
      val matches = layerRows.count { it is EditorLayerRow.Node && it.row.matched }
      // Where each layer row sits vertically, in root pixels, kept in a plain map rather than
      // snapshot state: it is written from layout on every scroll and every relayout, and a
      // recomposition per frame of scrolling is a price the panel does not need to pay. The drag
      // reads it from a callback, which is the only place it is ever read.
      val rowBounds = remember(layerRows) { mutableMapOf<Int, ClosedFloatingPointRange<Float>>() }
      var draggedLayer by remember { mutableStateOf<String?>(null) }
      var landing by remember { mutableStateOf<LayerLanding?>(null) }
      PanelHeading(
        "Layers",
        when {
          draggedLayer != null ->
            landing?.refusal?.message
              ?: landing?.let { "Drop into ${it.target.nodeId}.${it.target.slot}" }
              ?: "Drag over a layer or a slot"
          state.layerQuery.isNotBlank() -> "$matches of ${state.document.nodes.size} match"
          else -> "Drag a row onto a layer or a slot"
        },
      )
      SearchField(
        state.layerQuery,
        // Reusing the catalog's field meant reusing its placeholder, so an empty layers filter
        // invited you to search components. Two fields, two things to look for.
        placeholder = "Filter layers",
        onFocusChanged = onTextInputFocusChanged,
      ) {
        dispatch(UiBuilderEditorEvent.SearchLayers(it))
      }
      // The multi-node inspector is only as reachable as the selection is. Filtering to every text
      // on the screen and then taking all of them is what makes restyling a screen one edit.
      if (state.layerQuery.isNotBlank() && matches > 0) {
        TextButton(
          onClick = {
            onEditorInteraction()
            dispatch(UiBuilderEditorEvent.SelectAllMatches)
          },
          modifier = Modifier.padding(horizontal = 8.dp),
        ) {
          Text("Select all $matches")
        }
      }
      LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(layerRows, key = { _, row -> row.layerKey() }) { index, row ->
          val recordBounds = Modifier.onGloballyPositioned {
            val bounds = it.boundsInRoot()
            rowBounds[index] = bounds.top..bounds.bottom
          }
          when (row) {
            is EditorLayerRow.Slot ->
              SlotRow(
                row = row,
                modifier = recordBounds,
                // The slot a catalog drop would land in, so the answer the panel heading gives in
                // words is also given in the tree, next to the children it would join.
                isCatalogTarget = row.parent == dropTarget,
                landing = landing?.takeIf { it.marker == LayerLandingMarker.Into(index) },
              )
            is EditorLayerRow.Node ->
              LayerRow(
                row = row.row,
                indent = row.indent,
                modifier = recordBounds,
                // Every selected node is highlighted, not just the anchor — a selection you cannot
                // see is one you cannot trust before pressing Delete.
                selected = row.nodeId in state.selection,
                dragged = row.nodeId == draggedLayer,
                landing =
                  landing?.takeIf {
                    it.marker == LayerLandingMarker.Above(index) ||
                      it.marker == LayerLandingMarker.Below(index)
                  },
                collaborators = collaborators.filter { row.nodeId in it.selectedNodeIds },
                onSelect = { gesture ->
                  onEditorInteraction()
                  dispatch(
                    when (gesture) {
                      LayerSelectionGesture.Replace -> UiBuilderEditorEvent.SelectNode(row.nodeId)
                      LayerSelectionGesture.Toggle -> UiBuilderEditorEvent.ToggleNode(row.nodeId)
                      LayerSelectionGesture.Range ->
                        UiBuilderEditorEvent.ExtendSelectionTo(row.nodeId)
                    }
                  )
                },
                onDragTo = { y ->
                  draggedLayer = row.nodeId
                  landing =
                    layerLanding(
                      nodeId = row.nodeId,
                      y = y,
                      rows = layerRows,
                      bounds = rowBounds,
                      document = state.document,
                      refusalOf = { target -> moveRefusal(row.nodeId, target) },
                    )
                },
                onDrop = {
                  val drop = landing
                  draggedLayer = null
                  landing = null
                  if (drop != null) {
                    onEditorInteraction()
                    // Sent even when it will be refused: the reducer owns that answer and reports
                    // it through the same channel as every other refused edit, which is how the
                    // operator learns *why* a slot would not take the layer rather than watching
                    // the gesture evaporate.
                    dispatch(
                      UiBuilderEditorEvent.MoveNodeInto(row.nodeId, drop.target, drop.afterNodeId)
                    )
                  }
                },
                onDragCancel = {
                  draggedLayer = null
                  landing = null
                },
              )
          }
        }
      }
    }
  }
}

@Composable
private fun PinnedDesignCanvas(
  document: UiBuilderDocument,
  selectedNodeId: String?,
  onNodeSelected: (String) -> Unit,
  onCanvasMetrics: (Int, Int, Float) -> Unit,
  onCanvasBounds: (Rect) -> Unit,
  dropHovered: Boolean,
  showSelectionOverlay: Boolean,
  reference: ReferenceOverlayState,
  onMarkDrawn: (ReferenceMarkupKind, List<Float>) -> Unit,
  onPieceMoved: (String, Float, Float) -> Unit,
  collaborators: List<UiBuilderCollaborator>,
  onInspectionSnapshot: ((UiBuilderInspectionSnapshot) -> Unit)?,
  onInspectionInvalidated: ((UiBuilderInspectionCollector) -> Unit)?,
  contentAlignment: Alignment = Alignment.TopStart,
  modifier: Modifier = Modifier,
) {
  val sourceWidth =
    document.environment["widthDp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1280f
  val sourceHeight =
    document.environment["heightDp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 800f
  val density = LocalDensity.current
  var inspection by
    remember(document.id, document.revision) { mutableStateOf<UiBuilderInspectionSnapshot?>(null) }
  BoxWithConstraints(modifier.clipToBounds(), contentAlignment = contentAlignment) {
    val scale = minOf(maxWidth.value / sourceWidth, maxHeight.value / sourceHeight).coerceAtMost(1f)
    Surface(
      Modifier.align(contentAlignment)
        .wrapContentSize(Alignment.TopStart, unbounded = true)
        .requiredSize(sourceWidth.dp, sourceHeight.dp)
        .onSizeChanged { size ->
          val measuredWidth = with(density) { size.width.toDp().value.roundToInt() }
          val measuredHeight = with(density) { size.height.toDp().value.roundToInt() }
          onCanvasMetrics(measuredWidth, measuredHeight, scale)
        }
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
          transformOrigin = TransformOrigin(0f, 0f)
          compositingStrategy = CompositingStrategy.Offscreen
        }
        .onGloballyPositioned { onCanvasBounds(it.boundsInRoot()) }
        .then(
          if (dropHovered) Modifier.border(4.dp, MaterialTheme.colorScheme.primary) else Modifier
        ),
      shape = RoundedCornerShape(0.dp),
      shadowElevation = 0.dp,
    ) {
      Box(Modifier.fillMaxSize()) {
        UiBuilderSurface(
          document = document,
          editorOverlay = showSelectionOverlay,
          selectedNodeId = selectedNodeId,
          onNodeSelected = onNodeSelected,
          onInspectionSnapshot = { snapshot ->
            inspection = snapshot
            onInspectionSnapshot?.invoke(snapshot)
          },
          onInspectionInvalidated = onInspectionInvalidated,
        )
        // Over the document and under the collaborators: the reference is being compared against
        // what the document draws, so it goes on top of that; another person's selection is a fact
        // about this session and must not be hidden by a mock.
        ReferenceOverlayCanvas(reference, onMarkDrawn, onPieceMoved)
        RemotePresenceOverlay(collaborators, inspection)
      }
    }
  }
}

@Composable
private fun RemotePresenceOverlay(
  collaborators: List<UiBuilderCollaborator>,
  inspection: UiBuilderInspectionSnapshot?,
) {
  if (collaborators.isEmpty()) return
  val boundsByNode = inspection?.nodes?.associate { it.nodeId to it.bounds }.orEmpty()
  Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
    collaborators.forEach { collaborator ->
      val color = collaborator.colorArgbHex.toPresenceColor()
      collaborator.selectedNodeIds.forEach { nodeId ->
        val bounds = boundsByNode[nodeId] ?: return@forEach
        drawRect(
          color = color,
          topLeft = Offset(bounds.x, bounds.y),
          size = androidx.compose.ui.geometry.Size(bounds.width, bounds.height),
          style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
        )
      }
    }
  }
}

@Composable
private fun PanelHeading(title: String, supporting: String) {
  Row(
    Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column {
      Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
      Text(
        supporting,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun SearchField(
  value: String,
  placeholder: String,
  onFocusChanged: (Boolean) -> Unit,
  onValueChange: (String) -> Unit,
) {
  Surface(
    Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp, vertical = 5.dp),
    shape = RoundedCornerShape(10.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Row(
      Modifier.padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(Icons.Filled.Search, contentDescription = null, Modifier.size(18.dp))
      Box(Modifier.weight(1f)) {
        if (value.isEmpty()) {
          Text(
            placeholder,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        BasicTextField(
          value = value,
          onValueChange = onValueChange,
          modifier =
            Modifier.fillMaxWidth()
              .onFocusChanged { onFocusChanged(it.isFocused) }
              .semantics { contentDescription = "Component catalog search" },
          textStyle =
            MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
          singleLine = true,
        )
      }
    }
  }
}

@Composable
private fun KindHeading(kind: EditorComponentKind) {
  Text(
    kind.label.uppercase(),
    Modifier.fillMaxWidth()
      .background(Color(0xff202126))
      .padding(horizontal = 14.dp, vertical = 5.dp),
    color = MaterialTheme.colorScheme.primary,
    style = MaterialTheme.typography.labelSmall,
    fontWeight = FontWeight.Bold,
  )
}

/** [KindHeading] for a list grouped by something other than a component kind. */
@Composable
private fun GroupHeading(group: String) {
  Text(
    group.uppercase(),
    Modifier.fillMaxWidth()
      .background(Color(0xff202126))
      .padding(horizontal = 14.dp, vertical = 5.dp),
    color = MaterialTheme.colorScheme.primary,
    style = MaterialTheme.typography.labelSmall,
    fontWeight = FontWeight.Bold,
  )
}

/**
 * One published Remote Compose document, addable into the selected slot.
 *
 * No drag handle, unlike [CatalogRow]. A drag inserts on release, and this insert cannot: the bytes
 * are a network round trip away, so the drop would land nothing and the row would have promised
 * otherwise. Add is honest about being asynchronous; a drag would not be.
 */
@Composable
private fun RemoteComposeSourceRow(
  source: RemoteComposeSource,
  canAdd: Boolean,
  onAdd: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().height(42.dp).padding(start = 26.dp, end = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(source.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
      Text(
        source.id,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    TextButton(onClick = onAdd, enabled = canAdd) {
      Text("Add", Modifier.semantics { contentDescription = "Add ${source.label}" })
    }
  }
}

@Composable
private fun CatalogRow(
  item: EditorCatalogItem,
  onDrag: (Offset?) -> Unit,
  onDrop: (Offset) -> Unit,
  canAdd: Boolean,
  onAdd: () -> Unit,
) {
  var dragDistance by remember { mutableFloatStateOf(0f) }
  var dragOrigin by remember { mutableStateOf(Offset.Zero) }
  var lastPosition by remember { mutableStateOf(Offset.Zero) }
  Row(
    Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      Icons.Filled.DragIndicator,
      contentDescription = "Drag ${item.displayName}",
      modifier =
        Modifier.size(18.dp)
          .onGloballyPositioned { dragOrigin = it.boundsInRoot().topLeft }
          .pointerInput(item.componentId) {
            detectDragGestures(
              onDragStart = {
                dragDistance = 0f
                lastPosition = dragOrigin + it
                onDrag(lastPosition)
              },
              onDragEnd = {
                if (dragDistance > 8f) onDrop(lastPosition) else onDrag(null)
                dragDistance = 0f
              },
              onDragCancel = {
                dragDistance = 0f
                onDrag(null)
              },
              onDrag = { change, amount ->
                change.consume()
                dragDistance += amount.getDistance()
                lastPosition = dragOrigin + change.position
                onDrag(lastPosition)
              },
            )
          },
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(Modifier.padding(start = 8.dp).weight(1f)) {
      Text(item.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
      Text(
        item.componentId,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
      )
    }
    TextButton(
      onClick = onAdd,
      enabled = canAdd,
    ) {
      Text(
        "Add",
        Modifier.semantics { contentDescription = "Add ${item.displayName}" },
      )
    }
  }
}

/**
 * Where a dragged layer would land, and what the panel draws to say so.
 *
 * [marker] is the row the indicator is drawn on rather than a coordinate, because the indicator has
 * to survive the list scrolling under the pointer between the frame that resolved it and the frame
 * that draws it.
 */
private data class LayerLanding(
  val target: ParentSlot,
  val afterNodeId: String?,
  val marker: LayerLandingMarker,
  val refusal: EditorMoveRefusal?,
)

private sealed interface LayerLandingMarker {
  /** Between the row above and row [index]. */
  data class Above(val index: Int) : LayerLandingMarker

  /** Between row [index] and the row below. */
  data class Below(val index: Int) : LayerLandingMarker

  /** Inside the slot row [index] names, as its first child. */
  data class Into(val index: Int) : LayerLandingMarker
}

/**
 * The place a layer released at [y] would go, or null when the pointer is over nothing that can
 * take it.
 *
 * Resolved against the rows' measured bounds rather than a row height times an index: the panel
 * mixes node lines and slot lines, and the list scrolls. A node line splits in half — the top half
 * lands the drag before it, the bottom half after it, both in *that row's* slot, which is what
 * makes a drag between slots expressible at all. A slot line lands it first in that slot, which is
 * the only way into a slot that is still empty.
 */
private fun layerLanding(
  nodeId: String,
  y: Float,
  rows: List<EditorLayerRow>,
  bounds: Map<Int, ClosedFloatingPointRange<Float>>,
  document: UiBuilderDocument,
  refusalOf: (ParentSlot) -> EditorMoveRefusal?,
): LayerLanding? {
  val index = rows.indices.firstOrNull { bounds[it]?.contains(y) == true } ?: return null
  return when (val row = rows[index]) {
    is EditorLayerRow.Slot ->
      LayerLanding(row.parent, null, LayerLandingMarker.Into(index), refusalOf(row.parent))
    is EditorLayerRow.Node -> {
      // A root has no slot to be dropped beside. Dragging one is not refused with a message,
      // because there is nothing here to say no *to* — the pointer is simply over nothing.
      val target = row.row.parent ?: return null
      if (row.nodeId == nodeId) return null
      val span = bounds.getValue(index)
      val after =
        if (y > (span.start + span.endInclusive) / 2f) row.nodeId
        else document.childrenOf(target).takeWhile { it != row.nodeId }.lastOrNull()
      LayerLanding(
        target = target,
        afterNodeId = after,
        marker =
          if (after == row.nodeId) LayerLandingMarker.Below(index)
          else LayerLandingMarker.Above(index),
        refusal = refusalOf(target),
      )
    }
  }
}

private fun UiBuilderDocument.childrenOf(parent: ParentSlot): List<String> =
  nodes[parent.nodeId]?.slots?.get(parent.slot).orEmpty()

private fun EditorLayerRow.layerKey(): String =
  when (this) {
    is EditorLayerRow.Node -> "node:$nodeId"
    is EditorLayerRow.Slot -> "slot:${parent.nodeId}.${parent.slot}"
  }

/** The colour a landing indicator is drawn in: the accent when it will land, the error when not. */
@Composable
private fun LayerLanding.markerColor(): Color =
  if (refusal == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

/**
 * The slot a group of children sits in.
 *
 * Not selectable and not draggable: a slot is not a node, it is the place one goes. It is a drop
 * target, though, and the only one an empty slot has.
 */
@Composable
private fun SlotRow(
  row: EditorLayerRow.Slot,
  isCatalogTarget: Boolean,
  landing: LayerLanding?,
  modifier: Modifier = Modifier,
) {
  val accent = landing?.markerColor()
  Row(
    modifier
      .fillMaxWidth()
      .height(26.dp)
      .then(
        if (accent != null) Modifier.background(accent.copy(alpha = 0.22f))
        else if (isCatalogTarget) Modifier.background(Color(0xff26304a)) else Modifier
      )
      .padding(start = (8 + row.indent * 12).dp, end = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      row.parent.slot,
      Modifier.weight(1f).semantics {
        contentDescription =
          "Slot ${row.parent.slot} of ${row.parent.nodeId}, ${row.childCount} of " +
            (row.maxChildren?.toString() ?: "any")
      },
      color = accent ?: MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      // What is in the slot and what it will take, because "full" is the commonest reason a drop
      // is refused and the panel should have said so before the drop.
      when {
        row.childCount == 0 -> "empty"
        row.maxChildren != null -> "${row.childCount}/${row.maxChildren}"
        else -> row.childCount.toString()
      },
      color =
        if (row.full) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
    )
  }
}

@Composable
private fun LayerRow(
  row: EditorTreeRow,
  indent: Int,
  selected: Boolean,
  dragged: Boolean,
  landing: LayerLanding?,
  collaborators: List<UiBuilderCollaborator>,
  onSelect: (LayerSelectionGesture) -> Unit,
  onDragTo: (Float) -> Unit,
  onDrop: () -> Unit,
  onDragCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Where the handle sits in the window, so the pointer's offset inside it can be turned into the
  // one coordinate the whole panel shares. A drag leaves the handle immediately, and every row it
  // then passes over reports its own bounds in that same space.
  var handleOrigin by remember { mutableStateOf(Offset.Zero) }
  val background =
    when {
      dragged -> Color(0xff3b4468)
      selected -> Color(0xff30385a)
      else -> Color.Transparent
    }
  val marker = landing?.markerColor()
  Row(
    modifier
      .fillMaxWidth()
      .height(34.dp)
      .background(background)
      .drawBehind {
        // Drawn as a line at the edge the layer would land on rather than as a highlight over the
        // row, because "before this one" and "after this one" are different answers and a
        // highlight cannot tell them apart.
        if (marker == null) return@drawBehind
        val above = landing.marker is LayerLandingMarker.Above
        drawRect(
          color = marker,
          topLeft = Offset(0f, if (above) 0f else size.height - 3f),
          size = Size(size.width, 3f),
        )
      }
      .padding(start = (8 + indent * 12).dp, end = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // A 16dp icon in a 26dp target. The icon is the affordance; the box is what a pointer actually
    // has to hit, and the difference is most of why the drag read as broken.
    Box(
      Modifier.size(26.dp)
        .onGloballyPositioned { handleOrigin = it.boundsInRoot().topLeft }
        .pointerInput(row.nodeId) {
          detectDragGestures(
            onDragStart = { onDragTo(handleOrigin.y + it.y) },
            onDragEnd = onDrop,
            onDragCancel = onDragCancel,
            onDrag = { change, _ ->
              change.consume()
              onDragTo(handleOrigin.y + change.position.y)
            },
          )
        },
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        Icons.Filled.DragIndicator,
        contentDescription = "Reorder ${row.nodeId}",
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Row(
      Modifier.fillMaxHeight()
        .weight(1f)
        // Not `clickable`: it cannot see which modifier keys are down, and ctrl/⌘-click and
        // shift-click are how a selection is built up in every tool people arrive from.
        //
        // On the release, not the press. Every attempt to scroll this list on a touch screen
        // begins with a press, and selecting there meant scrolling the layers panel changed the
        // selection. `waitForUpOrCancellation` returns null once an ancestor claims the gesture,
        // which is the cancellation `clickable` gave for free and this had to get back.
        .pointerInput(row.nodeId) {
          awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val modifiers = currentEvent.keyboardModifiers
            if (waitForUpOrCancellation() == null) return@awaitEachGesture
            onSelect(
              when {
                modifiers.isShiftPressed -> LayerSelectionGesture.Range
                modifiers.isCtrlPressed || modifiers.isMetaPressed -> LayerSelectionGesture.Toggle
                else -> LayerSelectionGesture.Replace
              }
            )
          }
        }
        // Dropping `clickable` also dropped the activation action, the focusability and the key
        // handling it supplied, so a screen reader could find a layer and not select it, and the
        // keyboard could neither reach one nor activate it. The pointer path keeps the modifier
        // keys; these restore the rest. `focusable` and a semantics action are not enough on their
        // own: they expose focus and an accessibility action, and leave Enter and Space inert.
        .onKeyEvent { event ->
          if (
            event.type == KeyEventType.KeyUp &&
              (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar)
          ) {
            onSelect(
              when {
                event.isShiftPressed -> LayerSelectionGesture.Range
                event.isCtrlPressed || event.isMetaPressed -> LayerSelectionGesture.Toggle
                else -> LayerSelectionGesture.Replace
              }
            )
            true
          } else false
        }
        .focusable()
        .semantics {
          contentDescription = "Select ${row.nodeId}"
          this.selected = selected
          onClick(label = "Select") {
            onSelect(LayerSelectionGesture.Replace)
            true
          }
        },
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        row.label,
        Modifier.padding(start = 5.dp).weight(1f),
        // A row kept only to carry a matching descendant is context, and reads as context. Without
        // this a filter looks like it matched the ancestors too.
        color =
          if (row.matched) MaterialTheme.colorScheme.onSurface
          else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      collaborators.take(3).forEach { collaborator ->
        Box(
          Modifier.padding(end = 3.dp)
            .size(8.dp)
            .background(collaborator.colorArgbHex.toPresenceColor(), RoundedCornerShape(4.dp))
            .clearAndSetSemantics {}
        )
      }
      // The type when the row is named after its content, the id otherwise. A content-named row
      // would otherwise stop saying what it is, and an unnamed one already says that in `label`.
      Text(
        if (row.named) row.componentLabel else row.nodeId,
        Modifier.width(92.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

private fun String.toPresenceColor(): Color {
  val hex = removePrefix("#")
  val argb = hex.takeIf { it.length == 8 }?.toULongOrNull(16) ?: return Color(0xff7788aa)
  return Color(argb.toInt())
}

@Composable
private fun PropertyInspector(
  state: UiBuilderEditorState,
  fields: List<EditorPropertyField>,
  stateVariables: List<String>,
  comparisonBindingProperties: Set<String>,
  bindableProperties: Set<String>,
  problems: List<EditorProblem>,
  themeSettings: EditorThemeSettings,
  devicePresets: List<UiBuilderDevicePreset>,
  onPickReference: (suspend () -> ReferenceImportOutcome)?,
  onSnapshotDesign: (suspend () -> ReferenceImportOutcome)?,
  onFlatten: () -> Unit,
  catalogItems: List<EditorCatalogItem>,
  onPlaceComponent: (String) -> Unit,
  onPromotePiece: (ReferencePiece) -> Unit,
  canPromotePiece: (ReferencePiece) -> Boolean,
  referenceStatus: String?,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
  modifier: Modifier = Modifier.width(INSPECTOR_WIDTH).fillMaxHeight(),
) {
  val node = state.selectedNodeId?.let(state.document.nodes::get)
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.padding(16.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // "Layer", not "Properties". It is the odd label out beside Theme, Screen and Issues, it
        // is the one a fourth tab leaves no room for, and it is the word the panel beside it
        // already uses for the same thing.
        InspectorModeButton(
          "Layer",
          EditorInspectorMode.Properties,
          state,
          dispatch,
          Modifier.weight(1f),
        )
        InspectorModeButton(
          "Theme",
          EditorInspectorMode.Theme,
          state,
          dispatch,
          Modifier.weight(1f),
        )
        InspectorModeButton(
          "Screen",
          EditorInspectorMode.Screen,
          state,
          dispatch,
          Modifier.weight(1f),
        )
        InspectorModeButton(
          if (problems.isEmpty()) "Issues" else "Issues ${problems.size}",
          EditorInspectorMode.Issues,
          state,
          dispatch,
          Modifier.weight(1f),
        )
      }
      HorizontalDivider(Modifier.padding(vertical = 6.dp))
      if (state.inspectorMode == EditorInspectorMode.Issues) {
        ProblemsInspector(problems, dispatch)
        return@Column
      }
      if (state.inspectorMode == EditorInspectorMode.Theme) {
        ThemeBuilder(themeSettings, onTextInputFocusChanged, dispatch)
        return@Column
      }
      if (state.inspectorMode == EditorInspectorMode.Screen) {
        // Scrolled, because the frame controls already filled the panel before the reference
        // section joined them below. A tab that silently clips its last control is worse than one
        // that scrolls.
        Column(Modifier.verticalScroll(rememberScrollState())) {
          ScreenEnvironmentInspector(
            document = state.document,
            devicePresets = devicePresets,
            onTextInputFocusChanged = onTextInputFocusChanged,
            dispatch = dispatch,
          )
          HorizontalDivider(
            Modifier.padding(vertical = 14.dp),
            color = MaterialTheme.colorScheme.outline,
          )
          ReferenceInspector(
            reference = state.reference,
            themeSettings = themeSettings,
            onPickReference = onPickReference,
            onSnapshotDesign = onSnapshotDesign,
            onFlatten = onFlatten,
            catalogItems = catalogItems,
            onPlaceComponent = onPlaceComponent,
            onPromotePiece = onPromotePiece,
            canPromotePiece = canPromotePiece,
            hostStatus = referenceStatus,
            dispatch = dispatch,
          )
        }
        return@Column
      }
      if (node == null) {
        Text(
          "Select a layer on the canvas or in the tree.",
          Modifier.padding(top = 16.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return@Column
      }
      Text(
        node.componentId,
        Modifier.padding(top = 8.dp),
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        node.id,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
      HorizontalDivider(
        Modifier.padding(vertical = 14.dp),
        color = MaterialTheme.colorScheme.outline,
      )
      LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
        itemsIndexed(fields, key = { _, field -> field.name }) { _, field ->
          PropertyControl(
            field = field,
            stateVariables = if (field.name in bindableProperties) stateVariables else emptyList(),
            needsComparison = field.name in comparisonBindingProperties,
            onTextInputFocusChanged = onTextInputFocusChanged,
            onBind = { variable, equalsValue ->
              dispatch(
                UiBuilderEditorEvent.BindPropertyToState(
                  node.id,
                  field.name,
                  variable,
                  equalsValue,
                )
              )
            },
            onUnbind = { dispatch(UiBuilderEditorEvent.UnbindProperty(node.id, field.name)) },
            commit = { value ->
              dispatch(UiBuilderEditorEvent.CommitProperty(node.id, field.name, value))
            },
          )
        }
        if (fields.isEmpty()) {
          item {
            Text(
              "This component has no catalog properties.",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
        if (node.modifiers.isNotEmpty()) {
          item {
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Modifiers", style = MaterialTheme.typography.labelLarge)
            Text(
              "Shown from the document. Modifier parameter editing waits for an authoritative modifier operation.",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.labelSmall,
            )
          }
          itemsIndexed(node.modifiers) { _, modifier ->
            Text(
              modifier.toString(),
              Modifier.padding(top = 6.dp),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
      val outcome = state.lastOutcome
      if (outcome != null) {
        Text(
          when (outcome) {
            is CommandOutcome.Accepted ->
              "Operation accepted at revision ${outcome.committedRevision}"
            is CommandOutcome.Rejected -> "${outcome.code}: ${outcome.message}"
          },
          color =
            if (outcome is CommandOutcome.Accepted) Color(0xffa8f2c6)
            else MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }
  }
}

@Composable
private fun PropertyControl(
  field: EditorPropertyField,
  stateVariables: List<String>,
  needsComparison: Boolean,
  onTextInputFocusChanged: (Boolean) -> Unit,
  onBind: (String, String?) -> Unit,
  onUnbind: () -> Unit,
  commit: (String) -> Unit,
) {
  Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
    Text(
      field.label +
        (if (field.required) " *" else "") +
        // Say what an edit will hit. A control that silently spans six nodes is one people stop
        // trusting the first time it changes something they were not looking at.
        (if (field.nodeCount > 1) " · ${field.nodeCount} selected" else "") +
        (if (field.mixed) " · mixed" else ""),
      style = MaterialTheme.typography.labelLarge,
    )
    val bound = field.boundVariable
    if (bound != null) {
      // The literal control is not drawn for a bound property, because it does not work: an edit
      // is refused with "cannot be safely edited from its catalog metadata", which is a true
      // message and a poor answer to a control that looks editable. What a bound property needs is
      // to say what it is bound to and offer the way back.
      StateBindingRow(bound, onUnbind)
      return@Column
    }
    if (stateVariables.isNotEmpty() && field.control != EditorPropertyControl.Unsupported) {
      StateBindMenu(field, stateVariables, needsComparison, onTextInputFocusChanged, onBind)
    }
    when (field.control) {
      EditorPropertyControl.Boolean -> {
        val checked = field.value.toBooleanStrictOrNull() ?: false
        Row(
          Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(if (checked) "On" else "Off", style = MaterialTheme.typography.bodySmall)
          Switch(
            checked = checked,
            onCheckedChange = { commit(it.toString()) },
            modifier = Modifier.semantics { contentDescription = "${field.label} property" },
          )
        }
      }
      EditorPropertyControl.Enum ->
        if (field.name == "iconKey")
          GoogleIconPropertyControl(field, onTextInputFocusChanged, commit)
        else EnumPropertyControl(field, commit)
      EditorPropertyControl.Number ->
        DraftPropertyControl(field, onTextInputFocusChanged, commit, showSteppers = true)
      EditorPropertyControl.Text,
      EditorPropertyControl.Color ->
        DraftPropertyControl(field, onTextInputFocusChanged, commit, showSteppers = false)
      EditorPropertyControl.Unsupported ->
        Text(
          field.value.ifEmpty { "Not set" },
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
    }
    field.notes?.let {
      Text(
        it,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    field.error?.let {
      Text(
        it,
        Modifier.semantics { contentDescription = "${field.label} validation error" },
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.labelSmall,
      )
    }
  }
}

/**
 * What a bound property says instead of a control it cannot honour.
 *
 * The reducer refuses a literal edit on a state-bound property — the value is the binding, and
 * overwriting it silently would be the wrong answer — so the inspector drew a control that always
 * failed. This says what it is bound to and offers the one edit that does work.
 */
@Composable
private fun StateBindingRow(variable: String, onUnbind: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().padding(top = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
      Text(
        "state · $variable",
        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
      )
    }
    TextButton(
      onClick = onUnbind,
      modifier = Modifier.semantics { contentDescription = "Unbind $variable" },
    ) {
      Text("Unbind")
    }
  }
}

/**
 * Binding a property to a declared state variable.
 *
 * Two shapes, and the catalog decides which: a bare read yields the variable's value and suits a
 * property typed like it, while a boolean property cannot take a string variable's value and needs
 * `stateEquals` — a comparison, which needs a value to compare against. Asking for that value only
 * once a variable is chosen keeps the common case one click.
 */
@Composable
private fun StateBindMenu(
  field: EditorPropertyField,
  stateVariables: List<String>,
  needsComparison: Boolean,
  onTextInputFocusChanged: (Boolean) -> Unit,
  onBind: (String, String?) -> Unit,
) {
  var open by remember(field.nodeId, field.name) { mutableStateOf(false) }
  var pending by remember(field.nodeId, field.name) { mutableStateOf<String?>(null) }
  var comparison by remember(field.nodeId, field.name) { mutableStateOf("") }
  Box {
    TextButton(
      onClick = { open = true },
      modifier = Modifier.semantics { contentDescription = "Bind ${field.label} to state" },
    ) {
      Text("Bind to state", style = MaterialTheme.typography.labelMedium)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      stateVariables.forEach { variable ->
        DropdownMenuItem(
          text = { Text(variable) },
          onClick = {
            open = false
            if (needsComparison) pending = variable else onBind(variable, null)
          },
        )
      }
    }
  }
  val variable = pending
  if (variable != null) {
    Text(
      "$variable equals",
      Modifier.padding(top = 4.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.weight(1f)) {
        SearchField(
          comparison,
          placeholder = "Value to compare",
          onFocusChanged = onTextInputFocusChanged,
        ) {
          comparison = it
        }
      }
      TextButton(
        onClick = {
          onBind(variable, comparison)
          pending = null
          comparison = ""
        },
        enabled = comparison.isNotBlank(),
      ) {
        Text("Bind")
      }
    }
  }
}

@Composable
private fun DraftPropertyControl(
  field: EditorPropertyField,
  onTextInputFocusChanged: (Boolean) -> Unit,
  commit: (String) -> Unit,
  showSteppers: Boolean,
) {
  var draft by remember(field.nodeId, field.name, field.value) { mutableStateOf(field.value) }
  BasicTextField(
    value = draft,
    onValueChange = { draft = it },
    modifier =
      Modifier.fillMaxWidth()
        .onFocusChanged { onTextInputFocusChanged(it.isFocused) }
        .semantics { contentDescription = "${field.label} property" }
        .padding(top = 7.dp)
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
        .padding(10.dp),
    textStyle =
      MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
    singleLine = field.name != "text",
  )
  if (showSteppers) {
    val bounds = field.numberBounds
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      TextButton(
        onClick = {
          val current = draft.toDoubleOrNull() ?: bounds?.minimum ?: 0.0
          draft =
            (current - (bounds?.step ?: 1.0))
              .coerceIn(bounds!!.minimum, bounds.maximum)
              .editorNumber(bounds.integer)
          commit(draft)
        }
      ) {
        Text("−")
      }
      Text(
        bounds
          ?.let { "${it.minimum.editorNumber(it.integer)}…${it.maximum.editorNumber(it.integer)}" }
          .orEmpty(),
        Modifier.padding(top = 14.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
      TextButton(
        onClick = {
          val current = draft.toDoubleOrNull() ?: bounds?.minimum ?: 0.0
          draft =
            (current + (bounds?.step ?: 1.0))
              .coerceIn(bounds!!.minimum, bounds.maximum)
              .editorNumber(bounds.integer)
          commit(draft)
        }
      ) {
        Text("+")
      }
    }
  }
  Button(
    onClick = { commit(draft) },
    modifier = Modifier.padding(top = 7.dp).fillMaxWidth(),
  ) {
    Text("Apply ${field.label.lowercase()}")
  }
  if (field.name == "text") {
    TextButton(onClick = { commit("Edited in Compose") }, modifier = Modifier.fillMaxWidth()) {
      Text("Use sample text")
    }
  }
}

/**
 * What the export gate would refuse, listed where a person is already looking.
 *
 * Each row selects its node, because a message naming an id nobody can find is only half an answer.
 * Rows without a node — a catalog pin mismatch, an environment field — are not selectable and say
 * so by not reacting.
 */
@Composable
private fun ProblemsInspector(
  problems: List<EditorProblem>,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  if (problems.isEmpty()) {
    Text(
      "Nothing is blocking a Compose export of this design.",
      Modifier.padding(top = 16.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    return
  }
  Text(
    "These are what the Compose export gate refuses, checked against the whole document rather " +
      "than the last edit.",
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.labelSmall,
  )
  LazyColumn(Modifier.fillMaxWidth().padding(top = 10.dp)) {
    itemsIndexed(problems) { _, problem ->
      Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).let { base ->
          problem.nodeId?.let { id ->
            base.clickable { dispatch(UiBuilderEditorEvent.SelectNode(id)) }
          } ?: base
        }
      ) {
        Text(
          problem.code,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.labelMedium,
        )
        Text(problem.message, style = MaterialTheme.typography.bodySmall)
        val where =
          listOfNotNull(problem.nodeId, problem.componentId).joinToString(" · ").ifEmpty { null }
        if (where != null) {
          Text(
            where,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
          )
        }
      }
    }
  }
}

/**
 * The Kotlin the Compose export would write for the document on the canvas.
 *
 * ## Why it is here rather than behind the export button
 *
 * The builder's proposition is that a design *is* code. Until this pane the only way to read the
 * code a design produced was to run an export and open the artifact, which is a round trip long
 * enough that nobody made it after a single edit — so "what did dropping that Column do to the
 * source" was, in practice, unanswerable.
 *
 * ## Why it shows refusals in the same place
 *
 * [EditorGeneratedCode.Refused] is not an error state of this pane, it is the pane's other answer.
 * A design the export cannot express has no source to show, and the reasons are what a designer
 * needs in order to make one — putting them behind a different tab would mean the pane silently
 * showed nothing whenever it mattered most.
 *
 * The text is selectable and not editable: it is generated, and a pane that let you type into it
 * would be offering an edit the next keystroke on the canvas throws away.
 */
@Composable
private fun GeneratedCodePane(
  code: EditorGeneratedCode,
  /**
   * What produced the source, because two generators feed this pane.
   *
   * A widget's Kotlin is not a Compose export and saying so would be wrong twice over: it is Remote
   * Compose, and it goes out as a `WearWidgetDocument` rather than into that export's package.
   */
  caption: String,
  modifier: Modifier = Modifier,
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
      when (code) {
        is EditorGeneratedCode.Source -> {
          Text(
            caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
          )
          val vertical = rememberScrollState()
          val horizontal = rememberScrollState()
          val syntaxTheme = rememberCodePaneSyntaxTheme()
          // Tokenizing is keyed on the source, so an edit elsewhere on the canvas — a selection, a
          // scroll, a drag over the drop target — recomposes this pane without re-running it.
          val highlighted =
            remember(code.kotlin, syntaxTheme) { highlightKotlin(code.kotlin, syntaxTheme) }
          SelectionContainer(Modifier.padding(top = 8.dp)) {
            Text(
              highlighted,
              Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(horizontal),
              // The palette's own foreground rather than `onSurface`: whatever the highlighter did
              // not claim is still code, and two sources for the one colour would show up as the
              // unstyled runs sitting a shade off the styled ones.
              color = syntaxTheme.codeColor(),
              // Generated Kotlin is aligned by column, so a proportional face would misreport the
              // indentation the export actually writes.
              fontFamily = FontFamily.Monospace,
              style = MaterialTheme.typography.bodySmall,
              softWrap = false,
            )
          }
        }
        is EditorGeneratedCode.Refused -> {
          Text(
            "No Compose source · the export would refuse this design",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall,
          )
          LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            itemsIndexed(code.reasons) { _, reason ->
              Text(
                reason,
                Modifier.padding(bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
              )
            }
          }
        }
      }
    }
  }
}

/**
 * The same design, drawn by real Compose on the host instead of by this browser.
 *
 * ## Why the editor shows two renderers at once
 *
 * The Wasm canvas is immediate and costs the server nothing, and it cannot answer "what does this
 * look like on Android" — platform text metrics, the device frames the render lane knows, the
 * Robolectric-backed lane. Side by side is deliberate rather than a toggle: a difference between
 * the two renderers is a thing a designer needs to *see*, and one that replaced the other would
 * hide exactly that.
 *
 * ## Refusals, again, in the same place
 *
 * A design the generator cannot express has no native render, and the reasons are the actionable
 * half — the same rule the code pane follows, and the same list, because it is the same gate. A
 * transport failure says something different and says it separately: try again, versus fix the
 * design.
 */
@Composable
private fun NativeRenderPane(
  render: UiBuilderNativeRender?,
  pending: Boolean,
  selectedNodeId: String?,
  onNodeSelected: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
      Text(
        "Native render · compiled on the host",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
      when {
        pending && render == null ->
          Text(
            "Compiling this design…",
            Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        render == null ->
          Text(
            "Not rendered yet.",
            Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        render.failure != null ->
          Text(
            render.failure,
            Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
        render.refusals.isNotEmpty() -> {
          Text(
            "No native render · the generator refuses this design",
            Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall,
          )
          LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            itemsIndexed(render.refusals) { _, reason ->
              Text(
                reason,
                Modifier.padding(bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
              )
            }
          }
        }
        render.image != null ->
          NativeRenderFrame(
            image = render.image,
            nodeBounds = render.nodeBounds,
            selectedNodeId = selectedNodeId,
            onNodeSelected = onNodeSelected,
            modifier = Modifier.fillMaxSize().padding(top = 8.dp),
          )
        else ->
          Text(
            "The host compiled this design and returned no frame.",
            Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
          )
      }
    }
  }
}

/**
 * The frame itself, with the overlay that makes it a surface rather than a picture.
 *
 * ## The one coordinate transform
 *
 * The host reports each node's box in the frame's own pixels, and the frame is drawn scaled to fit
 * this pane. So there is exactly one factor — `displayed / image` — and it is computed here, where
 * the displayed size is decided, rather than being sent over the wire in a space that would have to
 * agree with a layout nobody on the server can see. The image is laid out at that exact size
 * instead of being left to [ContentScale.Fit], so the overlay and the pixels underneath it cannot
 * disagree about where the frame starts.
 *
 * ## Hit-testing picks the smallest box
 *
 * A click lands inside every ancestor of the node that drew it — the column, the card, the row —
 * and the innermost of those is the one a designer means, which is what the layers panel would
 * select too. Ties (a wrapper exactly the size of its child) go to whichever the host reported
 * first; there is no better answer and both are the same rectangle.
 *
 * A node with no reported box is not selectable here. That is the honest outcome for something the
 * render never placed, and the layers panel still selects it.
 */
@Composable
private fun NativeRenderFrame(
  image: ImageBitmap,
  nodeBounds: Map<String, UiBuilderNativeNodeBounds>,
  selectedNodeId: String?,
  onNodeSelected: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier) {
    val density = LocalDensity.current
    val frameWidth = image.width.toFloat()
    val frameHeight = image.height.toFloat()
    val availableWidth = with(density) { maxWidth.toPx() }
    val availableHeight = with(density) { maxHeight.toPx() }
    // `coerceAtMost(1f)`: a frame smaller than the pane is shown at its own size, because a render
    // blown up past 1:1 is a blurrier answer to "what does this look like on the device".
    val scale =
      minOf(availableWidth / frameWidth, availableHeight / frameHeight)
        .coerceAtMost(1f)
        .coerceAtLeast(0.01f)
    val shownWidth = with(density) { (frameWidth * scale).toDp() }
    val shownHeight = with(density) { (frameHeight * scale).toDp() }
    Box(
      Modifier.size(shownWidth, shownHeight).pointerInput(nodeBounds, scale) {
        detectTapGestures { offset ->
          val x = offset.x / scale
          val y = offset.y / scale
          nodeBounds
            .filterValues { it.contains(x, y) }
            .minByOrNull { it.value.area }
            ?.let { onNodeSelected(it.key) }
        }
      }
    ) {
      Image(
        bitmap = image,
        contentDescription = "Native render of this design",
        // The box is already the frame's exact displayed size, so this only says "no letterboxing
        // inside it" — the fit was decided above, where the overlay's scale was.
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.FillBounds,
      )
      val selected = selectedNodeId?.let(nodeBounds::get)
      if (selected != null) {
        val outline = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
          drawRect(
            color = outline,
            topLeft = Offset(selected.x * scale, selected.y * scale),
            size = Size(selected.width * scale, selected.height * scale),
            style = Stroke(width = 2f),
          )
        }
      }
    }
  }
}

@Composable
private fun InspectorModeButton(
  label: String,
  mode: EditorInspectorMode,
  state: UiBuilderEditorState,
  dispatch: (UiBuilderEditorEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val selected = state.inspectorMode == mode
  Surface(
    modifier
      .height(28.dp)
      .semantics {
        contentDescription = if (mode == EditorInspectorMode.Theme) "$label inspector" else label
      }
      .clickable { dispatch(UiBuilderEditorEvent.ShowInspector(mode)) },
    shape = RoundedCornerShape(9.dp),
    color =
      if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Text(
      label,
      Modifier.padding(vertical = 6.dp),
      color =
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
      // A size down from labelLarge: four tabs share the width three used to, and a clipped tab
      // label is worse than a smaller one.
      style = MaterialTheme.typography.labelMedium,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
      textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      maxLines = 1,
    )
  }
}

@Composable
private fun ScreenEnvironmentInspector(
  document: UiBuilderDocument,
  devicePresets: List<UiBuilderDevicePreset>,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  val current = document.screenEnvironmentSettings()
  var width by remember(document.id, current) { mutableStateOf(current.widthDp.toString()) }
  var height by remember(document.id, current) { mutableStateOf(current.heightDp.toString()) }
  var density by remember(document.id, current) { mutableStateOf(current.density.toString()) }
  var fontScale by remember(document.id, current) { mutableStateOf(current.fontScale.toString()) }
  var locale by remember(document.id, current) { mutableStateOf(current.locale) }
  var theme by remember(document.id, current) { mutableStateOf(current.theme) }
  var layoutDirection by remember(document.id, current) { mutableStateOf(current.layoutDirection) }
  var validationError by remember(document.id, current) { mutableStateOf<String?>(null) }

  Text(
    "Screen environment",
    style = MaterialTheme.typography.titleSmall,
    fontWeight = FontWeight.Bold,
  )
  Text(
    "Applies to the complete render, never an individual component.",
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodySmall,
  )
  HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)
  if (devicePresets.isNotEmpty()) {
    DevicePresetPicker(
      presets = devicePresets,
      selected = current.matchingDevicePreset(devicePresets),
      onPick = { preset ->
        // Width, height and density move together, in one dispatch, so the frame is one undoable
        // step — `updateEnvironment` folds the three `SetEnvironment` operations into a single
        // `DesignCommand`, and undo targets a command. Applying them as three edits would make
        // checking a phone, then a tablet, then undoing leave a phone-width tablet on the canvas.
        val applied = current.withDevicePreset(preset)
        width = applied.widthDp.toString()
        height = applied.heightDp.toString()
        density = applied.density.toString()
        validationError = applied.validationError()
        if (validationError == null) dispatch(UiBuilderEditorEvent.UpdateEnvironment(applied))
      },
    )
  }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    EnvironmentTextField(
      label = "Width (dp)",
      value = width,
      modifier = Modifier.weight(1f),
      onFocusChanged = onTextInputFocusChanged,
      onValueChange = { width = it },
    )
    EnvironmentTextField(
      label = "Height (dp)",
      value = height,
      modifier = Modifier.weight(1f),
      onFocusChanged = onTextInputFocusChanged,
      onValueChange = { height = it },
    )
  }
  Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    EnvironmentTextField(
      label = "Density",
      value = density,
      modifier = Modifier.weight(1f),
      onFocusChanged = onTextInputFocusChanged,
      onValueChange = { density = it },
    )
    EnvironmentTextField(
      label = "Font scale",
      value = fontScale,
      modifier = Modifier.weight(1f),
      onFocusChanged = onTextInputFocusChanged,
      onValueChange = { fontScale = it },
    )
  }
  EnvironmentTextField(
    label = "Locale",
    value = locale,
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    onFocusChanged = onTextInputFocusChanged,
    onValueChange = { locale = it },
  )
  EnvironmentChoiceHeading("Theme")
  Row(Modifier.fillMaxWidth()) {
    EditorScreenTheme.entries.forEach { option ->
      TextButton(
        onClick = { theme = option },
        modifier = Modifier.weight(1f).semantics { contentDescription = "${option.label} theme" },
      ) {
        Text(
          option.label,
          fontWeight = if (theme == option) FontWeight.Bold else FontWeight.Normal,
          color =
            if (theme == option) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
  EnvironmentChoiceHeading("Layout direction")
  Row(Modifier.fillMaxWidth()) {
    EditorLayoutDirection.entries.forEach { option ->
      TextButton(
        onClick = { layoutDirection = option },
        modifier =
          Modifier.weight(1f).semantics { contentDescription = "${option.label} layout direction" },
      ) {
        Text(
          option.label,
          fontWeight = if (layoutDirection == option) FontWeight.Bold else FontWeight.Normal,
          color =
            if (layoutDirection == option) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
  validationError?.let {
    Text(
      it,
      color = MaterialTheme.colorScheme.error,
      style = MaterialTheme.typography.bodySmall,
    )
  }
  Button(
    onClick = {
      val parsed =
        ScreenEnvironmentSettings(
          widthDp = width.toIntOrNull() ?: Int.MIN_VALUE,
          heightDp = height.toIntOrNull() ?: Int.MIN_VALUE,
          density = density.toDoubleOrNull() ?: Double.NaN,
          fontScale = fontScale.toDoubleOrNull() ?: Double.NaN,
          locale = locale.trim(),
          theme = theme,
          layoutDirection = layoutDirection,
        )
      validationError = parsed.validationError()
      if (validationError == null) dispatch(UiBuilderEditorEvent.UpdateEnvironment(parsed))
    },
    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
  ) {
    Text("Apply screen settings")
  }
}

/**
 * The frame menu — grouped by device family, each entry carrying the geometry the render lane
 * resolves for it.
 *
 * Shows every device the catalog knows rather than a curated handful. A curated handful is exactly
 * the hand-maintained list this feature exists to avoid, and it is the list that goes stale the
 * first time the render catalog learns a device.
 */
@Composable
private fun DevicePresetPicker(
  presets: List<UiBuilderDevicePreset>,
  selected: UiBuilderDevicePreset?,
  onPick: (UiBuilderDevicePreset) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Text("Device", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
  Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp)) {
    Button(
      onClick = { expanded = true },
      modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Device preset" },
    ) {
      Text(
        // A hand-typed frame is a legitimate state, not an error — name it rather than showing a
        // device the canvas is not actually at.
        selected?.label ?: "Custom size",
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      presets.groupBy(UiBuilderDevicePreset::group).forEach { (group, devices) ->
        Text(
          group,
          Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Bold,
        )
        devices.forEach { preset ->
          DropdownMenuItem(
            text = {
              Column {
                Text(
                  preset.label,
                  fontWeight =
                    if (preset.id == selected?.id) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                  preset.summary,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  style = MaterialTheme.typography.bodySmall,
                )
              }
            },
            onClick = {
              expanded = false
              onPick(preset)
            },
          )
        }
      }
    }
  }
}

@Composable
private fun EnumPropertyControl(field: EditorPropertyField, commit: (String) -> Unit) {
  var expanded by remember(field.nodeId, field.name) { mutableStateOf(false) }
  Box(Modifier.fillMaxWidth()) {
    Button(
      onClick = { expanded = true },
      modifier =
        Modifier.fillMaxWidth().semantics { contentDescription = "${field.label} property" },
    ) {
      Text(field.value.ifEmpty { "Choose…" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      field.choices.forEach { choice ->
        DropdownMenuItem(
          text = { Text(choice) },
          onClick = {
            expanded = false
            commit(choice)
          },
        )
      }
    }
  }
}

@Composable
private fun GoogleIconPropertyControl(
  field: EditorPropertyField,
  onTextInputFocusChanged: (Boolean) -> Unit,
  commit: (String) -> Unit,
) {
  var expanded by remember(field.nodeId, field.name) { mutableStateOf(false) }
  var query by remember(field.nodeId, field.name) { mutableStateOf("") }
  val current = googleMaterialIcon(field.value)
  Text(
    "Google Material Icons catalog",
    Modifier.padding(top = 7.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.labelSmall,
  )
  Button(
    onClick = { expanded = true },
    modifier =
      Modifier.padding(top = 7.dp).fillMaxWidth().semantics {
        contentDescription = "Choose Google icon"
      },
  ) {
    current?.let { Icon(it.imageVector, null, Modifier.size(20.dp)) }
    Text(current?.label ?: "Choose Google icon", Modifier.padding(start = 8.dp))
  }
  DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
    Text(
      "Google Material Icons",
      Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.Bold,
    )
    BasicTextField(
      value = query,
      onValueChange = { query = it },
      modifier =
        Modifier.width(280.dp)
          .padding(10.dp)
          .semantics { contentDescription = "Google icon search" }
          // The one text field in the editor that never reported focus. Every editor chord is
          // gated on `textInputFocused`, so while someone typed an icon name here Backspace still
          // meant delete-the-selection and Ctrl/⌘+V still meant paste-a-subtree.
          .onFocusChanged { onTextInputFocusChanged(it.isFocused) }
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
          .padding(10.dp),
      singleLine = true,
      textStyle =
        MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
    )
    GoogleMaterialIcons.filter {
        query.isBlank() ||
          it.label.contains(query, ignoreCase = true) ||
          it.key.contains(query, ignoreCase = true)
      }
      .forEach { icon ->
        DropdownMenuItem(
          text = { Text(icon.label) },
          leadingIcon = { Icon(icon.imageVector, null, Modifier.size(20.dp)) },
          onClick = {
            expanded = false
            commit(icon.key)
          },
        )
      }
  }
}

private fun Double.editorNumber(integer: Boolean): String =
  if (integer || this % 1.0 == 0.0) toLong().toString() else toString()

@Composable
private fun EnvironmentChoiceHeading(label: String) {
  Text(
    label,
    Modifier.padding(top = 8.dp),
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun EnvironmentTextField(
  label: String,
  value: String,
  modifier: Modifier,
  onFocusChanged: (Boolean) -> Unit,
  onValueChange: (String) -> Unit,
) {
  Column(modifier) {
    Text(
      label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      modifier =
        Modifier.fillMaxWidth()
          .onFocusChanged { onFocusChanged(it.isFocused) }
          .semantics { contentDescription = label }
          .padding(top = 3.dp)
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
          .padding(horizontal = 8.dp, vertical = 7.dp),
      textStyle =
        MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
      singleLine = true,
    )
  }
}

@Composable
private fun ThemeBuilder(
  settings: EditorThemeSettings,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var primary by remember(settings) { mutableStateOf(settings.primaryColor) }
  var background by remember(settings) { mutableStateOf(settings.backgroundColor) }
  var surface by remember(settings) { mutableStateOf(settings.surfaceColor) }
  var content by remember(settings) { mutableStateOf(settings.contentColor) }
  var typeScale by remember(settings) { mutableStateOf(settings.typeScale.toString()) }
  var cornerRadius by remember(settings) { mutableStateOf(settings.cornerRadiusDp.toString()) }

  Text("Theme builder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
  Text(
    "Design-wide colours, typography and shapes",
    Modifier.padding(top = 3.dp, bottom = 14.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodySmall,
  )
  ThemeField("Primary colour", primary, onTextInputFocusChanged) { primary = it }
  ThemeField("Background colour", background, onTextInputFocusChanged) { background = it }
  ThemeField("Surface colour", surface, onTextInputFocusChanged) { surface = it }
  ThemeField("Content colour", content, onTextInputFocusChanged) { content = it }
  ThemeField("Type scale (0.75–1.5)", typeScale, onTextInputFocusChanged) { typeScale = it }
  ThemeField("Corner radius (0–48dp)", cornerRadius, onTextInputFocusChanged) { cornerRadius = it }
  Button(
    onClick = {
      dispatch(
        UiBuilderEditorEvent.ApplyTheme(
          EditorThemeSettings(
            primaryColor = primary,
            backgroundColor = background,
            surfaceColor = surface,
            contentColor = content,
            typeScale = typeScale.toFloatOrNull() ?: Float.NaN,
            cornerRadiusDp = cornerRadius.toFloatOrNull() ?: Float.NaN,
          )
        )
      )
    },
    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
  ) {
    Text("Apply theme")
  }
}

@Composable
private fun ThemeField(
  label: String,
  value: String,
  onFocusChanged: (Boolean) -> Unit,
  onValueChange: (String) -> Unit,
) {
  Text(label, style = MaterialTheme.typography.labelMedium)
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    modifier =
      Modifier.fillMaxWidth()
        .padding(top = 4.dp, bottom = 10.dp)
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
        .onFocusChanged { onFocusChanged(it.isFocused) }
        .semantics { contentDescription = label }
        .padding(horizontal = 10.dp, vertical = 8.dp),
    singleLine = true,
    textStyle =
      MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
  )
}
