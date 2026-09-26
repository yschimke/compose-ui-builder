@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.uibuilder.ComponentDriftFinding
import ee.schimke.composeai.uibuilder.DesignRevisionPin
import ee.schimke.composeai.uibuilder.DesignUrlSelectors
import ee.schimke.composeai.uibuilder.LOTTIE_COMPONENT_ID
import ee.schimke.composeai.uibuilder.LocalUiBuilderAssetBitmaps
import ee.schimke.composeai.uibuilder.LocalUiBuilderAssetBytes
import ee.schimke.composeai.uibuilder.ParentSlot
import ee.schimke.composeai.uibuilder.REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID
import ee.schimke.composeai.uibuilder.RemoteComposeSource
import ee.schimke.composeai.uibuilder.canvas.LocalRemoteComposeDocuments
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapterMappings
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapters
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogComponentIds
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderFrameGeometry
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderNativeOnly
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderNavigator
import ee.schimke.composeai.uibuilder.canvas.LocalWearWidgetHostShape
import ee.schimke.composeai.uibuilder.canvas.UiBuilderDevicePreset
import ee.schimke.composeai.uibuilder.canvas.decodeRemoteComposeDocument
import ee.schimke.composeai.uibuilder.canvasAdapterIds
import ee.schimke.composeai.uibuilder.canvasAdapterMappings
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.componentDriftProblems
import ee.schimke.composeai.uibuilder.decodeUiBuilderAssetBitmap
import ee.schimke.composeai.uibuilder.designUrlPath
import ee.schimke.composeai.uibuilder.export.NewDesignState
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.export.isWearWidget
import ee.schimke.composeai.uibuilder.frameGeometry
import ee.schimke.composeai.uibuilder.inspector.LocalUiBuilderPageDestinations
import ee.schimke.composeai.uibuilder.inspector.UiBuilderPageDestination
import ee.schimke.composeai.uibuilder.nativeOnlyComponentIds
import ee.schimke.composeai.uibuilder.protocol.BrowserPreviewCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.reference.ReferenceCaptureRequest
import ee.schimke.composeai.uibuilder.reference.ReferenceComponentCapture
import ee.schimke.composeai.uibuilder.reference.ReferenceImage
import ee.schimke.composeai.uibuilder.reference.ReferenceImportOutcome
import ee.schimke.composeai.uibuilder.reference.ReferencePiece
import ee.schimke.composeai.uibuilder.reference.RestoredReference
import ee.schimke.composeai.uibuilder.reference.flattenReference
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionCollector
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import ee.schimke.composeai.uibuilder.uploadedAssets
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
internal val INSPECTOR_WIDTH = 320.dp

/** The left panel's width, named for the same reason [INSPECTOR_WIDTH] is. */
internal val NAVIGATOR_WIDTH = 280.dp

/**
 * How big a palette row's picture is.
 *
 * 4:3, matching the frame the component is drawn in, so the shrink is uniform and nothing is
 * squashed. Wide enough that a Card reads as a card rather than as a grey rectangle, and small
 * enough that a 280 dp panel still has room for a name, an id, a count and an Add.
 */
internal val COMPONENT_THUMBNAIL_SIZE = DpSize(44.dp, 33.dp)

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

internal val EditorColors =
  darkColorScheme(
    background = Color(0xff121316),
    surface = Color(0xff1b1c20),
    surfaceVariant = Color(0xff282a30),
    primary = Color(0xffb9c3ff),
    onPrimary = Color(0xff17215b),
    outline = Color(0xff454750),
  )

internal enum class MobileEditorPanel {
  None,
  Components,
  Layers,
  Properties,
  Code,
}

