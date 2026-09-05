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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
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
import androidx.compose.ui.unit.DpOffset
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
 * three-tab width. The tabs are rail switches now, so a sixth panel — Talk — costs this width
 * nothing.
 */
private val INSPECTOR_WIDTH = 320.dp

/** The left panel's width, named for the same reason [INSPECTOR_WIDTH] is. */
private val NAVIGATOR_WIDTH = 280.dp

/**
 * The code dock's width, wider than either side panel.
 *
 * Generated Kotlin is long lines. It used to sit under the canvas for exactly that reason, which
 * cost the canvas its height whenever it was open; docked beside the canvas it costs width, and
 * only while it is open, which is the trade the rail exists to let you make.
 */
private val CODE_DOCK_WIDTH = 520.dp

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
  Layers,
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
   * Which panels the editor starts with open: the components, the layers, the inspector.
   *
   * All three default to closed, because the canvas is what this editor is for and a panel is a
   * question about it. The rail beside each edge says the panel is there; a host that knows its
   * operator wants one open — a preview that exists to diff the panel, say — asks for it.
   */
  initialComponentsOpen: Boolean = false,
  initialLayersOpen: Boolean = false,
  initialInspectorOpen: Boolean = false,
  /**
   * The scale the canvas opens at, or null to frame the design in the workspace.
   *
   * Null everywhere a person is editing: framing is what a design tool does with a window. A host
   * that is *capturing* the canvas — comparing the editor's pixels against the same design drawn by
   * the clean harness — pins 1f instead, because a resampled frame is not the same picture.
   */
  initialCanvasZoom: Float? = null,
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
  /**
   * The discussion about this design, as the host last heard it.
   *
   * Replaced wholesale rather than merged: the host holds a socket onto the server's comment feed
   * and hands over what the server said, so the panel cannot show a reply the server has not
   * stored. See [DesignCommentBoard].
   */
  comments: DesignCommentBoard = DesignCommentBoard(),
  /**
   * Sends one comment, or null where the host keeps no discussion.
   *
   * Null in every preview and test, where the panel then says so rather than offering a Post button
   * that cannot work — the same rule [onPickReference] follows.
   */
  onPostComment: ((DesignCommentDraft) -> Unit)? = null,
  /** Closes a thread, or reopens it. Null alongside a null [onPostComment]. */
  onResolveCommentThread: ((threadId: String, resolved: Boolean) -> Unit)? = null,
  /** A sentence from the host — a refused comment, a feed that dropped. */
  commentStatus: String? = null,
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
            // A catalog whose canvas is only a stand-in opens on the host's renderer instead, where
            // the host has one. Not a preference — on `wear-m3` the canvas draws Material 3
            // lookalikes because a Wasm build cannot link `androidx.wear.compose:compose-material3`
            // at all, so a Wasm-first editor opens every Wear design on a picture of the wrong
            // library. An explicit [initialPreviewSurface] from the host still wins: it is a host
            // saying which surface it wants captured.
            previewSurface =
              if (
                initialPreviewSurface == EditorPreviewSurface.Wasm &&
                  !catalog.previewSurfaces.wasm.fidelity.isAuthoritative &&
                  onRequestNativeRender != null
              ) {
                EditorPreviewSurface.Native
              } else {
                initialPreviewSurface
              },
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
  // The scale the design is pinned at, or null while it is framed to the workspace. Local rather
  // than in [UiBuilderEditorState] for the same reason the open panels are: how far somebody has
  // zoomed in is a fact about their window, not about the design, and an authoritative snapshot
  // that reset it would be worse than one that remembers nothing.
  var canvasZoom by remember(document.id) { mutableStateOf(initialCanvasZoom) }
  // Which control the hover editor should put the caret in, set by an action that just created the
  // value being edited and cleared the moment it lands.
  var hoverFocusTarget by remember(document.id) { mutableStateOf<String?>(null) }
  var textInputFocused by remember { mutableStateOf(false) }
  var mobilePanel by remember(document.id) { mutableStateOf(MobileEditorPanel.None) }
  // Which panels are open. Local rather than in [UiBuilderEditorState] on purpose: what a
  // collaborator has open is not part of the document, and an editor that reopened someone else's
  // panels on every reconcile would be worse than one that remembers nothing.
  var navigatorTab by
    remember(document.id) {
      mutableStateOf(
        when {
          initialLayersOpen -> NavigatorTab.Layers
          initialComponentsOpen -> NavigatorTab.Insert
          else -> null
        }
      )
    }
  var inspectorOpen by remember(document.id) { mutableStateOf(initialInspectorOpen) }
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
  // Which conversation is open, in the panel and under the pin. Editor state rather than document
  // state, and per design: which thread somebody has expanded is a fact about a moment.
  var selectedThreadId by remember(document.id) { mutableStateOf<String?>(null) }
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
  // What the selection bar calls the selection: the layer's own name where the tree has one, its
  // component otherwise, and a count once there is more than one of them.
  val selectionLabel =
    when {
      state.selection.size > 1 -> "${state.selection.size} layers selected"
      else -> {
        val selectedNode = state.selectedNodeId?.let(state.document.nodes::get)
        val row =
          layerRows.filterIsInstance<EditorLayerRow.Node>().firstOrNull {
            it.nodeId == state.selectedNodeId
          }
        if (selectedNode == null) "Nothing selected"
        else "${row?.row?.label ?: selectedNode.componentId} · ${selectedNode.componentId}"
      }
    }
  /**
   * The selection's verbs, as menu rows, for whoever opens a menu under the pointer.
   *
   * Built here rather than at each call site because every question it asks — can this be pasted
   * into, is there anything to unwrap — is the reducer's, and the answers change with every edit.
   * The lambda takes the dismiss the menu owns, so the rows can close the menu they are in.
   */
  val selectionMenu: @Composable (() -> Unit) -> Unit = { close ->
    EditorSelectionMenuItems(
      modifierToggles = reducer.modifierToggles(state),
      onToggleModifier = { type ->
        focusEditor()
        state.selectedNodeId?.let { nodeId ->
          val adding =
            reducer.modifierToggles(state).firstOrNull { it.type == type }?.applied != true
          dispatch(UiBuilderEditorEvent.ToggleModifier(nodeId, type))
          // A modifier with a number worth choosing is added *and* handed the caret: the value the
          // menu picks is a starting point, not a decision.
          hoverFocusTarget =
            if (adding) MODIFIER_FOCUS_FIELDS[type]?.let { "modifier:$type.$it" } else null
        }
      },
      canDuplicate = reducer.canDuplicateSelected(state),
      canCopy = reducer.canCopySelected(state),
      canCut = reducer.canCutSelected(state),
      canPaste = reducer.canPaste(state),
      canDelete = reducer.canDeleteSelected(state),
      wrapCandidates = reducer.wrapCandidates(state),
      canUnwrap = reducer.canUnwrapSelected(state),
      onOpenProperties = {
        focusEditor()
        if (state.codePaneVisible) dispatch(UiBuilderEditorEvent.ToggleCodePane)
        dispatch(UiBuilderEditorEvent.ShowInspector(EditorInspectorMode.Properties))
        inspectorOpen = true
        mobilePanel = MobileEditorPanel.Properties
      },
      onDismiss = close,
      dispatch = ::dispatch,
    )
  }
  val navigator: @Composable (Modifier, NavigatorTab, Boolean, (() -> Unit)?) -> Unit =
    { modifier, navigatorTab, closeAfterDrop, onClose ->
      EditorNavigator(
        state = state,
        tab = navigatorTab,
        onClose = onClose,
        selectionMenu = selectionMenu,
        catalogSystemId = catalog.benchmark.catalogSystemId,
        catalogRows = reducer.catalogRows(state),
        totalCatalogComponents = catalog.components.size,
        layerRows = layerRows,
        collaborators = collaborators,
        dropTarget = reducer.dropTarget(state, draggedComponentId ?: "m3/text"),
        onCatalogDrag = { componentId, position ->
          if (position != null) focusEditor()
          draggedComponentId = componentId
          catalogDragPosition = position
        },
        onCatalogDrop = { componentId, variant, position ->
          // Where it was dropped, not where the selection happens to be. The palette's own legend
          // says a drag inserts the component "where it is dropped", and it did not: every drop
          // landed in the selected node's slot, so dragging onto a card put the component wherever
          // the last click had been. The renderer already reports each slot's box, and the
          // reference
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
            dispatch(UiBuilderEditorEvent.InsertComponent(componentId, target, variant))
            if (closeAfterDrop) mobilePanel = MobileEditorPanel.None
          }
          draggedComponentId = null
          catalogDragPosition = null
        },
        canAddCatalogComponent = { reducer.dropTarget(state, it) != null },
        onCatalogAdd = { componentId, variant ->
          focusEditor()
          reducer.dropTarget(state, componentId)?.let { target ->
            dispatch(UiBuilderEditorEvent.InsertComponent(componentId, target, variant))
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
      commentThreads = comments.pinned(state.reference.marks),
      selectedThreadId = selectedThreadId,
      onCommentThreadSelected = { threadId ->
        selectedThreadId = threadId
        dispatch(UiBuilderEditorEvent.ShowInspector(EditorInspectorMode.Comments))
      },
      onInspectionSnapshot = { snapshot ->
        canvasInspection = snapshot
        onInspectionSnapshot?.invoke(snapshot)
      },
      onInspectionInvalidated = onInspectionInvalidated,
      selectionMenu = selectionMenu,
      hoverEditor =
        if (state.previewMode || state.selection.size != 1) null
        else {
          {
            SelectionHoverEditor(
              label = selectionLabel,
              // The same rule the panel opens on: what the node carries, which is what the export
              // would write. A hovering card is the last place to list what a component *could*
              // have.
              fields =
                reducer.propertyFields(state).filter { field ->
                  field.written ||
                    field.required ||
                    field.boundVariable != null ||
                    field.error != null
                },
              modifierFields = reducer.modifierFields(state),
              focusTarget = hoverFocusTarget,
              onFocusHandled = { hoverFocusTarget = null },
              onCommitProperty = { name, value ->
                state.selectedNodeId?.let {
                  dispatch(UiBuilderEditorEvent.CommitProperty(it, name, value))
                }
              },
              onCommitModifier = { type, field, value ->
                state.selectedNodeId?.let {
                  dispatch(UiBuilderEditorEvent.SetModifierValue(it, type, field, value))
                }
              },
              onTextInputFocusChanged = { textInputFocused = it },
            )
          }
        },
      zoom = canvasZoom,
      onZoomChanged = {
        focusEditor()
        canvasZoom = it
      },
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
      onClose = { inspectorOpen = false },
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
      comments = comments,
      commentStatus = commentStatus,
      selectedThreadId = selectedThreadId,
      onSelectThread = { selectedThreadId = it },
      onPostComment = onPostComment,
      onResolveCommentThread = onResolveCommentThread,
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
            canUndo = reducer.canUndo(state),
            canRedo = reducer.canRedo(state),
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
            previewSurfaces = catalog.previewSurfaces,
            nativeAvailable = onRequestNativeRender != null,
            dispatch = ::dispatch,
          )
        }
        Box(Modifier.fillMaxSize()) {
          if (!compact) {
            // Which dock is showing, derived rather than stored: the code pane and the inspector
            // are one slot, and two flags that could both say yes is a layout bug waiting.
            val dock =
              when {
                state.codePaneVisible -> EditorDock.Code
                inspectorOpen ->
                  EditorDock.entries.first { it.inspectorMode() == state.inspectorMode }
                else -> null
              }
            Row(Modifier.fillMaxSize()) {
              EditorRail(
                NavigatorTab.entries.map { entry ->
                  EditorRailItem(
                    label = entry.label,
                    icon = entry.icon(),
                    selected = navigatorTab == entry,
                    onClick = {
                      focusEditor()
                      navigatorTab = if (navigatorTab == entry) null else entry
                    },
                  )
                }
              )
              navigatorTab?.let { open ->
                navigator(Modifier.width(NAVIGATOR_WIDTH).fillMaxHeight(), open, false) {
                  navigatorTab = null
                }
              }
              Column(Modifier.weight(1f).fillMaxHeight()) {
                // Above the canvas and only with a selection, so the verbs that act on a layer
                // arrive with it rather than sitting greyed in the top bar all session.
                if (state.selection.isNotEmpty()) {
                  SelectionActionBar(
                    selectionLabel = selectionLabel,
                    // The way to the properties of the thing you just selected, from beside the
                    // thing you just selected — offered only while they are not already showing.
                    onOpenProperties =
                      if (dock == EditorDock.Properties) null
                      else {
                        {
                          focusEditor()
                          if (state.codePaneVisible) {
                            dispatch(UiBuilderEditorEvent.ToggleCodePane)
                          }
                          dispatch(
                            UiBuilderEditorEvent.ShowInspector(EditorInspectorMode.Properties)
                          )
                          inspectorOpen = true
                        }
                      },
                    selectionMenu = selectionMenu,
                  )
                }
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
                        .padding(24.dp),
                      // Centred now that the canvas has the window rather than the strip between
                      // two nailed-open panels. A design pinned to the top-left of a workspace it
                      // does not fill reads as a page that failed to load.
                      Alignment.Center,
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
                CanvasStatusBar(
                  state = state,
                  sessionLabel = sessionLabel,
                  dropTargetLabel = reducer.dropTargetLabel(state, draggedComponentId ?: "m3/text"),
                  dragging = draggedComponentId != null,
                )
              }
              when (dock) {
                null -> Unit
                EditorDock.Code ->
                  if (generatedCode != null) {
                    Surface(
                      Modifier.width(CODE_DOCK_WIDTH).fillMaxHeight(),
                      color = MaterialTheme.colorScheme.surface,
                      tonalElevation = 2.dp,
                    ) {
                      Column(Modifier.fillMaxSize()) {
                        DockHeading(
                          title = "Code",
                          supporting = generatedCodeCaption,
                          onClose = { dispatch(UiBuilderEditorEvent.ToggleCodePane) },
                        )
                        GeneratedCodePane(
                          generatedCode,
                          generatedCodeCaption,
                          Modifier.fillMaxSize(),
                        )
                      }
                    }
                  }
                else -> inspector(Modifier.width(INSPECTOR_WIDTH).fillMaxHeight())
              }
              EditorRail(
                EditorDock.entries.map { entry ->
                  EditorRailItem(
                    label = entry.label,
                    icon = entry.icon(),
                    selected = dock == entry,
                    // Two docks carry a count, and both answer the same question from the rail:
                    // how much is waiting behind this icon. Counted rather than dotted, because a
                    // bare dot makes somebody open the panel to find out whether it is one or
                    // twenty.
                    badge =
                      when (entry) {
                        EditorDock.Issues -> problems.size
                        EditorDock.Comments -> comments.openThreads.size
                        else -> 0
                      },
                    onClick = {
                      focusEditor()
                      val mode = entry.inspectorMode()
                      if (mode == null) {
                        dispatch(UiBuilderEditorEvent.ToggleCodePane)
                      } else {
                        if (state.codePaneVisible) {
                          dispatch(UiBuilderEditorEvent.ToggleCodePane)
                        }
                        inspectorOpen = dock != entry
                        if (inspectorOpen) dispatch(UiBuilderEditorEvent.ShowInspector(mode))
                      }
                    },
                  )
                }
              )
            }
          } else {
            canvas(
              Modifier.fillMaxSize()
                .background(Color(0xff0d0e11))
                .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 64.dp),
              Alignment.Center,
            )
            val mobileNavigatorTab =
              when (mobilePanel) {
                MobileEditorPanel.Components -> NavigatorTab.Insert
                MobileEditorPanel.Layers -> NavigatorTab.Layers
                else -> null
              }
            mobileNavigatorTab?.let { open ->
              navigator(
                Modifier.align(Alignment.BottomCenter)
                  .fillMaxWidth()
                  .fillMaxHeight(0.72f)
                  .padding(bottom = 56.dp),
                open,
                true,
              ) {
                mobilePanel = MobileEditorPanel.None
              }
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
      MobilePanelButton("Layers", MobileEditorPanel.Layers, panel, onPanelChanged)
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

/**
 * The editor's top bar: what is being edited, history, what the canvas is for, and which panels are
 * open.
 *
 * Four zones in that order, because this row used to be eighteen text buttons of equal weight.
 * `Duplicate` and `Cut` sat beside `Help` and `Reconnect`, most of them greyed most of the time,
 * and the one control that changes what the canvas *is* — `Preview` — was indistinguishable from
 * the rest. Editing verbs moved to [SelectionActionBar], where they sit beside the thing they act
 * on and are only present when there is one; the document's revision and the session moved to
 * [CanvasStatusBar], where a status line belongs. What is left here is global: identity, undo, the
 * canvas mode, and the panels.
 */
@Composable
private fun EditorToolbar(
  state: UiBuilderEditorState,
  canUndo: Boolean,
  canRedo: Boolean,
  collaborators: List<UiBuilderCollaborator>,
  onNewDesign: (() -> Unit)?,
  onReconnect: (() -> Unit)?,
  onHelp: (() -> Unit)?,
  /**
   * The surface in use, or null where the host cannot compile — a project with one renderer is not
   * offered a choice between it and nothing.
   */
  previewSurface: EditorPreviewSurface? = null,
  /** What this design's catalog says each renderer's picture of it is worth. */
  previewSurfaces: UiBuilderPreviewSurfaces = UiBuilderPreviewSurfaces.DEFAULT,
  /** Whether the host can compile and draw this design at all. */
  nativeAvailable: Boolean = false,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var showShortcuts by remember { mutableStateOf(false) }
  var overflowOpen by remember { mutableStateOf(false) }
  if (showShortcuts) {
    EditorShortcutsDialog(onDismiss = { showShortcuts = false })
  }
  Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
    Row(
      Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      DocumentIdentity(state)
      Spacer(Modifier.width(10.dp))
      ToolbarIconAction("Undo", "Ctrl/⌘+Z", Icons.AutoMirrored.Filled.Undo, canUndo) {
        dispatch(UiBuilderEditorEvent.Undo)
      }
      ToolbarIconAction("Redo", "Ctrl/⌘+Shift+Z", Icons.AutoMirrored.Filled.Redo, canRedo) {
        dispatch(UiBuilderEditorEvent.Redo)
      }
      // Centred rather than left-packed, and the only control in the row wearing a label: it is
      // the mode switch, and a mode switch that reads like a button is the thing people press by
      // accident and cannot find on purpose.
      Spacer(Modifier.weight(1f))
      CanvasModeSwitch(
        previewing = state.previewMode,
        canvasClaim = previewSurfaces.wasm,
        nativeAvailable = nativeAvailable,
        dispatch = dispatch,
      )
      Spacer(Modifier.weight(1f))
      // Only once there is something to hide — a picture, a placed piece or a mark. An
      // always-present control for a feature most designs never use is exactly the crowding the
      // rest of this change is undoing.
      if (state.reference.hasContent) {
        ToolbarToggleAction(
          label = if (state.reference.settings.visible) "Hide reference" else "Show reference",
          icon =
            if (state.reference.settings.visible) Icons.Filled.Visibility
            else Icons.Filled.VisibilityOff,
          checked = state.reference.settings.visible,
        ) {
          dispatch(UiBuilderEditorEvent.ToggleReference)
        }
      }
      ToolbarToggleAction(
        label = if (state.codePaneVisible) "Code · hide" else "Code",
        icon = Icons.Filled.Code,
        checked = state.codePaneVisible,
      ) {
        dispatch(UiBuilderEditorEvent.ToggleCodePane)
      }
      if (previewSurface != null) {
        RenderSurfaceMenu(previewSurface, previewSurfaces, dispatch)
      }
      if (collaborators.isNotEmpty()) {
        Spacer(Modifier.width(6.dp))
        PresenceRow(collaborators)
        Spacer(Modifier.width(6.dp))
      }
      if (onNewDesign != null) {
        ToolbarIconAction("New design", "", Icons.AutoMirrored.Filled.NoteAdd, true, onNewDesign)
      }
      Box {
        ToolbarIconAction("More editor actions", "", Icons.Filled.MoreVert, true) {
          overflowOpen = true
        }
        DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
          DropdownMenuItem(
            text = { Text("Keyboard shortcuts") },
            leadingIcon = { Icon(Icons.Filled.Keyboard, contentDescription = null) },
            onClick = {
              overflowOpen = false
              showShortcuts = true
            },
          )
          if (onReconnect != null) {
            DropdownMenuItem(
              text = { Text("Reconnect") },
              leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
              onClick = {
                overflowOpen = false
                onReconnect()
              },
            )
          }
          if (onHelp != null) {
            DropdownMenuItem(
              text = { Text("Help") },
              leadingIcon = {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null)
              },
              onClick = {
                overflowOpen = false
                onHelp()
              },
            )
          }
        }
      }
    }
  }
}

