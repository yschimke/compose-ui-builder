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
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
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
import androidx.compose.material.icons.filled.CodeOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Link
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
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
 * How big a palette row's picture is.
 *
 * 4:3, matching the frame the component is drawn in, so the shrink is uniform and nothing is
 * squashed. Wide enough that a Card reads as a card rather than as a grey rectangle, and small
 * enough that a 280 dp panel still has room for a name, an id, a count and an Add.
 */
private val COMPONENT_THUMBNAIL_SIZE = DpSize(44.dp, 33.dp)

private val VARIANT_THUMBNAIL_SIZE = DpSize(32.dp, 24.dp)

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
  /** Which kind of screen it authors; the chooser orders and groups catalogs by it. */
  val platform: UiBuilderCatalogPlatform = UiBuilderCatalogPlatform.MOBILE,
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
  /**
   * The revision a link to one node may name: the last the server accepted, and only if that
   * revision is one the node was in. Null everywhere else, and null by default.
   *
   * Two questions rather than one, because a layer link gets them both wrong in opposite
   * directions. `state.document.revision` is not the answer to the first: the reducer raises it the
   * moment an edit is applied so the canvas can draw it, which is a claim about a submission still
   * in the queue — a link at that number resolves to nothing, or, once a collaborator's edit
   * claimed the number first, to a document the person who copied it never saw. But the last
   * accepted revision is not the answer either for a node that only exists because of a queued
   * insert, duplicate or paste: pairing it with the new node's id makes a link that is *reliably*
   * stale, opening on the missing-layer notice.
   *
   * So a host answers per node, and a null means the link names no revision and opens the living
   * design at that layer — which is right in both cases, and arrives at the layer as soon as the
   * edit that made it lands.
   */
  authoritativeRevisionFor: (String) -> Long? = { null },
  initialSelectedNodeId: String? = null,
  initialCatalogQuery: String = "",
  initialLayerQuery: String = "",
  initialInspectorMode: EditorInspectorMode = EditorInspectorMode.Properties,
  /**
   * Changes to make to the design as the editor opens, in order, as though somebody had made them.
   *
   * Empty everywhere a person is editing: this is not a way to author a document, and a host that
   * wants a different design should open a different one. It exists for the same reason
   * [initialCanvasZoom] pins a scale — a caller that is *picturing* the editor rather than running
   * it. The History panel is about what this session has done, so a session that has done nothing
   * draws the one state that says nothing about the panel, and the preview that has to diff it
   * hands the session the edits it is a picture of.
   *
   * Anything the reducer refuses is left out of the state the same way it would be for a person: a
   * seed that cannot be applied is not a reason to refuse to open the design.
   */
  initialEdits: List<UiBuilderEditorEvent> = emptyList(),
  initialPreviewMode: Boolean = false,
  initialCodePaneVisible: Boolean = false,
  /**
   * Whether the strip of revision thumbnails is open when the design opens.
   *
   * Off everywhere a person is editing — the strip is a question about the design's history, and
   * the History rail is where it is asked. It exists for the same caller [initialEdits] does: one
   * *picturing* the editor, which cannot press the control it wants a picture of.
   */
  initialHistoryBarVisible: Boolean = false,
  /**
   * The revision the strip is looking at, and the other end of a comparison, when the design opens.
   *
   * Applied after [initialEdits], because a peek is a view over a history and the seeded edits are
   * what the history is. Both null everywhere a person is editing.
   */
  initialRevisionPeek: Int? = null,
  initialRevisionCompare: Int? = null,
  /**
   * The component packs switched on when the design opens, by id — what the host remembered from
   * the last time this catalog's settings were changed. Ids the catalog has no pack for are
   * ignored, so a remembered pack an operator has since withdrawn does nothing.
   */
  initialEnabledPacks: Set<String> = emptySet(),
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
   * The two tool modes a host may want a picture of: whether an Add starts a top-level item, and
   * which unstored axes the variant strip draws.
   *
   * Both are editor state rather than document state, so without these the previews that exist to
   * diff them would have to click their way into the mode — which a static render cannot do. Every
   * other host leaves them at their defaults, which are the same off state a person's editor opens
   * in.
   */
  initialAddBeside: Boolean = false,
  initialVariantAxes: Set<EditorVariantAxis> = emptySet(),
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
  /**
   * Compiles this design on the host and draws it, in the host container frame it is handed.
   *
   * The shape is a parameter rather than something the host re-derives, because the pane must agree
   * with the canvas beside it: both are drawing the frame the editor is currently viewing, and a
   * render that picked its own would be the one disagreement this pane cannot be allowed to invent.
   * Ignored for every design whose root is not a widget container.
   */
  onRequestNativeRender: (suspend (WearWidgetHostShape) -> UiBuilderNativeRender)? = null,
  /** A render already in hand, for the previews that draw this pane without a host. */
  initialNativeRender: UiBuilderNativeRender? = null,
  initialPreviewSurface: EditorPreviewSurface = EditorPreviewSurface.Wasm,
  collaborators: List<UiBuilderCollaborator> = emptyList(),
  /**
   * What a read of the project's component library said about the components this design imported.
   *
   * Supplied by the host rather than fetched here, for the same reason the device presets are: it
   * is a request against a design id with the host's own credential, and the reducer has neither.
   * Empty (the default) is "nobody asked", which shows nothing — a host with no component library
   * behind it gets the editor it had before this existed.
   */
  componentDrift: List<ComponentDriftFinding> = emptyList(),
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
  /**
   * The thread the address bar names — now, not only when the editor mounted.
   *
   * Separate from the selection the panel keeps for itself: a link says where to start reading, and
   * the reader is free to move off it, which is why this is not simply the panel's state. But it is
   * *not* read once. A fragment-only navigation — a second `#thread=` link followed from inside the
   * open design, or Back over one — never reloads the page, so a host that reported only the
   * startup value would leave the panel on the previous conversation while the address bar named
   * the new one. Each new non-null value is opened and scrolled to exactly once, the way the first
   * one is.
   */
  linkedThreadId: String? = null,
  /**
   * How many times the browser has changed the fragment, and the only thing that moves this
   * selection after the first paint.
   *
   * [linkedThreadId] answers *what* the address bar names; this answers *whether the address bar
   * just changed*, and the two come apart. A host keeps that id in step with the URL, which means
   * clearing it when the reader opens a different thread — the fragment stops naming the old one at
   * that moment. Keyed on the id alone, this editor would read its own host's bookkeeping as a
   * navigation and immediately close the thread that caused it.
   */
  threadNavigations: Int = 0,
  /**
   * Which thread the panel has open now, told to the host on every change.
   *
   * The host uses it to keep the address bar honest: a `#thread=` naming a conversation the reader
   * has since closed is a URL that lies about what is on screen.
   */
  onSelectedThreadChanged: ((String?) -> Unit)? = null,
  /**
   * The committed revision `?revision=` pinned this editor to, or null for the living design.
   *
   * See [DesignRevisionPin] — it carries both the revision the link asked for and whether that is
   * what the canvas got, because a banner that cannot tell those apart cannot be trusted.
   */
  revisionPin: DesignRevisionPin? = null,
  /** Leaves a pinned revision for the design as it stands now. Null where the host cannot. */
  onGoToLatest: (() -> Unit)? = null,
  /**
   * A sentence about the link that opened this editor — a node id this design does not have.
   *
   * A notice rather than a refusal, and never an error: a selector that names nothing is a stale
   * link, and the design behind it still opens.
   */
  openingNotice: String? = null,
  /**
   * Copies a link to one place in this design and answers with a sentence, or null where the host
   * cannot reach a clipboard.
   *
   * The editor says *what* the link means — this node, this thread, at this revision — and the host
   * turns that into an address against its own origin; see [designUrlPath]. Null in every preview
   * and test, where the affordance is then absent rather than present and failing, which is the
   * rule [onPickReference] and [exportHost] already follow.
   */
  onCopyDesignLink: (suspend (DesignUrlSelectors) -> String)? = null,
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
   * Copies this design into the browser's own storage and opens it there, or null where it cannot.
   *
   * Null in every mode but a live server session: a design already kept in this browser has nowhere
   * to be taken, and a host with no local mode has nothing to take it into. What it costs and what
   * it keeps working is `docs/design/UI_BUILDER_LOCAL_STORAGE.md`; how it comes home again is
   * `docs/design/UI_BUILDER_DESIGN_PORTABILITY.md`.
   */
  onTakeOffline: (() -> Unit)? = null,
  /**
   * Replays this browser's stored edits back onto the server the design was taken from.
   *
   * Null unless this design has a fork point — a design created here has no server to go home to,
   * and publishing it is a create rather than a merge.
   */
  onSyncToServer: (() -> Unit)? = null,
  /**
   * Copies, links and downloads the rendered design, or null where the host cannot.
   *
   * Null in every preview and test, where the toolbar then carries no Export menu rather than one
   * whose every row fails — see [UiBuilderExportHost].
   */
  exportHost: UiBuilderExportHost? = null,
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
  /**
   * Fetches the serving catalog's rendered PNG for one Remote Compose source.
   *
   * Loaded lazily by the visible palette rows: a catalog may publish hundreds of documents, so
   * opening the panel must not download its whole sticker sheet. Null keeps the neutral component
   * glyph, which is still an honest visual affordance in offline previews and tests.
   */
  resolveRemoteComposeThumbnail: (suspend (RemoteComposeSource) -> ImageBitmap?)? = null,
  /**
   * Fetches the Base64-encoded document at an embedded node's `documentUrl`, or throws.
   *
   * The other half of [resolveRemoteComposeDocument] and deliberately a separate parameter. That
   * one is an *authoring* action: an author presses Add, the bytes are copied into the design, and
   * the design carries them for ever after. This one is a *reference*: the design carries a URL,
   * and what the canvas draws is whatever that URL serves today. A host that can do one and not the
   * other is a real configuration — a catalog with a published sticker sheet and no proxy for
   * arbitrary URLs is exactly it — so the two are asked for separately.
   *
   * Null leaves every `documentUrl` node drawing its waiting state, which is the honest answer for
   * a host that cannot fetch: the node is not broken, it is unresolved.
   */
  resolveRemoteComposeUrl: (suspend (String) -> String)? = null,
  /**
   * Fetches a Lottie animation's JSON from the URL a `remote-m3/lottie` element carries, or throws.
   *
   * Host-owned like [resolveRemoteComposeDocument], and for a sharper reason than "the network is
   * not a reducer's": the browser host resolves every request it makes against the page's own
   * origin and refuses the rest, so which animations are reachable is that host's rule to state,
   * not this composable's. Null simply leaves a URL unresolved, which the canvas already draws as
   * the unfinished thing it is.
   */
  loadLottieAnimation: (suspend (String) -> String)? = null,
  /**
   * Fetches the bytes behind one of the design's **uploaded** assets, by asset key, or throws.
   *
   * Host-owned for the reason the two above are: the design names a storage key and only the host
   * that stores it can turn that into pixels, over whatever route and credential it holds. Null
   * leaves every uploaded picture drawing its placeholder, which is the honest answer for a host
   * with no asset lane — the node is not broken, its picture is elsewhere.
   */
  resolveDesignAsset: (suspend (String) -> ByteArray)? = null,
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
            addBeside = initialAddBeside,
            variantAxes = initialVariantAxes,
            previewMode = initialPreviewMode,
            codePaneVisible = initialCodePaneVisible,
            historyBarVisible = initialHistoryBarVisible,
            enabledPacks =
              initialEnabledPacks.filterTo(mutableSetOf()) { catalog.componentPacks[it] != null },
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
  // After the reconcile above rather than inside the state it opens with, because that reconcile
  // fires on the first composition too: a session seeded at construction has a document the
  // authoritative one does not match, so it was rebuilt from the authoritative one and the seed
  // was gone before anything drew. Once per design, and never at all in the empty default.
  var seeded by remember(document.id) { mutableStateOf(false) }
  LaunchedEffect(document.id) {
    if (!seeded && (initialEdits.isNotEmpty() || initialRevisionPeek != null)) {
      seeded = true
      // The peek goes on last, and through the same events a press dispatches: an edit ends a peek,
      // so one applied before the seeded edits would be gone before anything drew.
      var seededState = initialEdits.fold(state, reducer::reduce)
      if (initialRevisionPeek != null) {
        seededState =
          reducer.reduce(seededState, UiBuilderEditorEvent.ShowRevision(initialRevisionPeek))
        if (initialRevisionCompare != null) {
          seededState =
            reducer.reduce(
              seededState,
              UiBuilderEditorEvent.CompareRevision(initialRevisionCompare),
            )
        }
      }
      state = seededState
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
  var draggedComponentVariant by remember { mutableStateOf<EditorCatalogVariant?>(null) }
  var draggedRemoteThumbnail by remember { mutableStateOf<ImageBitmap?>(null) }
  var canvasBounds by remember { mutableStateOf(Rect.Zero) }
  // The scale the design is pinned at, or null while it is framed to the workspace. Local rather
  // than in [UiBuilderEditorState] for the same reason the open panels are: how far somebody has
  // zoomed in is a fact about their window, not about the design, and an authoritative snapshot
  // that reset it would be worse than one that remembers nothing.
  var canvasZoom by remember(document.id) { mutableStateOf(initialCanvasZoom) }
  // Which control the hover editor should put the caret in, set by an action that just created the
  // value being edited and cleared the moment it lands.
  var hoverFocusTarget by remember(document.id) { mutableStateOf<String?>(null) }
  var textInputFocused by remember { mutableStateOf(false) }
  // Opened where the URL asked for a panel, on a narrow viewport as much as a wide one. The
  // compact layout draws its docks from this rather than from [inspectorOpen], so initialising only
  // that flag left `?node=` and `#thread=` selecting silently on a phone: the state was right and
  // nothing was on screen. `Properties` is the compact dock that hosts every inspector mode, Talk
  // included — the same pairing `onOpenProperties` already makes.
  var mobilePanel by
    remember(document.id) {
      mutableStateOf(
        if (initialInspectorOpen) MobileEditorPanel.Properties else MobileEditorPanel.None
      )
    }
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
  var showPacks by remember(document.id) { mutableStateOf(false) }
  // Offered only where there is something to switch: a settings entry over an empty list is a
  // control that teaches nothing.
  val onComponentPacks: (() -> Unit)? =
    if (catalog.componentPacks.isEmpty) null else ({ showPacks = true })
  // The source whose document is being fetched, or null. One at a time on purpose: the palette is a
  // list of 476 rows on the Remote M3 catalog, and a double-click that started two fetches would
  // insert the same component twice — the second insert lands against a document the first already
  // changed, and neither the author nor their collaborators asked for it.
  var pendingRemoteSource by remember(document.id) { mutableStateOf<RemoteComposeSource?>(null) }
  // A Remote Compose drop captures its pointer-resolved slot before fetching the document bytes.
  // Null is the ordinary Add path, which resolves against the current selection after the fetch.
  var pendingRemoteTarget by remember(document.id) { mutableStateOf<ParentSlot?>(null) }
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
  var selectedThreadId by remember(document.id) { mutableStateOf(linkedThreadId) }
  // A sentence the editor itself put up — a refused edit under a pinned revision, the answer to a
  // Copy link — kept apart from [openingNotice], which is the host's and does not expire.
  var transientNotice by remember(document.id) { mutableStateOf<String?>(null) }
  var transientNoticeGeneration by remember(document.id) { mutableStateOf(0) }
  LaunchedEffect(transientNoticeGeneration) {
    if (transientNotice == null) return@LaunchedEffect
    delay(EXPORT_STATUS_MILLIS)
    transientNotice = null
  }
  fun say(sentence: String) {
    transientNotice = sentence
    transientNoticeGeneration += 1
  }
  val editorScope = rememberCoroutineScope()
  fun selectThread(threadId: String?) {
    selectedThreadId = threadId
    onSelectedThreadChanged?.invoke(threadId)
  }
  fun canvasTarget(componentId: String, position: Offset): ParentSlot? {
    if (!canvasBounds.contains(position)) return null
    return canvasInspection?.let { snapshot ->
      reducer.catalogDropTarget(
        state,
        componentId,
        snapshot.slots,
        snapshot.nodes.mapNotNull { node -> node.bounds?.let { node.nodeId to it } }.toMap(),
        position.x,
        position.y,
      )
    }
  }
  val draggedTarget = draggedComponentId?.let { componentId ->
    catalogDragPosition?.let { position -> canvasTarget(componentId, position) }
  }
  val canvasDropHovered = draggedTarget != null
  /**
   * One editor event, and the one place a pinned revision stops being editable.
   *
   * The guard is the operation sequence rather than a list of which events are edits: the reducer
   * bumps it for every command it forms, accepted or rejected, and for nothing else. So selecting,
   * filtering, opening a panel, marking up the reference and switching a pack all pass through a
   * pinned editor untouched, while every change to the *document* is dropped before it can reach
   * the local state — which matters, because an optimistic edit that never becomes a submission
   * would leave the canvas showing a revision that exists nowhere.
   */
  fun dispatch(event: UiBuilderEditorEvent) {
    val previous = state
    val current = reducer.reduce(previous, event)
    if (revisionPin?.readOnly == true && current.operationSequence != previous.operationSequence) {
      say("Revision ${revisionPin.requested} is read-only. Go to latest to edit.")
      return
    }
    state = current
    reducer.acceptedSubmission(previous, current)?.let { onSubmission?.invoke(it) }
  }
  fun focusEditor() {
    textInputFocused = false
    editorFocusRequester.requestFocus()
  }
  // The host's answer, into the state the Issues panel reads. An effect rather than a value folded
  // in at composition because the fetch lands after mount, and the reducer's copy has to survive
  // the document rebuilds that happen between then and the next fetch.
  //
  // Re-dispatched whenever the document's component declarations change, and that is not belt and
  // braces. `stillDescribing` drops a finding the moment its component stops matching, which is
  // right — but an edit that drops one is very often reversible, and undo restores the exact
  // source the finding described. Without this the row would stay gone until a reload, because the
  // host has no reason to fetch again. Re-handing the host's own unfiltered list lets the reducer
  // decide afresh; anything still invalid is filtered out again, so this cannot resurrect a row
  // that has stopped being true.
  LaunchedEffect(componentDrift, state.document.components) {
    dispatch(UiBuilderEditorEvent.SetComponentDrift(componentDrift))
  }
  // Following the address bar after the first paint, for the navigation the browser answers without
  // reloading: a fragment-only move between two thread links, or Back over one.
  //
  // Null is a case and not a no-op. Back out of a `#thread=` URL to the fragment-free design is a
  // same-document navigation like any other, and leaving the previous conversation selected while
  // the address bar has stopped naming it is the same disagreement this effect exists to prevent.
  // Deselecting cannot fight the reader who closed a thread by hand: that path has already set the
  // selection to null, so this finds nothing to do. The panel is opened for a thread and not shut
  // again for a null — where the reader ended up is the board, and closing it under them would be
  // answering a navigation with more than it asked for.
  LaunchedEffect(threadNavigations) {
    // The value the editor mounted with is already the selection; only a later navigation acts.
    if (threadNavigations == 0) return@LaunchedEffect
    val threadId = linkedThreadId
    if (threadId == selectedThreadId) return@LaunchedEffect
    selectThread(threadId)
    if (threadId != null) {
      dispatch(UiBuilderEditorEvent.ShowInspector(EditorInspectorMode.Comments))
      inspectorOpen = true
      mobilePanel = MobileEditorPanel.Properties
    }
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
      // The link names the *anchor* rather than the whole selection: a URL selects one node, and
      // the anchor is the node every other single-selection question in this editor is asked of.
      // Pinned to the revision the host confirmed this layer was in, and offered only where there
      // is one. A layer that exists solely because of a queued insert, duplicate or paste is in no
      // revision yet, and neither shape of link to it works: pinned, it names a revision the layer
      // was not in; unpinned, it opens the living design, where the recipient's own first snapshot
      // has no such node — so the editor falls back to the root and never reselects when the edit
      // lands. Nothing here can make that link correct, so the row is withheld for the moment the
      // queue takes rather than copying an address that is wrong on arrival.
      onCopyLink =
        onCopyDesignLink?.let { copy ->
          state.selectedNodeId?.let { nodeId ->
            authoritativeRevisionFor(nodeId)?.let { revision ->
              {
                editorScope.launch {
                  say(
                    copyLinkSentence(
                      copy,
                      DesignUrlSelectors(revision = revision, nodeId = nodeId),
                    )
                  )
                }
                Unit
              }
            }
          }
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
        packs = catalog.componentPacks,
        onManagePacks = onComponentPacks,
        thumbnailOf = reducer::previewDocument,
        layerRows = layerRows,
        collaborators = collaborators,
        dropTarget = reducer.dropTarget(state, draggedComponentId ?: "m3/text"),
        onCatalogDrag = { componentId, variant, position ->
          if (position == null) {
            draggedComponentId = null
            draggedComponentVariant = null
          } else {
            focusEditor()
            draggedComponentId = componentId
            draggedComponentVariant = variant
          }
          draggedRemoteThumbnail = null
          catalogDragPosition = position
        },
        onCatalogDrop = { componentId, variant, position ->
          // Where it was dropped, not where the selection happens to be. The palette's own legend
          // says a drag inserts the component "where it is dropped", and it did not: every drop
          // landed in the selected node's slot, so dragging onto a card put the component wherever
          // the last click had been. The renderer already reports each slot's box, and the
          // reference
          // overlay already promotes a piece into the slot under it — this asks the same question.
          val target = canvasTarget(componentId, position)
          if (target != null) {
            dispatch(UiBuilderEditorEvent.InsertComponent(componentId, target, variant))
            if (closeAfterDrop) mobilePanel = MobileEditorPanel.None
          }
          draggedComponentId = null
          draggedComponentVariant = null
          draggedRemoteThumbnail = null
          catalogDragPosition = null
        },
        // Beside the design, every component can be added: a top-level item is in no slot, so there
        // is no compatibility to satisfy. The one thing that can still refuse is the wrap itself.
        canAddCatalogComponent = {
          // The component as well as the document: beside the design there is no slot to satisfy,
          // but a component whose emitter demands the root is still not one a board can hold.
          if (state.addBeside) reducer.besideRefusal(state, it) == null
          else reducer.dropTarget(state, it) != null
        },
        // Only in beside mode, and only what is specific to the component. `besideRefusal` falls
        // through to the document's own answer when the component has nothing to say, so asking it
        // per row on a non-board Wear design returned the *wrap* refusal for every component in the
        // catalog — the same sentence on all 41 rows, under a destination line already carrying it.
        // The document's refusal belongs to the panel; only a component's belongs to a row.
        catalogAddRefusal = {
          if (state.addBeside && reducer.besideRefusal(state) == null)
            reducer.besideRefusal(state, it)
          else null
        },
        besideRefusal = reducer.besideRefusal(state),
        onCatalogAdd = { componentId, variant ->
          focusEditor()
          if (state.addBeside) {
            dispatch(UiBuilderEditorEvent.InsertComponentBeside(componentId, variant))
            if (closeAfterDrop) mobilePanel = MobileEditorPanel.None
          } else
            reducer.dropTarget(state, componentId)?.let { target ->
              dispatch(UiBuilderEditorEvent.InsertComponent(componentId, target, variant))
              if (closeAfterDrop) mobilePanel = MobileEditorPanel.None
            }
        },
        remoteComposeSources =
          if (resolveRemoteComposeDocument == null) emptyList() else remoteComposeSources,
        pendingRemoteComposeSource = pendingRemoteSource,
        remoteComposeFailure = remoteSourceFailure,
        resolveRemoteComposeThumbnail = resolveRemoteComposeThumbnail,
        onAddRemoteComposeSource = { source ->
          focusEditor()
          if (pendingRemoteSource == null) {
            pendingRemoteTarget = null
            pendingRemoteSource = source
          }
          if (closeAfterDrop) mobilePanel = MobileEditorPanel.None
        },
        onRemoteComposeDrag = { source, thumbnail, position ->
          if (position != null) focusEditor()
          draggedComponentId = REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID
          draggedComponentVariant = null
          draggedRemoteThumbnail = thumbnail
          catalogDragPosition = position
          if (position == null) {
            draggedComponentId = null
            draggedComponentVariant = null
            draggedRemoteThumbnail = null
          }
        },
        onRemoteComposeDrop = { source, position ->
          val target = canvasTarget(REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID, position)
          if (target != null && pendingRemoteSource == null) {
            pendingRemoteTarget = target
            pendingRemoteSource = source
            if (closeAfterDrop) mobilePanel = MobileEditorPanel.None
          }
          draggedComponentId = null
          draggedComponentVariant = null
          draggedRemoteThumbnail = null
          catalogDragPosition = null
        },
        moveRefusal = { nodeId, target -> reducer.moveRefusal(state, nodeId, target) },
        onEditorInteraction = ::focusEditor,
        onTextInputFocusChanged = { textInputFocused = it },
        dispatch = ::dispatch,
        modifier = modifier,
      )
    }
  // The strip beside the design: the devices it claims, plus whichever unstored axes are switched
  // on. Computed here rather than in the canvas because it is a question about the *design* — its
  // stored `exportDevices` and the editor's own axes — and the canvas draws what it is handed.
  val variantPanes =
    remember(state.document, devicePresets, state.variantAxes) {
      state.document.variantPanes(devicePresets, state.variantAxes)
    }
  val canvas: @Composable (Modifier, Alignment) -> Unit = { modifier, alignment ->
    PinnedDesignCanvas(
      document = state.document,
      variants = variantPanes,
      selectedNodeId = state.selectedNodeId,
      onNodeSelected = {
        focusEditor()
        dispatch(UiBuilderEditorEvent.SelectNode(it))
      },
      onCanvasMetrics = { width, height, scale -> onCanvasMetrics(width, height, scale) },
      onCanvasBounds = {
        canvasBounds = it
        onCanvasBoundsChanged(it)
      },
      dropHovered = canvasDropHovered,
      dropTarget = draggedTarget,
      dragPreview =
        if (draggedRemoteThumbnail == null)
          draggedComponentId?.let { reducer.previewDocument(it, draggedComponentVariant) }
        else null,
      dragPreviewBitmap = draggedRemoteThumbnail,
      dragPosition = catalogDragPosition,
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
        selectThread(threadId)
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
  val documentProblems = remember(reducer, state.document) { reducer.problems(state.document) }
  // Appended rather than folded into `problems`, which is a pure function of the document and stays
  // one: drift is a fact about another host's library, fetched by whoever is hosting this editor
  // and handed over as state. Keyed on the findings alone, so the cache above survives a fetch.
  val driftProblems =
    remember(state.componentDrift) { componentDriftProblems(state.componentDrift) }
  val problems = documentProblems + driftProblems
  // Keyed on the operation counter rather than on the document: an undo puts the document back to
  // one the history has already seen, and the entry it moved the marker to is the whole point.
  val operationHistory =
    remember(reducer, state.operationSequence, state.document.revision) {
      reducer.operationHistory(state)
    }
  // The same history as a strip of pictures, and only while the strip is open: each row costs a
  // rebuilt document held in memory and a composed thumbnail on screen, and a session nobody is
  // reviewing should not pay for either. Keyed exactly as the list above is, for the same reason —
  // an undo puts the document back to one the strip has already drawn, and the row it moved the
  // marker to is the point.
  val revisionEntries =
    remember(
      reducer,
      state.operationSequence,
      state.document.revision,
      state.historyBarVisible,
      operationHistory,
    ) {
      if (state.historyBarVisible) revisionTimeline(state, operationHistory) else emptyList()
    }
  val revisionComparison =
    remember(state.operationSequence, state.revisionPeek, state.revisionCompare) {
      val peeked = state.revisionPeek
      val compared = state.revisionCompare
      if (peeked == null || compared == null) null
      else revisionDiff(state, catalog, peeked, compared)
    }
  val peekedRevision = revisionEntries.firstOrNull { it.revision == state.revisionPeek }
  val comparedRevision = revisionEntries.firstOrNull { it.revision == state.revisionCompare }
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
  // Keyed on the host shape as well, so switching the frame re-renders rather than leaving the
  // pane showing the widget in the container the canvas has stopped drawing.
  LaunchedEffect(nativeRequested, state.document.revision, state.wearWidgetHostShape) {
    if (!nativeRequested) return@LaunchedEffect
    nativePending = true
    nativeRender =
      try {
        onRequestNativeRender(state.wearWidgetHostShape)
      } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
        throw cancelled
      } catch (failure: Throwable) {
        UiBuilderNativeRender(failure = failure.message ?: "the native render request failed")
      }
    nativePending = false
  }
  LaunchedEffect(pendingRemoteSource) {
    val source = pendingRemoteSource ?: return@LaunchedEffect
    val resolve =
      resolveRemoteComposeDocument
        ?: run {
          pendingRemoteTarget = null
          pendingRemoteSource = null
          return@LaunchedEffect
        }
    val encoded =
      try {
        resolve(source)
      } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
        throw cancelled
      } catch (failure: Throwable) {
        remoteSourceFailure = "${source.label}: ${failure.message ?: "could not be fetched"}"
        pendingRemoteTarget = null
        pendingRemoteSource = null
        return@LaunchedEffect
      }
    // A pointer drop promised a particular visible slot before this network round trip began.
    // Preserve that promise: the reducer validates the captured slot against the current document,
    // so a collaborator removing it during the fetch is refused instead of silently retargeted.
    pendingRemoteTarget?.let { target ->
      remoteSourceFailure = null
      dispatch(UiBuilderEditorEvent.InsertRemoteComposeDocument(source, encoded, target))
      pendingRemoteTarget = null
      pendingRemoteSource = null
      return@LaunchedEffect
    }
    // Resolved against the selection as it stands NOW, not as it stood when the row was pressed: a
    // fetch takes a round trip, and the reducer would refuse a target the author has since moved
    // away from. Asking again is what makes the insert land where the canvas says it will.
    // Beside the design when that is the mode, resolved here for the same reason the target below
    // is: both are read as they stand NOW rather than as they stood when the row was pressed, and a
    // fetch is a round trip. Dispatching the ordinary insert regardless is what made a Remote
    // Compose row offered under Add beside either refuse after its fetch or land inside the
    // selection while the panel promised a top-level item.
    if (state.addBeside) {
      val refusal = reducer.besideRefusal(state, REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID)
      remoteSourceFailure = refusal?.let { "${source.label}: $it" }
      if (refusal == null) {
        dispatch(UiBuilderEditorEvent.InsertRemoteComposeDocumentBeside(source, encoded))
      }
      pendingRemoteTarget = null
      pendingRemoteSource = null
      return@LaunchedEffect
    }
    val target = reducer.dropTarget(state, REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID)
    if (target == null) {
      remoteSourceFailure = "${source.label}: no compatible slot is selected"
    } else {
      remoteSourceFailure = null
      dispatch(UiBuilderEditorEvent.InsertRemoteComposeDocument(source, encoded, target))
    }
    pendingRemoteTarget = null
    pendingRemoteSource = null
  }
  // Every `documentUrl` the design references, and what came back for it.
  //
  // Keyed by URL rather than by node, so two nodes pointing at one document are one fetch and one
  // decode. Held across revisions on purpose: an edit elsewhere in the design must not re-fetch
  // content that has not changed, and a URL removed from the design costs a map entry rather than a
  // round trip to discover it is gone.
  val remoteDocumentsByUrl = remember { mutableStateMapOf<String, Result<RcDocument>>() }
  val referencedUrls =
    state.document.nodes.values
      .filter { it.componentId == REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID }
      .mapNotNull { node ->
        (node.properties["documentUrl"] as? JsonObject)
          ?.get("value")
          ?.jsonPrimitive
          ?.contentOrNull
          ?.takeIf(String::isNotBlank)
      }
      .distinct()
      .sorted()
  LaunchedEffect(referencedUrls, resolveRemoteComposeUrl) {
    val resolve = resolveRemoteComposeUrl ?: return@LaunchedEffect
    referencedUrls.filterNot(remoteDocumentsByUrl::containsKey).forEach { url ->
      // Stored per URL as it arrives rather than after the whole list, so one unreachable
      // document does not hold the others off the canvas. The failure is stored too: a URL that
      // 404s is answered once and drawn as the error it is, instead of being retried every frame.
      remoteDocumentsByUrl[url] =
        try {
          decodeRemoteComposeDocument(resolve(url))
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
          throw cancelled
        } catch (failure: Throwable) {
          Result.failure(failure)
        }
    }
  }
  // Every uploaded asset the design names, decoded once per content digest.
  //
  // Keyed by digest rather than by asset key, which is what `LocalUiBuilderAssetBitmaps` is keyed
  // by too: re-pointing a key at a new picture changes the digest and fetches again, while an edit
  // anywhere else in the design finds its pictures already here. A failed fetch or decode is stored
  // as null so the placeholder is drawn once rather than the request retried every recomposition.
  val assetBitmapsByDigest = remember { mutableStateMapOf<String, ImageBitmap?>() }
  val uploadedAssets = state.document.uploadedAssets()
  LaunchedEffect(uploadedAssets, resolveDesignAsset) {
    val resolve = resolveDesignAsset ?: return@LaunchedEffect
    uploadedAssets
      .filterNot { (_, asset) -> assetBitmapsByDigest.containsKey(asset.contentDigest) }
      .forEach { (assetKey, asset) ->
        assetBitmapsByDigest[asset.contentDigest] =
          try {
            decodeUiBuilderAssetBitmap(resolve(assetKey))
          } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
          } catch (_: Throwable) {
            null
          }
      }
  }
  // The URL half of a Lottie element, resolved into the JSON half exactly once.
  //
  // Once, because the two halves are one source: `url` says which animation this is and `json` is
  // what the export compiles, and re-fetching a resolved element would overwrite an animation an
  // author may have edited by hand with whatever that URL serves today. A failed fetch is not
  // retried either — [attemptedLottieUrls] remembers the attempt, so a 404 is one message rather
  // than a loop hammering the host for as long as the design is open.
  val attemptedLottieUrls = remember(document.id) { mutableSetOf<String>() }
  val unresolvedLottie =
    state.document.nodes.values.firstOrNull { node ->
      node.componentId == LOTTIE_COMPONENT_ID &&
        node.propertyText("url").isNotEmpty() &&
        node.propertyText("json").isEmpty()
    }
  LaunchedEffect(unresolvedLottie?.id, unresolvedLottie?.propertyText("url")) {
    val node = unresolvedLottie ?: return@LaunchedEffect
    val load = loadLottieAnimation ?: return@LaunchedEffect
    val url = node.propertyText("url")
    if (!attemptedLottieUrls.add("${node.id}\u0000$url")) return@LaunchedEffect
    val json =
      try {
        load(url)
      } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
        throw cancelled
      } catch (failure: Throwable) {
        remoteSourceFailure = "$url: ${failure.message ?: "could not be fetched"}"
        return@LaunchedEffect
      }
    remoteSourceFailure = null
    dispatch(UiBuilderEditorEvent.CommitProperty(node.id, "json", json))
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
  // `variantsDrawn` is a parameter rather than a captured value because only the layout knows it:
  // the
  // compact branch draws the canvas unconditionally, while the wide one hands the pane to the
  // host's
  // renderer when that is the chosen surface. Deciding it up here got the narrow window wrong — the
  // strip was visibly drawn while the inspector said it was not.
  val inspector: @Composable (Modifier, Boolean) -> Unit = { modifier, variantsDrawn ->
    PropertyInspector(
      state = state,
      onClose = { inspectorOpen = false },
      fields = propertyFields,
      stateVariables = reducer.stateVariableNames(state),
      comparisonBindingProperties = comparisonBindingProperties,
      bindableProperties = bindableProperties,
      problems = problems,
      operationHistory = operationHistory,
      themeSettings = reducer.themeSettings(state),
      devicePresets = devicePresets,
      variantsDrawn = variantsDrawn,
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
      onSelectThread = ::selectThread,
      // Read once. The panel scrolls to the thread the URL named as it opens, and never again —
      // a later scroll would be the page fighting somebody who has started reading elsewhere.
      revealThreadId = linkedThreadId,
      onPostComment = onPostComment,
      onResolveCommentThread = onResolveCommentThread,
      // A thread's link carries the thread in the fragment and, where the thread is pinned to a
      // layer, that layer too: a conversation about a button is worth opening beside the button.
      // No revision — a discussion is about the living design, not the moment it was linked.
      onCopyThreadLink =
        onCopyDesignLink?.let { copy ->
          { thread: DesignCommentThread ->
            editorScope.launch {
              say(
                copyLinkSentence(
                  copy,
                  DesignUrlSelectors(nodeId = thread.anchor?.nodeId, threadId = thread.id),
                )
              )
            }
            Unit
          }
        },
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

  // Provided once here rather than at each surface: the canvas, every palette thumbnail and the
  // preview frame all draw a pack component, and all of them should draw its placeholder.
  CompositionLocalProvider(
    LocalUiBuilderNativeOnly provides catalog.nativeOnlyComponentIds,
    LocalRemoteComposeDocuments provides { url -> remoteDocumentsByUrl[url] },
    LocalUiBuilderAssetBitmaps provides { digest -> assetBitmapsByDigest[digest] },
    // Here for the same reason as the line above it: the canvas, the extent beside it and every
    // variant pane draw the same widget, and all of them should draw the frame being viewed.
    LocalWearWidgetHostShape provides state.wearWidgetHostShape,
  ) {
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
              onTakeOffline = onTakeOffline,
              onSyncToServer = onSyncToServer,
              exportHost = exportHost,
              onComponentPacks = onComponentPacks,
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
              onTakeOffline = onTakeOffline,
              onSyncToServer = onSyncToServer,
              exportHost = exportHost,
              onComponentPacks = onComponentPacks,
              // Absent where the host cannot draw: a project with no compile lane has exactly one
              // renderer, and offering a choice between it and nothing is not a choice.
              previewSurface = if (onRequestNativeRender == null) null else state.previewSurface,
              previewSurfaces = catalog.previewSurfaces,
              nativeAvailable = onRequestNativeRender != null,
              dispatch = ::dispatch,
            )
          }
          // Under the toolbar and over everything else, on both layouts: what a link asked for is
          // the first thing to know about this page, and a strip inside one of the docks would be
          // behind a panel that starts closed.
          EditorUrlBanner(
            revisionPin = revisionPin,
            onGoToLatest = onGoToLatest,
            openingNotice = openingNotice,
            transientNotice = transientNotice,
          )
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
                  // An old revision replaces the editing surface rather than being drawn over it:
                  // a canvas that took a drop at revision 12 of a design that is at revision 40
                  // would be editing a picture, and not composing the editing surface at all is
                  // that rule holding itself rather than being enforced by an overlay.
                  if (peekedRevision != null) {
                    RevisionReviewPane(
                      peeked = peekedRevision,
                      compared = comparedRevision,
                      diff = revisionComparison,
                      onBackToNow = {
                        focusEditor()
                        dispatch(UiBuilderEditorEvent.ShowRevision(null))
                      },
                      modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                  } else {
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                      // The visual editor never leaves the workspace. Additional positions are
                      // previews of the same document, not alternative renderers that replace the
                      // authoring coordinate space.
                      canvas(
                        Modifier.weight(1f)
                          .fillMaxHeight()
                          .background(Color(0xff0d0e11))
                          .padding(24.dp),
                        Alignment.Center,
                      )
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
                      if (state.previewSurface == EditorPreviewSurface.Both) {
                        LiveWasmPreviewPane(
                          document = state.document,
                          modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                      }
                    }
                  }
                  if (state.historyBarVisible) {
                    RevisionHistoryBar(
                      entries = revisionEntries,
                      peeked = state.revisionPeek,
                      compared = state.revisionCompare,
                      onPeek = {
                        focusEditor()
                        dispatch(UiBuilderEditorEvent.ShowRevision(it))
                      },
                      onCompare = {
                        focusEditor()
                        dispatch(UiBuilderEditorEvent.CompareRevision(it))
                      },
                      onClose = {
                        focusEditor()
                        dispatch(UiBuilderEditorEvent.ToggleHistoryBar)
                      },
                    )
                  }
                  CanvasStatusBar(
                    state = state,
                    sessionLabel = sessionLabel,
                    dropTargetLabel =
                      draggedTarget?.let { "${it.nodeId}.${it.slot}" } ?: "No compatible slot",
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
                  else ->
                    inspector(
                      Modifier.width(INSPECTOR_WIDTH).fillMaxHeight(),
                      // The editor canvas and its variant strip are now present in every additive
                      // pane layout, including the two-pane editor + native preview choice.
                      true,
                    )
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
                          // The panel and the strip are one control. They are the same history
                          // asked two questions — what was done, and what it looked like — and a
                          // second switch would only let somebody have half of it.
                          if (
                            entry == EditorDock.History && inspectorOpen != state.historyBarVisible
                          ) {
                            dispatch(UiBuilderEditorEvent.ToggleHistoryBar)
                          }
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
                    .padding(bottom = 56.dp),
                  // Always, here: this branch draws the canvas whatever surface is chosen, and
                  // never
                  // the host's pane.
                  true,
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
        if (showPacks) {
          ComponentPacksDialog(
            packs = catalog.componentPacks,
            enabledPacks = state.enabledPacks,
            onToggle = { dispatch(UiBuilderEditorEvent.TogglePack(it)) },
            onDismiss = { showPacks = false },
          )
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
  // Pre-filled, so a design can be created in one click; a person who wants their own name
  // overwrites it, and one who wants another roll asks for it.
  var designId by remember { mutableStateOf(NewDesignNames.random()) }
  val selectedCatalog = catalogs.first { it.systemId == selectedCatalogId }
  val selectedTemplate =
    selectedCatalog.templates.firstOrNull { it.id == selectedTemplateId }
      ?: selectedCatalog.templates.first()
  val designIdValid = designId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))
  var declared by remember { mutableStateOf(listOf<NewDesignState>()) }
  // Folded away until asked for: most new designs declare no state at all, and the three
  // controls it takes to add one made the dialog read as a form with a required last section.
  var stateExpanded by remember { mutableStateOf(false) }
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
        // In platform order — phone, watch, Remote Compose widget — and grouped under a platform
        // heading only where a platform has more than one catalog to choose between. With one
        // catalog per platform the chip already says which platform it is, and a heading over a
        // single chip would say it twice.
        val byPlatform = catalogs.groupBy { it.platform }.entries.sortedBy { it.key.ordinal }
        byPlatform.forEach { (platform, platformCatalogs) ->
          if (platformCatalogs.size > 1) {
            Text(
              platform.label,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.labelMedium,
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            platformCatalogs.forEach { catalog ->
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
          trailingIcon = {
            TextButton(
              onClick = { designId = NewDesignNames.random() },
              modifier = Modifier.semantics { contentDescription = "Suggest another name" },
            ) {
              Text("Shuffle")
            }
          },
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
        if (!stateExpanded && declared.isEmpty()) {
          TextButton(
            onClick = { stateExpanded = true },
            modifier = Modifier.semantics { contentDescription = "Add state variables" },
          ) {
            Text("Add state variables…")
          }
        } else {
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
              modifier =
                Modifier.weight(1f).semantics { contentDescription = "State initial value" },
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
  onTakeOffline: (() -> Unit)?,
  onSyncToServer: (() -> Unit)?,
  exportHost: UiBuilderExportHost?,
  onComponentPacks: (() -> Unit)? = null,
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
      if (exportHost != null) ExportMenu(exportHost, showStatus = false)
      Box {
        TextButton(
          onClick = { expanded = true },
          modifier = Modifier.semantics { contentDescription = "More editor actions" },
        ) {
          Text("More")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
          // The host container shapes, on a widget design. Rows rather than the wide toolbar's
          // menu-inside-a-menu, because this is already the overflow: a second dropdown off one
          // row is a worse thing to hit on a narrow screen than two rows that read as a pair. The
          // wide toolbar's control is the same choice, and without these the whole rectangular
          // frame — canvas and native render — would be unreachable under 840dp.
          state.document.wearWidgetScaffoldSize()?.let { size ->
            WearWidgetHostShape.entries.forEach { option ->
              val spec = size.hostSpec(option)
              DropdownMenuItem(
                text = {
                  Text("${option.label} container · ${spec.frameWidthDp}×${spec.frameHeightDp}dp")
                },
                onClick = {
                  expanded = false
                  dispatch(UiBuilderEditorEvent.ShowWearWidgetHostShape(option))
                },
                leadingIcon = {
                  if (option == state.wearWidgetHostShape) {
                    Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp))
                  }
                },
              )
            }
          }
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
          if (onTakeOffline != null) {
            DropdownMenuItem(
              text = { Text("Keep in this browser") },
              onClick = {
                expanded = false
                onTakeOffline()
              },
            )
          }
          if (onSyncToServer != null) {
            DropdownMenuItem(
              text = { Text("Sync to the server") },
              onClick = {
                expanded = false
                onSyncToServer()
              },
            )
          }
          if (onComponentPacks != null) {
            DropdownMenuItem(
              text = { Text("Component packs…") },
              onClick = {
                expanded = false
                onComponentPacks()
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
  onTakeOffline: (() -> Unit)? = null,
  onSyncToServer: (() -> Unit)? = null,
  /** Copies, links and downloads the render, or null where the host cannot; hides the menu. */
  exportHost: UiBuilderExportHost?,
  /** Opens the component-pack settings, or null where the catalog offers no pack. */
  onComponentPacks: (() -> Unit)? = null,
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
      // Beside Code, because they are the two answers to "how do I get this out": the Kotlin the
      // design is, and the picture it draws. Absent where the host cannot render one.
      if (exportHost != null) ExportMenu(exportHost)
      if (previewSurface != null) {
        RenderSurfaceMenu(previewSurface, previewSurfaces, dispatch)
      }
      // Beside the renderer menu, because they are the two "what am I looking at" choices: which
      // renderer draws the design, and which host frame it is drawn inside.
      state.document.wearWidgetScaffoldSize()?.let { size ->
        WidgetHostShapeMenu(state.wearWidgetHostShape, size, dispatch)
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
          if (onTakeOffline != null) {
            DropdownMenuItem(
              text = { Text("Keep in this browser") },
              onClick = {
                overflowOpen = false
                onTakeOffline()
              },
            )
          }
          if (onSyncToServer != null) {
            DropdownMenuItem(
              text = { Text("Sync to the server") },
              onClick = {
                overflowOpen = false
                onSyncToServer()
              },
            )
          }
          if (onComponentPacks != null) {
            DropdownMenuItem(
              text = { Text("Component packs…") },
              leadingIcon = { Icon(Icons.Filled.Widgets, contentDescription = null) },
              onClick = {
                overflowOpen = false
                onComponentPacks()
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

/**
 * The Export menu: the design as a picture, out of the builder and into Figma, a link or a file.
 *
 * One button, because the catalog viewer's preview page has one row and this toolbar has no room
 * for six; the rows are [exportMenuEntries], grouped by verb. Each row hands its work to the host
 * and shows the sentence the host answers with beside the button for a moment — "SVG copied", or
 * why it was not — since a clipboard write that says nothing is indistinguishable from one that
 * failed. The button stays enabled while a row runs: a second press while an export renders is a
 * second export, which is harmless, and a disabled button reads as a broken one.
 */
@Composable
private fun ExportMenu(host: UiBuilderExportHost, showStatus: Boolean = true) {
  val groups = remember(host.formats) { exportMenuEntries(host.formats) }
  if (groups.isEmpty()) return
  var open by remember { mutableStateOf(false) }
  var status by remember { mutableStateOf<String?>(null) }
  var statusGeneration by remember { mutableStateOf(0) }
  val scope = rememberCoroutineScope()
  LaunchedEffect(statusGeneration) {
    if (status == null) return@LaunchedEffect
    delay(EXPORT_STATUS_MILLIS)
    status = null
  }
  Row(verticalAlignment = Alignment.CenterVertically) {
    val shown = status
    if (showStatus && shown != null) {
      Text(
        shown,
        Modifier.widthIn(max = 260.dp).semantics { contentDescription = "Export status" },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Box {
      ToolbarIconAction("Export", "", Icons.Filled.IosShare, true) { open = true }
      DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        ExportMenuRows(groups) { entry ->
          open = false
          scope.launch {
            status =
              try {
                host.perform(entry)
              } catch (failure: Exception) {
                "${entry.label} failed: ${failure.message ?: "unknown error"}"
              }
            statusGeneration++
          }
        }
      }
    }
  }
}

/**
 * The rows of the Export menu, without the popup around them.
 *
 * Separate from [ExportMenu] so a preview can draw them: a `DropdownMenu` is a popup window, which
 * a static render does not capture, and rows nobody can diff are rows that drift. The verb groups
 * are divided, and every row carries its second line, because "Copy SVG" alone does not say that it
 * is the Figma route.
 */
@Composable
internal fun ExportMenuRows(
  groups: List<List<EditorExportMenuEntry>>,
  onPick: (EditorExportMenuEntry) -> Unit,
) {
  groups.forEachIndexed { index, group ->
    if (index > 0) HorizontalDivider()
    group.forEach { entry ->
      DropdownMenuItem(
        text = {
          Column {
            Text(entry.label)
            Text(
              entry.detail,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        },
        leadingIcon = {
          Icon(
            when (entry) {
              is EditorExportMenuEntry.CopyPicture -> Icons.Filled.ContentCopy
              is EditorExportMenuEntry.CopyLink -> Icons.Filled.Link
              is EditorExportMenuEntry.Download -> Icons.Filled.Download
            },
            contentDescription = null,
          )
        },
        modifier = Modifier.semantics { contentDescription = entry.label },
        onClick = { onPick(entry) },
      )
    }
  }
}

/** How long an export's answer stays beside the button. */
private const val EXPORT_STATUS_MILLIS = 4_000L

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
 * Which host container a Wear widget is framed in, as a menu of the shapes the platform ships.
 *
 * Offered only on a widget design, and that is not a cosmetic gate: on anything else the choice
 * would change nothing, and a control that does nothing is worse than no control.
 *
 * The shape is the **host's**, not the design's — the launcher draws the frame from
 * `WearWidgetParams`, and the same `WearWidgetDocument` appears inside each one. So this switches a
 * view rather than editing anything: no revision, no operation, nothing in the export. What it buys
 * a designer is the answer to "does my widget survive the other frame", which for the rectangular
 * container is a real question — its content box and padding both differ from the squircle's, so a
 * layout that just fits in one can clip in the other.
 *
 * A menu rather than a segmented pair, matching [RenderSurfaceMenu] beside it: each position wants
 * a sentence, and there is room for a third shape here if the round container's per-diameter
 * footprint is ever worth drawing.
 */
@Composable
private fun WidgetHostShapeMenu(
  shape: WearWidgetHostShape,
  size: WearWidgetScaffoldSize,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    TextButton(
      onClick = { open = true },
      modifier = Modifier.semantics { contentDescription = "Host container (${shape.label})" },
    ) {
      Icon(Icons.Filled.Dashboard, contentDescription = null, modifier = Modifier.size(18.dp))
      Text(shape.label, Modifier.padding(start = 6.dp))
      Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      WearWidgetHostShape.entries.forEach { option ->
        val spec = size.hostSpec(option)
        DropdownMenuItem(
          text = {
            Column {
              Text(option.label)
              // The footprint, because that is what the choice actually changes and a designer
              // comparing two frames wants the numbers rather than an adjective.
              Text(
                "${spec.frameWidthDp}×${spec.frameHeightDp}dp frame · " +
                  "${spec.contentWidthDp}×${spec.contentHeightDp}dp content",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          },
          onClick = {
            dispatch(UiBuilderEditorEvent.ShowWearWidgetHostShape(option))
            open = false
          },
          leadingIcon = {
            if (option == shape) {
              Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp))
            }
          },
        )
      }
    }
  }
}

/**
 * The widget container this design's root is, or null when it is not a widget design at all.
 *
 * Read from the root rather than from the catalog, because the frame follows the scaffold the
 * design was created with and nothing else can change it.
 */
internal fun UiBuilderDocument.wearWidgetScaffoldSize(): WearWidgetScaffoldSize? {
  val rootId = roots.singleOrNull() ?: return null
  val componentId = nodes[rootId]?.componentId ?: return null
  return WearWidgetScaffoldSize.entries.firstOrNull { it.componentId == componentId }
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
      modifier = Modifier.semantics { contentDescription = "Workspace panes (${surface.label()})" },
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
  /** Copies a link that opens this design on this layer, or null where nothing is selected. */
  onCopyLink: (() -> Unit)? = null,
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
  }
  // Beside Properties rather than among the clipboard verbs, because both of these are ways of
  // *pointing at* the selected layer while Copy and Cut are ways of moving it. The Export menu's
  // Copy link is the design's address; this one is a layer's, which is the thing somebody pastes
  // when they mean "this button, here".
  if (onCopyLink != null) {
    DropdownMenuItem(
      text = { Text("Copy link") },
      leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
      modifier = Modifier.semantics { contentDescription = "Copy link to this layer" },
      onClick = {
        onDismiss()
        onCopyLink()
      },
    )
  }
  if (onOpenProperties != null || onCopyLink != null) HorizontalDivider()
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

/**
 * Runs one Copy link against the host and hands back the sentence to show.
 *
 * A failure is a sentence too, for the reason the export host gives: the affordance is a button in
 * a menu, and a clipboard the browser refused should be reported where the button was rather than
 * swallowed into a console nobody has open.
 */
private suspend fun copyLinkSentence(
  copy: suspend (DesignUrlSelectors) -> String,
  selectors: DesignUrlSelectors,
): String =
  try {
    copy(selectors)
  } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
    throw cancelled
  } catch (failure: Exception) {
    "Copy link failed: ${failure.message ?: "unknown error"}"
  }

/**
 * What a pinned revision, a stale selector or a just-copied link has to say, over the canvas.
 *
 * One strip rather than three, because all three are the same kind of sentence — something about
 * *this opening of this design* that the canvas cannot show — and a page that grows a new bar per
 * kind of news is a page whose design moves under the reader. The revision line is the only one
 * that persists; the rest expire, which is why they are drawn after it rather than instead of it.
 */
@Composable
private fun EditorUrlBanner(
  revisionPin: DesignRevisionPin?,
  onGoToLatest: (() -> Unit)?,
  openingNotice: String?,
  transientNotice: String?,
) {
  val pinned = revisionPin?.pinned == true
  val message =
    when {
      pinned -> "Showing revision ${revisionPin.requested} · read-only"
      revisionPin != null ->
        "Revision ${revisionPin.requested} is not available — showing the latest design."
      else -> null
    }
  if (message == null && openingNotice == null && transientNotice == null) return
  Surface(
    Modifier.fillMaxWidth(),
    color =
      if (pinned) MaterialTheme.colorScheme.secondaryContainer
      else MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        if (pinned) Icons.Filled.History else Icons.Filled.Link,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
      )
      Text(
        listOfNotNull(message, openingNotice, transientNotice).joinToString("  ·  "),
        Modifier.weight(1f).semantics { contentDescription = "Design link notice" },
        style = MaterialTheme.typography.labelMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      if (revisionPin != null && onGoToLatest != null) {
        TextButton(
          onClick = onGoToLatest,
          modifier = Modifier.semantics { contentDescription = "Go to latest revision" },
        ) {
          Text("Go to latest")
        }
      }
    }
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
    EditorPreviewSurface.Wasm -> "1 pane"
    EditorPreviewSurface.Native -> "2 panes"
    EditorPreviewSurface.Both -> "3 panes"
  }

/**
 * One line under each renderer's name, which is where a catalog's own caveat belongs.
 *
 * "Drawn in this browser" is a complete description on `m3-catalog`, where the canvas draws the
 * same Material 3 the export names. On `wear-m3` it is the least interesting true thing about it,
 * and the interesting one — those are stand-ins for a library no browser can link — is exactly what
 * somebody choosing a renderer needs to read.
 */
internal fun EditorPreviewSurface.supportingText(
  surfaces: UiBuilderPreviewSurfaces = UiBuilderPreviewSurfaces.DEFAULT
): String {
  val wasmDescription =
    if (surfaces.wasm.fidelity.isAuthoritative) "Wasm" else "Wasm stand-in, for authoring"
  return when (this) {
    EditorPreviewSurface.Wasm -> "Visual editor · $wasmDescription"
    EditorPreviewSurface.Native ->
      if (surfaces.native.backend == UiBuilderPreviewSurfaces.BACKEND_ANDROID)
        "Editor · $wasmDescription + static Android preview"
      else "Editor · $wasmDescription + static target preview"
    EditorPreviewSurface.Both -> "Editor · $wasmDescription + static target + interactive preview"
  }
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

/**
 * The component-pack settings: one switch per pack the catalog carries.
 *
 * A dialog rather than a panel because it is a *setting* — a decision about what the palette
 * offers, made once per catalog and remembered by the host — and not a thing to look at while
 * designing. The components of a pack that is off stay in the catalog: switching a pack off after
 * dropping one of its components hides the shelf, not the node.
 */
@Composable
private fun ComponentPacksDialog(
  packs: UiBuilderComponentPacks,
  enabledPacks: Set<String>,
  onToggle: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Component packs") },
    text = { ComponentPacksPanel(packs, enabledPacks, onToggle) },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
  )
}

/** The switch list, separate from the dialog so it can be previewed on its own. */
@Composable
internal fun ComponentPacksPanel(
  packs: UiBuilderComponentPacks,
  enabledPacks: Set<String>,
  onToggle: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier.width(460.dp).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
      "Other catalogs' components, offered on a shelf of their own. A pack component is drawn on " +
        "the canvas as a named placeholder and rendered as itself by the host's native preview, " +
        "which compiles the design against that catalog's bundle.",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
    packs.packs.forEach { pack ->
      val enabled = pack.id in enabledPacks
      Row(
        Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .clickable { onToggle(pack.id) }
          .padding(horizontal = 8.dp, vertical = 6.dp)
          .semantics { contentDescription = "${pack.label} pack" },
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(pack.label, style = MaterialTheme.typography.bodyLarge)
          Text(
            "${pack.componentIds.size} components · ${pack.platform.label}" +
              (pack.nativeCatalog?.let { " · renders against $it" } ?: ""),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
          )
          if (pack.notes.isNotEmpty()) {
            Text(
              pack.notes,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
        Switch(checked = enabled, onCheckedChange = { onToggle(pack.id) })
      }
    }
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
  /** The packs the catalog carries, for the palette's own summary row. */
  packs: UiBuilderComponentPacks = UiBuilderComponentPacks.NONE,
  /** Opens the pack settings, or null where there is nothing to switch. */
  onManagePacks: (() -> Unit)? = null,
  thumbnailOf: (String, EditorCatalogVariant?) -> UiBuilderDocument?,
  layerRows: List<EditorLayerRow>,
  collaborators: List<UiBuilderCollaborator>,
  dropTarget: ParentSlot?,
  onCatalogDrag: (String, EditorCatalogVariant?, Offset?) -> Unit,
  onCatalogDrop: (String, EditorCatalogVariant?, Offset) -> Unit,
  canAddCatalogComponent: (String) -> Boolean,
  /**
   * Why an Add beside would refuse *this component*, or null.
   *
   * Separate from [besideRefusal], which is the document's answer and belongs on the destination
   * line: this one is about the thing being added, so it belongs on that thing's row. Without it a
   * Wear scaffold on a design that already has a board was a disabled Add and no reason anywhere —
   * the row knew why and did not say.
   */
  catalogAddRefusal: (String) -> String? = { null },
  /** Why an Add beside would refuse, or null — see `UiBuilderEditorReducer.besideRefusal`. */
  besideRefusal: String? = null,
  onCatalogAdd: (String, EditorCatalogVariant?) -> Unit,
  remoteComposeSources: List<RemoteComposeSource>,
  pendingRemoteComposeSource: RemoteComposeSource?,
  remoteComposeFailure: String?,
  resolveRemoteComposeThumbnail: (suspend (RemoteComposeSource) -> ImageBitmap?)?,
  onAddRemoteComposeSource: (RemoteComposeSource) -> Unit,
  onRemoteComposeDrag: (RemoteComposeSource, ImageBitmap?, Offset?) -> Unit,
  onRemoteComposeDrop: (RemoteComposeSource, Offset) -> Unit,
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
            packs = packs,
            onManagePacks = onManagePacks,
            thumbnailOf = thumbnailOf,
            dropTarget = dropTarget,
            onCatalogDrag = onCatalogDrag,
            onCatalogDrop = onCatalogDrop,
            canAddCatalogComponent = canAddCatalogComponent,
            catalogAddRefusal = catalogAddRefusal,
            besideRefusal = besideRefusal,
            onCatalogAdd = onCatalogAdd,
            remoteComposeSources = remoteComposeSources,
            pendingRemoteComposeSource = pendingRemoteComposeSource,
            remoteComposeFailure = remoteComposeFailure,
            resolveRemoteComposeThumbnail = resolveRemoteComposeThumbnail,
            onAddRemoteComposeSource = onAddRemoteComposeSource,
            onRemoteComposeDrag = onRemoteComposeDrag,
            onRemoteComposeDrop = onRemoteComposeDrop,
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
  /** The packs the catalog carries, for the palette's own summary row. */
  packs: UiBuilderComponentPacks = UiBuilderComponentPacks.NONE,
  /** Opens the pack settings, or null where there is nothing to switch. */
  onManagePacks: (() -> Unit)? = null,
  /** The document a row's picture draws, from the reducer that would perform the insert. */
  thumbnailOf: (String, EditorCatalogVariant?) -> UiBuilderDocument?,
  dropTarget: ParentSlot?,
  onCatalogDrag: (String, EditorCatalogVariant?, Offset?) -> Unit,
  onCatalogDrop: (String, EditorCatalogVariant?, Offset) -> Unit,
  canAddCatalogComponent: (String) -> Boolean,
  /**
   * Why an Add beside would refuse *this component*, or null.
   *
   * Separate from [besideRefusal], which is the document's answer and belongs on the destination
   * line: this one is about the thing being added, so it belongs on that thing's row. Without it a
   * Wear scaffold on a design that already has a board was a disabled Add and no reason anywhere —
   * the row knew why and did not say.
   */
  catalogAddRefusal: (String) -> String? = { null },
  /** Why an Add beside would refuse, or null — see `UiBuilderEditorReducer.besideRefusal`. */
  besideRefusal: String? = null,
  onCatalogAdd: (String, EditorCatalogVariant?) -> Unit,
  remoteComposeSources: List<RemoteComposeSource>,
  pendingRemoteComposeSource: RemoteComposeSource?,
  remoteComposeFailure: String?,
  resolveRemoteComposeThumbnail: (suspend (RemoteComposeSource) -> ImageBitmap?)?,
  onAddRemoteComposeSource: (RemoteComposeSource) -> Unit,
  onRemoteComposeDrag: (RemoteComposeSource, ImageBitmap?, Offset?) -> Unit,
  onRemoteComposeDrop: (RemoteComposeSource, Offset) -> Unit,
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
    // Where an Add beside would land, in the same voice as the line above it: a board says how many
    // items it already holds, and a design that has to be wrapped says that is what will happen.
    val boardRootId = state.document.boardRootId
    val besideDestination =
      when {
        besideRefusal != null -> null
        boardRootId != null -> {
          val held =
            state.document.nodes[boardRootId]?.slots?.get(UiBuilderBoard.SLOT).orEmpty().size
          "Adds beside $held item(s) on the board"
        }
        state.document.roots.isEmpty() -> "Adds as this design's first item"
        else -> "Adds beside the design, on a new board"
      }
    Text(
      when {
        state.addBeside -> besideDestination ?: besideRefusal.orEmpty()
        dropTarget != null -> "Adds into ${dropTarget.nodeId}.${dropTarget.slot}"
        else -> "Select a layer that can hold a component"
      },
      Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
      color =
        if (if (state.addBeside) besideDestination == null else dropTarget == null)
          MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.primary,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    AddBesideSwitch(state.addBeside) { dispatch(UiBuilderEditorEvent.ToggleAddBeside) }
    if (!packs.isEmpty && onManagePacks != null) {
      PacksSummaryRow(packs, state.enabledPacks, onManagePacks)
    }
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
              thumbnail = thumbnailOf(row.item.componentId, null),
              expanded = row.expanded,
              onDrag = { onCatalogDrag(row.item.componentId, null, it) },
              onDrop = { onCatalogDrop(row.item.componentId, null, it) },
              canAdd = canAddCatalogComponent(row.item.componentId),
              refusal = catalogAddRefusal(row.item.componentId),
              onAdd = { onCatalogAdd(row.item.componentId, null) },
              onToggleVariants = {
                dispatch(UiBuilderEditorEvent.ToggleCatalogComponent(row.item.componentId))
              },
            )
          is EditorCatalogRow.Variant ->
            CatalogVariantRow(
              variant = row.variant,
              thumbnail = thumbnailOf(row.variant.componentId, row.variant),
              componentName = row.componentName,
              onDrag = { onCatalogDrag(row.variant.componentId, row.variant, it) },
              onDrop = { onCatalogDrop(row.variant.componentId, row.variant, it) },
              canAdd = canAddCatalogComponent(row.variant.componentId),
              refusal = catalogAddRefusal(row.variant.componentId),
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
            resolveThumbnail = resolveRemoteComposeThumbnail,
            canDrag = pendingRemoteComposeSource == null,
            // Enabled off the same question the insert will ask, so a row that cannot land is
            // visibly unavailable rather than pressable and then refused.
            canAdd =
              pendingRemoteComposeSource == null &&
                canAddCatalogComponent(REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID),
            onAdd = { onAddRemoteComposeSource(source) },
            onDrag = { thumbnail, position -> onRemoteComposeDrag(source, thumbnail, position) },
            onDrop = { onRemoteComposeDrop(source, it) },
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
  History("History"),
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
    EditorDock.History -> EditorInspectorMode.History
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
    EditorDock.History -> Icons.Filled.History
    EditorDock.Code -> Icons.Filled.Code
  }

private fun NavigatorTab.icon(): ImageVector =
  when (this) {
    NavigatorTab.Insert -> Icons.Filled.Widgets
    NavigatorTab.Layers -> Icons.Filled.AccountTree
  }

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
  /**
   * The read-only panes drawn beside the design, in strip order — see [UiBuilderVariantPane].
   *
   * Exactly one pane on this canvas takes edits, and it is the extent below: no variant carries a
   * selection overlay, a hit-test, a drop target or a comment pin. That is the load-bearing rule of
   * the feature rather than a limitation of it — a design has one document, so an edit made on the
   * tablet pane would be an edit to the same tree the phone pane draws, and offering a coordinate
   * space per pane for one shared outcome is what makes a multi-variant editor confusing
   * ([`UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`](../../../../../../docs/design/UI_BUILDER_CANVAS_FRAMES_VARIANTS.md)).
   */
  variants: List<UiBuilderVariantPane> = emptyList(),
  selectedNodeId: String?,
  onNodeSelected: (String) -> Unit,
  onCanvasMetrics: (Int, Int, Float) -> Unit,
  onCanvasBounds: (Rect) -> Unit,
  dropHovered: Boolean,
  /** The exact slot under the dragged pointer, or null outside a compatible target. */
  dropTarget: ParentSlot? = null,
  /** The same generated document the palette thumbnail draws, carried beside the pointer. */
  dragPreview: UiBuilderDocument? = null,
  /** The published Remote Compose capture carried while its document bytes are still remote. */
  dragPreviewBitmap: ImageBitmap? = null,
  /** Pointer position in the editor root coordinate space. */
  dragPosition: Offset? = null,
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
    // Only a design that outgrows its frame gets the second pane. One that fits would be drawn
    // twice identically, and two identical pictures side by side say nothing the one said.
    val overflowsFrame = expandedHeightDp > sourceHeight + 0.5f
    // Every pane in the strip, gap included, because fit has to frame what is actually drawn.
    val stripWidth =
      (if (overflowsFrame) sourceWidth + CANVAS_PANE_GAP_DP.value else 0f) +
        variants.sumOf { (CANVAS_PANE_GAP_DP.value + it.widthDp).toDouble() }.toFloat()
    val pairWidth = sourceWidth + stripWidth
    // Fit frames the whole row, not the extent alone: zooming to fit a design whose companion or
    // whose tablet variant is off the right edge is not fitting the design. Height is the tallest
    // pane, which is the extent unless a variant's frame is longer than the design is.
    val stripHeight = maxOf(expandedHeightDp, variants.maxOfOrNull { it.heightDp } ?: 0f)
    // A variant's label is laid out *above* its scaled frame at a fixed size, so it does not shrink
    // with the zoom: the room it needs comes off the workspace before the scale is worked out,
    // rather than being scaled along with the frame. Folding it into `stripHeight` instead made the
    // tallest variant overflow a workspace that claimed to be fitting it.
    val labelRoom = if (variants.isEmpty()) 0f else VARIANT_LABEL_ROOM_DP
    val fitScale =
      minOf(
          workspaceWidth.value / pairWidth,
          (workspaceHeight.value - labelRoom).coerceAtLeast(0f) / stripHeight,
        )
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
                  compositingStrategy = CompositingStrategy.Offscreen
                }
                .onGloballyPositioned {
                  frameBounds = it.boundsInRoot()
                  onCanvasBounds(frameBounds)
                }
                .then(
                  if (dropHovered) Modifier.border(1.dp, MaterialTheme.colorScheme.primary)
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
                  // has to be asked in: the frame is offset in the workspace and its own pixels
                  // reach the screen through [drawScale].
                  val point =
                    Offset(
                      frameBounds.left + position.x * drawScale,
                      frameBounds.top + position.y * drawScale,
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
                          ((menuAt?.x ?: 0f) * drawScale).toDp(),
                          ((menuAt?.y ?: 0f) * drawScale).toDp(),
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
                DropTargetOverlay(
                  dropTarget = dropTarget,
                  inspection = inspection,
                  frameBounds = frameBounds,
                  drawScale = drawScale,
                )
                // Over the document and under the collaborators: the reference is being compared
                // against
                // what the document draws, so it goes on top of that; another person's selection is
                // a
                // fact
                // about this session and must not be hidden by a mock.
                ReferenceOverlayCanvas(reference, onMarkDrawn, onPieceMoved)
                RemotePresenceOverlay(collaborators, inspection)
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
          if (overflowsFrame) {
            ConstrainedFramePane(
              document = document,
              widthDp = sourceWidth,
              heightDp = sourceHeight,
              scale = scale,
              densityRatio = densityRatio,
            )
          }
          // Keyed by the pane rather than by its position in the row. Every pane draws the same
          // document id, and `UiBuilderSurface` remembers its bounds and its design state against
          // that id, so an unkeyed loop hands a removed pane's composition — and its scroll
          // positions — to whichever pane slid into its slot.
          variants.forEach { variant ->
            key(variant.id) { VariantPane(pane = variant, scale = scale, hostDensity = density) }
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
    if (dropHovered && dragPosition != null) {
      val ghostModifier =
        Modifier.align(Alignment.TopStart)
          .offset(
            x = with(density) { (dragPosition.x - workspaceBounds.left + 14f).toDp() },
            y = with(density) { (dragPosition.y - workspaceBounds.top + 14f).toDp() },
          )
      when {
        dragPreview != null -> DragPreviewGhost(document = dragPreview, modifier = ghostModifier)
        dragPreviewBitmap != null ->
          DragBitmapPreviewGhost(bitmap = dragPreviewBitmap, modifier = ghostModifier)
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

/** The compatible slot the pointer will insert into, on the geometry the renderer reported. */
@Composable
private fun DropTargetOverlay(
  dropTarget: ParentSlot?,
  inspection: UiBuilderInspectionSnapshot?,
  frameBounds: Rect,
  drawScale: Float,
) {
  val target = dropTarget ?: return
  val slotBounds =
    inspection
      ?.slots
      ?.firstOrNull { it.parentNodeId == target.nodeId && it.slotName == target.slot }
      ?.bounds
  // An empty slot has no child-union box yet. Its parent is the honest visible landing region;
  // once it has children the tighter slot union wins.
  val bounds =
    slotBounds ?: inspection?.nodes?.firstOrNull { it.nodeId == target.nodeId }?.bounds ?: return
  val local =
    UiBuilderPixelBounds(
      x = (bounds.x - frameBounds.left) / drawScale,
      y = (bounds.y - frameBounds.top) / drawScale,
      width = bounds.width / drawScale,
      height = bounds.height / drawScale,
    )
  val color = MaterialTheme.colorScheme.primary
  Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
    drawRect(
      color = color.copy(alpha = 0.16f),
      topLeft = Offset(local.x, local.y),
      size = Size(local.width, local.height),
    )
    drawRect(
      color = color,
      topLeft = Offset(local.x, local.y),
      size = Size(local.width, local.height),
      style = Stroke(width = 4f),
    )
  }
}

/** A translucent live rendering of the component travelling with a catalog drag. */
@Composable
private fun DragPreviewGhost(document: UiBuilderDocument, modifier: Modifier = Modifier) {
  val width = 88.dp
  val height = 66.dp
  val scale = width.value / PREVIEW_FRAME_WIDTH_DP
  Surface(
    modifier.size(width, height).alpha(0.88f),
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHighest,
    border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
    tonalElevation = 6.dp,
  ) {
    Box(Modifier.clipToBounds(), contentAlignment = Alignment.Center) {
      Box(
        Modifier.requiredSize(PREVIEW_FRAME_WIDTH_DP.dp, PREVIEW_FRAME_HEIGHT_DP.dp)
          .graphicsLayer {
            scaleX = scale
            scaleY = scale
          }
          .clearAndSetSemantics {}
      ) {
        UiBuilderSurface(document = document, editorOverlay = false)
      }
    }
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
) {
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
      UiBuilderSurface(
        document = document,
        editorOverlay = false,
        renderSessionId = renderSessionId,
        unrolled = false,
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
private fun VariantPane(pane: UiBuilderVariantPane, scale: Float, hostDensity: Density) {
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
    )
  }
}

/** Room above a variant pane for its label, in canvas dp. */
private const val VARIANT_LABEL_ROOM_DP = 18f

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
    humanizeSourceSlug(group).uppercase(),
    Modifier.fillMaxWidth()
      .background(Color(0xff202126))
      .padding(horizontal = 14.dp, vertical = 5.dp),
    color = MaterialTheme.colorScheme.primary,
    style = MaterialTheme.typography.labelSmall,
    fontWeight = FontWeight.Bold,
  )
}

/**
 * One published Remote Compose document, addable or draggable into a compatible slot.
 *
 * The thumbnail is the grip, like [CatalogRow]. A drop captures the exact slot immediately, then
 * fetches the document bytes; the reducer revalidates that captured slot when the fetch completes.
 */
@Composable
private fun RemoteComposeSourceRow(
  source: RemoteComposeSource,
  resolveThumbnail: (suspend (RemoteComposeSource) -> ImageBitmap?)?,
  canDrag: Boolean,
  canAdd: Boolean,
  onAdd: () -> Unit,
  onDrag: (ImageBitmap?, Offset?) -> Unit,
  onDrop: (Offset) -> Unit,
) {
  var thumbnail by remember(source.id) { mutableStateOf<ImageBitmap?>(null) }
  LaunchedEffect(source.id, resolveThumbnail) {
    thumbnail =
      try {
        resolveThumbnail?.invoke(source)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        null
      }
  }
  Row(
    Modifier.fillMaxWidth().height(44.dp).padding(start = 14.dp, end = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val dragModifier =
      if (canDrag) {
        Modifier.catalogDrag(
            dragKey = source.id,
            onDrag = { onDrag(thumbnail, it) },
            onDrop = onDrop,
          )
          .semantics { contentDescription = "Drag ${source.label}" }
      } else {
        Modifier
      }
    Surface(
      Modifier.size(COMPONENT_THUMBNAIL_SIZE).then(dragModifier),
      shape = RoundedCornerShape(4.dp),
      color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
      if (thumbnail != null) {
        Image(
          bitmap = thumbnail!!,
          contentDescription = null,
          modifier = Modifier.fillMaxSize().padding(2.dp).clearAndSetSemantics {},
          contentScale = ContentScale.Fit,
        )
      } else {
        Icon(
          Icons.Filled.Widgets,
          contentDescription = null,
          modifier = Modifier.padding(8.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Text(
      source.label,
      Modifier.padding(start = 6.dp).weight(1f),
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
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
/**
 * How many packs are on, and the way to the switch — said in the palette, because the palette is
 * where somebody notices a component they expected is not there.
 */
@Composable
private fun PacksSummaryRow(
  packs: UiBuilderComponentPacks,
  enabledPacks: Set<String>,
  onManagePacks: () -> Unit,
) {
  val on = packs.packs.count { it.id in enabledPacks }
  Row(
    Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      when {
        on == 0 ->
          "${packs.packs.size} component ${if (packs.packs.size == 1) "pack" else "packs"} off"
        else -> "$on of ${packs.packs.size} component packs on"
      },
      Modifier.weight(1f),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    TextButton(
      onClick = onManagePacks,
      modifier = Modifier.semantics { contentDescription = "Manage component packs" },
    ) {
      Text("Packs…", style = MaterialTheme.typography.labelMedium)
    }
  }
}

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
  /** The component drawn small — see [CatalogThumbnail]. Null keeps the plain drag handle. */
  thumbnail: UiBuilderDocument?,
  expanded: Boolean,
  onDrag: (Offset?) -> Unit,
  onDrop: (Offset) -> Unit,
  canAdd: Boolean,
  /** Why Add is refused for this component, shown in place of the id — see [CatalogRow]'s KDoc. */
  refusal: String?,
  onAdd: () -> Unit,
  onToggleVariants: () -> Unit,
) {
  Row(
    // Exactly 44 dp unless this row is refused. `heightIn` alone was applied to every row, and in a
    // LazyColumn — which measures with an unbounded maximum — that let every row size to its text
    // instead, moving the whole catalog and leaving `IndentGuide`'s `fillMaxHeight` with no bound
    // to fill. A refused row still has to grow, so it takes its height from its content with
    // `IntrinsicSize.Min`, which is bounded and so keeps the guide drawn.
    Modifier.fillMaxWidth()
      .then(
        if (refusal == null) Modifier.height(44.dp)
        else Modifier.heightIn(min = 44.dp).height(IntrinsicSize.Min)
      )
      .padding(end = 4.dp),
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
    val unexportable = item.exportsToCompose == false
    Box(Modifier.unexportable(unexportable)) {
      CatalogThumbnail(
        document = thumbnail,
        dragKey = item.componentId,
        label = item.displayName,
        size = COMPONENT_THUMBNAIL_SIZE,
        onDrag = onDrag,
        onDrop = onDrop,
      )
    }
    Column(Modifier.padding(start = 6.dp).weight(1f).unexportable(unexportable)) {
      Text(item.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
      // The id is what a row says when there is nothing more pressing to say. A refusal is more
      // pressing: the reader is looking at a disabled Add and asking why, and the id does not
      // answer that. One line for one row, so the answer is never somewhere else.
      Text(
        refusal ?: item.componentId,
        color =
          if (refusal != null) MaterialTheme.colorScheme.error
          else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        // A refusal wraps to whatever it needs: there are two of them, both a sentence long, and
        // clipping one costs exactly the clause that says what to do instead. An id still gets one
        // line, because an id that does not fit is no less identifiable for being cut.
        maxLines = if (refusal != null) Int.MAX_VALUE else 1,
        // Ellipsis only where something is elided. An id has always been clipped here, and
        // switching it to "…" changed every row that carries a long one — a restyle nobody asked
        // for, riding in on a change about refusals.
        overflow = if (refusal != null) TextOverflow.Ellipsis else TextOverflow.Clip,
      )
    }
    if (unexportable) UnexportableBadge(item.displayName)
    if (item.variants.isNotEmpty()) {
      Text(
        item.variants.size.toString(),
        Modifier.padding(end = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    CatalogAddButton(canAdd, onAdd, item.displayName, refusal)
  }
}

/**
 * The insert panel's mode switch: does an Add fill a slot, or start an item beside the design?
 *
 * A row under the destination line because that is the line it changes — the switch and the
 * sentence saying where the next Add lands read as one statement, and a control that changes what a
 * button does belongs beside the description of what the button does rather than in a menu.
 *
 * Off is the behaviour every design has had. On, an Add appends a top-level item, wrapping the
 * design in a board first if it is not already on one
 * ([`UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`](../../../../../../docs/design/UI_BUILDER_CANVAS_FRAMES_VARIANTS.md)).
 */
@Composable
private fun AddBesideSwitch(checked: Boolean, onToggle: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text("Add beside", style = MaterialTheme.typography.labelMedium)
      Text(
        "Place items side by side instead of inside the selection",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Switch(
      checked = checked,
      onCheckedChange = { onToggle() },
      modifier =
        Modifier.semantics {
          contentDescription =
            if (checked) "Add into the selected layer instead" else "Add beside the design instead"
        },
    )
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
  /** The variant drawn small — see [CatalogThumbnail]. Null keeps the plain drag handle. */
  thumbnail: UiBuilderDocument?,
  /** The component this variant is of, for the names a screen reader and a script use. */
  componentName: String,
  onDrag: (Offset?) -> Unit,
  onDrop: (Offset) -> Unit,
  canAdd: Boolean,
  /**
   * Why Add is refused for this variant, or null.
   *
   * Carried to the button's label and not drawn: a variant's refusal is its component's, and the
   * component's own row is directly above with the sentence already on it. Repeating it once per
   * variant would say the same thing four times under one heading.
   */
  refusal: String?,
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
    // Dimmed with its component and no badge of its own: the row above already carries the word,
    // and a variant is a detail of that row rather than a second component.
    val unexportable = variant.exportsToCompose == false
    Box(Modifier.unexportable(unexportable)) {
      CatalogThumbnail(
        document = thumbnail,
        dragKey = "${variant.componentId}#${variant.value}",
        label = qualified,
        // Smaller than a component's, because a variant is a detail of the row above it and a
        // column of equal-sized pictures loses the hierarchy the indent just established.
        size = VARIANT_THUMBNAIL_SIZE,
        onDrag = onDrag,
        onDrop = onDrop,
      )
    }
    Row(
      Modifier.padding(start = 6.dp).weight(1f).unexportable(unexportable),
      verticalAlignment = Alignment.CenterVertically,
    ) {
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
    CatalogAddButton(canAdd, onAdd, qualified, refusal)
  }
}

/**
 * How far a row the Compose export cannot write fades.
 *
 * Faded rather than disabled, because the row is not broken: the canvas draws the component, the
 * PNG and SVG exports carry it, and only the Kotlin is missing. Far enough to read as greyed at a
 * glance next to a covered row, not so far that the name and the picture stop being legible — the
 * row still has to be findable by someone who wants the thing on the canvas.
 */
private const val UNEXPORTABLE_ALPHA = 0.45f

private fun Modifier.unexportable(unexportable: Boolean): Modifier =
  if (unexportable) alpha(UNEXPORTABLE_ALPHA) else this

/**
 * The mark on a row the Compose export cannot write, beside the Add it does not take away.
 *
 * An icon rather than a word, and the reason is the panel's width. It is 280 dp at its narrowest
 * and the name column gives way to whatever sits here: a two-word label turned "Supporting pane"
 * into "Supporting" and "Search input field" into "Search" — the row saying less about what the
 * component is in order to say what it cannot do. Code, crossed out, is the whole message in 16 dp;
 * the fade on the rest of the row is what makes it read at a glance, and the description carries
 * the sentence for the reader who cannot see either. "Compose export" rather than "export": the PNG
 * and SVG exports do carry these, and the toolbar's Export offers all three.
 */
@Composable
private fun UnexportableBadge(componentName: String) {
  Icon(
    Icons.Filled.CodeOff,
    contentDescription =
      "$componentName renders on the canvas, but the Compose export cannot write it yet",
    modifier = Modifier.padding(end = 6.dp).size(16.dp),
    tint = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/**
 * The one verb every palette row carries, sized so two levels of indent still leave room for it.
 */
@Composable
private fun CatalogAddButton(
  canAdd: Boolean,
  onAdd: () -> Unit,
  label: String,
  refusal: String? = null,
) {
  TextButton(
    onClick = onAdd,
    enabled = canAdd,
    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
  ) {
    // A disabled button is not reachable by touch exploration in every reader, so the reason rides
    // on the label rather than only on the row's own text.
    Text(
      "Add",
      Modifier.semantics {
        contentDescription = if (refusal == null) "Add $label" else "Add $label — $refusal"
      },
    )
  }
}

/**
 * A palette row's picture: the component itself, inserted into an empty frame and shrunk.
 *
 * **Rendered, not baked.** The catalog's tree shows a prebaked PNG per row because that page has
 * the pixels on disk; the builder has something better — the renderer that is about to draw the
 * thing for real. So the row draws [UiBuilderEditorReducer.previewDocument], which is the result of
 * the same `InsertComponent` the row's Add dispatches. A thumbnail therefore cannot disagree with
 * what pressing Add does, no generator task has to be re-run when a default changes, no PNGs are
 * committed, and a catalog nobody has baked artwork for still gets pictures.
 *
 * Drawn at [PREVIEW_FRAME_WIDTH_DP] and scaled down rather than laid out at 44 dp, which is the
 * difference between a shrunken component and a component squeezed until its text wraps to nothing.
 * `graphicsLayer` rather than `scale`, so the shrink is a draw-time transform over a subtree that
 * laid itself out at a sensible size.
 *
 * It is also the **grip**: you drag the picture of the thing you are placing, which is both more
 * obvious than a dot-grid handle and how the row affords two things in the width of one.
 */
@Composable
private fun CatalogThumbnail(
  document: UiBuilderDocument?,
  dragKey: String,
  label: String,
  size: DpSize,
  onDrag: (Offset?) -> Unit,
  onDrop: (Offset) -> Unit,
) {
  // A component the frame could not hold keeps the handle it always had. A picture that could not
  // be drawn is better absent than faked.
  if (document == null) {
    CatalogDragHandle(dragKey, label, onDrag, onDrop)
    return
  }
  val scale = size.width.value / PREVIEW_FRAME_WIDTH_DP
  Box(
    Modifier.size(size)
      .clip(RoundedCornerShape(4.dp))
      .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      Modifier.requiredSize(PREVIEW_FRAME_WIDTH_DP.dp, PREVIEW_FRAME_HEIGHT_DP.dp)
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
        }
        // A picture of a Switch is not a Switch. Without this the row would publish every semantics
        // node inside the thumbnail — so a screen reader would read a palette row as a switch it
        // could toggle, and `getByRole("button", …)` would match forty pictures of buttons that are
        // not on the canvas. The row's own name is set on the overlay below.
        .clearAndSetSemantics {}
    ) {
      UiBuilderSurface(document = document, editorOverlay = false)
    }
    // The gesture sits ON TOP of the picture rather than under it. A Switch drawn in a thumbnail is
    // a real Switch and would eat the press that was meant to start a drag; a later sibling wins
    // the hit test, so the whole tile drags however interactive the thing inside it happens to be.
    Box(
      Modifier.matchParentSize().catalogDrag(dragKey, onDrag, onDrop).semantics {
        contentDescription = "Drag $label"
      }
    )
  }
}

/**
 * The grip a palette row is dragged onto the canvas by, where it has no picture to drag instead.
 */
@Composable
private fun CatalogDragHandle(
  dragKey: String,
  label: String,
  onDrag: (Offset?) -> Unit,
  onDrop: (Offset) -> Unit,
) {
  Icon(
    Icons.Filled.DragIndicator,
    contentDescription = "Drag $label",
    modifier = Modifier.size(18.dp).catalogDrag(dragKey, onDrag, onDrop),
    tint = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/**
 * The drag a palette row starts: report where the pointer is, and on release either drop there or
 * withdraw.
 *
 * A modifier rather than a composable, because two different things carry this gesture — the
 * thumbnail and the fallback handle — and two copies of nine lines of drag bookkeeping is two
 * chances for a drop threshold to drift apart.
 *
 * The origin is captured from layout rather than taken from the drag's own coordinates: the events
 * arrive local to this element, and the canvas needs them in the root's space to hit-test a slot.
 */
private fun Modifier.catalogDrag(
  dragKey: String,
  onDrag: (Offset?) -> Unit,
  onDrop: (Offset) -> Unit,
): Modifier = composed {
  var dragDistance by remember { mutableFloatStateOf(0f) }
  var dragOrigin by remember { mutableStateOf(Offset.Zero) }
  var lastPosition by remember { mutableStateOf(Offset.Zero) }
  val currentOnDrag = rememberUpdatedState(onDrag)
  val currentOnDrop = rememberUpdatedState(onDrop)
  Modifier.onGloballyPositioned { dragOrigin = it.boundsInRoot().topLeft }
    .pointerInput(dragKey) {
      detectDragGestures(
        onDragStart = {
          dragDistance = 0f
          lastPosition = dragOrigin + it
          currentOnDrag.value(lastPosition)
        },
        onDragEnd = {
          // Below the threshold it was a press, not a drag, so the insert is withdrawn rather
          // than landed wherever the pointer happened to rest.
          if (dragDistance > 8f) currentOnDrop.value(lastPosition) else currentOnDrag.value(null)
          dragDistance = 0f
        },
        onDragCancel = {
          dragDistance = 0f
          currentOnDrag.value(null)
        },
        onDrag = { change, amount ->
          change.consume()
          dragDistance += amount.getDistance()
          lastPosition = dragOrigin + change.position
          currentOnDrag.value(lastPosition)
        },
      )
    }
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
  operationHistory: List<EditorOperationEntry>,
  themeSettings: EditorThemeSettings,
  devicePresets: List<UiBuilderDevicePreset>,
  /**
   * Whether the builder's own canvas — the surface the variant strip is drawn on — is on screen.
   */
  variantsDrawn: Boolean,
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
  revealThreadId: String?,
  onPostComment: ((DesignCommentDraft) -> Unit)?,
  onResolveCommentThread: ((String, Boolean) -> Unit)?,
  onCopyThreadLink: ((DesignCommentThread) -> Unit)?,
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
            EditorInspectorMode.History -> "History"
          },
        supporting =
          when (state.inspectorMode) {
            EditorInspectorMode.Properties -> node?.id ?: "Nothing selected"
            EditorInspectorMode.Theme -> "Applies to the whole design"
            EditorInspectorMode.Screen -> "Frame, density and reference"
            EditorInspectorMode.Issues -> "What the export would refuse"
            EditorInspectorMode.Comments -> "What people and agents have said"
            EditorInspectorMode.History -> "What has been done, newest first"
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
        operationHistory = operationHistory,
        themeSettings = themeSettings,
        devicePresets = devicePresets,
        variantsDrawn = variantsDrawn,
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
        revealThreadId = revealThreadId,
        onPostComment = onPostComment,
        onResolveCommentThread = onResolveCommentThread,
        onCopyThreadLink = onCopyThreadLink,
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
  operationHistory: List<EditorOperationEntry>,
  themeSettings: EditorThemeSettings,
  devicePresets: List<UiBuilderDevicePreset>,
  /**
   * Whether the builder's own canvas — the surface the variant strip is drawn on — is on screen.
   */
  variantsDrawn: Boolean,
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
  revealThreadId: String?,
  onPostComment: ((DesignCommentDraft) -> Unit)?,
  onResolveCommentThread: ((String, Boolean) -> Unit)?,
  onCopyThreadLink: ((DesignCommentThread) -> Unit)?,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
    if (state.inspectorMode == EditorInspectorMode.Issues) {
      ProblemsInspector(problems, dispatch)
      return@Column
    }
    if (state.inspectorMode == EditorInspectorMode.History) {
      OperationHistoryInspector(operationHistory) { nodeId ->
        dispatch(UiBuilderEditorEvent.SelectNode(nodeId))
      }
      return@Column
    }
    if (state.inspectorMode == EditorInspectorMode.Comments) {
      // Scrolled for the same reason the Screen panel is: a design with a dozen threads on it
      // fills the dock, and a panel that silently clips its last thread is worse than one that
      // scrolls.
      // The scroll state is held here rather than inside the panel because the panel has to be
      // able to move it: a `#thread=` link opens a design and then has to bring one conversation
      // out of a dozen into view.
      val commentScroll = rememberScrollState()
      Column(Modifier.verticalScroll(commentScroll)) {
        CommentsInspector(
          board = comments,
          reference = state.reference,
          selectedNodeId = state.selectedNodeId,
          nodeLabel = { nodeId -> state.document.nodes[nodeId]?.componentId ?: nodeId },
          selectedThreadId = selectedThreadId,
          onSelectThread = onSelectThread,
          revealThreadId = revealThreadId,
          scrollState = commentScroll,
          onPost = onPostComment,
          onResolve = onResolveCommentThread,
          onCopyLink = onCopyThreadLink,
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
          variantAxes = state.variantAxes,
          variantsDrawn = variantsDrawn,
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
  // Two lists, because the panel makes a claim about every row it shows. "What the export gate
  // refuses" is true of a missing required property and false of a component whose library has
  // moved — that design still exports, and always will, because it holds the body it drew. Mixing
  // them told somebody their export would fail when it would not.
  val (blocking, advisories) = problems.partition { it.blocking }
  if (problems.isEmpty()) {
    Text(
      "Nothing is blocking a Compose export of this design.",
      Modifier.padding(top = 16.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    return
  }
  Text(
    if (blocking.isEmpty())
      "Nothing is blocking a Compose export of this design. These are worth knowing about."
    else
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
      itemsIndexed(blocking) { _, problem -> ProblemRow(problem, blocking = true, dispatch) }
      if (advisories.isNotEmpty() && blocking.isNotEmpty()) {
        item {
          Text(
            "Not blocking an export",
            Modifier.padding(top = 4.dp, bottom = 10.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
          )
        }
      }
      itemsIndexed(advisories) { _, problem -> ProblemRow(problem, blocking = false, dispatch) }
    }
  }
}

/**
 * One row, coloured by whether it stops an export.
 *
 * The error colour is the panel's loudest signal and it should mean one thing. An advisory in it
 * reads as a build failure at a glance, which is the misreading the split above exists to prevent.
 */
@Composable
private fun ProblemRow(
  problem: EditorProblem,
  blocking: Boolean,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  Column(
    Modifier.fillMaxWidth().padding(bottom = 12.dp).let { base ->
      problem.nodeId?.let { id -> base.clickable { dispatch(UiBuilderEditorEvent.SelectNode(id)) } }
        ?: base
    }
  ) {
    Text(
      problem.code,
      color =
        if (blocking) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
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

/**
 * What has been done to this design, newest first, and which of it undo would take back.
 *
 * The panel exists for one sentence in the toolbar that was never written: undo takes something
 * back without saying what, and on a design being edited by more than one person the something is
 * very often not what you last did. So the entry undo is aimed at is marked, the entry redo would
 * return is marked, and everybody else's changes sit in the list between them — unmarked, because
 * they are not yours to take back, and named, because they are usually the answer.
 *
 * Read-only on purpose. Walking the history from a row is a different feature with a much harder
 * question behind it — what happens to the changes somebody else made in between — and a panel that
 * only tells the truth about the buttons that already exist is worth having before that is
 * answered.
 */
@Composable
private fun OperationHistoryInspector(
  entries: List<EditorOperationEntry>,
  onSelectNode: (String) -> Unit,
) {
  if (entries.isEmpty()) {
    Text(
      "Nothing has been changed in this session yet.",
      Modifier.padding(top = 16.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    return
  }
  Text(
    "Undo and redo act on the marked entries, which are your own changes.",
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.labelSmall,
  )
  // Selectable for the same reason the issues are: a value somebody is comparing against is a value
  // they want to paste somewhere. A tap still selects the node underneath.
  SelectionContainer {
    LazyColumn(Modifier.fillMaxWidth().padding(top = 10.dp)) {
      items(entries, key = EditorOperationEntry::operationId) { entry ->
        OperationHistoryRow(entry, onSelectNode)
      }
    }
  }
}

@Composable
private fun OperationHistoryRow(entry: EditorOperationEntry, onSelectNode: (String) -> Unit) {
  val marked =
    entry.standing == EditorOperationStanding.NextUndo ||
      entry.standing == EditorOperationStanding.NextRedo
  // Undone entries are drawn back rather than removed: what redo would put back is as much a part
  // of "where am I in this history" as what undo would take away.
  val faded = entry.standing == EditorOperationStanding.Undone
  Column(
    Modifier.fillMaxWidth()
      .padding(bottom = 4.dp)
      .let { base ->
        if (marked)
          base
            .background(
              MaterialTheme.colorScheme.surfaceVariant,
              RoundedCornerShape(6.dp),
            )
            .padding(8.dp)
        else base.padding(vertical = 4.dp)
      }
      .let { base -> entry.nodeId?.let { id -> base.clickable { onSelectNode(id) } } ?: base }
  ) {
    entry.standing.marker()?.let { marker ->
      Text(
        marker,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    Text(
      entry.summary,
      color =
        if (faded) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurface,
      style = MaterialTheme.typography.bodySmall,
    )
    entry.changes.forEach { change ->
      Text(
        change.readable(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    Text(
      listOfNotNull(
          "Revision ${entry.revision}",
          if (entry.mine) "you" else entry.actorId,
          if (faded) "undone" else null,
        )
        .joinToString(" · "),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
  }
}

/**
 * One change as a line: what the value is now, and what it was.
 *
 * No arrow, and that is not a style preference: the browser build has no glyph for one, and the
 * first render of this panel drew a box between every before and after. An absent end is said with
 * a missing half rather than a dash, for the same reason — "text was Hello" says the property is
 * gone, in characters the font is known to have.
 */
internal fun EditorOperationChange.readable(): String =
  when {
    after != null && before != null -> "$label  $after  \u00b7  was $before"
    after != null -> "$label  $after"
    before != null -> "$label  was $before"
    else -> label
  }

/** What the two entries the toolbar is aimed at say about themselves, and nothing for the rest. */
private fun EditorOperationStanding.marker(): String? =
  when (this) {
    EditorOperationStanding.NextUndo -> "Undo takes this back"
    EditorOperationStanding.NextRedo -> "Redo puts this back"
    EditorOperationStanding.Applied,
    EditorOperationStanding.Undone -> null
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
 * A clean, interactive rendition beside the editing canvas.
 *
 * It shares the document but not the editor overlay or renderer session, so controls can be used
 * without changing selection and without their remembered state leaking into the authoring pane.
 * Remote M3 plays through the real CMP/Wasm Remote Compose player here; catalogs whose own
 * capability declaration calls Wasm a stand-in continue to say so in the pane chooser.
 */
@Composable
private fun LiveWasmPreviewPane(
  document: UiBuilderDocument,
  modifier: Modifier = Modifier,
) {
  val widthDp =
    document.environment["widthDp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1280f
  val heightDp =
    document.environment["heightDp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 800f
  val hostDensity = LocalDensity.current
  val densityRatio = document.renderDensity(hostDensity).density / hostDensity.density
  Surface(modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
      Text(
        "Live preview · interactive Wasm target",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
      BoxWithConstraints(
        Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
        contentAlignment = Alignment.Center,
      ) {
        val scale =
          minOf(maxWidth.value / widthDp, maxHeight.value / heightDp).coerceIn(MIN_CANVAS_ZOOM, 1f)
        ConstrainedFramePane(
          document = document,
          widthDp = widthDp,
          heightDp = heightDp,
          scale = scale,
          densityRatio = densityRatio,
          renderSessionId = "live-preview",
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
  /** The unstored axes the strip is drawing — see [UiBuilderEditorState.variantAxes]. */
  variantAxes: Set<EditorVariantAxis>,
  /**
   * Whether the surface that draws the strip is the one on screen.
   *
   * False on the host's renderer, which draws one render of one frame and has no strip to put a
   * variant in. The controls then say so instead of accepting a choice nothing acts on — the
   * devices still reach the export, which is why the picker stays live and only the comparison
   * chips go quiet.
   */
  variantsDrawn: Boolean,
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

  // "Frame" rather than "Screen environment", and the two are not the same claim. What these fields
  // describe is a measuring surface — a width, a density, a theme — and a device is one way to fill
  // it in, not what it is. Presenting the two as one thing was wrong in both directions: a
  // hand-typed 1400 x 1000 frame sat under a heading that claimed a device, and a design of loose
  // assets on a board appeared to be a phone
  // ([`UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`](../../../../../../docs/design/UI_BUILDER_CANVAS_FRAMES_VARIANTS.md)).
  Text("Frame", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
  Text(
    "What the design is measured in. Applies to the complete render, never an individual component.",
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodySmall,
  )
  HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)
  // Said on a board, and nothing is hidden because of it. The frame still applies — the items are
  // laid out down the middle of that width, at that density, under that theme — so removing the
  // width, the density or the presets would take away controls the picture still obeys. What
  // changes is only the claim.
  if (document.isBoard) {
    Text(
      "A board of ${document.boardItemCount} items",
      style = MaterialTheme.typography.labelLarge,
    )
    Text(
      "This design holds several top-level items rather than one screen, so its frame is a canvas " +
        "to lay them out in rather than a device it runs on.",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)
  }
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
    ExportDevicePicker(
      presets = devicePresets,
      selected = current.exportDevices,
      drawn = variantsDrawn,
      onToggle = { id ->
        // The whole set per edit, matching the protocol change and for its reason: a toggle that
        // sent an add or a remove would let two people's ideas of the set drift apart between them.
        val next =
          if (id in current.exportDevices) current.exportDevices - id
          else current.exportDevices + id
        dispatch(UiBuilderEditorEvent.UpdateEnvironment(current.copy(exportDevices = next)))
      },
    )
  }
  VariantAxisPicker(variantAxes, variantsDrawn) {
    dispatch(UiBuilderEditorEvent.ToggleVariantAxis(it))
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
  // "Set frame from" rather than "Device": picking one writes the width, the height and the density
  // and then stops mattering. The design does not become that device, which is why a frame that
  // matches no preset reads as "Custom size" below rather than as the nearest phone.
  Text("Set frame from", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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

/**
 * The devices a design is exported as, beside the one it is drawn at.
 *
 * A multi-select rather than a second single choice, because the answer is genuinely a set: a
 * screen claims to work on a phone *and* a foldable *and* a tablet, and picking them one at a time
 * would make "which does this cover?" a question you answer by remembering. The frame above stays
 * single — it is the canvas somebody approved — and this says where else the export has to hold up.
 *
 * Checked state is the set's membership, so the menu is also the report: open it and the ticks are
 * the answer. Nothing here is the frame device, which is why picking none is a legitimate state and
 * reads as "exports at its own frame alone" rather than as an empty selection nobody finished.
 */
/**
 * The unstored axes the variant strip draws, as chips.
 *
 * Chips rather than another dropdown, and beside the export devices rather than under them, because
 * they are the other half of the same question — what am I looking at this design as? — while being
 * a different kind of answer. The devices above are the design's own claim and travel with it into
 * the export; these three are a way of looking, held in editor state, off again when the design is
 * reopened. Wording says so: "Also shown and exported as" against "Also compare"
 * ([`UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`](../../../../../../docs/design/UI_BUILDER_CANVAS_FRAMES_VARIANTS.md)).
 */
@Composable
private fun VariantAxisPicker(
  selected: Set<EditorVariantAxis>,
  drawn: Boolean,
  onToggle: (EditorVariantAxis) -> Unit,
) {
  Text("Also compare", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
  if (!drawn) {
    Text(
      "The comparison strip is drawn on the builder's own canvas. This design is being previewed " +
        "on the host's renderer, which draws one frame.",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
  }
  Row(
    Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    EditorVariantAxis.entries.forEach { axis ->
      FilterChip(
        selected = axis in selected,
        enabled = drawn,
        onClick = { onToggle(axis) },
        label = { Text(axis.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
      )
    }
  }
}

@Composable
private fun ExportDevicePicker(
  presets: List<UiBuilderDevicePreset>,
  selected: List<String>,
  /** Whether the strip that draws these devices is on screen — see the heading below. */
  drawn: Boolean,
  onToggle: (String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  // The list says what it does in both directions: it is still the set the export writes as
  // `@Preview(device = …)`, and it is also the set the workspace draws beside the design. Before
  // the
  // variant strip existed a design could claim three devices and show its author one, and the two
  // decisions were made in different places with neither showing the other.
  //
  // Which is exactly why the heading drops "shown" where the strip is not drawn. The picker stays
  // live — these devices still reach the export, and that is worth choosing on any surface — but a
  // heading promising a picture the host's renderer never draws is the same disagreement in the
  // other direction.
  // An id this host has no preset for is exported but never drawn: the strip skips it rather than
  // inventing a frame for it (see `variantPanes`), and a preset carries the only geometry there is.
  // So the heading must not count it among the shown — a design that arrived from MCP naming a
  // device this deployment does not offer would otherwise tell its author every exported target had
  // been looked at.
  val undrawable = selected.count { id -> presets.none { it.id == id } }
  Text(
    if (drawn && undrawable == 0) "Also shown and exported as" else "Also exported as",
    style = MaterialTheme.typography.labelMedium,
    fontWeight = FontWeight.Bold,
  )
  if (drawn && undrawable > 0) {
    Text(
      "$undrawable of these is not a device this host can draw, so it is exported without a pane.",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
  }
  Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp)) {
    Button(
      onClick = { expanded = true },
      modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Export devices" },
    ) {
      Text(
        // Naming the devices while there are few enough to read beats a count: "Pixel 6, Pixel
        // Fold" is the answer, where "2 devices" is a prompt to go and look.
        when {
          selected.isEmpty() -> "This frame only"
          selected.size <= 2 ->
            selected.joinToString(", ") { id -> presets.firstOrNull { it.id == id }?.label ?: id }
          else -> "${selected.size} devices"
        },
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
          val checked = preset.id in selected
          DropdownMenuItem(
            text = {
              Column {
                Text(
                  preset.label,
                  fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                  preset.summary,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  style = MaterialTheme.typography.bodySmall,
                )
              }
            },
            leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
            // The menu stays open: picking a set one item at a time through a menu that closes
            // after each is the interaction this control exists to avoid.
            onClick = { onToggle(preset.id) },
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

/**
 * One string property's literal, or empty.
 *
 * A third copy of a two-line read, and deliberately not a shared one: `UiBuilderRenderer` and
 * `UiBuilderEditorState` each keep their own because the alternative — an internal helper on the
 * node type — is a vocabulary every caller in this module then reaches for, and a property is not
 * always a literal. This one is used only where the answer being empty is itself the signal: a
 * Lottie element that has a URL and no animation yet.
 */
private fun UiBuilderNode.propertyText(name: String): String =
  (properties[name] as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull.orEmpty()