@Composable
fun UiBuilderEditor(
  document: UiBuilderDocument,
  catalog: CapabilityCatalog,
  /** Host chrome. The browser keeps Material; an IDE host may supply native controls. */
  chrome: UiBuilderChrome = MaterialUiBuilderChrome,
  /**
   * The component record the host serving [catalog] generates its exports from, where it has one.
   *
   * See `UiBuilderEditorReducer`'s own parameter. Null — the default, and every caller but the live
   * browser session — leaves the code pane reading the record embedded at build time, which is what
   * it always read.
   */
  catalogRecord: ComponentRecordFile? = null,
  pageDestinations: List<UiBuilderPageDestination> = emptyList(),
  onNavigatePage: (String) -> Unit = {},
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
   * The components the reader has pinned to the top of the insert panel, or null while they have
   * never said — in which case the catalog's own declaration answers. See
   * [UiBuilderEditorState.pinnedComponents].
   */
  initialPinnedComponents: Set<String>? = null,
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
  /** Exports the current design for a catalog-declared document player in Browser Preview. */
  onRequestDocumentPreview: (suspend (UiBuilderDocument) -> UiBuilderDocumentPreview)? = null,
  /**
   * Opens the live session a native render named, or null where this host cannot stream one.
   *
   * The seam is the host's because reaching a session is the host's business — which origin, which
   * socket, which token — and `:ui-builder` is common Compose with no `WebSocket` in it, the same
   * rule the comment host and the protocol transport already follow. Absent, the native pane draws
   * the still it already drew; present, it draws the stream and taps reach the composition.
   */
  onOpenNativeStream: ((UiBuilderNativeLive) -> UiBuilderNativeStream)? = null,
  /** A render already in hand, for the previews that draw this pane without a host. */
  initialNativeRender: UiBuilderNativeRender? = null,
  /**
   * Which design panes the workspace opens with.
   *
   * The authoring canvas alone, which is what somebody who opened a design editor asked for. A host
   * that wants another pane in the picture — a preview that exists to diff one — names it here; a
   * catalog whose canvas is only a stand-in has the native pane added for it below.
   */
  initialPanes: Set<EditorPane> = setOf(EditorPane.Editor),
  /**
   * The panes this host puts inside this Compose workspace.
   *
   * IntelliJ keeps the visual editor in an editor tab and puts Preview / Native in a tool window,
   * so each surface names only the panes it owns. Browser and Desktop hosts keep all three.
   */
  availablePanes: Set<EditorPane> = EditorPane.entries.toSet(),
  /** Whether an editor-only initial request gains its usual free browser Preview beside it. */
  openDefaultPreview: Boolean = true,
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
   * A selection made outside the editor — a host's own layer tree beside this canvas — to apply as
   * though the layer had been picked here. A new [EditorSelectionRequest] is a new request, so
   * choosing the same layer twice selects it twice. Null everywhere the editor owns every way in.
   */
  selectionRequest: EditorSelectionRequest? = null,
  /**
   * Draws the editor's toolbar and rails in the host's own chrome instead of in the page. See
   * [UiBuilderHostChrome]. Null — everywhere but an IDE webview — keeps the editor's own.
   */
  hostChrome: UiBuilderHostChrome? = null,
  /**
   * The colours of the editor's own UI. [UiBuilderEditorTheme.Default] everywhere but a host that
   * themes it to match its own panels.
   */
  theme: UiBuilderEditorTheme = UiBuilderEditorTheme.Default,
  /**
   * Copies an OpenCode-ready prompt for working on this live design through MCP.
   *
   * Null where this editor has no live server or clipboard. The host owns the prompt because it
   * knows its origin; the editor only makes the workflow discoverable.
   */
  onCopyAiPrompt: (suspend () -> String)? = null,
  /**
   * Leaves the editor for the host's index of every design this account may open, or null where the
   * host has no such page.
   *
   * On the toolbar rather than behind the browser's Back button, because Back is not where a design
   * goes: the editor is normally arrived at from a link, so "the rest of my designs" was a URL you
   * had to already know. It sits beside **New design** — the other way out of this design and into
   * another one.
   */
  onBrowseDesigns: (() -> Unit)? = null,
  /**
   * Starts a new design of the reader's own from this one, as it is now, and opens it — or null
   * where the host cannot. The way somebody who may only look at a design (a public one, or one
   * shared read-only) takes it somewhere they can change it, and a quick branch for anyone else.
   */
  onForkDesign: (() -> Unit)? = null,
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
  /**
   * Draws the editable design through its pinned catalog runtime.
   *
   * Null keeps the in-process renderer for JVM previews and tests. The browser host supplies an
   * isolated renderer; all editor overlays remain siblings in [PinnedDesignCanvas].
   */
  canvasRenderer: UiBuilderCanvasRenderer? = null,
) {
  require(availablePanes.isNotEmpty()) { "a UI Builder workspace must expose at least one pane" }
  val reducer =
    remember(catalog, catalogRecord, actorId, clientId, operationIdPrefix) {
      UiBuilderEditorReducer(catalog, actorId, clientId, operationIdPrefix, catalogRecord)
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
            codePaneVisible = initialCodePaneVisible,
            historyBarVisible = initialHistoryBarVisible,
            enabledPacks =
              initialEnabledPacks.filterTo(mutableSetOf()) { catalog.componentPacks[it] != null },
            pinnedComponents = initialPinnedComponents,
            // Opening a design always gives its editable canvas a free browser preview beside it.
            // Native compiles a whole design through the host and is therefore explicit-only — a
            // document opening must not spend a render merely because its catalog's browser canvas
            // is a stand-in. Widgets use the same policy; their preview additionally fans out over
            // the launcher host shapes. An explicit [initialPanes] from the host still wins.
            panes =
              (if (
                  openDefaultPreview &&
                    initialPanes == setOf(EditorPane.Editor) &&
                    EditorPane.Preview in availablePanes
                ) {
                  setOf(EditorPane.Editor, EditorPane.Preview)
                } else initialPanes)
                .intersect(availablePanes)
                .ifEmpty { setOf(availablePanes.first()) },
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
  // The node a canvas move has picked up, and the root-space point it is being carried at — the
  // same point the palette drag reports, so one plan resolver and one marker serve both drags.
  var draggedNodeId by remember { mutableStateOf<String?>(null) }
  var draggedRemoteThumbnail by remember { mutableStateOf<ImageBitmap?>(null) }
  var canvasBounds by remember { mutableStateOf(Rect.Zero) }
  // The canvas pane's own rectangle — the workspace, not the design. It is what tells an
  // empty-ground drop apart from a drop on another pane: the pointer over the pane but not over
  // the design is the beside gesture's ground.
  var canvasWorkspaceBounds by remember { mutableStateOf(Rect.Zero) }
  // The scale the design is pinned at, or null while it is framed to the workspace. Local rather
  // than in [UiBuilderEditorState] for the same reason the open panels are: how far somebody has
  // zoomed in is a fact about their window, not about the design, and an authoritative snapshot
  // that reset it would be worse than one that remembers nothing.
  var canvasZoom by remember(document.id) { mutableStateOf(initialCanvasZoom) }
  // Which control the hover editor should put the caret in, set by an action that just created the
  // value being edited and cleared the moment it lands.
  var hoverFocusTarget by remember(document.id) { mutableStateOf<String?>(null) }
  // The node whose text is being typed over on the canvas, and what it said when that started.
  var inlineTextEdit by remember(document.id) { mutableStateOf<CanvasInlineTextEdit?>(null) }
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
  // The canvas's current scroll offset, reported by the canvas so a follow-up can reconcile the
  // drag hit-test with the scroll — see the auto-scroll: the inspection's boxes and the drawn
  // pointer do not currently agree once the workspace has scrolled, which predates this change.
  var canvasScroll by remember(document.id) { mutableStateOf(Offset.Zero) }

  fun canvasDropPlan(componentId: String, position: Offset): UiBuilderDropPlan? {
    if (!canvasBounds.contains(position)) return null
    return canvasInspection?.let { snapshot ->
      reducer.catalogDropPlan(
        state,
        componentId,
        snapshot.slots,
        snapshot.nodes.mapNotNull { node -> node.bounds?.let { node.nodeId to it } }.toMap(),
        position.x,
        position.y,
      )
    }
  }

  fun canvasMovePlan(nodeId: String, position: Offset): UiBuilderDropPlan? {
    if (!canvasBounds.contains(position)) return null
    return canvasInspection?.let { snapshot ->
      reducer.canvasMovePlan(
        state,
        nodeId,
        snapshot.slots,
        snapshot.nodes.mapNotNull { node -> node.bounds?.let { node.nodeId to it } }.toMap(),
        position.x,
        position.y,
      )
    }
  }

  // Resolved on every move of either drag: the plan is what the marker is drawn from, what the
  // release is landed with, and what the status bar narrates — one answer, asked once.
  val draggedCatalogPlan = draggedComponentId?.let { componentId ->
    catalogDragPosition?.let { position -> canvasDropPlan(componentId, position) }
  }
  val draggedMovePlan = draggedNodeId?.let { nodeId ->
    catalogDragPosition?.let { position -> canvasMovePlan(nodeId, position) }
  }
  val draggedPlan = draggedCatalogPlan ?: draggedMovePlan
  val canvasDropHovered = draggedPlan != null
  // The empty recommended slots, from the same inspection the drop plan reads — one region per
  // slot, computed by the reducer, so the hint drawn and the target hit cannot disagree.
  val slotPlaceholders =
    remember(state.document, canvasInspection) {
      canvasInspection?.let { snapshot ->
        reducer.slotPlaceholders(
          state,
          snapshot.slots,
          snapshot.nodes.mapNotNull { node -> node.bounds?.let { node.nodeId to it } }.toMap(),
        )
      } ?: emptyList()
    }
  // Over the workspace but not over the design: the beside ground. The status bar names what a
  // release there would do — the panel's own add-beside, or the reason it would refuse — because
  // a gesture that acts on release must say so while the pointer is still down.
  val draggingOverBesideGround =
    draggedComponentId != null &&
      draggedPlan == null &&
      catalogDragPosition?.let { position ->
        canvasWorkspaceBounds.contains(position) && !canvasBounds.contains(position)
      } == true
  val dropTargetLabel =
    when {
      draggedPlan != null -> dropPlanLabel(draggedPlan)
      draggingOverBesideGround ->
        reducer.besideRefusal(state, draggedComponentId ?: "")?.let { "Won't land here: $it" }
          ?: "Release to add beside the design"
      else -> "No compatible slot"
    }
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
  fun openProperties() {
    if (state.codePaneVisible) dispatch(UiBuilderEditorEvent.ToggleCodePane)
    dispatch(UiBuilderEditorEvent.ShowInspector(EditorInspectorMode.Properties))
    inspectorOpen = true
    mobilePanel = MobileEditorPanel.Properties
  }

  /**
   * Selection is the beginning of editing, not a separate mode an author has to discover.
   *
   * The canvas deliberately starts uncluttered, but leaving its Properties dock closed after an
   * author chooses a component turns the most common edit into a second hunt through the rail. The
   * same action is used for a layer-tree selection, so the two ways of choosing a component stay in
   * agreement.
   */
  fun selectNodeForEditing(nodeId: String) {
    focusEditor()
    dispatch(UiBuilderEditorEvent.SelectNode(nodeId))
    openProperties()
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
  LaunchedEffect(selectionRequest) {
    val request = selectionRequest ?: return@LaunchedEffect
    // A layer the document no longer has — the host's tree lagging an edit — selects nothing.
    if (request.nodeId in state.document.nodes) selectNodeForEditing(request.nodeId)
  }
  LaunchedEffect(Unit) { editorFocusRequester.requestFocus() }
  LaunchedEffect(canvasDropHovered, draggedPlan, draggingOverBesideGround, dropTargetLabel) {
    onDropTargetChanged(canvasDropHovered || draggingOverBesideGround, dropTargetLabel)
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
   * What the insert panel calls the place an Add would land.
   *
   * The panel used to answer with an id — "Adds into toolbar-discover-row.children" — which is the
   * document's name for the place rather than the reader's. The layers panel and the breadcrumbs
   * both call that node "Row"; this says the same thing and then the slot, so the three places that
   * name a destination agree.
   */
  fun insertDestinationLabel(target: ParentSlot): String {
    val name =
      layerRows
        .filterIsInstance<EditorLayerRow.Node>()
        .firstOrNull { it.nodeId == target.nodeId }
        ?.row
        ?.label ?: state.document.nodes[target.nodeId]?.componentId ?: target.nodeId
    return "$name › ${target.slot}"
  }

  /**
   * The selection's verbs, as menu rows, for whoever opens a menu under the pointer.
   *
   * Built here rather than at each call site because every question it asks — can this be pasted
   * into, is there anything to unwrap — is the reducer's, and the answers change with every edit.
   * The lambda takes the dismiss the menu owns, so the rows can close the menu they are in.
   */
  val selectionMenu: (() -> Unit) -> List<UiBuilderMenuEntry> = { close ->
    editorSelectionMenuEntries(
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
          // The caret is being handed to the quick editor, so it has to be open to take it.
          if (hoverFocusTarget != null) dispatch(UiBuilderEditorEvent.ShowQuickEditor)
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
      onQuickEdit =
        if (state.selection.size == 1) {
          {
            focusEditor()
            dispatch(UiBuilderEditorEvent.ShowQuickEditor)
          }
        } else null,
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
        // The rows the palette lists. Subtracting the hidden set's size miscounted a catalog that
        // does not declare a hidden id, since hiding it removed nothing.
        totalCatalogComponents = reducer.listedComponentCount(state),
        pinnedComponents = reducer.pinnedComponents(state),
        packs = catalog.componentPacks,
        onManagePacks = onComponentPacks,
        thumbnailOf = reducer::previewDocument,
        layerRows = layerRows,
        collaborators = collaborators,
        onOpenProperties = ::openProperties,
        dropTarget = reducer.dropTarget(state, draggedComponentId ?: "m3/text"),
        dropTargetLabel =
          reducer.dropTarget(state, draggedComponentId ?: "m3/text")?.let(::insertDestinationLabel),
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
          // overlay already promotes a piece into the slot under it — this asks the same question,
          // and now the seam as well: the marker the pointer watched is the seam the drop lands at,
          // not an append after the fact.
          canvasDropPlan(componentId, position)?.let { plan ->
            dispatch(
              UiBuilderEditorEvent.InsertComponent(
                componentId,
                plan.target,
                variant,
                plan.afterNodeId,
              )
            )
            if (closeAfterDrop) mobilePanel = MobileEditorPanel.None
          }
            ?: run {
              // Empty ground: the pointer is over the workspace but not over the design. That is a
              // place nothing can be inserted *into*, and the honest answer to a drop there is the
              // panel's own "add beside" — a top-level item on the board — rather than nothing. A
              // design that cannot take one (a Wear screen is exported as itself) says so instead
              // of
              // swallowing the gesture.
              if (canvasWorkspaceBounds.contains(position) && !canvasBounds.contains(position)) {
                val refusal = reducer.besideRefusal(state, componentId)
                if (refusal == null) {
                  dispatch(UiBuilderEditorEvent.InsertComponentBeside(componentId, variant))
                  if (closeAfterDrop) mobilePanel = MobileEditorPanel.None
                } else {
                  say(refusal)
                }
              }
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
          val target = canvasDropPlan(REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID, position)?.target
          if (target != null && pendingRemoteSource == null) {
            pendingRemoteTarget = target
            pendingRemoteSource = source
            if (closeAfterDrop) mobilePanel = MobileEditorPanel.None
          } else if (
            target == null &&
              pendingRemoteSource == null &&
              canvasWorkspaceBounds.contains(position) &&
              !canvasBounds.contains(position) &&
              reducer.besideRefusal(state, REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID) == null
          ) {
            // The same empty-ground answer the catalog drop gives: a played document is exactly
            // the kind of asset a board holds, and the pointer said "not inside anything".
            pendingRemoteTarget = null
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
  // The ghosts the two drags carry, built once per drag rather than per pointer move: a palette
  // drag carries the component itself at its own size in the design's own theme, a canvas move
  // carries the subtree it picked up. The canvas scales and caps them to the slot under the
  // pointer, so what is in the air is what would land. A Remote Compose drag carries no generated
  // ghost at all — its published capture is the picture, and a ghost document without its bytes
  // would render the component's own error diagnostic.
  val dragGhostPreview =
    remember(
      draggedComponentId,
      draggedComponentVariant,
      state.document.id,
      state.document.revision,
      draggedRemoteThumbnail == null,
    ) {
      if (draggedRemoteThumbnail != null) {
        null
      } else {
        draggedComponentId?.let { reducer.dragGhostDocument(state, it, draggedComponentVariant) }
      }
    }
  val moveDragGhostPreview =
    remember(draggedNodeId, state.document.id, state.document.revision) {
      draggedNodeId?.let { reducer.nodeGhostDocument(state, it) }
    }
  // What the ghost names when the component cannot stand alone in a frame — a named chip, never a
  // faked picture of a Tab or a Scaffold.
  val dragGhostLabel =
    draggedComponentId?.let { catalog.componentsById[it]?.displayName ?: it }
      ?: draggedNodeId?.let { state.document.nodes[it]?.componentId }
  // The strip beside the design: the devices it claims, plus whichever unstored axes are switched
  // on. Computed here rather than in the canvas because it is a question about the *design* — its
  // stored `exportDevices` and the editor's own axes — and the canvas draws what it is handed.
  val variantPanes =
    remember(state.document, devicePresets, state.variantAxes) {
      state.document.variantPanes(devicePresets, state.variantAxes)
    }
  // A dedicated Preview view has no authoring canvas beside it, so its first frame is the current
  // design. In the combined workspace that frame would be a duplicate and the pane remains the
  // comparison-only strip it has always been.
  val previewPanes =
    remember(state.document, variantPanes, availablePanes) {
      if (EditorPane.Editor in availablePanes || state.document.wearWidgetScaffoldSize() != null) {
        variantPanes
      } else {
        val settings = state.document.screenEnvironmentSettings()
        listOf(
          UiBuilderVariantPane(
            id = "preview-current",
            label = "Current · ${settings.widthDp}×${settings.heightDp}dp",
            widthDp = settings.widthDp.toFloat(),
            heightDp = settings.heightDp.toFloat(),
            document = state.document,
          )
        ) + variantPanes
      }
    }
  // The read-only pane. The catalog decides whether this is the constrained canvas renderer or an
  // exported artifact played by a browser adapter. No catalog or platform id is interpreted here:
  // the typed capability is the whole switch, and an unknown adapter falls back to the canvas.
  val documentBackedPreview =
    catalog.browserPreview?.takeIf {
      it.renderer == BrowserPreviewCapabilityV1.REMOTE_COMPOSE_DOCUMENT_RENDERER &&
        it.format == ExportFormatV1.RC &&
        onRequestDocumentPreview != null
    }
  val previewPane: @Composable (Modifier) -> Unit = { modifier ->
    if (documentBackedPreview != null) {
      RemoteDocumentDesignPreviewPane(
        document = state.document,
        variants = previewPanes,
        authoritativeGeneration = authoritativeGeneration,
        request = requireNotNull(onRequestDocumentPreview),
        modifier = modifier,
      )
    } else {
      DesignPreviewPane(
        document = state.document,
        variants = previewPanes,
        modifier = modifier,
        deviceRenderer = canvasRenderer,
      )
    }
  }
  val canvas: @Composable (Modifier, Alignment) -> Unit = { modifier, alignment ->
    PinnedDesignCanvas(
      document = state.document,
      selectedNodeId = state.selectedNodeId,
      onNodeSelected = { selectNodeForEditing(it) },
      onCanvasMetrics = { width, height, scale -> onCanvasMetrics(width, height, scale) },
      onCanvasBounds = {
        canvasBounds = it
        onCanvasBoundsChanged(it)
      },
      dropHovered = canvasDropHovered,
      dropPlan = draggedPlan,
      slotPlaceholders = slotPlaceholders,
      // A catalogue drag carries the component as the ghost, drawn at landing size; a canvas move
      // carries the subtree it picked up, built from the same document the canvas is drawing.
      dragPreview = dragGhostPreview,
      dragPreviewBitmap = draggedRemoteThumbnail,
      moveDragPreview = moveDragGhostPreview,
      dragGhostLabel = dragGhostLabel,
      dragPosition = catalogDragPosition,
      moveOrigin =
        draggedNodeId?.let { id ->
          canvasInspection?.nodes?.firstOrNull { node -> node.nodeId == id }?.bounds
        },
      onCanvasScroll = { canvasScroll = it },
      onWorkspaceBounds = { canvasWorkspaceBounds = it },
      showSelectionOverlay = showSelectionOverlay,
      moveDragEnabled = true,
      onNodeDragStarted = { nodeId, position ->
        focusEditor()
        if (nodeId != state.selectedNodeId) dispatch(UiBuilderEditorEvent.SelectNode(nodeId))
        draggedNodeId = nodeId
        catalogDragPosition = position
      },
      onNodeDragged = { position -> catalogDragPosition = position },
      onNodeDragEnded = { position ->
        val nodeId = draggedNodeId
        val plan = nodeId?.let { id -> position?.let { canvasMovePlan(id, it) } }
        if (nodeId != null && plan != null) {
          dispatch(UiBuilderEditorEvent.MoveNodeInto(nodeId, plan.target, plan.afterNodeId))
        }
        draggedNodeId = null
        catalogDragPosition = null
      },
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
      canvasRenderer = canvasRenderer,
      selectionMenu = selectionMenu,
      onHoverEditorDismiss = { dispatch(UiBuilderEditorEvent.HideQuickEditor) },
      // Double-click a label to type over it where it is. Anything without free text of its own
      // keeps what the click already did: select it.
      onNodeDoubleClicked = { nodeId ->
        reducer.inlineText(state, nodeId)?.let { text ->
          if (state.selectedNodeId != nodeId) dispatch(UiBuilderEditorEvent.SelectNode(nodeId))
          dispatch(UiBuilderEditorEvent.HideQuickEditor)
          inlineTextEdit = CanvasInlineTextEdit(nodeId, text)
        }
      },
      inlineTextEdit = inlineTextEdit,
      onInlineTextDone = { text, focusMovedAway ->
        val edit = inlineTextEdit
        inlineTextEdit = null
        if (edit != null && text != null && text != edit.text) {
          dispatch(UiBuilderEditorEvent.CommitProperty(edit.nodeId, "text", text))
        }
        // Back to the editor's keys after Enter or Esc; a click away keeps what it clicked.
        if (!focusMovedAway) focusEditor()
      },
      onTextInputFocusChanged = { textInputFocused = it },
      hoverEditor =
        if (state.selection.size != 1 || !state.quickEditorOpen) null
        else {
          { dragHandle ->
            SelectionHoverEditor(
              label = selectionLabel,
              dragHandle = dragHandle,
              onDismiss = { dispatch(UiBuilderEditorEvent.HideQuickEditor) },
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
              sizing = reducer.sizing(state),
              onResize = { width, height ->
                state.selectedNodeId?.let {
                  dispatch(UiBuilderEditorEvent.ResizeNode(it, width, height))
                }
              },
              focusTarget = hoverFocusTarget,
              onFocusHandled = { hoverFocusTarget = null },
              onCommitProperty = { name, value ->
                state.selectedNodeId?.let {
                  dispatch(UiBuilderEditorEvent.CommitProperty(it, name, value))
                }
              },
              onCommitModifier = { field, value ->
                state.selectedNodeId?.let {
                  dispatch(
                    UiBuilderEditorEvent.SetModifierValue(
                      it,
                      field.type,
                      field.field,
                      value,
                      field.index,
                    )
                  )
                }
              },
              onTextInputFocusChanged = { textInputFocused = it },
            )
          }
        },
      sizing = reducer.sizing(state),
      onResize = { nodeId, width, height ->
        focusEditor()
        dispatch(UiBuilderEditorEvent.ResizeNode(nodeId, width, height))
      },
      zoom = canvasZoom,
      onZoomChanged = {
        focusEditor()
        canvasZoom = it
      },
      // Only while no other pane is showing the frame. The preview pane draws the design at its
      // own frame and at every device it claims; the native pane draws it compiled. Either one is a
      // better answer to "what does someone see on the device?" than a third copy inside the
      // editing surface — which is what this companion is, and why a Wear screen was showing the
      // same round frame three times across two panes.
      frameCompanion = state.panes.none { it == EditorPane.Preview || it == EditorPane.Native },
      contentAlignment = alignment,
      modifier = modifier,
    )
  }
  // Cached against the document, because it is not cheap and depends on nothing else: it walks
  // every node and every property against the catalog, traverses the graph and looks for cycles.
  // Called inline it would run all of that on every recomposition of the inspector — which is
  // every keystroke in a property field and every frame of a drag.
  val assetBitmapsByDigest = remember { mutableStateMapOf<String, ImageBitmap?>() }
  // The encoded bytes too, for catalog runtimes and for the widget export the code pane and the
  // problems panel run: they draw in a sandboxed frame that is sent the
  // document and nothing else, so the picture has to travel inside it (`LocalUiBuilderAssetBytes`).
  val assetBytesByDigest = remember { mutableStateMapOf<String, ByteArray>() }
  val documentProblems =
    remember(reducer, state.document, assetBytesByDigest.size) {
      reducer.problems(state.document) { assetBytesByDigest[it] }
    }
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
    val (pointX, pointY) = state.document.referencePieceCentrePx(piece, state.wearWidgetHostShape)
    return reducer.promotionTarget(
      state = state,
      componentId = componentId,
      slots = canvasInspection?.slots.orEmpty(),
      pointX = pointX,
      pointY = pointY,
    )
  }
  // Cached the same way and for the same reason, and only while the pane is open: generating is a
  // projection plus a full generator run, which nobody should pay for on every recomposition — or
  // at all, with the pane closed.
  var nativeRender by remember(document.id) { mutableStateOf(initialNativeRender) }
  // Whether the native pane can draw anything at all. A host with neither lane still gets the row
  // in the menu, disabled and carrying the reason — a control that vanishes teaches nobody that the
  // pane exists.
  val nativeAvailable = onRequestNativeRender != null
  // Whether the open panes need the host to compile anything. Derived rather than stored: the pane
  // set is the setting, and a second flag that could disagree with it is a bug waiting. Only the
  // native pane ever asks — which is the whole reason [EditorPane.Preview] is a separate choice.
  val nativeRequested = onRequestNativeRender != null && EditorPane.Native in state.panes
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
  // The live session behind the still, where the render named one and this host can open it. Held
  // against the coordinates rather than against the render, so a re-render that lands on the same
  // session — the editor asks again on every revision — keeps the socket and its seat instead of
  // tearing a daemon down and standing an identical one back up.
  val nativeLive = nativeRender?.live?.takeIf { onOpenNativeStream != null }
  var nativeStream by remember(document.id) { mutableStateOf<UiBuilderNativeStream?>(null) }
  DisposableEffect(nativeLive, onOpenNativeStream) {
    val opened = nativeLive?.let { live -> onOpenNativeStream?.invoke(live) }
    nativeStream = opened
    onDispose {
      opened?.close()
      // Only when it is still ours: a second effect may already have replaced it, and closing the
      // live socket on the way out of the old one would take the new one's frames with it.
      if (nativeStream === opened) nativeStream = null
    }
  }
  // The third pane: the design as the target platform draws it, compiled on the host. Document
  // playback belongs only to Browser Preview; silently putting it here would make Native claim an
  // authority it does not have and make opening Preview spend the wrong lane.
  val nativePane: @Composable (Modifier) -> Unit = { paneModifier ->
    NativeRenderPane(
      render = nativeRender,
      pending = nativePending,
      stream = nativeStream,
      backend = catalog.previewSurfaces.native.backend,
      selectedNodeId = state.selectedNodeId,
      onNodeSelected = { selectNodeForEditing(it) },
      modifier = paneModifier,
    )
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
  val uploadedAssets = state.document.uploadedAssets()
  LaunchedEffect(uploadedAssets, resolveDesignAsset) {
    val resolve = resolveDesignAsset ?: return@LaunchedEffect
    uploadedAssets
      .filterNot { (_, asset) -> assetBitmapsByDigest.containsKey(asset.contentDigest) }
      .forEach { (assetKey, asset) ->
        val bytes =
          try {
            resolve(assetKey)
          } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
          } catch (_: Throwable) {
            null
          }
        if (bytes != null) assetBytesByDigest[asset.contentDigest] = bytes
        assetBitmapsByDigest[asset.contentDigest] = bytes?.let {
          try {
            decodeUiBuilderAssetBitmap(it)
          } catch (_: Throwable) {
            null
          }
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
      remember(reducer, state.document, assetBytesByDigest.size) {
        reducer.generatedCode(state.document) { assetBytesByDigest[it] }
      }
    } else null
  // Named where the document is, not inside the pane: the pane is handed source and has no way to
  // tell which generator wrote it.
  val generatedCodeCaption =
    if (state.document.isWearWidget()) "Wear widget · Remote Compose"
    else if (catalog.platform == UiBuilderCatalogPlatform.REMOTE_COMPOSE) "Remote Compose source"
    else if (catalog.platform == UiBuilderCatalogPlatform.A2UI) "A2UI messages · Compose"
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
  // the compact branch has no preview pane at all, while the wide one draws the variants only when
  // that pane is open. Deciding it up here got the narrow window wrong — the strip was visibly
  // drawn while the inspector said it was not.
  val inspector: @Composable (Modifier, Boolean) -> Unit = { modifier, variantsDrawn ->
    PropertyInspector(
      state = state,
      onClose = { inspectorOpen = false },
      fields = propertyFields,
      modifierFields = reducer.modifierFields(state),
      modifierToggles = reducer.modifierToggles(state),
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
          }
        },
      onTextInputFocusChanged = { textInputFocused = it },
      dispatch = ::dispatch,
      modifier = modifier,
    )
  }

  // Provided once here rather than at each surface: the canvas, every palette thumbnail and the
  // preview frame all draw a pack component, and all of them should draw its placeholder.
  CompositionLocalProvider(
    LocalUiBuilderChrome provides chrome,
    LocalUiBuilderNativeOnly provides catalog.nativeOnlyComponentIds,
    LocalUiBuilderCatalogComponentIds provides catalog.componentsById.keys,
    // From the catalog for the same reason as the two lines above: which adapter draws a component
    // is the catalog's statement, not this build's. Empty for every catalog today.
    LocalUiBuilderCanvasAdapters provides catalog.canvasAdapterIds,
    LocalUiBuilderCanvasAdapterMappings provides catalog.canvasAdapterMappings,
    // And the frame, from the same place and for the same reason: which drawing frames a catalog's
    // screens, and how much room its content gets inside that drawing, is the catalog's statement.
    LocalUiBuilderFrameGeometry provides catalog.frameGeometry,
    // The platform word, so the renderer can answer "is this composition drawn with a watch
    // library" from what the catalog says rather than from a namespace it recognises.
    LocalUiBuilderCatalogPlatform provides catalog.platform.wireValue,
    LocalUiBuilderPageDestinations provides pageDestinations.filter { it.designId != document.id },
    LocalUiBuilderNavigator provides onNavigatePage,
    LocalRemoteComposeDocuments provides { url -> remoteDocumentsByUrl[url] },
    LocalUiBuilderAssetBitmaps provides { digest -> assetBitmapsByDigest[digest] },
    LocalUiBuilderAssetBytes provides { digest -> assetBytesByDigest[digest] },
    // Here for the same reason as the line above it: the canvas, the extent beside it and every
    // variant pane draw the same widget, and all of them should draw the frame being viewed.
    LocalWearWidgetHostShape provides state.wearWidgetHostShape,
  ) {
    // Composed but never shown: it photographs a component and hands back the pixels. Mounted
    // here rather than inside the panel so that a capture survives the inspector switching tabs,
    // and inside the provider rather than beside it so the specimen is drawn with the same
    // catalog ids the canvas is: outside it, a component this canvas has no case for was
    // photographed as the error container while the canvas beside it drew the placeholder.
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

    EditorTheme(theme) {
      BoxWithConstraints(Modifier.fillMaxSize()) {
        // A host drawing the toolbar and rails has taken the width they cost, and its panes are
        // resized by the person rather than by a phone, so it keeps the desktop layout.
        val compact = maxWidth < 840.dp && hostChrome == null
        // A host may put rendered output in its own view (for example IntelliJ's Preview tool
        // window). That surface owns no editor chrome: toolbars, navigator, inspector and status
        // stay with the visual editor instead of being duplicated around a read-only render.
        val dedicatedOutput = EditorPane.Editor !in availablePanes
        Column(
          Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .focusRequester(editorFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
              editorShortcut(
                event,
                enabled = !textInputFocused,
                editing = state.editing,
                dispatch = ::dispatch,
              )
            }
        ) {
          if (!dedicatedOutput && hostChrome != null) {
            HostChromeToolbar(
              chrome = hostChrome,
              state = state,
              canUndo = reducer.canUndo(state),
              canRedo = reducer.canRedo(state),
              onTidy = {
                focusEditor()
                val edits = reducer.tidyPlan(state).changedValues
                if (edits == 0) {
                  say("Every dp value is already on the 4dp grid")
                } else {
                  dispatch(UiBuilderEditorEvent.Tidy)
                  say("Tidied $edits values to the 4dp grid")
                }
              },
              onComponentPacks = onComponentPacks,
              onHelp = onHelp,
              dispatch = ::dispatch,
            )
          } else if (!dedicatedOutput) {
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
                onBrowseDesigns = onBrowseDesigns,
                onForkDesign = onForkDesign,
                onReconnect = onReconnect,
                onHelp = onHelp,
                onCopyAiPrompt = onCopyAiPrompt,
                onNotice = ::say,
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
                onBrowseDesigns = onBrowseDesigns,
                onForkDesign = onForkDesign,
                onReconnect = onReconnect,
                onHelp = onHelp,
                onCopyAiPrompt = onCopyAiPrompt,
                onNotice = ::say,
                onTakeOffline = onTakeOffline,
                onSyncToServer = onSyncToServer,
                exportHost = exportHost,
                onTidy = {
                  focusEditor()
                  val edits = reducer.tidyPlan(state).changedValues
                  if (edits == 0) {
                    say("Every dp value is already on the 4dp grid")
                  } else {
                    dispatch(UiBuilderEditorEvent.Tidy)
                    say("Tidied $edits values to the 4dp grid")
                  }
                },
                onComponentPacks = onComponentPacks,
                panes = state.panes,
                availablePanes = availablePanes,
                previewSurfaces = catalog.previewSurfaces,
                nativeAvailable = nativeAvailable,
                dispatch = ::dispatch,
              )
            }
          }
          // Under the toolbar and over everything else, on both layouts: what a link asked for is
          // the first thing to know about this page, and a strip inside one of the docks would be
          // behind a panel that starts closed.
          if (!dedicatedOutput) {
            EditorUrlBanner(
              revisionPin = revisionPin,
              onGoToLatest = onGoToLatest,
              openingNotice = openingNotice,
              transientNotice = transientNotice,
            )
          }
          Box(Modifier.fillMaxSize()) {
            if (dedicatedOutput) {
              Row(Modifier.fillMaxSize()) {
                if (EditorPane.Preview in state.panes) {
                  previewPane(Modifier.weight(1f).fillMaxHeight())
                }
                if (EditorPane.Native in state.panes && nativeAvailable) {
                  nativePane(Modifier.weight(1f).fillMaxHeight())
                }
              }
            } else if (!compact) {
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
                HostOrOwnRail(
                  hostChrome,
                  "navigator",
                  NavigatorTab.entries.map { entry ->
                    EditorRailItem(
                      id = "navigator.${entry.name.lowercase()}",
                      label = entry.label,
                      icon = entry.icon(),
                      selected = navigatorTab == entry,
                      onClick = {
                        focusEditor()
                        navigatorTab = if (navigatorTab == entry) null else entry
                      },
                    )
                  },
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
                      breadcrumbs =
                        remember(state.document, state.selection) {
                          if (state.selection.size == 1) reducer.selectionPath(state)
                          else emptyList()
                        },
                      onBreadcrumbSelected = {
                        focusEditor()
                        dispatch(UiBuilderEditorEvent.SelectNode(it))
                      },
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
                      // Whichever panes are on, in the enum's own order and sharing the width
                      // equally. Each is a whole answer to "what am I looking at" rather than a
                      // rung of a ladder, so none of them is conditional on another being drawn.
                      if (EditorPane.Editor in state.panes) {
                        canvas(
                          Modifier.weight(1f)
                            .fillMaxHeight()
                            .background(LocalUiBuilderEditorPalette.current.workspace)
                            .padding(24.dp),
                          Alignment.Center,
                        )
                      }
                      if (EditorPane.Preview in state.panes) {
                        previewPane(Modifier.weight(1f).fillMaxHeight())
                      }
                      if (EditorPane.Native in state.panes && nativeAvailable) {
                        nativePane(Modifier.weight(1f).fillMaxHeight())
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
                    dropTargetLabel = dropTargetLabel,
                    dragging = draggedComponentId != null || draggedNodeId != null,
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
                      // The devices and axes are drawn by the preview pane and nowhere else now, so
                      // "is the strip on screen" is exactly "is that pane open". The authoring
                      // canvas holds one frame whatever is switched on beside it.
                      EditorPane.Preview in state.panes,
                    )
                }
                HostOrOwnRail(
                  hostChrome,
                  "dock",
                  EditorDock.entries.map { entry ->
                    EditorRailItem(
                      id = "dock.${entry.name.lowercase()}",
                      label = entry.label,
                      icon = entry.icon(),
                      selected = dock == entry,
                      // Two docks carry a count, and both answer the same question from the rail:
                      // how much is waiting behind this icon. Counted rather than dotted, because a
                      // bare dot makes somebody open the panel to find out whether it is one or
                      // twenty.
                      badge =
                        when (entry) {
                          EditorDock.Issues -> problemBadgeCount(problems)
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
                  },
                )
              }
            } else {
              canvas(
                Modifier.fillMaxSize()
                  .background(LocalUiBuilderEditorPalette.current.workspace)
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
                // Carrying a component, the sheet steps aside: it covers most of the canvas the
                // component is being carried to. Faded rather than removed, because the drag lives
                // in the tile that started it, and taking the tile out of the composition would
                // cancel the gesture in the finger's hand.
                val carrying = draggedComponentId != null
                navigator(
                  Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.72f)
                    .padding(bottom = 56.dp)
                    .graphicsLayer { alpha = if (carrying) 0f else 1f },
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
                  // Never, here: the compact layout draws the authoring canvas and has no room for
                  // a preview pane beside it, so nothing on this branch draws a device or an axis.
                  false,
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