/** The file, the way a design tool names one: a mark, the title, and what it is pinned to. */
@Composable
private fun DocumentIdentity(state: UiBuilderEditorState, modifier: Modifier = Modifier) {
  val catalogSystemId =
    state.document.catalogPin["systemId"]?.jsonPrimitive?.contentOrNull.orEmpty()
  Row(modifier.widthIn(max = 320.dp), verticalAlignment = Alignment.CenterVertically) {
    Surface(
      Modifier.size(28.dp),
      shape = RoundedCornerShape(8.dp),
      color = MaterialTheme.colorScheme.primary,
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          Icons.Filled.Widgets,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
          tint = MaterialTheme.colorScheme.onPrimary,
        )
      }
    }
    // The design's title and the catalog it is pinned to, selectable: they are the two strings
    // anyone naming this design elsewhere has to reproduce exactly.
    SelectionContainer {
      Column(Modifier.padding(start = 10.dp)) {
        Text(
          state.document.title,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          if (catalogSystemId.isEmpty()) "Compose UI Builder"
          else "Compose UI Builder · $catalogSystemId",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/**
 * Design or Preview, as two positions of one control rather than a button that renames itself.
 *
 * A button reading "Previewing · exit" is a coin toss — it names the state on the way in and the
 * action on the way out — and it is the wrong shape for the question anyway. This is a mode, so it
 * gets the control every tool uses for a mode.
 */
@Composable
private fun CanvasModeSwitch(
  previewing: Boolean,
  /**
   * What the browser's own canvas is worth on this catalog.
   *
   * Preview mode is a claim — "this is your screen, without the editor on top of it" — and on a
   * catalog whose canvas draws stand-ins the claim is false. Where the host can compile the design
   * the mode still exists and answers with the host's renderer instead; where it cannot, the
   * position is refused, carrying the catalog's own sentence about why rather than a grey button.
   */
  canvasClaim: UiBuilderPreviewSurfaces.SurfaceClaim = UiBuilderPreviewSurfaces.DEFAULT.wasm,
  nativeAvailable: Boolean = false,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  val canvasIsAuthoritative = canvasClaim.fidelity.isAuthoritative
  val previewEnabled = canvasIsAuthoritative || nativeAvailable
  val previewDescription =
    when {
      previewEnabled -> "Preview (Ctrl/⌘+Enter)"
      canvasClaim.reason.isNotEmpty() -> "Preview unavailable: ${canvasClaim.reason}"
      else -> "Preview unavailable: this catalog has no faithful renderer on this host"
    }
  SingleChoiceSegmentedButtonRow {
    SegmentedButton(
      selected = !previewing,
      onClick = { if (previewing) dispatch(UiBuilderEditorEvent.TogglePreview) },
      shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
      icon = {},
      label = { Text("Design", style = MaterialTheme.typography.labelLarge) },
      modifier =
        Modifier.semantics { contentDescription = "Design mode (Ctrl/⌘+Enter)" }.width(112.dp),
    )
    SegmentedButton(
      selected = previewing,
      enabled = previewEnabled,
      onClick = {
        if (previewing) return@SegmentedButton
        // Switch the renderer *before* the mode, so the first frame Preview shows is already the
        // faithful one. Entering Preview and then noticing the canvas is a lookalike is the
        // sequence this whole declaration exists to prevent.
        if (!canvasIsAuthoritative) {
          dispatch(UiBuilderEditorEvent.ShowPreviewSurface(EditorPreviewSurface.Native))
        }
        dispatch(UiBuilderEditorEvent.TogglePreview)
      },
      shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
      icon = {},
      label = { Text("Preview", style = MaterialTheme.typography.labelLarge) },
      modifier = Modifier.semantics { contentDescription = previewDescription }.width(112.dp),
    )
  }
}

/**
 * Which renderer draws the canvas, as a menu of three named choices.
 *
 * It used to be one button that cycled Wasm → Native → Both. A cycling control hides two thirds of
 * itself: you cannot see what the other positions are, you cannot reach one without passing through
 * the other, and each position needs a sentence that a button face has no room for.
 */
@Composable
private fun RenderSurfaceMenu(
  surface: EditorPreviewSurface,
  /**
   * The catalog's own claims, so an option that cannot tell the truth says so where it is chosen.
   *
   * The Wasm entry is never *removed* on such a catalog: the browser canvas is what a node is
   * selected and dragged on, and an editor with no canvas is not an editor. What it loses is the
   * word "immediate" standing alone as its whole description.
   */
  surfaces: UiBuilderPreviewSurfaces = UiBuilderPreviewSurfaces.DEFAULT,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    TextButton(
      onClick = { open = true },
      modifier = Modifier.semantics { contentDescription = "Render surface (${surface.label()})" },
    ) {
      Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
      Text(surface.label(), Modifier.padding(start = 6.dp))
      Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      EditorPreviewSurface.entries.forEach { option ->
        DropdownMenuItem(
          text = {
            Column {
              Text(option.label())
              Text(
                option.supportingText(surfaces),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
              )
            }
          },
          leadingIcon = {
            if (option == surface) Icon(Icons.Filled.Check, contentDescription = null)
            else Spacer(Modifier.size(24.dp))
          },
          onClick = {
            open = false
            dispatch(UiBuilderEditorEvent.ShowPreviewSurface(option))
          },
        )
      }
    }
  }
}

/** Who else is in the document, as the avatar stack every collaborative tool puts here. */
@Composable
private fun PresenceRow(collaborators: List<UiBuilderCollaborator>) {
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    collaborators.take(4).forEach { collaborator ->
      Surface(
        Modifier.size(28.dp).clearAndSetSemantics {},
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
  }
}

/**
 * A right-click, and where in this element it landed.
 *
 * Compose has no context-click gesture in common code, so this reads the pointer stream directly.
 * It watches the [PointerEventPass.Initial] pass and consumes the press, so a right-click on a
 * layer row does not also start the drag the same row listens for with the left button.
 */
private fun Modifier.onSecondaryClick(key: Any?, onClick: (Offset) -> Unit): Modifier =
  pointerInput(key) {
    awaitPointerEventScope {
      while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
          val position = event.changes.firstOrNull()?.position ?: Offset.Zero
          event.changes.forEach { it.consume() }
          onClick(position)
        }
      }
    }
  }

/**
 * Everything that can be done to the current selection, as menu rows.
 *
 * One list, three places: the layers tree's context menu, the canvas's, and the overflow beside the
 * selection label. The verbs used to exist only as a row of icon buttons above the canvas — always
 * present, mostly greyed, and nowhere near the layer they act on. A context menu puts them under
 * the pointer that is already on the thing, which is where every other design tool keeps them.
 */
@Composable
private fun EditorSelectionMenuItems(
  /** The layout modifiers this selection can be given or have taken away; empty for many nodes. */
  modifierToggles: List<EditorModifierToggle>,
  onToggleModifier: (String) -> Unit,
  canDuplicate: Boolean,
  canCopy: Boolean,
  canCut: Boolean,
  canPaste: Boolean,
  canDelete: Boolean,
  wrapCandidates: List<EditorCatalogItem>,
  canUnwrap: Boolean,
  onOpenProperties: (() -> Unit)?,
  onDismiss: () -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  fun act(event: UiBuilderEditorEvent) {
    onDismiss()
    dispatch(event)
  }
  if (onOpenProperties != null) {
    DropdownMenuItem(
      text = { Text("Properties") },
      leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null) },
      onClick = {
        onDismiss()
        onOpenProperties()
      },
    )
    HorizontalDivider()
  }
  DropdownMenuItem(
    text = { Text("Duplicate") },
    enabled = canDuplicate,
    leadingIcon = { Icon(Icons.Filled.LibraryAdd, contentDescription = null) },
    trailingIcon = { MenuShortcut("Ctrl/\u2318+D") },
    onClick = { act(UiBuilderEditorEvent.DuplicateSelected) },
  )
  DropdownMenuItem(
    text = { Text("Copy") },
    enabled = canCopy,
    leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
    trailingIcon = { MenuShortcut("Ctrl/\u2318+C") },
    onClick = { act(UiBuilderEditorEvent.CopySelected) },
  )
  DropdownMenuItem(
    text = { Text("Cut") },
    enabled = canCut,
    leadingIcon = { Icon(Icons.Filled.ContentCut, contentDescription = null) },
    trailingIcon = { MenuShortcut("Ctrl/\u2318+X") },
    onClick = { act(UiBuilderEditorEvent.CutSelected) },
  )
  DropdownMenuItem(
    text = { Text("Paste") },
    enabled = canPaste,
    leadingIcon = { Icon(Icons.Filled.ContentPaste, contentDescription = null) },
    trailingIcon = { MenuShortcut("Ctrl/\u2318+V") },
    onClick = { act(UiBuilderEditorEvent.Paste) },
  )
  DropdownMenuItem(
    text = { Text("Delete") },
    enabled = canDelete,
    leadingIcon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) },
    trailingIcon = { MenuShortcut("Delete") },
    onClick = { act(UiBuilderEditorEvent.DeleteSelected) },
  )
  // Layout before the container verbs, because it is what a right-click on a laid-out node is
  // usually for: the chain is the node's own business, and wrapping is its parent's.
  if (modifierToggles.isNotEmpty()) {
    HorizontalDivider()
    modifierToggles.forEach { toggle ->
      DropdownMenuItem(
        text = { Text(toggle.label) },
        leadingIcon = {
          // The tick says what is already true. A menu of layout verbs with no state is one people
          // press twice to find out what it did.
          if (toggle.applied) Icon(Icons.Filled.Check, contentDescription = null)
        },
        modifier =
          Modifier.semantics {
            contentDescription =
              if (toggle.applied) "Remove ${toggle.label}" else "Apply ${toggle.label}"
          },
        onClick = {
          onDismiss()
          onToggleModifier(toggle.type)
        },
      )
    }
  }
  if (wrapCandidates.isNotEmpty() || canUnwrap) HorizontalDivider()
  // Behind one row rather than inline: the containers a selection can be wrapped in run to thirty
  // on this catalog, and a menu whose last verb is thirty rows below the first is not a menu.
  if (wrapCandidates.isNotEmpty()) {
    var wrapOpen by remember { mutableStateOf(false) }
    Box {
      DropdownMenuItem(
        text = { Text("Wrap in…") },
        leadingIcon = { Icon(Icons.Filled.Widgets, contentDescription = null) },
        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        onClick = { wrapOpen = true },
      )
      // Only what will work: the candidates are computed from both ends, so every row here is a
      // promise rather than a guess.
      DropdownMenu(expanded = wrapOpen, onDismissRequest = { wrapOpen = false }) {
        wrapCandidates.forEach { candidate ->
          DropdownMenuItem(
            text = { Text(candidate.displayName) },
            onClick = {
              wrapOpen = false
              act(UiBuilderEditorEvent.WrapSelection(candidate.componentId))
            },
          )
        }
      }
    }
  }
  if (canUnwrap) {
    DropdownMenuItem(
      text = { Text("Unwrap") },
      onClick = { act(UiBuilderEditorEvent.UnwrapSelection) },
    )
  }
}

