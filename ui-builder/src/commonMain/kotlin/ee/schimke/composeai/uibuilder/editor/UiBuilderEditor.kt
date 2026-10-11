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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.uibuilder.CommandOutcome
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
import ee.schimke.composeai.uibuilder.capability.LocalUiBuilderCatalogScrollers
import ee.schimke.composeai.uibuilder.capability.scrollers
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
import ee.schimke.composeai.uibuilder.inspector.LocalHoveredCommentThread
import ee.schimke.composeai.uibuilder.inspector.LocalUiBuilderPageDestinations
import ee.schimke.composeai.uibuilder.inspector.SuggestionRow
import ee.schimke.composeai.uibuilder.inspector.UiBuilderPageDestination
import ee.schimke.composeai.uibuilder.nativeOnlyComponentIds
import ee.schimke.composeai.uibuilder.protocol.BrowserPreviewCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.reference.DesignCapture
import ee.schimke.composeai.uibuilder.reference.DesignCaptureRequest
import ee.schimke.composeai.uibuilder.reference.NodeCapture
import ee.schimke.composeai.uibuilder.reference.NodeCaptureRequest
import ee.schimke.composeai.uibuilder.reference.ReferenceBox
import ee.schimke.composeai.uibuilder.reference.ReferenceCaptureRequest
import ee.schimke.composeai.uibuilder.reference.ReferenceComponentCapture
import ee.schimke.composeai.uibuilder.reference.ReferenceFindings
import ee.schimke.composeai.uibuilder.reference.ReferenceImage
import ee.schimke.composeai.uibuilder.reference.ReferenceImportOutcome
import ee.schimke.composeai.uibuilder.reference.ReferenceLayer
import ee.schimke.composeai.uibuilder.reference.ReferenceMeasureInput
import ee.schimke.composeai.uibuilder.reference.ReferenceMeasureKind
import ee.schimke.composeai.uibuilder.reference.ReferencePiece
import ee.schimke.composeai.uibuilder.reference.ReferenceWorkbench
import ee.schimke.composeai.uibuilder.reference.RestoredReference
import ee.schimke.composeai.uibuilder.reference.contentBounds
import ee.schimke.composeai.uibuilder.reference.decodeReferenceImage
import ee.schimke.composeai.uibuilder.reference.encodeReferencePng
import ee.schimke.composeai.uibuilder.reference.facts
import ee.schimke.composeai.uibuilder.reference.flattenReference
import ee.schimke.composeai.uibuilder.reference.measureAgainstReference
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionCollector
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import ee.schimke.composeai.uibuilder.runCatchingCancellable
import ee.schimke.composeai.uibuilder.uploadedAssets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The inspector's width, shared because the desktop layout passes its own modifier and would ignore
 * a [PropertyInspector] default.
 */
internal val INSPECTOR_WIDTH = 320.dp

/** The left panel's width, named for the same reason [INSPECTOR_WIDTH] is. */
internal val NAVIGATOR_WIDTH = 280.dp

/**
 * A palette row's picture size: 4:3 like the thumbnail frame, so the shrink is uniform, and small
 * enough for a 280 dp panel.
 */
internal val COMPONENT_THUMBNAIL_SIZE = DpSize(44.dp, 33.dp)

private val VARIANT_THUMBNAIL_SIZE = DpSize(32.dp, 24.dp)

/** The code dock's width, wider than either side panel because generated Kotlin has long lines. */
private val CODE_DOCK_WIDTH = 520.dp

/** Pixels per dp for a flattened reference: 2× so hairline strokes survive being re-fitted. */
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

/**
 * Height fraction of the compact layout's Properties and Code sheets: half, so the edited element
 * stays visible above them.
 */
private const val MOBILE_EDITING_SHEET_FRACTION = 0.5f

/** How long a phone's first device tab draws before the other device tabs are built behind it. */
private const val MOBILE_PREVIEW_WARM_DELAY_MS = 600L

/** The compact layout's bottom tab bar, above whatever home-indicator inset the window has. */
internal val MOBILE_DOCK_HEIGHT = 56.dp

/**
 * The compact layout's panels: the wide layout's two rails folded into one bottom dock, one panel
 * per inspector mode.
 */
internal enum class MobileEditorPanel(val inspectorMode: EditorInspectorMode? = null) {
  None,
  Components,
  Layers,
  Properties(EditorInspectorMode.Properties),
  Theme(EditorInspectorMode.Theme),
  Screen(EditorInspectorMode.Screen),
  Issues(EditorInspectorMode.Issues),
  Comments(EditorInspectorMode.Comments),
  History(EditorInspectorMode.History),
  Code;

  companion object {
    fun of(mode: EditorInspectorMode): MobileEditorPanel = entries.first {
      it.inspectorMode == mode
    }
  }
}