/** The chord beside a menu row, in the quiet the rest of this editor spells shortcuts in. */
@Composable
private fun MenuShortcut(chord: String) {
  Text(
    chord,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.labelSmall,
  )
}

/**
 * What can be done to the selection, beside the selection, only while there is one.
 *
 * These seven verbs used to live in the top bar, where they were greyed out for the whole of every
 * session that never selected anything — which is what an empty document is. Here they name their
 * subject: the bar says what is selected and then what can be done to it, and it is absent entirely
 * when the answer is "nothing".
 *
 * Icons for the six that every tool draws the same way, words for the two that no icon conveys —
 * wrapping a selection in a container, and taking it back out.
 */
@Composable
private fun SelectionActionBar(
  selectionLabel: String,
  onOpenProperties: (() -> Unit)?,
  /** The same rows the context menus carry; the bar holds no second copy of the verbs. */
  selectionMenu: @Composable (() -> Unit) -> Unit,
  modifier: Modifier = Modifier,
) {
  var menuOpen by remember { mutableStateOf(false) }
  Surface(
    modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
  ) {
    Row(
      Modifier.fillMaxWidth().height(48.dp).padding(start = 18.dp, end = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        selectionLabel,
        Modifier.weight(1f),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (onOpenProperties != null) {
        TextButton(
          onClick = onOpenProperties,
          // Not "Open properties panel", which is the rail switch's name: two controls answering
          // to one name is a locator that resolves to both and a screen reader that cannot say
          // which is which.
          modifier = Modifier.semantics { contentDescription = "Edit properties" },
        ) {
          Text("Properties")
        }
      }
      // One control where seven icons were. Everything they did is now a right-click away on the
      // layer itself, in the tree or on the canvas; this is the same menu for anyone who reaches
      // for a button instead, and it is where the chords are written down.
      Box {
        ToolbarIconAction("Selection actions", "", Icons.Filled.MoreVert, true) { menuOpen = true }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
          selectionMenu { menuOpen = false }
        }
      }
    }
  }
}

/**
 * The line under the canvas: what the document is at, where a drag would land, and the session.
 *
 * Every one of these was in the top bar, competing with controls. None of them is a control — they
 * are the answers to "is this saved", "did that land" and "what happens if I let go", which is the
 * bottom of the window in every tool that has them.
 */
@Composable
private fun CanvasStatusBar(
  state: UiBuilderEditorState,
  sessionLabel: String,
  dropTargetLabel: String,
  dragging: Boolean,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp,
  ) {
    // Selectable, because the bar is where a rejection and the live-session status land, and both
    // are sentences a person needs in a bug report rather than retyped off a screenshot.
    SelectionContainer {
      Row(
        Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        StatusText("Revision ${state.document.revision}")
        StatusText("${state.document.nodes.size} nodes")
        if (state.selection.size > 1) StatusText("${state.selection.size} selected")
        // Only while something is being dragged. The drop target is the answer to a question nobody
        // is asking with both hands still: it read "No compatible slot" at rest, which is a warning
        // about nothing.
        if (dragging) {
          StatusText("Drop target: $dropTargetLabel", color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.weight(1f))
        val outcome = state.lastOutcome
        if (outcome is CommandOutcome.Rejected) {
          StatusText("${outcome.code}: ${outcome.message}", color = MaterialTheme.colorScheme.error)
        }
        Surface(shape = RoundedCornerShape(10.dp), color = Color(0xff214c37)) {
          Text(
            sessionLabel,
            Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            color = Color(0xffa8f2c6),
            style = MaterialTheme.typography.labelSmall,
          )
        }
      }
    }
  }
}

@Composable
private fun StatusText(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
  Text(text, color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1)
}

private fun EditorPreviewSurface.label(): String =
  when (this) {
    EditorPreviewSurface.Wasm -> "Wasm"
    EditorPreviewSurface.Native -> "Native"
    EditorPreviewSurface.Both -> "Both"
  }

/**
 * One line under each renderer's name, which is where a catalog's own caveat belongs.
 *
 * "Drawn in this browser" is a complete description on `m3-catalog`, where the canvas draws the
 * same Material 3 the export names. On `wear-m3` it is the least interesting true thing about it,
 * and the interesting one — those are stand-ins for a library no browser can link — is exactly what
 * somebody choosing a renderer needs to read.
 */
private fun EditorPreviewSurface.supportingText(
  surfaces: UiBuilderPreviewSurfaces = UiBuilderPreviewSurfaces.DEFAULT
): String =
  when (this) {
    EditorPreviewSurface.Wasm ->
      if (surfaces.wasm.fidelity.isAuthoritative) "Drawn in this browser"
      else "Drawn in this browser — stand-ins, for authoring"
    EditorPreviewSurface.Native ->
      if (surfaces.native.backend == UiBuilderPreviewSurfaces.BACKEND_ANDROID)
        "Compiled and drawn on the host, on Android"
      else "Compiled and drawn on the host"
    EditorPreviewSurface.Both -> "Side by side, to compare them"
  }

/**
 * One icon control, with the label and its chord in the tooltip and in the semantics.
 *
 * The contentDescription keeps the `"$label ($shortcut)"` shape the text buttons had, because it is
 * what the accessibility tree and every script that drives this editor look for.
 */
@Composable
private fun ToolbarIconAction(
  label: String,
  shortcut: String,
  icon: ImageVector,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  EditorTooltip(label, shortcut) {
    IconButton(
      onClick = onClick,
      enabled = enabled,
      modifier = Modifier.semantics { contentDescription = "$label ($shortcut)" },
    ) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
    }
  }
}

/** [ToolbarIconAction] for a control that is on or off, and says which by staying lit. */
@Composable
private fun ToolbarToggleAction(
  label: String,
  icon: ImageVector,
  checked: Boolean,
  onClick: () -> Unit,
) {
  EditorTooltip(label, "") {
    FilledIconToggleButton(
      checked = checked,
      onCheckedChange = { onClick() },
      modifier = Modifier.semantics { contentDescription = "$label ()" },
    ) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
    }
  }
}

/**
 * The hover label an icon control needs to be as discoverable as the word it replaced.
 *
 * Not optional decoration: a toolbar of unlabelled glyphs is only usable by someone who already
 * knows the tool, and the whole point of moving to icons was to make room, not to make a puzzle.
 */
@Composable
private fun EditorTooltip(label: String, shortcut: String, content: @Composable () -> Unit) {
  TooltipBox(
    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
    tooltip = { PlainTooltip { Text(if (shortcut.isEmpty()) label else "$label · $shortcut") } },
    state = rememberTooltipState(),
    content = content,
  )
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

/**
 * The two questions the left panel answers: what can I add, and what is already here.
 *
 * "Components" rather than "Insert", which reads better on a rail: it is the word this editor
 * already uses for the panel, for its heading and in the accessibility name every script that
 * drives the editor looks for — a rail that renamed the panel would be a silent break for all
 * three.
 */
private enum class NavigatorTab(val label: String) {
  Insert("Components"),
  Layers("Layers"),
}

/**
 * The left panel: insert something, or find something already inserted.
 *
 * One tab at a time rather than the three stacked scroll windows this used to be — a 240 dp catalog
 * above a 180 dp palette above whatever height was left for the layers. Every one of them was too
 * short to use on the design it was describing, and none of them could borrow the space the other
 * two were wasting. Tabs give each list the whole panel, and the tab strip says which question is
 * being asked.
 */
@Composable
private fun EditorNavigator(
  state: UiBuilderEditorState,
  /** The verbs a layer answers to, for the tree's context menu. */
  selectionMenu: @Composable (() -> Unit) -> Unit,
  tab: NavigatorTab,
  onClose: (() -> Unit)?,
  catalogSystemId: String,
  catalogRows: List<EditorCatalogRow>,
  totalCatalogComponents: Int,
  layerRows: List<EditorLayerRow>,
  collaborators: List<UiBuilderCollaborator>,
  dropTarget: ParentSlot?,
  onCatalogDrag: (String, Offset?) -> Unit,
  onCatalogDrop: (String, EditorCatalogVariant?, Offset) -> Unit,
  canAddCatalogComponent: (String) -> Boolean,
  onCatalogAdd: (String, EditorCatalogVariant?) -> Unit,
  remoteComposeSources: List<RemoteComposeSource>,
  pendingRemoteComposeSource: RemoteComposeSource?,
  remoteComposeFailure: String?,
  onAddRemoteComposeSource: (RemoteComposeSource) -> Unit,
  moveRefusal: (String, ParentSlot) -> EditorMoveRefusal?,
  onEditorInteraction: () -> Unit,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
  modifier: Modifier = Modifier.width(NAVIGATOR_WIDTH).fillMaxHeight(),
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.fillMaxSize()) {
      DockHeading(
        title =
          when (tab) {
            NavigatorTab.Insert -> "$catalogSystemId components"
            NavigatorTab.Layers -> "Layers · ${state.document.nodes.size}"
          },
        onClose = onClose,
      )
      when (tab) {
        NavigatorTab.Insert ->
          InsertPanel(
            state = state,
            catalogRows = catalogRows,
            totalCatalogComponents = totalCatalogComponents,
            dropTarget = dropTarget,
            onCatalogDrag = onCatalogDrag,
            onCatalogDrop = onCatalogDrop,
            canAddCatalogComponent = canAddCatalogComponent,
            onCatalogAdd = onCatalogAdd,
            remoteComposeSources = remoteComposeSources,
            pendingRemoteComposeSource = pendingRemoteComposeSource,
            remoteComposeFailure = remoteComposeFailure,
            onAddRemoteComposeSource = onAddRemoteComposeSource,
            onTextInputFocusChanged = onTextInputFocusChanged,
            dispatch = dispatch,
          )
        NavigatorTab.Layers ->
          LayersPanel(
            state = state,
            layerRows = layerRows,
            selectionMenu = selectionMenu,
            collaborators = collaborators,
            dropTarget = dropTarget,
            moveRefusal = moveRefusal,
            onEditorInteraction = onEditorInteraction,
            onTextInputFocusChanged = onTextInputFocusChanged,
            dispatch = dispatch,
          )
      }
    }
  }
}

/**
 * Everything that can be put on the canvas, in one list that owns the whole panel.
 *
 * The catalog and the Remote Compose palette share a search field and a scroll, because they answer
 * one question — "what can I put here?" — and a typed name has to narrow both or it narrows
 * neither.
 */