@Composable
fun UiBuilderEditor(
  document: UiBuilderDocument,
  catalog: CapabilityCatalog,
  /** Host chrome. The browser keeps Material; an IDE host may supply native controls. */
  chrome: UiBuilderChrome = MaterialUiBuilderChrome,
  /**
   * The component record the host serving [catalog] generates exports from, or null to use the
   * record embedded at build time. See `UiBuilderEditorReducer`.
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
  /**
   * The top-level designs of the open file, drawn as a strip above the canvas; null for a single-design
   * file or a non-file host. See [UiBuilderFileDesigns].
   */
  fileDesigns: UiBuilderFileDesigns? = null,
  /**
   * Who can open this design, shown in the toolbar; null hides the control. Only the host knows (an
   * IDE or MCP host editing a server design must not claim it is private).
   */
  visibilityLabel: String? = null,
  onManageVisibility: (() -> Unit)? = null,
  onReconnect: (() -> Unit)? = null,
  onSubmission: ((EditorSubmission) -> Unit)? = null,
  authoritativeGeneration: Int = 0,
  /**
   * The revision a link to one node may name, or null for none.
   *
   * Not `state.document.revision`, which the reducer raises before the server accepts the edit; and
   * not the last accepted revision for a node created by a still-queued edit. A host answers per
   * node, and null makes the link open the living design at that layer.
   */
  authoritativeRevisionFor: (String) -> Long? = { null },
  initialSelectedNodeId: String? = null,
  initialCatalogQuery: String = "",
  initialLayerQuery: String = "",
  initialInspectorMode: EditorInspectorMode = EditorInspectorMode.Properties,
  /**
   * Edits applied as the editor opens, as though somebody made them. Empty when a person is
   * editing; for callers picturing the editor (e.g. previews of the History panel). Refused edits
   * are skipped, not fatal.
   */
  initialEdits: List<UiBuilderEditorEvent> = emptyList(),
  initialCodePaneVisible: Boolean = false,
  /**
   * Whether the revision strip is open initially. Off when a person is editing; for callers
   * picturing the editor, like [initialEdits].
   */
  initialHistoryBarVisible: Boolean = false,
  /**
   * The revision the strip is peeking at, and the comparison's other end, on open. Applied after
   * [initialEdits]; null when a person is editing.
   */
  initialRevisionPeek: Int? = null,
  initialRevisionCompare: Int? = null,
  /**
   * Component pack ids switched on at open, as the host remembered them. Unknown ids are ignored.
   */
  initialEnabledPacks: Set<String> = emptySet(),
  /**
   * Components pinned to the top of the insert panel, or null to use the catalog's declaration. See
   * [UiBuilderEditorState.pinnedComponents].
   */
  initialPinnedComponents: Set<String>? = null,
  /**
   * Which panels start open. All default to closed (the canvas is the point); hosts such as panel
   * previews can ask for one.
   */
  initialComponentsOpen: Boolean = false,
  initialLayersOpen: Boolean = false,
  initialInspectorOpen: Boolean = false,
  /**
   * Initial tool modes (Add beside, variant strip axes), so static previews can picture them.
   * Defaults match a person's editor.
   */
  initialAddBeside: Boolean = false,
  initialVariantAxes: Set<EditorVariantAxis> = emptySet(),
  /**
   * The canvas's opening scale, or null to frame the design. Hosts capturing canvas pixels pin 1f
   * to avoid resampling.
   */
  initialCanvasZoom: Float? = null,
  /**
   * How the canvas frames the design on open; see [UiBuilderEditorState.canvasView]. Extent unless
   * a preview pictures the device view.
   */
  initialCanvasView: EditorCanvasView = EditorCanvasView.Extent,
  /**
   * Asks the host to compile and render this design with real Compose, or null where it cannot (no
   * compile lane, previews, tests), hiding the control.
   */
  /**
   * Compiles this design on the host and draws it in the given widget container frame, which the
   * pane passes so it matches the canvas. Ignored for designs whose root is not a widget container.
   */
  onRequestNativeRender: (suspend (WearWidgetHostShape) -> UiBuilderNativeRender)? = null,
  /** Exports the current design for a catalog-declared document player in Browser Preview. */
  onRequestDocumentPreview: (suspend (UiBuilderDocument) -> UiBuilderDocumentPreview)? = null,
  /**
   * Opens the live session a native render named, or null where this host cannot stream one (`:ui-builder`
   * has no `WebSocket`). Without it the native pane draws the still.
   */
  onOpenNativeStream: ((UiBuilderNativeLive) -> UiBuilderNativeStream)? = null,
  /** A render already in hand, for the previews that draw this pane without a host. */
  initialNativeRender: UiBuilderNativeRender? = null,
  /**
   * Which design panes the workspace opens with: the authoring canvas unless a host (e.g. a
   * preview) names others.
   */
  initialPanes: Set<EditorPane> = setOf(EditorPane.Editor),
  /**
   * The panes this host puts inside this Compose workspace. IntelliJ puts Preview/Native in a tool
   * window; browser and desktop hosts keep all three.
   */
  availablePanes: Set<EditorPane> = EditorPane.entries.toSet(),
  /** Whether an editor-only initial request gains its usual free browser Preview beside it. */
  openDefaultPreview: Boolean = true,
  collaborators: List<UiBuilderCollaborator> = emptyList(),
  /**
   * Component-library drift for components this design imported, supplied by the host (it holds the
   * credential). Empty shows nothing.
   */
  componentDrift: List<ComponentDriftFinding> = emptyList(),
  /**
   * What the project's shared component library publishes, for the palette's "Project library"
   * shelf; read by the host, like [componentDrift].
   */
  componentLibrary: List<EditorLibraryComponent> = emptyList(),
  /** Fetches one published component's body and digest, or null; null where there is no library. */
  loadLibrarySymbol: (suspend (EditorLibraryComponent) -> EditorLibrarySymbol?)? = null,
  /**
   * Publishes one of this design's components to the project library; null hides the button.
   * [onLibraryChanged] is told after a publish.
   */
  publishLibraryComponent: (suspend (EditorLibraryPublication) -> EditorLibraryPublishResult)? =
    null,
  onLibraryChanged: () -> Unit = {},
  /**
   * Device frames the Screen inspector offers, supplied by the host because `wasmJs` cannot resolve
   * the JVM-only render catalog. Empty hides the menu.
   */
  devicePresets: List<UiBuilderDevicePreset> = emptyList(),
  /**
   * A reference the host loaded back from storage, or null. Applied through the reducer's attach
   * path so stored SVGs are read like fresh imports; watched by identity, since it may arrive late
   * or be replaced.
   */
  restoredReference: RestoredReference? = null,
  /**
   * Asks the host for a picture (file picker, paste, sniffing, refusal, identity), or null where it
   * cannot, which hides import.
   */
  onPickReference: (suspend () -> ReferenceImportOutcome)? = null,
  /**
   * Renders the design as it stands via the host's export lane and returns the pixels, or null. The
   * first step of the markup loop.
   */
  onSnapshotDesign: (suspend () -> ReferenceImportOutcome)? = null,
  /**
   * Fetches a reference from a link (a Figma frame or image URL), or null where the host cannot
   * reach the network. See [parseReferenceUrl].
   */
  onImportReferenceUrl: (suspend (String) -> ReferenceImportOutcome)? = null,
  /**
   * A picture the host caught on the clipboard, or null. It becomes the reference if none is
   * attached, else a piece to position over it.
   */
  pastedReference: ReferenceImage? = null,
  /** A sentence from the host — a refused paste, a store that would not keep it. */
  referenceStatus: String? = null,
  /**
   * The discussion about this design as the host last heard it, replaced wholesale (never merged)
   * from the server's feed. See [DesignCommentBoard].
   */
  comments: DesignCommentBoard = DesignCommentBoard(),
  /**
   * Sends one comment, or null where the host keeps no discussion; the panel then says so instead
   * of offering Post.
   */
  onPostComment: ((DesignCommentDraft) -> Unit)? = null,
  /** Closes a thread, or reopens it. Null alongside a null [onPostComment]. */
  onResolveCommentThread: ((threadId: String, resolved: Boolean) -> Unit)? = null,
  /** A sentence from the host — a refused comment, a feed that dropped. */
  commentStatus: String? = null,
  /**
   * "Notify me about replies" state from the host's [CommentNotificationsController]. Hidden by
   * default.
   */
  commentNotifications: CommentNotificationsState = CommentNotificationsState.Hidden,
  /** The switch's click. Null alongside a hidden [commentNotifications]. */
  onToggleCommentNotifications: (() -> Unit)? = null,
  /**
   * Who approved or rejected which revision, as the host last reported it. Shown at the top of the
   * comments tab.
   */
  review: DesignReview = DesignReview(),
  /** Records a verdict on the revision on screen, or null where the host keeps no reviews. */
  onDecide: ((DesignReviewVerdict, note: String?) -> Unit)? = null,
  /** A sentence from the host — a refused or failed verdict. */
  reviewStatus: String? = null,
  /**
   * Pending suggested edits (usually an agent's), listed at the top of the comments tab; each can
   * be previewed beside the current design.
   */
  suggestions: DesignSuggestions = DesignSuggestions(),
  /**
   * Accepts a suggestion (the host merges it), or null. With both callbacks null and no
   * [suggestions], the section is not drawn.
   */
  onAcceptSuggestion: ((suggestionId: String) -> Unit)? = null,
  /** Rejects a suggestion — the host archives it, and the design never sees it. */
  onRejectSuggestion: ((suggestionId: String) -> Unit)? = null,
  /** A sentence from the host — a list that failed to load, a refused decision. */
  suggestionStatus: String? = null,
  /** The suggestion shown in place of the canvas when the editor mounts; for previews. */
  initialShownSuggestionId: String? = null,
  /**
   * The thread the address bar currently names. Each new non-null value opens and scrolls to it
   * once; the reader may move off it. Not read only at mount, since fragment-only navigations do
   * not reload.
   */
  linkedThreadId: String? = null,
  /**
   * Count of browser fragment changes; the only thing that moves the thread selection after first
   * paint. Needed because the host clears [linkedThreadId] when the reader opens another thread,
   * which must not read as a navigation.
   */
  threadNavigations: Int = 0,
  /** The thread open in the panel, reported on every change so the host keeps `#thread=` honest. */
  onSelectedThreadChanged: ((String?) -> Unit)? = null,
  /**
   * The committed revision `?revision=` pinned this editor to, or null for the living design. See
   * [DesignRevisionPin].
   */
  revisionPin: DesignRevisionPin? = null,
  /** Leaves a pinned revision for the design as it stands now. Null where the host cannot. */
  onGoToLatest: (() -> Unit)? = null,
  /**
   * A notice about the opening link (e.g. a node id this design lacks). Never an error: the design
   * still opens.
   */
  openingNotice: String? = null,
  /** A button beside [openingNotice]: what the host asks the person to do about it. */
  openingNoticeAction: EditorNoticeAction? = null,
  /**
   * Copies a link to one place in this design and returns a sentence, or null where the host has no
   * clipboard. The host builds the address from the selectors; see [designUrlPath].
   */
  onCopyDesignLink: (suspend (DesignUrlSelectors) -> String)? = null,
  newDesignCatalogs: List<UiBuilderNewDesignCatalog> = emptyList(),
  onCreatePublicDesign: ((String, String, String, List<NewDesignState>) -> Unit)? = null,
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
   * A selection made outside the editor (e.g. a host's layer tree). Each new
   * [EditorSelectionRequest] selects again, even for the same layer.
   */
  selectionRequest: EditorSelectionRequest? = null,
  /**
   * Draws the toolbar and rails in the host's own chrome. See [UiBuilderHostChrome]. Null (all but
   * IDE webviews) keeps the editor's own.
   */
  hostChrome: UiBuilderHostChrome? = null,
  /**
   * Receives generated Kotlin when a host-owned Code control is invoked (e.g. VS Code opens a real
   * editor). Null keeps the code dock.
   */
  onGeneratedCode: ((EditorGeneratedCode) -> Unit)? = null,
  /** The colours of the editor's own UI; [UiBuilderEditorTheme.Default] unless a host themes it. */
  theme: UiBuilderEditorTheme = UiBuilderEditorTheme.Default,
  /**
   * Copies a prompt that hands this live design to any MCP-capable agent; null where there is no
   * live server or clipboard. The host owns the prompt because it knows its origin.
   */
  onCopyAiPrompt: (suspend () -> String)? = null,
  agentHost: UiBuilderAgentHost? = null,
  /**
   * Leaves the editor for the host's index of designs this account may open, or null where there is
   * none. On the toolbar beside **New design** because editors are usually reached from a link.
   */
  onBrowseDesigns: (() -> Unit)? = null,
  /**
   * Starts a new design of the reader's own from this one as it is now and opens it, or null where
   * the host cannot. How a read-only viewer gets an editable copy.
   */
  onForkDesign: (() -> Unit)? = null,
  /**
   * Copies this design into browser storage and opens it there, or null outside a live server
   * session. See `docs/design/UI_BUILDER_LOCAL_STORAGE.md` and
   * `docs/design/UI_BUILDER_DESIGN_PORTABILITY.md`.
   */
  onTakeOffline: (() -> Unit)? = null,
  /**
   * Replays this browser's stored edits onto the server the design was taken from. Null unless the
   * design has a fork point.
   */
  onSyncToServer: (() -> Unit)? = null,
  /**
   * Creates this browser design on the server as a new design (never a merge), or null where the
   * server would not take this caller's create. The counterpart of [onSyncToServer] for designs
   * with no fork point.
   */
  onPublishToServer: (() -> Unit)? = null,
  /**
   * Copies, links and downloads the rendered design, or null where the host cannot (no Export menu
   * then). See [UiBuilderExportHost].
   */
  exportHost: UiBuilderExportHost? = null,
  /**
   * Published Remote Compose documents the serving catalog offers as content. Host-supplied because
   * they are the serving catalog's previews, not [catalog]'s components; see [RemoteComposeSource].
   * Empty hides the palette.
   */
  remoteComposeSources: List<RemoteComposeSource> = emptyList(),
  /**
   * Fetches one source's document, Base64-encoded, or throws. The panel requires both this and
   * [remoteComposeSources].
   */
  resolveRemoteComposeDocument: (suspend (RemoteComposeSource) -> String)? = null,
  /**
   * Fetches the serving catalog's rendered PNG for one Remote Compose source, loaded lazily by
   * visible rows. Null keeps the neutral component glyph.
   */
  resolveRemoteComposeThumbnail: (suspend (RemoteComposeSource) -> ImageBitmap?)? = null,
  /**
   * Fetches the Base64-encoded document at an embedded node's `documentUrl`, or throws.
   *
   * Separate from [resolveRemoteComposeDocument], which copies bytes into the design on Add; this
   * resolves a live reference, and a host may support only one. Null leaves `documentUrl` nodes in
   * their waiting state.
   */
  resolveRemoteComposeUrl: (suspend (String) -> String)? = null,
  /**
   * Fetches a Lottie animation's JSON from a `remote-m3/lottie` element's URL, or throws. Host-owned
   * because the host decides which origins are reachable; null leaves the URL unresolved.
   */
  loadLottieAnimation: (suspend (String) -> String)? = null,
  /**
   * Fetches the bytes of an uploaded asset by key, or throws. Only the storing host can resolve it;
   * null leaves uploaded pictures as placeholders.
   */
  resolveDesignAsset: (suspend (String) -> ByteArray)? = null,
  /**
   * Draws the editable design through its pinned catalog runtime. Null keeps the in-process
   * renderer (JVM previews, tests); overlays stay siblings in [PinnedDesignCanvas].
   */
  canvasRenderer: UiBuilderCanvasRenderer? = null,
  /**
   * Sends a comment about one node to the surrounding chat conversation (e.g. the MCP App), or
   * null. When present the node menu offers **Comment**; the text arrives with the node id and,
   * where possible, a picture of the node. Never called with blank text. The design's own
   * discussion uses [onPostComment].
   */
  onCommentOnNode: ((UiBuilderNodeComment) -> Unit)? = null,
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
            canvasView = initialCanvasView,
            codePaneVisible = initialCodePaneVisible,
            historyBarVisible = initialHistoryBarVisible,
            enabledPacks =
              initialEnabledPacks.filterTo(mutableSetOf()) { catalog.componentPacks[it] != null },
            pinnedComponents = initialPinnedComponents,
            // Every design gets a free browser preview beside its canvas. Native is explicit-only
            // because it compiles through the host; widgets fan the preview out over launcher
            // shapes. An explicit [initialPanes] wins.
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
  // After the reconcile above, which fires on first composition too and would otherwise drop the
  // seed. Once per design.
  var seeded by remember(document.id) { mutableStateOf(false) }
  LaunchedEffect(document.id) {
    if (!seeded && (initialEdits.isNotEmpty() || initialRevisionPeek != null)) {
      seeded = true
      // The peek goes last: an edit ends a peek.
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
  // Applied once, and only over an editor with no reference of its own, so a late or repeated
  // delivery never overwrites the operator's marks.
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
  // The canvas pane's own rectangle, distinguishing a drop on empty workspace ground (beside
  // gesture) from a drop on another pane.
  var canvasWorkspaceBounds by remember { mutableStateOf(Rect.Zero) }
  // The pinned scale, or null while framed. Local, not in [UiBuilderEditorState]: zoom is a fact
  // about this window, not the design.
  var canvasZoom by remember(document.id) { mutableStateOf(initialCanvasZoom) }
  // Which control the hover editor should put the caret in, set by an action that just created the
  // value being edited and cleared the moment it lands.
  var hoverFocusTarget by remember(document.id) { mutableStateOf<String?>(null) }
  // The node whose text is being typed over on the canvas, and what it said when that started.
  var inlineTextEdit by remember(document.id) { mutableStateOf<CanvasInlineTextEdit?>(null) }
  var textInputFocused by remember { mutableStateOf(false) }
  // The canvas and nothing else around it: see [UiBuilderWorkspace.FocusedCanvas].
  val focusedCanvas = chrome.workspace == UiBuilderWorkspace.FocusedCanvas
  // The node the Comment field is open beside, and the comment on its way to the host while the
  // node is photographed for it.
  var commentNodeId by remember(document.id) { mutableStateOf<String?>(null) }
  var pendingComment by
    remember(document.id) { mutableStateOf<Pair<NodeCaptureRequest, String>?>(null) }
  var commentSequence by remember(document.id) { mutableStateOf(0) }
  LaunchedEffect(state.selectedNodeId) {
    if (commentNodeId != state.selectedNodeId) commentNodeId = null
  }
  // Which compact tab is showing: a preview pane's id, or null for the editor. Per design, like the
  // panel, and a pane that goes away — its device unticked — falls back to the editor.
  var mobileView by remember(document.id) { mutableStateOf<String?>(null) }
  // The device tabs opened so far, which stay composed so switching back to one is immediate.
  val visitedMobilePreviews = remember(document.id) { mutableStateListOf<String>() }
  // Opened where the URL asked for a panel on compact layouts too, so `?node=` and `#thread=` are
  // visible on a phone. `Properties` hosts every inspector mode.
  var mobilePanel by
    remember(document.id) {
      mutableStateOf(
        if (initialInspectorOpen) MobileEditorPanel.Properties else MobileEditorPanel.None
      )
    }
  // Which panels are open. Local on purpose: collaborators' open panels are not part of the
  // document.
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
  var showAgentPrompt by remember(document.id) { mutableStateOf(false) }
  var showPacks by remember(document.id) { mutableStateOf(false) }
  // Offered only where there is something to switch: a settings entry over an empty list is a
  // control that teaches nothing.
  val onComponentPacks: (() -> Unit)? =
    if (catalog.componentPacks.isEmpty) null else ({ showPacks = true })
  // The source being fetched, or null. One at a time so a double-click does not insert twice.
  var pendingRemoteSource by remember(document.id) { mutableStateOf<RemoteComposeSource?>(null) }
  // A Remote Compose drop's pointer-resolved slot, captured before the fetch; null for the ordinary
  // Add path.
  var pendingRemoteTarget by remember(document.id) { mutableStateOf<ParentSlot?>(null) }
  // Only transport failures; decode failures are reported by the reducer as rejections.
  var remoteSourceFailure by remember(document.id) { mutableStateOf<String?>(null) }
  val editorFocusRequester = remember { FocusRequester() }
  // Held here rather than inside the flatten, which is not a composable: a text mark has to be set
  // in the same font when it is baked in as when it was drawn.
  val flattenTextMeasurer = rememberTextMeasurer()
  // The canvas's own layout: promoting a piece asks which slot is under it in the rendered layout.
  var canvasInspection by
    remember(document.id) { mutableStateOf<UiBuilderInspectionSnapshot?>(null) }
  var captureRequest by remember(document.id) { mutableStateOf<ReferenceCaptureRequest?>(null) }
  var captureSequence by remember(document.id) { mutableStateOf(0) }
  var captureFailure by remember(document.id) { mutableStateOf<String?>(null) }
  // The reference measurements: the frame's box in the root (to turn the inspection into frame dp),
  // the photograph of the design they compare against, and what the last one found.
  var frameRootBounds by remember(document.id) { mutableStateOf(Rect.Zero) }
  var designCapture by remember(document.id) { mutableStateOf<DesignCaptureRequest?>(null) }
  var designCaptureSequence by remember(document.id) { mutableStateOf(0) }
  var pendingMeasure by remember(document.id) { mutableStateOf<ReferenceMeasureKind?>(null) }
  // A snapshot asked of the editor itself, for a host with no export lane: the capture's answer
  // completes it.
  var pendingSnapshot by
    remember(document.id) { mutableStateOf<CompletableDeferred<ImageBitmap?>?>(null) }
  var referenceFindings by remember(document.id) { mutableStateOf<ReferenceFindings?>(null) }
  var referenceMeasuring by remember(document.id) { mutableStateOf(false) }
  // Which conversation is open, in the panel and under the pin. Editor state rather than document
  // state, and per design: which thread somebody has expanded is a fact about a moment.
  var selectedThreadId by remember(document.id) { mutableStateOf(linkedThreadId) }
  // The thread a pointer is over in the panel: its pin shows on the canvas while it is.
  val hoveredThread = remember(document.id) { mutableStateOf<String?>(null) }
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
  if (showAgentPrompt && agentHost != null) {
    AgentPromptDialog(agentHost, { showAgentPrompt = false }, ::say)
  }
  val editorScope = rememberCoroutineScope()
  val systemClipboard = LocalClipboard.current
  fun selectThread(threadId: String?) {
    selectedThreadId = threadId
    onSelectedThreadChanged?.invoke(threadId)
  }
  // The canvas's scroll offset. Known gap: the drag hit-test and the drawn pointer disagree once the
  // workspace has scrolled.
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
  // Empty recommended slot hints, from the same inspection as the drop plan so hint and target
  // agree. Only while dragging: at rest an empty container is usually intentional.
  val dragInHand = draggedComponentId != null || draggedNodeId != null
  val slotPlaceholders =
    remember(state.document, canvasInspection, dragInHand) {
      if (!dragInHand) return@remember emptyList()
      canvasInspection?.let { snapshot ->
        reducer.slotPlaceholders(
          state,
          snapshot.slots,
          snapshot.nodes.mapNotNull { node -> node.bounds?.let { node.nodeId to it } }.toMap(),
        )
      } ?: emptyList()
    }
  // Over the workspace but not the design: the beside ground. The status bar names what a release
  // would do while the pointer is still down.
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
   * The guard is the operation sequence, which the reducer bumps for every command (accepted or
   * rejected) and nothing else. Non-document events pass through; document changes are dropped so
   * the canvas never shows an optimistic edit that is never submitted.
   */
  fun reduceAndSubmit(event: UiBuilderEditorEvent) {
    val previous = state
    val current = reducer.reduce(previous, event)
    if (revisionPin?.readOnly == true && current.operationSequence != previous.operationSequence) {
      say("Revision ${revisionPin.requested} is read-only. Go to latest to edit.")
      return
    }
    state = current
    reducer.acceptedSubmission(previous, current)?.let { onSubmission?.invoke(it) }
  }
  fun dispatch(event: UiBuilderEditorEvent) {
    when (event) {
      // A paste may come from another tab, window or IDE: what it copied is on the system
      // clipboard as text, and a fragment this editor did not copy is pasted instead of its own.
      UiBuilderEditorEvent.Paste ->
        if (readsSystemClipboardOnEveryPaste || state.clipboard == null) {
          editorScope.launch {
            val incoming = readSystemClipboardText()?.let(::decodeEditorClipboard)
            if (incoming != null && !incoming.sameFragmentAs(state.clipboard))
              reduceAndSubmit(UiBuilderEditorEvent.ReceiveClipboard(incoming))
            reduceAndSubmit(UiBuilderEditorEvent.Paste)
          }
          return
        }
      else -> Unit
    }
    reduceAndSubmit(event)
    // Apply writes one lane per event; dispatch a second time for the other lane, but only once so
    // an unsettleable lane cannot spin.
    if (
      event == UiBuilderEditorEvent.ApplyTunables &&
        state.lastOutcome is CommandOutcome.Accepted &&
        state.document.tunableWrites(state.tunables, state.tunedValues).isNotEmpty()
    ) {
      reduceAndSubmit(event)
    }
    // What a copy or cut took is offered to the rest of the system too, so a design open somewhere
    // else can paste it. A clipboard the host refuses changes nothing here.
    if (event == UiBuilderEditorEvent.CopySelected || event == UiBuilderEditorEvent.CutSelected) {
      state.clipboard?.let { copied ->
        editorScope.launch {
          runCatching {
            systemClipboard.setClipEntry(plainTextClipEntry(encodeEditorClipboard(copied)))
          }
        }
      }
    }
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
   * Selecting a node also opens its Properties dock; used for canvas and layer-tree selection
   * alike.
   */
  fun selectNodeForEditing(nodeId: String) {
    focusEditor()
    dispatch(UiBuilderEditorEvent.SelectNode(nodeId))
    openProperties()
  }
  // The host's drift findings into the reducer. Re-dispatched when component declarations change:
  // `stillDescribing` drops findings that stop matching, and undo can make them true again without
  // the host refetching.
  LaunchedEffect(componentDrift, state.document.components) {
    dispatch(UiBuilderEditorEvent.SetComponentDrift(componentDrift))
  }
  // Follows fragment-only address-bar navigations after first paint. Null deselects too (Back to a
  // fragment-free URL), but never closes the panel.
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
   * Bake the reference stack into one picture and make it the base. Sized from the document's frame
   * at 2× dp, not the on-screen canvas, so the result is zoom-independent.
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
        facts =
          state.reference.image?.let { image ->
            val (widthDp, heightDp) = state.document.canvasFrameDp(WearWidgetHostShape.Default)
            image.facts(widthDp, heightDp, environment.density.toFloat())
          },
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
  // Cached: the filter scans four strings per node and the panel recomposes far more often than its
  // inputs change.
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
   * What the insert panel calls the place an Add would land: the node's layer name plus the slot,
   * matching the layers panel and breadcrumbs.
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
   * The selection's verbs as menu rows. Built here because every answer comes from the reducer; the
   * lambda takes the menu's dismiss so rows can close it.
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
      canOfferMakeComponent = state.selection.size == 1,
      componentActions = reducer.componentActions(state),
      makeComponentRefusal =
        if (state.selection.size == 1) reducer.makeComponentRefusal(state) else null,
      // The focused canvas has no inspector to open; its quick editor is the way to a property.
      onOpenProperties =
        if (focusedCanvas) null
        else {
          {
            focusEditor()
            if (state.codePaneVisible) dispatch(UiBuilderEditorEvent.ToggleCodePane)
            dispatch(UiBuilderEditorEvent.ShowInspector(EditorInspectorMode.Properties))
            inspectorOpen = true
            mobilePanel = MobileEditorPanel.Properties
          }
        },
      onQuickEdit =
        if (state.selection.size == 1) {
          {
            focusEditor()
            commentNodeId = null
            dispatch(UiBuilderEditorEvent.ShowQuickEditor)
          }
        } else null,
      onComment =
        if (onCommentOnNode != null && state.selection.size == 1) {
          {
            dispatch(UiBuilderEditorEvent.HideQuickEditor)
            commentNodeId = state.selectedNodeId
          }
        } else null,
      // The link names the anchor node, pinned to the host-confirmed revision. Withheld for a layer
      // created by a still-queued edit, since no link to it would resolve correctly yet.
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
        localPalette =
          EditorLocalComponentPalette(
            components = reducer.localComponents(state),
            preview = { reducer.localComponentPreview(state, it) },
            target = { reducer.localComponentTarget(state, it) },
            library = componentLibrary,
            onAddLibrary =
              loadLibrarySymbol?.let { load ->
                { component ->
                  focusEditor()
                  editorScope.launch {
                    val symbol = runCatchingCancellable { load(component) }.getOrNull()
                    if (symbol == null) {
                      say("Could not fetch ${component.title} from the project library")
                      return@launch
                    }
                    val target = reducer.libraryComponentTarget(state, symbol)
                    if (target == null) say("Select a layer that can hold ${component.title}")
                    else dispatch(UiBuilderEditorEvent.InsertLibraryComponent(symbol, target))
                  }
                }
              },
          ),
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
          // Insert where dropped (slot and seam under the pointer), not into the selection.
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
              // Empty ground (workspace but not design) means "add beside"; a design that cannot
              // take one says so.
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
        // Per row only the component's own refusal; the document-level refusal belongs to the
        // panel.
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
  // Drag ghosts, built once per drag: the palette component or the moved subtree, which the canvas
  // scales to the hovered slot. Remote Compose drags use their published capture instead.
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
  // What every design surface draws: the document, with each linked tunable's slider value in
  // place of what its targets hold. The same instance as the document while nothing is tuned.
  val drawnDocument =
    remember(state.document, state.tunables, state.tunedValues) {
      state.document.tuned(state.tunables, state.tunedValues)
    }
  // The strip beside the design: claimed devices plus enabled unstored axes, computed from the
  // design.
  val variantPanes =
    remember(drawnDocument, devicePresets, state.variantAxes) {
      drawnDocument.variantPanes(devicePresets, state.variantAxes)
    }
  // A dedicated Preview view has no canvas beside it, so its first frame is the current design.
  val previewPanes =
    remember(drawnDocument, variantPanes, availablePanes) {
      if (EditorPane.Editor in availablePanes || drawnDocument.widgetPreviewPanes() != null) {
        variantPanes
      } else {
        listOf(drawnDocument.currentFramePane()) + variantPanes
      }
    }
  // The read-only pane: the catalog's typed capability picks the canvas renderer or a browser
  // adapter; unknown adapters fall back to the canvas.
  val documentBackedPreview =
    catalog.browserPreview?.takeIf {
      it.renderer == BrowserPreviewCapabilityV1.REMOTE_COMPOSE_DOCUMENT_RENDERER &&
        it.format == ExportFormatV1.RC &&
        onRequestDocumentPreview != null
    }
  // Compact preview tabs, one frame each (see [MobileViewTabs]), the design's own frame first.
  // Widgets use their host shapes or grid sizes.
  val mobilePreviewPanes =
    remember(drawnDocument, variantPanes, documentBackedPreview) {
      drawnDocument.widgetPreviewPanes(resizable = documentBackedPreview == null)
        ?: (listOf(drawnDocument.currentFramePane("Preview")) + variantPanes)
    }
  // Frozen until asked: see [PreviewTimeToggle]. Editor state rather than the document's, since
  // letting time run in a preview is not an edit to the design.
  var previewTimeRunning by remember { mutableStateOf(false) }
  val previewPane: @Composable (Modifier) -> Unit = { modifier ->
    if (documentBackedPreview != null) {
      RemoteDocumentDesignPreviewPane(
        document = drawnDocument,
        variants = previewPanes,
        authoritativeGeneration = authoritativeGeneration,
        request = requireNotNull(onRequestDocumentPreview),
        modifier = modifier,
        timeRunning = previewTimeRunning,
        onTimeRunningChange = { previewTimeRunning = it },
      )
    } else {
      DesignPreviewPane(
        document = drawnDocument,
        variants = previewPanes,
        modifier = modifier,
        deviceRenderer = canvasRenderer,
        timeRunning = previewTimeRunning,
        onTimeRunningChange = { previewTimeRunning = it },
      )
    }
  }
  // One preview tab's frame, drawn alone and fitted to the screen, by whichever pane the catalog
  // previews with.
  val mobilePreview: @Composable (UiBuilderVariantPane, Modifier) -> Unit = { pane, modifier ->
    if (documentBackedPreview != null) {
      // Keyed by tab: tabs sharing one document (a widget's host shapes) must each request their
      // own result.
      key(pane.id) {
        RemoteDocumentDesignPreviewPane(
          document = drawnDocument,
          variants = listOf(pane),
          authoritativeGeneration = authoritativeGeneration,
          request = requireNotNull(onRequestDocumentPreview),
          modifier = modifier,
          exactPanes = true,
          timeRunning = previewTimeRunning,
          onTimeRunningChange = { previewTimeRunning = it },
        )
      }
    } else {
      DesignPreviewPane(
        document = drawnDocument,
        variants = listOf(pane),
        modifier = modifier,
        deviceRenderer = canvasRenderer,
        exactPanes = true,
        timeRunning = previewTimeRunning,
        onTimeRunningChange = { previewTimeRunning = it },
      )
    }
  }
  // The editing surface draws a Wear widget in the editing frame, not the viewed host shape, so it
  // does not reshape under the author. Only the compact layout, which has no preview pane, draws
  // the chosen shape on the canvas.
  var canvasIsOnlySurface by remember { mutableStateOf(false) }
  val canvasHostShape =
    if (canvasIsOnlySurface) state.wearWidgetHostShape else WearWidgetHostShape.Default
  // Decoded once per picture, for its size and for measuring; the overlay keeps its own decode.
  val referenceBitmap =
    remember(state.reference.image?.id) { state.reference.image?.let(::decodeReferenceImage) }
  val referenceFrameDp = state.document.canvasFrameDp(canvasHostShape)
  val referenceFacts =
    state.reference.image?.facts(
      frameWidthDp = referenceFrameDp.first,
      frameHeightDp = referenceFrameDp.second,
      designDensity = state.document.screenEnvironmentSettings().density.toFloat(),
      decodedWidthPx = referenceBitmap?.width ?: 0,
      decodedHeightPx = referenceBitmap?.height ?: 0,
    )
  // The frame as the canvas actually lays it out: the width is the design's, the height grows
  // with the content in the authoring view, and the overlay's fractions are of that height.
  fun measuredFrameHeightDp(): Float =
    if (frameRootBounds.width > 0f) {
      frameRootBounds.height / frameRootBounds.width * referenceFrameDp.first
    } else referenceFrameDp.second
  fun requestDesignCapture(heightDp: Float = measuredFrameHeightDp()) {
    designCaptureSequence += 1
    designCapture = DesignCaptureRequest(designCaptureSequence, referenceFrameDp.first, heightDp)
  }
  /** Measure the reference against the design: across the frame, or for one layer. */
  fun measureReference(kind: ReferenceMeasureKind) {
    if (state.reference.image == null || referenceMeasuring) return
    referenceMeasuring = true
    pendingMeasure = kind
    requestDesignCapture()
  }
  /** The canvas's layout as layers in frame dp, from the inspection the canvas last reported. */
  fun referenceLayers(): List<ReferenceLayer> {
    val root = frameRootBounds
    if (root.width <= 0f) return emptyList()
    val perDp = root.width / referenceFrameDp.first
    return canvasInspection?.nodes.orEmpty().mapNotNull { node ->
      val bounds = node.bounds ?: return@mapNotNull null
      val box =
        ReferenceBox(
          (bounds.x - root.left) / perDp,
          (bounds.y - root.top) / perDp,
          (bounds.x + bounds.width - root.left) / perDp,
          (bounds.y + bounds.height - root.top) / perDp,
        )
      ReferenceLayer(
        nodeId = node.nodeId,
        bounds = box,
        text = node.text != null,
        content = state.document.nodes[node.nodeId]?.contentBounds(box) ?: box,
      )
    }
  }
  fun onDesignCaptured(request: DesignCaptureRequest, bitmap: ImageBitmap?) {
    if (designCapture != request) return
    designCapture = null
    pendingSnapshot?.let {
      pendingSnapshot = null
      it.complete(bitmap)
    }
    val kind = pendingMeasure ?: return
    pendingMeasure = null
    val baseBitmap = referenceBitmap
    if (bitmap == null || baseBitmap == null) {
      referenceMeasuring = false
      referenceFindings =
        ReferenceFindings(
          documentRevision = state.document.revision,
          referenceId = state.reference.image?.id,
          frameWidthDp = referenceFrameDp.first,
          message =
            if (bitmap == null) "The design could not be photographed on this platform."
            else "The reference picture could not be decoded.",
        )
      return
    }
    val input =
      ReferenceMeasureInput(
        kind = kind,
        design = bitmap,
        referenceBitmap = baseBitmap,
        reference = state.reference,
        facts = referenceFacts,
        frameWidthDp = referenceFrameDp.first,
        frameHeightDp = measuredFrameHeightDp(),
        layers = referenceLayers(),
        nodes = state.document.nodes,
        documentRevision = state.document.revision,
      )
    editorScope.launch {
      try {
        referenceFindings = withContext(Dispatchers.Default) { measureAgainstReference(input) }
      } finally {
        referenceMeasuring = false
      }
    }
  }
  // A host with no export lane still gets a Snapshot: the editor photographs its own design, with
  // the renderer the canvas uses, which for an offline host is the design's export renderer too.
  val snapshotDesign: (suspend () -> ReferenceImportOutcome) =
    onSnapshotDesign
      ?: {
        val deferred = CompletableDeferred<ImageBitmap?>()
        pendingSnapshot = deferred
        // The device's frame rather than the authoring view's grown one: a snapshot is a picture
        // of the screen, and it is compared against the screen.
        requestDesignCapture(referenceFrameDp.second)
        val bitmap = deferred.await()
        val png = bitmap?.let(::encodeReferencePng)
        if (bitmap == null || png == null)
          ReferenceImportOutcome.Refused("The design could not be photographed here.")
        else
          ReferenceImportOutcome.Imported(
            ReferenceImage(
              id = "snapshot-${document.id}-${state.document.revision}-$designCaptureSequence",
              name = "Snapshot of this design",
              mediaType = "image/png",
              base64 = kotlin.io.encoding.Base64.Default.encode(png),
              widthPx = bitmap.width,
              heightPx = bitmap.height,
            )
          )
      }
  val designCanvas: @Composable (Modifier, Alignment) -> Unit = { modifier, alignment ->
    CompositionLocalProvider(LocalWearWidgetHostShape provides canvasHostShape) {
      PinnedDesignCanvas(
        document = drawnDocument,
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
        referenceFacts = referenceFacts,
        // Only while it still describes what is on screen: an edit or a new picture since makes
        // the outlines a picture of a different design.
        referenceFindings =
          referenceFindings?.takeIf {
            it.documentRevision == state.document.revision &&
              it.referenceId == state.reference.image?.id
          },
        onFrameRootBounds = { frameRootBounds = it },
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
        onHoverEditorDismiss = {
          commentNodeId = null
          dispatch(UiBuilderEditorEvent.HideQuickEditor)
        },
        // The Comment field is only for typing, so it takes the keys; the quick editor does not.
        hoverEditorFocusable = commentNodeId != null && commentNodeId == state.selectedNodeId,
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
          if (
            state.selection.size == 1 &&
              commentNodeId != null &&
              commentNodeId == state.selectedNodeId &&
              onCommentOnNode != null
          ) {
            { dragHandle ->
              NodeCommentCard(
                label = selectionLabel,
                dragHandle = dragHandle,
                onSend = { text ->
                  val nodeId = commentNodeId
                  commentNodeId = null
                  textInputFocused = false
                  if (nodeId != null) {
                    pendingComment = NodeCaptureRequest(nodeId, ++commentSequence) to text
                  }
                  focusEditor()
                },
                onDismiss = {
                  commentNodeId = null
                  textInputFocused = false
                  focusEditor()
                },
                onTextInputFocusChanged = { textInputFocused = it },
              )
            }
          } else if (state.selection.size != 1 || !state.quickEditorOpen) null
          else {
            { dragHandle ->
              SelectionHoverEditor(
                label = selectionLabel,
                dragHandle = dragHandle,
                onDismiss = { dispatch(UiBuilderEditorEvent.HideQuickEditor) },
                // Only what the node carries, i.e. what the export would write.
                fields =
                  inspectorPropertyFields(reducer.propertyFields(state)).filter { field ->
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
        canvasView = state.canvasView,
        onCanvasViewChanged = { view ->
          focusEditor()
          dispatch(UiBuilderEditorEvent.SetCanvasView(view))
        },
        // What the pop-out's header calls its container: the name the layers panel gives it.
        nodeLabel = { nodeId ->
          layerRows
            .filterIsInstance<EditorLayerRow.Node>()
            .firstOrNull { it.nodeId == nodeId }
            ?.row
            ?.label
            ?: state.document.nodes[nodeId]?.componentId?.let {
              catalog.componentsById[it]?.displayName ?: it
            }
            ?: nodeId
        },
        // Only when no other pane shows the frame, and never for Wear or m3 designs, whose device
        // view belongs to the preview pane.
        frameCompanion =
          LocalUiBuilderCatalogPlatform.current != UiBuilderCatalogPlatform.WEAR.wireValue &&
            LocalUiBuilderCatalogPlatform.current != UiBuilderCatalogPlatform.MOBILE.wireValue &&
            catalog.platform != UiBuilderCatalogPlatform.WEAR &&
            catalog.platform != UiBuilderCatalogPlatform.MOBILE &&
            state.panes.none { it == EditorPane.Preview || it == EditorPane.Native },
        contentAlignment = alignment,
        modifier = modifier,
      )
    }
  }
  // The Tune card floats over the canvas rather than sitting in a dock, so a slider and the design
  // it moves are on screen together whichever dock is open.
  val canvas: @Composable (Modifier, Alignment) -> Unit = { modifier, alignment ->
    Box(modifier) {
      designCanvas(Modifier.fillMaxSize(), alignment)
      if (state.tunables.isNotEmpty() && state.revisionPeek == null) {
        TunePanel(
          document = state.document,
          tunables = state.tunables,
          values = state.tunedValues,
          onTextInputFocusChanged = { textInputFocused = it },
          dispatch = ::dispatch,
          // Top, clear of the zoom bar along the bottom edge.
          modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        )
      }
    }
  }
  // Cached against the document: it walks every node and property, and the inspector recomposes on
  // every keystroke and drag frame.
  val assetBitmapsByDigest = remember { mutableStateMapOf<String, ImageBitmap?>() }
  // Encoded bytes too: sandboxed catalog runtimes and the widget export receive only the document,
  // so pictures travel inside it (`LocalUiBuilderAssetBytes`).
  val assetBytesByDigest = remember { mutableStateMapOf<String, ByteArray>() }
  val documentProblems =
    remember(reducer, state.document, assetBytesByDigest.size) {
      reducer.problems(state.document) { assetBytesByDigest[it] }
    }
  // Appended rather than folded into the pure `problems`: drift comes from the host. Keyed on the
  // findings alone so the cache above survives a fetch.
  val driftProblems =
    remember(state.componentDrift) { componentDriftProblems(state.componentDrift) }
  val problems = documentProblems + driftProblems
  // Keyed on the operation counter, not the document: an undo restores an earlier document but
  // moves the marker.
  val operationHistory =
    remember(reducer, state.operationSequence, state.document.revision) {
      reducer.operationHistory(state)
    }
  // The history as pictures, only while the strip is open (each row costs a rebuilt document and a
  // thumbnail). Keyed like the list above.
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
  // Suggestions are the host's, not edits, so the shown one stays out of the reducer.
  var shownSuggestionId by remember { mutableStateOf(initialShownSuggestionId) }
  val suggestionRows =
    remember(suggestions, state.operationSequence, state.document.revision, catalog) {
      suggestions.open.map { suggestion ->
        SuggestionRow(
          suggestion = suggestion,
          diff = suggestionDiff(state, suggestion, catalog),
          behind = suggestion.isBehind(state),
        )
      }
    }
  val shownSuggestionRow = suggestionRows.firstOrNull {
    it.suggestion.suggestionId == shownSuggestionId && it.suggestion.document != null
  }
  val acceptSuggestion: ((String) -> Unit)? = onAcceptSuggestion?.let { accept ->
    { id: String ->
      if (shownSuggestionId == id) shownSuggestionId = null
      accept(id)
    }
  }
  val rejectSuggestion: ((String) -> Unit)? = onRejectSuggestion?.let { reject ->
    { id: String ->
      if (shownSuggestionId == id) shownSuggestionId = null
      reject(id)
    }
  }
  val suggestionPanel =
    if (
      onAcceptSuggestion == null &&
        onRejectSuggestion == null &&
        suggestions.open.isEmpty() &&
        suggestions.lastOutcome == null
    ) {
      null
    } else {
      SuggestionPanel(
        rows = suggestionRows,
        shownSuggestionId = shownSuggestionRow?.suggestion?.suggestionId,
        outcome = suggestions.lastOutcome,
        status = suggestionStatus,
        onShow = { shownSuggestionId = it },
        onAccept = acceptSuggestion,
        onReject = rejectSuggestion,
      )
    }
  val comparedRevision = revisionEntries.firstOrNull { it.revision == state.revisionCompare }
  /**
   * The slot a piece would be built into, hit-tested at its centre. Fractions become pixels here
   * because that needs the frame and density.
   */
  fun promotionTargetFor(piece: ReferencePiece): ParentSlot? {
    val componentId = piece.componentId ?: return null
    // The editing frame where a pane shows the host shape, the chosen one where the canvas is
    // alone.
    val (pointX, pointY) = state.document.referencePieceCentrePx(piece, canvasHostShape)
    return reducer.promotionTarget(
      state = state,
      componentId = componentId,
      slots = canvasInspection?.slots.orEmpty(),
      pointX = pointX,
      pointY = pointY,
    )
  }
  // Only while the pane is open: generating is a projection plus a full generator run.
  var nativeRender by remember(document.id) { mutableStateOf(initialNativeRender) }
  // Even with no lane the menu row stays, disabled with its reason, so the pane is discoverable.
  val nativeAvailable = onRequestNativeRender != null
  // Derived from the pane set rather than stored. Only the native pane needs a compile.
  val nativeRequested = onRequestNativeRender != null && EditorPane.Native in state.panes
  var nativePending by remember(document.id) { mutableStateOf(false) }
  // Re-render on each revision and host-shape change, so the native pane never shows a stale frame
  // beside the live canvas.
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
  // The live session behind the still, if this host can open it. Keyed on the coordinates so a re-render
  // on the same session keeps the socket.
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
  // The native pane: the design compiled on the host. Document playback belongs only to Browser
  // Preview.
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
    // A pointer drop's captured slot; the reducer revalidates it, so a concurrently removed slot is
    // refused rather than retargeted.
    pendingRemoteTarget?.let { target ->
      remoteSourceFailure = null
      dispatch(UiBuilderEditorEvent.InsertRemoteComposeDocument(source, encoded, target))
      pendingRemoteTarget = null
      pendingRemoteSource = null
      return@LaunchedEffect
    }
    // Resolve the beside mode and target as they stand after the fetch round trip, not when the row
    // was pressed.
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
  // Every `documentUrl` the design references and its result, keyed by URL so shared documents
  // fetch once. Held across revisions so unrelated edits do not refetch.
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
      // Stored per URL as it arrives, failures included, so one bad URL neither blocks others nor
      // retries every frame.
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
  // Every uploaded asset, decoded once per content digest (as `LocalUiBuilderAssetBitmaps` is
  // keyed). Failures are stored as null so they are not retried every recomposition.
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
  // Resolve a Lottie element's `url` into its `json` exactly once: re-fetching could overwrite
  // hand-edited JSON, and [attemptedLottieUrls] keeps a 404 from looping.
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
  // `variantsDrawn` is a parameter because only the layout knows whether the preview pane draws
  // variants.
  val inspector: @Composable (Modifier, Boolean) -> Unit = { modifier, variantsDrawn ->
    PropertyInspector(
      state = state,
      // Clear both layouts' flags: the compact layout draws this sheet from [mobilePanel].
      onClose = {
        inspectorOpen = false
        // Whichever inspector mode the compact sheet is showing: it is one sheet for all of them.
        if (mobilePanel.inspectorMode != null) mobilePanel = MobileEditorPanel.None
      },
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
      onSnapshotDesign = snapshotDesign,
      onFlatten = ::flattenCurrentReference,
      catalogItems = reducer.catalogItems(""),
      localComponents = reducer.localComponents(state),
      onPublishComponent =
        publishLibraryComponent?.let { publish ->
          { componentKey ->
            val publication = reducer.libraryPublication(state, componentKey)
            if (publication == null) {
              say(
                reducer.libraryPublicationRefusal(state, componentKey)
                  ?: "This component cannot be published"
              )
            } else {
              editorScope.launch {
                say("Publishing ${publication.title} to the project library")
                when (
                  val result = runCatchingCancellable {
                    publish(publication)
                  }
                    .getOrElse {
                      EditorLibraryPublishResult.Refused(
                        "The project library could not be reached: ${it.message ?: it}"
                      )
                    }
                ) {
                  is EditorLibraryPublishResult.Published -> {
                    dispatch(
                      UiBuilderEditorEvent.RecordLibrarySource(
                        publication.componentKey,
                        EditorLibrarySource(
                          publication.system,
                          publication.componentId,
                          result.digest,
                        ),
                      )
                    )
                    onLibraryChanged()
                    say("Published ${publication.title} to the project library")
                  }
                  is EditorLibraryPublishResult.Refused -> say(result.message)
                }
              }
            }
          }
        },
      onUpdateFromLibrary =
        loadLibrarySymbol?.let { load ->
          { componentKey ->
            // The drift row names the symbol, palette id included, as the server spells it.
            val finding = state.componentDrift.firstOrNull { it.componentKey == componentKey }
            if (finding != null) {
              val listed =
                componentLibrary.firstOrNull {
                  it.system == finding.system && it.componentId == finding.componentId
                }
                  ?: EditorLibraryComponent(
                    system = finding.system,
                    componentId = finding.componentId,
                    paletteId = finding.paletteId,
                    title = finding.componentId,
                  )
              editorScope.launch {
                val symbol = runCatchingCancellable { load(listed) }.getOrNull()
                if (symbol == null) say("Could not fetch ${listed.title} from the project library")
                else dispatch(UiBuilderEditorEvent.UpdateLibraryComponent(componentKey, symbol))
              }
            }
          }
        },
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
      referenceWorkbench =
        ReferenceWorkbench(
          facts = referenceFacts,
          findings = referenceFindings,
          findingsCurrent =
            referenceFindings?.let {
              it.documentRevision == state.document.revision &&
                it.referenceId == state.reference.image?.id
            } ?: false,
          measuring = referenceMeasuring,
          selectedNodeId = state.selection.singleOrNull(),
          nodeLabel = { id -> reducer.nodeLabel(state, id) },
          onMeasureDifferences = { measureReference(ReferenceMeasureKind.Differences) },
          onMatchLayer = { measureReference(ReferenceMeasureKind.Layer(it)) },
          onApplyAlignment = { alignment ->
            dispatch(
              UiBuilderEditorEvent.AlignNodeToReference(
                nodeId = alignment.nodeId,
                moveXDp = alignment.moveXDp,
                moveYDp = alignment.moveYDp,
                fontSizeSp = alignment.fontSizeSp,
                widthDp = alignment.widthDp,
                heightDp = alignment.heightDp,
              )
            )
          },
          onSelectNode = { selectNodeForEditing(it) },
          onImportUrl = onImportReferenceUrl,
        ),
      comments = comments,
      commentStatus = commentStatus,
      review = review,
      onDecide = onDecide,
      reviewStatus = reviewStatus,
      suggestionPanel = suggestionPanel,
      selectedThreadId = selectedThreadId,
      onSelectThread = ::selectThread,
      // Read once. The panel scrolls to the thread the URL named as it opens, and never again —
      // a later scroll would be the page fighting somebody who has started reading elsewhere.
      revealThreadId = linkedThreadId,
      onPostComment = onPostComment,
      onResolveCommentThread = onResolveCommentThread,
      // A thread link carries the thread and its pinned layer, but no revision: a discussion is
      // about the living design.
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
      commentNotifications = commentNotifications,
      onToggleCommentNotifications = onToggleCommentNotifications,
      onTextInputFocusChanged = { textInputFocused = it },
      dispatch = ::dispatch,
      modifier = modifier,
      designTokens = remember(state.document) { reducer.designTokenRows(state) },
      themeHost = remember(state.document, state.selectedNodeId) { reducer.themePanelHost(state) },
    )
  }

  // Provided once here rather than at each surface: the canvas, every palette thumbnail and the
  // preview frame all draw a pack component, and all of them should draw its placeholder.
  CompositionLocalProvider(
    LocalHoveredCommentThread provides hoveredThread,
    LocalUiBuilderChrome provides chrome,
    LocalUiBuilderNativeOnly provides catalog.nativeOnlyComponentIds,
    LocalUiBuilderCatalogComponentIds provides catalog.componentsById.keys,
    // The catalog's own scrolling containers, by role, read beside the ids the canvas knew.
    LocalUiBuilderCatalogScrollers provides catalog.scrollers,
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
    // Composed but never shown: captures a component's pixels. Mounted here so a capture survives
    // inspector tab switches, and inside the provider so it uses the canvas's catalog ids.
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

    // The whole design, photographed for a reference measurement or a host-less snapshot. Mounted
    // here for the reason the two captures beside it are: the same catalog locals the canvas has.
    DesignCapture(
      request = designCapture,
      document = state.document.withWearWidgetHostShape(canvasHostShape),
      onCaptured = ::onDesignCaptured,
    )

    // The node a sent comment is about, photographed for the host and then handed over with the
    // comment. A failed photograph still sends the comment: the words are the message.
    NodeCapture(
      request = pendingComment?.first,
      document = state.document,
      onCaptured = { request, image ->
        val comment = pendingComment
        if (comment != null && comment.first == request) {
          pendingComment = null
          onCommentOnNode?.invoke(UiBuilderNodeComment(request.nodeId, comment.second, image))
        }
      },
    )

    EditorTheme(theme) {
      BoxWithConstraints(Modifier.fillMaxSize()) {
        // A host drawing the toolbar and rails has taken the width they cost, and its panes are
        // resized by the person rather than by a phone, so it keeps the desktop layout.
        val compact = !focusedCanvas && maxWidth < 840.dp && hostChrome == null
        SideEffect { canvasIsOnlySurface = compact || focusedCanvas }
        // A host may show rendered output in its own view (e.g. IntelliJ's Preview tool window);
        // that surface has no editor chrome.
        val dedicatedOutput = EditorPane.Editor !in availablePanes
        // Phone browser insets (notch, corners, home indicator, keyboard). The background runs
        // under them; chrome does not.
        val viewportInsets = LocalEditorViewportInsets.current
        Column(
          Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(
              start = viewportInsets.left,
              top = viewportInsets.top,
              end = viewportInsets.right,
              // The compact dock pads itself so its surface reaches the screen's edge; an open
              // keyboard covers the home indicator, so it is the only inset left at the bottom.
              bottom =
                when {
                  viewportInsets.keyboardOpen -> viewportInsets.keyboard
                  compact -> 0.dp
                  else -> viewportInsets.bottom
                },
            )
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
          if (focusedCanvas) {
            // No toolbar: the host's own bar above the editor is the focused layout's chrome.
          } else if (!dedicatedOutput && hostChrome != null) {
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
                visibilityLabel = visibilityLabel,
                onManageVisibility = onManageVisibility,
                agentHost = agentHost,
                onOpenAgentPrompt = { showAgentPrompt = true },
                onNotice = ::say,
                onTakeOffline = onTakeOffline,
                onSyncToServer = onSyncToServer,
                onPublishToServer = onPublishToServer,
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
                visibilityLabel = visibilityLabel,
                onManageVisibility = onManageVisibility,
                agentHost = agentHost,
                onOpenAgentPrompt = { showAgentPrompt = true },
                onNotice = ::say,
                onTakeOffline = onTakeOffline,
                onSyncToServer = onSyncToServer,
                onPublishToServer = onPublishToServer,
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
          // Which of the file's designs this is: under the toolbar on every layout, the compact and
          // the focused canvas included, because which design is open is a question about the page.
          if (!dedicatedOutput && fileDesigns?.shown == true) {
            EditorDesignStrip(fileDesigns, activeTitle = state.document.title)
          }
          // Under the toolbar on both layouts, so a link's notice is never hidden behind a closed
          // dock.
          if (!dedicatedOutput) {
            EditorUrlBanner(
              revisionPin = revisionPin,
              onGoToLatest = onGoToLatest,
              openingNotice = openingNotice,
              transientNotice = transientNotice,
              noticeAction = openingNoticeAction,
            )
          }
          BoxWithConstraints(Modifier.fillMaxSize()) {
            if (dedicatedOutput) {
              Row(Modifier.fillMaxSize()) {
                if (EditorPane.Preview in state.panes) {
                  previewPane(Modifier.weight(1f).fillMaxHeight())
                }
                if (EditorPane.Native in state.panes && nativeAvailable) {
                  nativePane(Modifier.weight(1f).fillMaxHeight())
                }
              }
            } else if (focusedCanvas) {
              // The canvas with the whole panel: no rails, docks, panes or status bar. Selection,
              // the node menu, the quick editor and inline text are all drawn by the canvas itself.
              canvas(
                Modifier.fillMaxSize()
                  .background(LocalUiBuilderEditorPalette.current.workspace)
                  .padding(12.dp),
                Alignment.Center,
              )
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
                  // An old revision replaces the editing surface rather than overlaying it, so
                  // nothing can edit a picture.
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
                  } else if (shownSuggestionRow != null) {
                    // A proposal replaces the editing surface for the reason an old revision
                    // does: nobody has accepted it, so there is nothing yet to edit.
                    val suggestionId = shownSuggestionRow.suggestion.suggestionId
                    SuggestionReviewPane(
                      suggestion = shownSuggestionRow.suggestion,
                      current = state.document,
                      diff = shownSuggestionRow.diff,
                      behind = shownSuggestionRow.behind,
                      onAccept = acceptSuggestion?.let { { it(suggestionId) } },
                      onReject = rejectSuggestion?.let { { it(suggestionId) } },
                      onBack = {
                        focusEditor()
                        shownSuggestionId = null
                      },
                      modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                  } else {
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                      // Enabled panes in enum order, sharing the width equally; none depends on
                      // another.
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
                      // Devices and axes are drawn only by the preview pane.
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
                      // Counts rather than dots, so the rail says how much is waiting.
                      badge =
                        when (entry) {
                          EditorDock.Issues -> problemBadgeCount(problems)
                          EditorDock.Comments -> comments.openThreads.size
                          else -> 0
                        },
                      onClick = {
                        focusEditor()
                        if (
                          entry == EditorDock.Code && hostChrome != null && onGeneratedCode != null
                        ) {
                          onGeneratedCode(
                            reducer.generatedCode(state.document) { assetBytesByDigest[it] }
                          )
                        } else {
                          val mode = entry.inspectorMode()
                          if (mode == null) {
                            dispatch(UiBuilderEditorEvent.ToggleCodePane)
                          } else {
                            if (state.codePaneVisible) {
                              dispatch(UiBuilderEditorEvent.ToggleCodePane)
                            }
                            inspectorOpen = dock != entry
                            if (inspectorOpen) dispatch(UiBuilderEditorEvent.ShowInspector(mode))
                            // The panel and the strip are one control: the same history, two views.
                            if (
                              entry == EditorDock.History &&
                                inspectorOpen != state.historyBarVisible
                            ) {
                              dispatch(UiBuilderEditorEvent.ToggleHistoryBar)
                            }
                          }
                        }
                      },
                    )
                  },
                )
              }
            } else {
              val shownPreview = mobileView?.let { id ->
                mobilePreviewPanes.firstOrNull { it.id == id }
              }
              Column(Modifier.fillMaxSize()) {
                if (!viewportInsets.keyboardOpen) {
                  MobileViewTabs(
                    labels =
                      listOf("Editor") +
                        mobilePreviewPanes.map(UiBuilderVariantPane::mobileTabLabel),
                    selectedIndex = shownPreview?.let { mobilePreviewPanes.indexOf(it) + 1 } ?: 0,
                    onSelected = { index ->
                      mobileView = mobilePreviewPanes.getOrNull(index - 1)?.id
                      // A sheet edits the canvas, which a preview tab is not showing.
                      if (mobileView != null) mobilePanel = MobileEditorPanel.None
                    },
                  )
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                  // Editing sheets keep the canvas visible above them. Components and Layers sheets
                  // do not shrink it, since they carry things to the canvas.
                  val editingSheet =
                    mobilePanel.inspectorMode != null ||
                      (mobilePanel == MobileEditorPanel.Code && generatedCode != null)
                  // This box's height rather than the window's: the sheets are a fraction of this
                  // box, and an open keyboard has already been taken out of it.
                  val editingSheetHeight = maxHeight * MOBILE_EDITING_SHEET_FRACTION
                  // Hidden while the keyboard is up: the sheet being typed into needs the room.
                  val dockHeight =
                    if (viewportInsets.keyboardOpen) 0.dp
                    else MOBILE_DOCK_HEIGHT + viewportInsets.bottom
                  if (shownPreview == null) {
                    canvas(
                      Modifier.fillMaxSize()
                        .background(LocalUiBuilderEditorPalette.current.workspace)
                        .padding(
                          start = 8.dp,
                          top = 8.dp,
                          end = 8.dp,
                          bottom =
                            (if (editingSheet) editingSheetHeight else 0.dp) + dockHeight + 8.dp,
                        ),
                      Alignment.Center,
                    )
                  }
                  // Every device tab once opened stays composed; hidden tabs are laid out at zero
                  // size, drawing nothing and taking no pointer, so switching back is instant.
                  // Drawn instead of the canvas, because a catalog runtime's page layer cannot be
                  // covered by Compose siblings.
                  val keptPreviews = mobilePreviewPanes.filter {
                    it.id in visitedMobilePreviews || it.id == shownPreview?.id
                  }
                  SideEffect {
                    shownPreview?.let {
                      if (it.id !in visitedMobilePreviews) visitedMobilePreviews += it.id
                    }
                  }
                  // After the first device tab has drawn, build the others behind it one per frame;
                  // never before.
                  LaunchedEffect(shownPreview != null) {
                    if (shownPreview == null) return@LaunchedEffect
                    delay(MOBILE_PREVIEW_WARM_DELAY_MS)
                    for (pane in mobilePreviewPanes) {
                      if (pane.id !in visitedMobilePreviews) {
                        visitedMobilePreviews += pane.id
                        withFrameNanos {}
                      }
                    }
                  }
                  keptPreviews.forEach { pane ->
                    key(pane.id) {
                      mobilePreview(
                        pane,
                        if (pane.id == shownPreview?.id) {
                          Modifier.fillMaxSize().padding(bottom = dockHeight)
                        } else {
                          // Out of the semantics tree as well as off the screen: a screen reader
                          // would otherwise read every kept tab's frame as if it were showing.
                          Modifier.size(0.dp).clearAndSetSemantics {}
                        },
                      )
                    }
                  }
                  val mobileNavigatorTab =
                    when (mobilePanel) {
                      MobileEditorPanel.Components -> NavigatorTab.Insert
                      MobileEditorPanel.Layers -> NavigatorTab.Layers
                      else -> null
                    }
                  mobileNavigatorTab?.let { open ->
                    // While carrying a component the sheet fades aside; not removed, since the drag
                    // lives in its tile.
                    val carrying = draggedComponentId != null
                    navigator(
                      Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.72f)
                        .padding(bottom = dockHeight)
                        .graphicsLayer { alpha = if (carrying) 0f else 1f },
                      open,
                      true,
                    ) {
                      mobilePanel = MobileEditorPanel.None
                    }
                  }
                  if (mobilePanel.inspectorMode != null) {
                    inspector(
                      Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(MOBILE_EDITING_SHEET_FRACTION)
                        .padding(bottom = dockHeight),
                      // The compact layout has no preview pane, so no devices or axes are drawn.
                      false,
                    )
                  }
                  if (mobilePanel == MobileEditorPanel.Code && generatedCode != null) {
                    GeneratedCodePane(
                      generatedCode,
                      generatedCodeCaption,
                      Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(MOBILE_EDITING_SHEET_FRACTION)
                        .padding(bottom = dockHeight),
                    )
                  }
                  if (!viewportInsets.keyboardOpen) {
                    // The dock names the panel the sheet shows, which other things (a `#thread=`
                    // link) can change.
                    val dockPanel =
                      if (mobilePanel.inspectorMode != null) {
                        MobileEditorPanel.of(state.inspectorMode)
                      } else {
                        mobilePanel
                      }
                    MobilePanelDock(
                      panel = dockPanel,
                      issueCount = problemBadgeCount(problems),
                      talkCount = comments.openThreads.size,
                      onPanelChanged = {
                        // Every panel edits or feeds the canvas, so opening one goes back to it.
                        val opened =
                          if (shownPreview != null || dockPanel != it) it
                          else MobileEditorPanel.None
                        mobileView = null
                        opened.inspectorMode?.let { mode ->
                          dispatch(UiBuilderEditorEvent.ShowInspector(mode))
                        }
                        mobilePanel = opened
                      },
                      modifier = Modifier.align(Alignment.BottomCenter),
                      bottomInset = viewportInsets.bottom,
                    )
                  }
                }
              }
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
            onCreatePublic = onCreatePublicDesign,
          )
        }
      }
    }
  }
}