@Composable
private fun InsertPanel(
  state: UiBuilderEditorState,
  catalogRows: List<EditorCatalogRow>,
  /**
   * Every component the catalog has, which is what the All row counts — not what survived a filter.
   */
  totalCatalogComponents: Int,
  dropTarget: ParentSlot?,
  onCatalogDrag: (String, Offset?) -> Unit,
  onCatalogDrop: (String, EditorCatalogVariant?, Offset) -> Unit,
  canAddCatalogComponent: (String) -> Boolean,
  onCatalogAdd: (String, EditorCatalogVariant?) -> Unit,
  remoteComposeSources: List<RemoteComposeSource>,
  pendingRemoteComposeSource: RemoteComposeSource?,
  remoteComposeFailure: String?,
  onAddRemoteComposeSource: (RemoteComposeSource) -> Unit,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  val visibleSources =
    remember(remoteComposeSources, state.catalogQuery) {
      filterRemoteComposeSources(remoteComposeSources, state.catalogQuery)
    }
  Column(Modifier.fillMaxSize()) {
    SearchField(
      state.catalogQuery,
      placeholder = "Search components",
      onFocusChanged = onTextInputFocusChanged,
    ) {
      dispatch(UiBuilderEditorEvent.SearchCatalog(it))
    }
    // Where an Add would land, said before it is pressed rather than after it is refused. The
    // beginner's question about this panel is not what the components are called.
    Text(
      dropTarget?.let { "Adds into ${it.nodeId}.${it.slot}" }
        ?: "Select a layer that can hold a component",
      Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
      color =
        if (dropTarget == null) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.primary,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
      item {
        CatalogAllRow(totalCatalogComponents) {
          if (state.catalogQuery.isNotBlank()) dispatch(UiBuilderEditorEvent.SearchCatalog(""))
          dispatch(UiBuilderEditorEvent.ExpandAllCatalogGroups)
        }
      }
      items(catalogRows, key = EditorCatalogRow::catalogRowKey) { row ->
        when (row) {
          is EditorCatalogRow.Group ->
            CatalogGroupRow(row) { dispatch(UiBuilderEditorEvent.ToggleCatalogGroup(row.name)) }
          is EditorCatalogRow.Component ->
            CatalogRow(
              item = row.item,
              expanded = row.expanded,
              onDrag = { onCatalogDrag(row.item.componentId, it) },
              onDrop = { onCatalogDrop(row.item.componentId, null, it) },
              canAdd = canAddCatalogComponent(row.item.componentId),
              onAdd = { onCatalogAdd(row.item.componentId, null) },
              onToggleVariants = {
                dispatch(UiBuilderEditorEvent.ToggleCatalogComponent(row.item.componentId))
              },
            )
          is EditorCatalogRow.Variant ->
            CatalogVariantRow(
              variant = row.variant,
              componentName = row.componentName,
              onDrag = { onCatalogDrag(row.variant.componentId, it) },
              onDrop = { onCatalogDrop(row.variant.componentId, row.variant, it) },
              canAdd = canAddCatalogComponent(row.variant.componentId),
              onAdd = { onCatalogAdd(row.variant.componentId, row.variant) },
            )
        }
      }
      if (catalogRows.isEmpty()) {
        item { EmptyPanelNote("No component matches “${state.catalogQuery}”.") }
      }
      if (remoteComposeSources.isNotEmpty()) {
        item {
          HorizontalDivider(color = MaterialTheme.colorScheme.outline)
          PanelHeading(
            "Remote Compose documents",
            remoteComposeFailure
              ?: pendingRemoteComposeSource?.let { "Fetching ${it.label}…" }
              ?: "${visibleSources.size} of ${remoteComposeSources.size} published",
          )
        }
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
  }
}

/** The document as a tree, filtered, with the whole panel to be a tree in. */
@Composable
private fun LayersPanel(
  state: UiBuilderEditorState,
  layerRows: List<EditorLayerRow>,
  selectionMenu: @Composable (() -> Unit) -> Unit,
  collaborators: List<UiBuilderCollaborator>,
  dropTarget: ParentSlot?,
  moveRefusal: (String, ParentSlot) -> EditorMoveRefusal?,
  onEditorInteraction: () -> Unit,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  val matches = layerRows.count { it is EditorLayerRow.Node && it.row.matched }
  // Where each layer row sits vertically, in root pixels, kept in a plain map rather than snapshot
  // state: it is written from layout on every scroll and every relayout, and a recomposition per
  // frame of scrolling is a price the panel does not need to pay. The drag reads it from a
  // callback, which is the only place it is ever read.
  val rowBounds = remember(layerRows) { mutableMapOf<Int, ClosedFloatingPointRange<Float>>() }
  var draggedLayer by remember { mutableStateOf<String?>(null) }
  var landing by remember { mutableStateOf<LayerLanding?>(null) }
  Column(Modifier.fillMaxSize()) {
    SearchField(
      state.layerQuery,
      // Reusing the catalog's field meant reusing its placeholder, so an empty layers filter
      // invited you to search components. Two fields, two things to look for.
      placeholder = "Filter layers",
      onFocusChanged = onTextInputFocusChanged,
    ) {
      dispatch(UiBuilderEditorEvent.SearchLayers(it))
    }
    Row(
      Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        when {
          draggedLayer != null ->
            landing?.refusal?.message
              ?: landing?.let { "Drop into ${it.target.nodeId}.${it.target.slot}" }
              ?: "Drag over a layer or a slot"
          state.layerQuery.isNotBlank() -> "$matches of ${state.document.nodes.size} match"
          else -> "Drag a row onto a layer or a slot"
        },
        Modifier.weight(1f),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 2,
      )
      // The multi-node inspector is only as reachable as the selection is. Filtering to every text
      // on the screen and then taking all of them is what makes restyling a screen one edit.
      if (state.layerQuery.isNotBlank() && matches > 0) {
        TextButton(
          onClick = {
            onEditorInteraction()
            dispatch(UiBuilderEditorEvent.SelectAllMatches)
          }
        ) {
          Text("Select all $matches")
        }
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
              // The slot a catalog drop would land in, so the answer the panel gives in words is
              // also given in the tree, next to the children it would join.
              isCatalogTarget = row.parent == dropTarget,
              landing = landing?.takeIf { it.marker == LayerLandingMarker.Into(index) },
            )
          is EditorLayerRow.Node ->
            LayerRow(
              row = row.row,
              indent = row.indent,
              modifier = recordBounds,
              selectionMenu = selectionMenu,
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
                  // Sent even when it will be refused: the reducer owns that answer and reports it
                  // through the same channel as every other refused edit, which is how the
                  // operator learns *why* a slot would not take the layer rather than watching the
                  // gesture evaporate.
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
      if (layerRows.isEmpty()) {
        item { EmptyPanelNote("No layer matches “${state.layerQuery}”.") }
      }
    }
  }
}

/** What a filtered list says when it has filtered everything away. */
@Composable
private fun EmptyPanelNote(text: String) {
  Text(
    text,
    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodySmall,
  )
}

/** A dock panel's title bar: what this panel is, and the way back to the whole canvas. */
@Composable
private fun DockHeading(title: String, onClose: (() -> Unit)?, supporting: String? = null) {
  Row(
    Modifier.fillMaxWidth().height(44.dp).padding(start = 14.dp, end = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (supporting != null) {
        Text(
          supporting,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (onClose != null) {
      ToolbarIconAction("Close $title", "", Icons.Filled.Close, true, onClose)
    }
  }
  HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

/** The right-hand docks, in the order the rail lists them. */
private enum class EditorDock(val label: String) {
  Properties("Properties"),
  Theme("Theme"),
  Screen("Screen"),
  Issues("Issues"),
  Comments("Talk"),
  Code("Code"),
}

/**
 * The inspector mode a dock stands for, or null for the one that is not an inspector.
 *
 * The mapping is one way on purpose: [EditorInspectorMode] is document state that survives a reload
 * and travels to a collaborator, and which dock is open is not.
 */
private fun EditorDock.inspectorMode(): EditorInspectorMode? =
  when (this) {
    EditorDock.Properties -> EditorInspectorMode.Properties
    EditorDock.Theme -> EditorInspectorMode.Theme
    EditorDock.Screen -> EditorInspectorMode.Screen
    EditorDock.Issues -> EditorInspectorMode.Issues
    EditorDock.Comments -> EditorInspectorMode.Comments
    EditorDock.Code -> null
  }

/**
 * The strip of panel switches that flanks the canvas.
 *
 * Every panel in this editor used to be nailed open: 300 dp of catalog on the left and 360 dp of
 * inspector on the right, on every screen, whether or not the design being drawn was 400 dp wide.
 * The canvas — the thing the editor is for — got whatever was left. A rail makes each panel a
 * switch: the icon says the panel exists, pressing it opens the panel, pressing it again gives the
 * space back to the design.
 */
@Composable
private fun EditorRail(items: List<EditorRailItem>, modifier: Modifier = Modifier) {
  Surface(modifier.fillMaxHeight().width(52.dp), color = MaterialTheme.colorScheme.surface) {
    Column(
      Modifier.fillMaxHeight().padding(vertical = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      items.forEach { item ->
        EditorTooltip(item.label, "") {
          Surface(
            Modifier.size(40.dp)
              .semantics {
                selected = item.selected
                contentDescription =
                  if (item.selected) "Close ${item.label.lowercase()} panel"
                  else "Open ${item.label.lowercase()} panel"
              }
              .clickable(onClick = item.onClick),
            shape = RoundedCornerShape(12.dp),
            color =
              if (item.selected) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.surface,
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                item.icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint =
                  if (item.selected) MaterialTheme.colorScheme.onPrimary
                  else MaterialTheme.colorScheme.onSurfaceVariant,
              )
              // A count rather than a dot: "three problems" and "one problem" are different
              // enough decisions that the badge may as well say which.
              if (item.badge > 0) {
                Surface(
                  Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp),
                  shape = RoundedCornerShape(7.dp),
                  color = MaterialTheme.colorScheme.error,
                ) {
                  Text(
                    item.badge.toString(),
                    Modifier.padding(horizontal = 4.dp),
                    color = MaterialTheme.colorScheme.onError,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

/** One switch on an [EditorRail]. */
private data class EditorRailItem(
  val label: String,
  val icon: ImageVector,
  val selected: Boolean,
  val badge: Int = 0,
  val onClick: () -> Unit,
)

private fun EditorDock.icon(): ImageVector =
  when (this) {
    EditorDock.Properties -> Icons.Filled.Tune
    EditorDock.Theme -> Icons.Filled.Palette
    EditorDock.Screen -> Icons.Filled.PhoneAndroid
    EditorDock.Issues -> Icons.Filled.ErrorOutline
    EditorDock.Comments -> Icons.Filled.ChatBubbleOutline
    EditorDock.Code -> Icons.Filled.Code
  }

private fun NavigatorTab.icon(): ImageVector =
  when (this) {
    NavigatorTab.Insert -> Icons.Filled.Widgets
    NavigatorTab.Layers -> Icons.Filled.AccountTree
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
  /** Threads with somewhere to sit on the frame; see [DesignCommentBoard.pinned]. */
  commentThreads: List<DesignCommentThread>,
  selectedThreadId: String?,
  onCommentThreadSelected: (String) -> Unit,
  onInspectionSnapshot: ((UiBuilderInspectionSnapshot) -> Unit)?,
  onInspectionInvalidated: ((UiBuilderInspectionCollector) -> Unit)?,
  /** The verbs a layer answers to, for the canvas's own context menu. */
  selectionMenu: @Composable (() -> Unit) -> Unit,
  /**
   * The tight editor that follows the selection over the design, or null where there is nothing to
   * follow. Positioned here, because only the canvas knows where the selected node is drawn.
   */
  hoverEditor: (@Composable () -> Unit)?,
  /** The scale the design is drawn at, or null to frame it in whatever room the workspace has. */
  zoom: Float?,
  onZoomChanged: (Float?) -> Unit,
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
    // What "fit" means: the largest scale at which the whole frame is on screen. It is no longer
    // capped at 1:1, which is the whole of "autozoom": a 411 x 891 dp phone opened in a desktop
    // workspace was drawn as a stamp in the middle of an empty page, and a 1280 x 800 dp design in
    // a narrow window was drawn shrunk with room to spare beside it. Neither is the design framed.
    // Named, because the scrolling box below is a different receiver and cannot see the
    // constraints scope these come from.
    val workspaceWidth = maxWidth
    val workspaceHeight = maxHeight
    val fitScale =
      minOf(workspaceWidth.value / sourceWidth, workspaceHeight.value / sourceHeight)
        .coerceIn(MIN_CANVAS_ZOOM, MAX_CANVAS_ZOOM)
    val scale = zoom ?: fitScale
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
    // The workspace's own rectangle, so a node's root-space box can be turned into an offset in
    // this box — which is where the hover editor is placed.
    var workspaceBounds by remember(document.id) { mutableStateOf(Rect.Zero) }
    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberScrollState()
    Box(
      Modifier.fillMaxSize()
        .onGloballyPositioned { workspaceBounds = it.boundsInRoot() }
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
        Box(Modifier.size((sourceWidth * scale).dp, (sourceHeight * scale).dp)) {
          Surface(
            Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
              .requiredSize(sourceWidth.dp, sourceHeight.dp)
              .onSizeChanged { size ->
                measuredDp =
                  with(density) {
                    size.width.toDp().value.roundToInt() to size.height.toDp().value.roundToInt()
                  }
              }
              .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
                compositingStrategy = CompositingStrategy.Offscreen
              }
              .onGloballyPositioned {
                frameBounds = it.boundsInRoot()
                onCanvasBounds(frameBounds)
              }
              .then(
                if (dropHovered) Modifier.border(4.dp, MaterialTheme.colorScheme.primary)
                else Modifier
              ),
            shape = RoundedCornerShape(0.dp),
            shadowElevation = 0.dp,
          ) {
            // Where a right-click landed on the design, in the frame's own pixels, and null
            // while no menu is open.
            var menuAt by remember(document.id) { mutableStateOf<Offset?>(null) }
            Box(
              Modifier.fillMaxSize().onSecondaryClick(document.id) { position ->
                if (!showSelectionOverlay) return@onSecondaryClick
                // The inspection reports each box in root pixels, which is the space this press
                // has to be asked in: the frame is offset in the workspace and drawn at [scale].
                val point =
                  Offset(
                    frameBounds.left + position.x * scale,
                    frameBounds.top + position.y * scale,
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
                if (hit != null) {
                  if (hit != selectedNodeId) onNodeSelected(hit)
                  menuAt = position
                }
              }
            ) {
              Box {
                DropdownMenu(
                  expanded = menuAt != null,
                  onDismissRequest = { menuAt = null },
                  offset =
                    with(density) {
                      DpOffset(
                        ((menuAt?.x ?: 0f) * scale).toDp(),
                        ((menuAt?.y ?: 0f) * scale).toDp(),
                      )
                    },
                ) {
                  selectionMenu { menuAt = null }
                }
              }
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
              // Over the document and under the collaborators: the reference is being compared
              // against
              // what the document draws, so it goes on top of that; another person's selection is a
              // fact
              // about this session and must not be hidden by a mock.
              ReferenceOverlayCanvas(reference, onMarkDrawn, onPieceMoved)
              RemotePresenceOverlay(collaborators, inspection)
              // Above everything, because a pin is the one thing on this canvas a person clicks
              // that is
              // not part of the design: it must not end up under a mock somebody just turned up the
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
      }
    }
    // Beside the selected node rather than over it, and outside the scaled frame so the type stays
    // the size it was designed at however far the design is zoomed out.
    val selectedBounds = selectedNodeId?.let { id ->
      inspection?.nodes?.firstOrNull { it.nodeId == id }?.bounds
    }
    if (hoverEditor != null && showSelectionOverlay && selectedBounds != null) {
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
private val MODIFIER_FOCUS_FIELDS = mapOf("padding" to "startDp", "weight" to "weight")

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
      ToolbarIconAction("Zoom out", "", Icons.Filled.Remove, scale > MIN_CANVAS_ZOOM) {
        onZoomChanged(canvasZoomStep(scale, zoomIn = false))
      }
      TextButton(
        onClick = { onZoomChanged(1f) },
        modifier = Modifier.semantics { contentDescription = "Zoom to 100%" },
      ) {
        Text(canvasZoomLabel(scale, fitting), style = MaterialTheme.typography.labelLarge)
      }
      ToolbarIconAction("Zoom in", "", Icons.Filled.Add, scale < MAX_CANVAS_ZOOM) {
        onZoomChanged(canvasZoomStep(scale, zoomIn = true))
      }
      ToolbarToggleAction("Fit to window", Icons.Filled.FitScreen, fitting) {
        onZoomChanged(if (fitting) scale else null)
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
  /** What the accessibility tree — and every script that drives this editor — calls the box. */
  searchLabel: String = "Component catalog search",
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
              .semantics { contentDescription = searchLabel },
          textStyle =
            MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
          singleLine = true,
        )
      }
    }
  }
}

/**
 * The heading over a run of rows grouped by something the reducer decided — the Remote Compose
 * palette's source groups.
 *
 * A label rather than a control, unlike [CatalogGroupRow]: nothing collapses here, because the list
 * it heads is short and a twisty that hides four rows is a twisty nobody presses.
 */
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

/**
 * The row above the shelves: how many components there are in total, and a way back to all of them.
 *
 * The catalog's tree leads with the same row for the same reason — the default view of a catalog
 * should be the whole catalog, and once you have filtered or collapsed your way into a corner of it
 * there has to be something to press to get back out. Pressing it clears the search and opens every
 * shelf.
 */
@Composable
private fun CatalogAllRow(total: Int, onShowAll: () -> Unit) {
  Surface(
    Modifier.fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 4.dp)
      .clip(RoundedCornerShape(20.dp))
      .clickable(onClick = onShowAll),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Row(
      Modifier.padding(start = 16.dp, end = 8.dp).height(40.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        "All",
        Modifier.weight(1f).semantics { contentDescription = "Show all $total components" },
        style = MaterialTheme.typography.bodyLarge,
      )
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
      ) {
        Text(
          total.toString(),
          Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
          color = MaterialTheme.colorScheme.onPrimaryContainer,
          style = MaterialTheme.typography.labelMedium,
        )
      }
    }
  }
}

/**
 * A shelf: its name, how many components are on it, and a twisty.
 *
 * Sentence case on a plain ground, not the small-caps label on a filled bar the kind headings used
 * to draw. That styling belonged to a heading over a run of rows; this is a **row** — the whole
 * width is the hit target, it opens and shuts, and it is the top of a tree whose other rows are
 * sentence case too. An open shelf takes the accent bar and the accent colour, which is the only
 * thing on the panel saying which branch you are inside.
 */
@Composable
private fun CatalogGroupRow(row: EditorCatalogRow.Group, onToggle: () -> Unit) {
  val accent =
    if (row.expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
  Row(
    Modifier.fillMaxWidth().height(40.dp).clickable(onClick = onToggle).semantics {
      contentDescription = "${if (row.expanded) "Collapse" else "Expand"} ${row.name}"
    },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // The accent bar, drawn in the gutter every child row's guide line also runs down, so the open
    // shelf and its contents are one column rather than a coloured row above unrelated rows.
    Box(
      Modifier.padding(start = 8.dp)
        .width(3.dp)
        .height(24.dp)
        .background(
          if (row.expanded) MaterialTheme.colorScheme.primary else Color.Transparent,
          RoundedCornerShape(2.dp),
        )
    )
    DisclosureTriangle(row.expanded, accent, Modifier.padding(start = 5.dp))
    Text(
      row.name,
      Modifier.padding(start = 2.dp).weight(1f),
      color = accent,
      style = MaterialTheme.typography.bodyLarge,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    RowCount(row.count, MaterialTheme.colorScheme.surfaceVariant)
  }
}

/**
 * The twisty a shelf or a component turns to say whether it is open.
 *
 * One glyph rotated rather than two, so the states cannot drift apart in weight, and a solid
 * triangle rather than a chevron because that is what the catalog's tree draws and this panel is
 * meant to read as the same control.
 */
@Composable
private fun DisclosureTriangle(expanded: Boolean, tint: Color, modifier: Modifier = Modifier) {
  Icon(
    Icons.Filled.ArrowDropDown,
    contentDescription = null,
    modifier.size(20.dp).rotate(if (expanded) 0f else -90f),
    tint = tint,
  )
}

/** A placeholder the width of [DisclosureTriangle], so rows without one still line up. */
@Composable
private fun DisclosureSpacer() {
  Spacer(Modifier.width(20.dp))
}

/**
 * How many things are under a row.
 *
 * A shelf count sits in a pill and a component's variant count does not, which is the weighting the
 * catalog's tree uses: the top level is how you choose where to look, and a number that far down is
 * a detail about one row.
 */
@Composable
private fun RowCount(count: Int, background: Color) {
  Surface(
    Modifier.padding(end = 12.dp),
    shape = RoundedCornerShape(10.dp),
    color = background,
  ) {
    Text(
      count.toString(),
      Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
  }
}

/**
 * The vertical rule that runs down a shelf's children, in the gutter its heading's accent bar is
 * in.
 *
 * Without it a component row indented under a heading and a component row indented under nothing
 * look the same, which at 280 dp — where a long display name pushes the indent out of view — is
 * most of them.
 */
@Composable
private fun IndentGuide(depth: Int) {
  Box(Modifier.width(9.dp + 12.dp * (depth - 1)).fillMaxHeight(), Alignment.CenterStart) {
    Box(
      Modifier.padding(start = 9.dp)
        .width(1.dp)
        .fillMaxHeight()
        .background(MaterialTheme.colorScheme.outlineVariant)
    )
  }
}

@Composable
private fun CatalogRow(
  item: EditorCatalogItem,
  expanded: Boolean,
  onDrag: (Offset?) -> Unit,
  onDrop: (Offset) -> Unit,
  canAdd: Boolean,
  onAdd: () -> Unit,
  onToggleVariants: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().height(44.dp).padding(end = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IndentGuide(depth = 1)
    // Only a component that HAS variants gets a twisty, and a spacer keeps the ones that do not
    // aligned with the ones that do — a ragged left edge on half the rows reads as a rendering
    // fault rather than as a component with nothing behind it.
    if (item.variants.isEmpty()) DisclosureSpacer()
    else {
      Box(
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onToggleVariants).semantics {
          contentDescription = "${if (expanded) "Hide" else "Show"} ${item.displayName} variants"
        }
      ) {
        DisclosureTriangle(expanded, MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    CatalogDragHandle(item.componentId, item.displayName, onDrag, onDrop)
    Column(Modifier.padding(start = 6.dp).weight(1f)) {
      Text(item.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
      Text(
        item.componentId,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
      )
    }
    if (item.variants.isNotEmpty()) {
      Text(
        item.variants.size.toString(),
        Modifier.padding(end = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    CatalogAddButton(canAdd, onAdd, item.displayName)
  }
}

/**
 * One variant under its component: the same row, one level further in, adding the same component
 * with one property already chosen.
 *
 * It carries a drag handle for the same reason the component row does — a variant you can only Add
 * into the selected slot is a variant that cannot be dropped where you are looking, and "drop it
 * where you want it" is the palette's own promise.
 */
@Composable
private fun CatalogVariantRow(
  variant: EditorCatalogVariant,
  /** The component this variant is of, for the names a screen reader and a script use. */
  componentName: String,
  onDrag: (Offset?) -> Unit,
  onDrop: (Offset) -> Unit,
  canAdd: Boolean,
  onAdd: () -> Unit,
) {
  val label = variant.label
  // "Filled tonal" is what the row shows, because the component it sits under is directly above it.
  // What a script or a screen reader asks for has no such context and has to be unambiguous: a
  // Button's `text` variant and the Text component would otherwise both answer to "Drag Text".
  val qualified = "$label $componentName"
  Row(
    Modifier.fillMaxWidth().height(36.dp).padding(end = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IndentGuide(depth = 2)
    CatalogDragHandle("${variant.componentId}#${variant.value}", qualified, onDrag, onDrop)
    Row(Modifier.padding(start = 6.dp).weight(1f), verticalAlignment = Alignment.CenterVertically) {
      Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      // Which one a plain Add on the row above would have given you, said on the row rather than
      // left to be discovered by adding one and reading the inspector.
      if (variant.default) {
        Text(
          "default",
          Modifier.padding(start = 6.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }
    CatalogAddButton(canAdd, onAdd, qualified)
  }
}

/**
 * The one verb every palette row carries, sized so two levels of indent still leave room for it.
 */
@Composable
private fun CatalogAddButton(canAdd: Boolean, onAdd: () -> Unit, label: String) {
  TextButton(
    onClick = onAdd,
    enabled = canAdd,
    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
  ) {
    Text("Add", Modifier.semantics { contentDescription = "Add $label" })
  }
}

/**
 * The grip a palette row is dragged onto the canvas by.
 *
 * Lifted out of [CatalogRow] when the variant rows arrived: the gesture is nine lines of drag
 * bookkeeping that has nothing to do with what the row shows, and two copies of it would be two
 * chances for a drop threshold to drift.
 */
@Composable
private fun CatalogDragHandle(
  dragKey: String,
  label: String,
  onDrag: (Offset?) -> Unit,
  onDrop: (Offset) -> Unit,
) {
  var dragDistance by remember { mutableFloatStateOf(0f) }
  var dragOrigin by remember { mutableStateOf(Offset.Zero) }
  var lastPosition by remember { mutableStateOf(Offset.Zero) }
  Icon(
    Icons.Filled.DragIndicator,
    contentDescription = "Drag $label",
    modifier =
      Modifier.size(18.dp)
        .onGloballyPositioned { dragOrigin = it.boundsInRoot().topLeft }
        .pointerInput(dragKey) {
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
}

/**
 * A stable identity per row, for the `LazyColumn`.
 *
 * A group and a component can share a name and a component and its variant share an id, so the row
 * kind is part of the key — without it, expanding a group would reuse the group row's slot for the
 * first component under it and the list would animate the wrong things.
 */
private fun EditorCatalogRow.catalogRowKey(): String =
  when (this) {
    is EditorCatalogRow.Group -> "group:$name"
    is EditorCatalogRow.Component -> "component:${item.componentId}"
    is EditorCatalogRow.Variant -> "variant:${variant.componentId}#${variant.value}"
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
  selectionMenu: @Composable (() -> Unit) -> Unit,
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
  // Where the right-click landed inside this row, and null while no menu is open.
  var menuAt by remember(row.nodeId) { mutableStateOf<Offset?>(null) }
  val density = LocalDensity.current
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
      .onSecondaryClick(row.nodeId) { position ->
        // Selecting first, and only when it is not already part of the selection: a right-click on
        // one of six selected layers must not collapse the selection it is about to act on.
        if (!selected) onSelect(LayerSelectionGesture.Replace)
        menuAt = position
      }
      .padding(start = (8 + indent * 12).dp, end = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // A zero-size anchor, so the menu opens where the pointer is rather than off the row's start.
    Box {
      DropdownMenu(
        expanded = menuAt != null,
        onDismissRequest = { menuAt = null },
        offset = DpOffset(with(density) { (menuAt?.x ?: 0f).toDp() }, 0.dp),
      ) {
        selectionMenu { menuAt = null }
      }
    }
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

/**
 * The selection's values, beside the selection.
 *
 * Deliberately the smallest thing that can be an editor: the properties this node actually carries
 * and the numbers inside its modifiers, each one row, each committed where it is typed. No adding,
 * no removing, no binding and no wrapping — those change what the node *is*, they belong in the
 * panel that has room to say so, and a card floating over the design is the wrong place to be
 * offered them. What is left is the thing people do most while looking at a design: change a number
 * and watch it move.
 */
@Composable
private fun SelectionHoverEditor(
  label: String,
  fields: List<EditorPropertyField>,
  modifierFields: List<EditorModifierField>,
  /**
   * `property:<name>` or `modifier:<type>.<field>`, for the control a just-run action should land
   * in.
   */
  focusTarget: String?,
  onFocusHandled: () -> Unit,
  onCommitProperty: (String, String) -> Unit,
  onCommitModifier: (String, String, String) -> Unit,
  onTextInputFocusChanged: (Boolean) -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 4.dp,
    shadowElevation = 8.dp,
    modifier = Modifier.semantics { contentDescription = "Selection editor" },
  ) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
      Text(
        label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
        fields.forEach { field ->
          HoverEditorRow(
            label = field.label,
            value = field.value,
            control = field.control,
            choices = field.choices,
            focused = focusTarget == "property:${field.name}",
            onFocusHandled = onFocusHandled,
            onTextInputFocusChanged = onTextInputFocusChanged,
          ) {
            onCommitProperty(field.name, it)
          }
        }
        modifierFields.forEach { field ->
          HoverEditorRow(
            label = field.label,
            value = field.value,
            control =
              if (field.choices.isEmpty()) EditorPropertyControl.Number
              else EditorPropertyControl.Enum,
            choices = field.choices,
            focused = focusTarget == "modifier:${field.type}.${field.field}",
            onFocusHandled = onFocusHandled,
            onTextInputFocusChanged = onTextInputFocusChanged,
          ) {
            onCommitModifier(field.type, field.field, it)
          }
        }
        if (fields.isEmpty() && modifierFields.isEmpty()) {
          Text(
            "Nothing is set on this layer.",
            Modifier.padding(vertical = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
  }
}

/** One row of the hover editor: what it is called, and the smallest control that can change it. */
@Composable
private fun HoverEditorRow(
  label: String,
  value: String,
  control: EditorPropertyControl,
  choices: List<String>,
  focused: Boolean,
  onFocusHandled: () -> Unit,
  onTextInputFocusChanged: (Boolean) -> Unit,
  onCommit: (String) -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().padding(vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      label,
      Modifier.width(86.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Box(Modifier.weight(1f)) {
      when (control) {
        // Committed on the press rather than on a later Apply: a switch that needs confirming is a
        // switch nobody believes.
        EditorPropertyControl.Boolean ->
          Switch(
            checked = value == "true",
            onCheckedChange = { onCommit(it.toString()) },
            modifier = Modifier.semantics { contentDescription = "$label value" },
          )
        EditorPropertyControl.Enum ->
          HoverEnumControl(label = label, value = value, choices = choices, onCommit = onCommit)
        else ->
          HoverTextControl(
            label = label,
            value = value,
            focused = focused,
            onFocusHandled = onFocusHandled,
            onTextInputFocusChanged = onTextInputFocusChanged,
            onCommit = onCommit,
          )
      }
    }
  }
}

/**
 * A one-line field that commits what was typed when the caret leaves it, or on Enter.
 *
 * No Apply button, which the panel has room for and this does not: the rule here is that leaving
 * the field is the commit, and Enter is the way to say so without moving the pointer.
 */
@Composable
private fun HoverTextControl(
  label: String,
  value: String,
  focused: Boolean,
  onFocusHandled: () -> Unit,
  onTextInputFocusChanged: (Boolean) -> Unit,
  onCommit: (String) -> Unit,
) {
  var draft by remember(value) { mutableStateOf(value) }
  // What this field has already sent. Enter commits, and so does losing focus — including the
  // focus loss that *disposal* is, when the commit's own document change rebuilds this card.
  // Without remembering it, one press of Enter wrote the same value twice: two revisions, two
  // undo steps and two rounds to every collaborator for one edit.
  var sent by remember(value) { mutableStateOf(value) }
  val requester = remember { FocusRequester() }
  // A modifier the menu just added lands the caret in its first number, so "add padding" is one
  // press and then a number rather than a press and a hunt for where it went.
  LaunchedEffect(focused) {
    if (focused) {
      requester.requestFocus()
      onFocusHandled()
    }
  }
  Surface(
    shape = RoundedCornerShape(6.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
  ) {
    BasicTextField(
      value = draft,
      onValueChange = { draft = it },
      singleLine = true,
      textStyle =
        MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
      cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
      modifier =
        Modifier.fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 6.dp)
          .focusRequester(requester)
          .onFocusChanged { state ->
            onTextInputFocusChanged(state.isFocused)
            if (!state.isFocused && draft != sent) {
              sent = draft
              onCommit(draft)
            }
          }
          .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key in ENTER_KEYS) {
              if (draft != sent) {
                sent = draft
                onCommit(draft)
              }
              true
            } else false
          }
          .semantics { contentDescription = "$label value" },
    )
  }
}

/** The same row for a property whose values the catalog names. */
@Composable
private fun HoverEnumControl(
  label: String,
  value: String,
  choices: List<String>,
  onCommit: (String) -> Unit,
) {
  var open by remember(label) { mutableStateOf(false) }
  Box {
    TextButton(
      onClick = { open = true },
      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
      modifier = Modifier.semantics { contentDescription = "$label value" },
    ) {
      Text(
        value.ifEmpty { "Choose…" },
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      choices.forEach { choice ->
        DropdownMenuItem(
          text = { Text(choice) },
          onClick = {
            open = false
            onCommit(choice)
          },
        )
      }
    }
  }
}

private val ENTER_KEYS = setOf(Key.Enter, Key.NumPadEnter)

@Composable
private fun PropertyInspector(
  state: UiBuilderEditorState,
  onClose: (() -> Unit)?,
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
  comments: DesignCommentBoard,
  commentStatus: String?,
  selectedThreadId: String?,
  onSelectThread: (String?) -> Unit,
  onPostComment: ((DesignCommentDraft) -> Unit)?,
  onResolveCommentThread: ((String, Boolean) -> Unit)?,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
  modifier: Modifier = Modifier.width(INSPECTOR_WIDTH).fillMaxHeight(),
) {
  val node = state.selectedNodeId?.let(state.document.nodes::get)
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column {
      // The four inspectors used to share a row of tabs inside this panel, which is why it had to
      // be 360 dp wide: the tabs, not the controls, set the floor. They are switches for a panel
      // rather than controls in one, so they moved to the rail, and the panel narrowed.
      DockHeading(
        title =
          when (state.inspectorMode) {
            EditorInspectorMode.Properties -> "Properties"
            EditorInspectorMode.Theme -> "Theme"
            EditorInspectorMode.Screen -> "Screen"
            EditorInspectorMode.Issues ->
              if (problems.isEmpty()) "Issues" else "Issues · ${problems.size}"
            EditorInspectorMode.Comments ->
              comments.openThreads.size.let { if (it == 0) "Talk" else "Talk · $it" }
          },
        supporting =
          when (state.inspectorMode) {
            EditorInspectorMode.Properties -> node?.id ?: "Nothing selected"
            EditorInspectorMode.Theme -> "Applies to the whole design"
            EditorInspectorMode.Screen -> "Frame, density and reference"
            EditorInspectorMode.Issues -> "What the export would refuse"
            EditorInspectorMode.Comments -> "What people and agents have said"
          },
        onClose = onClose,
      )
      InspectorBody(
        state = state,
        node = node,
        fields = fields,
        stateVariables = stateVariables,
        comparisonBindingProperties = comparisonBindingProperties,
        bindableProperties = bindableProperties,
        problems = problems,
        themeSettings = themeSettings,
        devicePresets = devicePresets,
        onPickReference = onPickReference,
        onSnapshotDesign = onSnapshotDesign,
        onFlatten = onFlatten,
        catalogItems = catalogItems,
        onPlaceComponent = onPlaceComponent,
        onPromotePiece = onPromotePiece,
        canPromotePiece = canPromotePiece,
        referenceStatus = referenceStatus,
        comments = comments,
        commentStatus = commentStatus,
        selectedThreadId = selectedThreadId,
        onSelectThread = onSelectThread,
        onPostComment = onPostComment,
        onResolveCommentThread = onResolveCommentThread,
        onTextInputFocusChanged = onTextInputFocusChanged,
        dispatch = dispatch,
      )
    }
  }
}

/** Whichever inspector the rail has chosen, drawn under [PropertyInspector]'s heading. */
@Composable
private fun InspectorBody(
  state: UiBuilderEditorState,
  node: UiBuilderNode?,
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
  comments: DesignCommentBoard,
  commentStatus: String?,
  selectedThreadId: String?,
  onSelectThread: (String?) -> Unit,
  onPostComment: ((DesignCommentDraft) -> Unit)?,
  onResolveCommentThread: ((String, Boolean) -> Unit)?,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
    if (state.inspectorMode == EditorInspectorMode.Issues) {
      ProblemsInspector(problems, dispatch)
      return@Column
    }
    if (state.inspectorMode == EditorInspectorMode.Comments) {
      // Scrolled for the same reason the Screen panel is: a design with a dozen threads on it
      // fills the dock, and a panel that silently clips its last thread is worse than one that
      // scrolls.
      Column(Modifier.verticalScroll(rememberScrollState())) {
        CommentsInspector(
          board = comments,
          reference = state.reference,
          selectedNodeId = state.selectedNodeId,
          nodeLabel = { nodeId -> state.document.nodes[nodeId]?.componentId ?: nodeId },
          selectedThreadId = selectedThreadId,
          onSelectThread = onSelectThread,
          onPost = onPostComment,
          onResolve = onResolveCommentThread,
          hostStatus = commentStatus,
          onTextInputFocusChanged = onTextInputFocusChanged,
        )
      }
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
    // Which properties this node has been given since it was selected. Local and per node: adding
    // one here means "show me the control", not "write a value" — nothing reaches the document
    // until the control is used, so a property revealed and left alone changes neither the design
    // nor the exported Kotlin.
    var revealed by remember(node.id) { mutableStateOf(emptySet<String>()) }
    var propertyQuery by remember(node.id) { mutableStateOf("") }
    var addingProperty by remember(node.id) { mutableStateOf(false) }
    // What the export would write, plus what it would refuse without: the panel opens on the node
    // as the code has it. A bound property counts as written, and so does one being complained
    // about, because hiding the field an error names is how an error becomes unfixable.
    val shownFields = fields.filter {
      it.written ||
        it.required ||
        it.boundVariable != null ||
        it.error != null ||
        it.name in revealed
    }
    val shownNames = shownFields.map { it.name }.toSet()
    fun matches(field: EditorPropertyField): Boolean =
      propertyQuery.isBlank() ||
        field.label.contains(propertyQuery, ignoreCase = true) ||
        field.name.contains(propertyQuery, ignoreCase = true)
    val visibleFields = shownFields.filter(::matches)
    // Everything the component allows and this node has not been given. Offered, never listed: a
    // search reaches it in one word, and until then it is thirty controls nobody asked for.
    val addableFields = fields.filterNot { it.name in shownNames }.filter(::matches)
    // Open the drawer whenever a search is running, so typing a property's name finds it whether
    // or not the node already has one.
    val addOpen = addingProperty || propertyQuery.isNotBlank()
    if (fields.isNotEmpty()) {
      Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(Modifier.weight(1f)) {
          SearchField(
            value = propertyQuery,
            placeholder = "Search properties",
            searchLabel = "Property search",
            onFocusChanged = onTextInputFocusChanged,
            onValueChange = { propertyQuery = it },
          )
        }
        ToolbarIconAction(
          label = if (addOpen) "Close add property" else "Add property",
          shortcut = "",
          icon = if (addOpen) Icons.Filled.Close else Icons.Filled.Add,
          enabled = true,
        ) {
          addingProperty = !addOpen
          if (!addingProperty) propertyQuery = ""
        }
      }
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
      itemsIndexed(visibleFields, key = { _, field -> field.name }) { _, field ->
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
      } else if (visibleFields.isEmpty() && !addOpen) {
        item {
          Text(
            "Nothing is set on this layer. Add a property to give it one.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
      if (addOpen) {
        item {
          HorizontalDivider(Modifier.padding(vertical = 12.dp))
          Text(
            if (propertyQuery.isBlank()) "Add a property"
            else "Add a property · ${addableFields.size} match",
            style = MaterialTheme.typography.labelLarge,
          )
          Text(
            "The catalog allows these. Adding one shows its control; the export writes it once it has a value.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
          )
        }
        itemsIndexed(addableFields, key = { _, field -> "add:${field.name}" }) { _, field ->
          AddPropertyRow(field) {
            revealed = revealed + field.name
            addingProperty = false
            propertyQuery = ""
          }
        }
        if (addableFields.isEmpty()) {
          item {
            Text(
              "Every property this component declares is already here.",
              Modifier.padding(top = 6.dp),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
          }
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
  }
}

/** One property the node does not have yet, and the press that puts its control on the panel. */
@Composable
private fun AddPropertyRow(field: EditorPropertyField, onAdd: () -> Unit) {
  TextButton(
    onClick = onAdd,
    modifier =
      Modifier.fillMaxWidth().semantics { contentDescription = "Add ${field.label} property" },
  ) {
    Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(16.dp))
    Spacer(Modifier.width(8.dp))
    Text(field.label, Modifier.weight(1f))
    Text(
      field.control.name.lowercase(),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
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
      SelectionContainer {
        Text(
          it,
          Modifier.semantics { contentDescription = "${field.label} validation error" },
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.labelSmall,
        )
      }
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
  val dirty = draft != field.value
  // The field and its Apply on one line, and the Apply only once the value has actually been
  // edited. A full-width filled button under every property is what made this panel need 360 dp
  // and five scrolls to reach a font size: on a text leaf it drew six of them, all identical, none
  // of them doing anything until something above it changed.
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    BasicTextField(
      value = draft,
      onValueChange = { draft = it },
      modifier =
        Modifier.weight(1f)
          .onFocusChanged { onTextInputFocusChanged(it.isFocused) }
          .semantics { contentDescription = "${field.label} property" }
          .padding(top = 7.dp)
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
          .padding(10.dp),
      textStyle =
        MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
      singleLine = field.name != "text",
    )
    if (dirty) {
      TextButton(
        onClick = { commit(draft) },
        modifier =
          Modifier.padding(start = 4.dp, top = 7.dp).semantics {
            // The name the accessibility tree and every script driving this editor already look
            // for, even though the face is now one word: a control that renamed itself when it
            // shrank would be a silent break rather than a smaller button.
            contentDescription = "Apply ${field.label.lowercase()}"
          },
        contentPadding = PaddingValues(horizontal = 10.dp),
      ) {
        Text("Apply")
      }
    }
  }
  if (showSteppers) {
    val bounds = field.numberBounds
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      TextButton(
        onClick = {
          val current = draft.toDoubleOrNull() ?: bounds?.minimum ?: 0.0
          draft =
            (current - (bounds?.step ?: 1.0))
              .coerceIn(bounds!!.minimum, bounds.maximum)
              .editorNumber(bounds.integer)
          commit(draft)
        },
        contentPadding = PaddingValues(horizontal = 12.dp),
      ) {
        Text("−")
      }
      Text(
        bounds
          ?.let { "${it.minimum.editorNumber(it.integer)}…${it.maximum.editorNumber(it.integer)}" }
          .orEmpty(),
        Modifier.weight(1f),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      )
      TextButton(
        onClick = {
          val current = draft.toDoubleOrNull() ?: bounds?.minimum ?: 0.0
          draft =
            (current + (bounds?.step ?: 1.0))
              .coerceIn(bounds!!.minimum, bounds.maximum)
              .editorNumber(bounds.integer)
          commit(draft)
        },
        contentPadding = PaddingValues(horizontal = 12.dp),
      ) {
        Text("+")
      }
    }
  }
  if (field.name == "text") {
    TextButton(
      onClick = { commit("Edited in Compose") },
      contentPadding = PaddingValues(horizontal = 10.dp),
    ) {
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
  // A refusal is something a person quotes — into an issue, into a chat, into a search — so the
  // list is selectable. A tap still selects the node: [SelectionContainer] claims the long press
  // and the drag, not the click underneath it.
  SelectionContainer {
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
          // Selectable for the same reason the source is: a refusal is the pane's other answer,
          // and it is no more quotable than the Kotlin if it can only be read.
          SelectionContainer {
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
        // The host's own words about why it could not draw this, so they are selectable: what a
        // compile failure says is the whole content of the report somebody is about to file.
        render.failure != null ->
          SelectionContainer {
            Text(
              render.failure,
              Modifier.padding(top = 12.dp),
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        render.refusals.isNotEmpty() -> {
          Text(
            "No native render · the generator refuses this design",
            Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall,
          )
          SelectionContainer {
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
    SelectionContainer {
      Text(
        it,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
      )
    }
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
