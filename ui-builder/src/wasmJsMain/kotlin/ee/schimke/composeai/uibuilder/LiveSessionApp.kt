@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.ui.ExperimentalComposeUiApi::class,
  kotlin.js.ExperimentalWasmJsInterop::class,
)

package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.canvas.UiBuilderDevicePreset
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.client.BrowserUiBuilderHttpTransport
import ee.schimke.composeai.uibuilder.client.BrowserUiBuilderSocketState
import ee.schimke.composeai.uibuilder.client.BrowserUiBuilderWebSocketTransport
import ee.schimke.composeai.uibuilder.client.MonotonicUiBuilderRequestIds
import ee.schimke.composeai.uibuilder.client.SnapshotDisposition
import ee.schimke.composeai.uibuilder.client.UiBuilderClientUpdate
import ee.schimke.composeai.uibuilder.client.UiBuilderHttpResult
import ee.schimke.composeai.uibuilder.client.UiBuilderLiveSessionApi
import ee.schimke.composeai.uibuilder.client.UiBuilderLiveSessionSync
import ee.schimke.composeai.uibuilder.client.UiBuilderProtocolHttpClient
import ee.schimke.composeai.uibuilder.client.UiBuilderProtocolUpdateClient
import ee.schimke.composeai.uibuilder.client.preparePropertyDelta
import ee.schimke.composeai.uibuilder.client.toProtocolSubmission
import ee.schimke.composeai.uibuilder.client.toRendererDocument
import ee.schimke.composeai.uibuilder.editor.DesignCommentBoard
import ee.schimke.composeai.uibuilder.editor.EditorInspectorMode
import ee.schimke.composeai.uibuilder.editor.EditorSubmission
import ee.schimke.composeai.uibuilder.editor.UI_BUILDER_PRESENCE_HEARTBEAT_MILLIS
import ee.schimke.composeai.uibuilder.editor.UiBuilderCatalogRecoveryUi
import ee.schimke.composeai.uibuilder.editor.UiBuilderCollaborator
import ee.schimke.composeai.uibuilder.editor.UiBuilderDocumentPreview
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderExportHost
import ee.schimke.composeai.uibuilder.editor.UiBuilderHomeDesign
import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeRender
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignCatalog
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignScreen
import ee.schimke.composeai.uibuilder.editor.UiBuilderPresenceState
import ee.schimke.composeai.uibuilder.editor.UiBuilderUnavailableScreen
import ee.schimke.composeai.uibuilder.editor.catalogRecoveryCommand
import ee.schimke.composeai.uibuilder.editor.exportFormatsFor
import ee.schimke.composeai.uibuilder.export.NewDesignNames
import ee.schimke.composeai.uibuilder.export.NewDesignState
import ee.schimke.composeai.uibuilder.export.RemoteDocumentExportSupport
import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.encodeNewDesignStates
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.inspector.UiBuilderPageDestination
import ee.schimke.composeai.uibuilder.local.LocalDesignSyncBack
import ee.schimke.composeai.uibuilder.local.LocalUiBuilderHttpTransport
import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradePreviewStatusV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradePreviewV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignsResponseV1
import ee.schimke.composeai.uibuilder.protocol.GetSnapshotRequestV1
import ee.schimke.composeai.uibuilder.protocol.ListDesignsRequestV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.OperationOutcomeResponseV1
import ee.schimke.composeai.uibuilder.protocol.PresenceV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import ee.schimke.composeai.uibuilder.protocol.UpdatePresenceRequestV1
import ee.schimke.composeai.uibuilder.reference.ReferenceImage
import ee.schimke.composeai.uibuilder.reference.ReferenceImportOutcome
import ee.schimke.composeai.uibuilder.reference.ReferenceOverlayState
import ee.schimke.composeai.uibuilder.reference.RestoredReference
import kotlin.io.encoding.Base64
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.Image

/**
 * Resolves the server's view of this caller before the session starts.
 *
 * The page cannot name its own actor: the server derives it from the operator token, the GitHub
 * session or a presented agent grant, and rejects any request whose declared actor differs. So the
 * editor asks first, and only falls back to the historical guess when the endpoint is unreachable
 * (an older server, or a static host with no live session behind it).
 */
@Composable
internal fun LiveSessionApp() {
  var config by remember { mutableStateOf<LiveSessionConfig?>(null) }
  var failure by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(Unit) {
    bootPhase("Checking who you are")
    try {
      config = liveSessionConfig(resolveServerActorId())
    } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
      throw cancelled
    } catch (thrown: Throwable) {
      // A failure here used to be a blank page: `config` stays null and nothing below draws. The
      // one that found this was `crypto.randomUUID` missing on an insecure origin; the next one
      // should be a sentence on the page rather than an empty tab.
      failure = thrown.message ?: thrown.toString()
    }
  }
  failure?.let { message ->
    LaunchedEffect(Unit) { dismissBootScreen() }
    Column(
      Modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.Start,
    ) {
      Text("The editor could not start", style = MaterialTheme.typography.titleMedium)
      Text(message, style = MaterialTheme.typography.bodySmall)
    }
    return
  }
  // The local mode is the one that can open a design without a navigation: it has just written the
  // design to this browser, so re-entering the editor with a new config is the whole of "open it".
  // A server design still goes through the New design form, whose `303` is the navigation.
  config?.let { current -> LiveSessionApp(current) { config = it } }
}

@Composable
private fun LiveSessionApp(
  config: LiveSessionConfig,
  onOpenDesign: (LiveSessionConfig) -> Unit = {},
) {
  val scope = rememberCoroutineScope()
  // The store, the catalogs it falls back on, and the service that answers this page's own
  // requests from them. Null in the ordinary server-backed session, where all three are the
  // server's.
  val localSession =
    remember(config) { if (config.localStorage) BrowserLocalSession(config) else null }
  val http =
    remember(config, localSession) {
      UiBuilderProtocolHttpClient(
        actorId = config.actorId,
        endpoint = config.httpEndpoint,
        transport =
          localSession?.let { LocalUiBuilderHttpTransport(it.service) }
            ?: BrowserUiBuilderHttpTransport(),
        requestIds = MonotonicUiBuilderRequestIds(config.clientId),
      )
    }
  var document by remember { mutableStateOf<UiBuilderDocument?>(null) }
  var authoritativeDocument by remember { mutableStateOf<DesignDocumentV1?>(null) }
  // The durable cursor the server last answered with, which is half of a fork point: a design taken
  // offline has to record *which* revision of *which* history it forked from, and the sequence is
  // what says where in that history it was.
  var authoritativeSequence by remember { mutableStateOf(0L) }
  var catalog by remember { mutableStateOf<CapabilityCatalog?>(null) }
  var newDesignCatalogs by remember { mutableStateOf<List<UiBuilderNewDesignCatalog>>(emptyList()) }
  // The chooser's answer is a form-factor summary; seeding a design locally needs the catalog's
  // own revision and runtime id, which only the capability document carries.
  var catalogCapabilities by remember { mutableStateOf<List<CatalogCapabilityV1>>(emptyList()) }
  var devicePresets by remember { mutableStateOf<List<UiBuilderDevicePreset>>(emptyList()) }
  var pageDestinations by remember { mutableStateOf<List<UiBuilderPageDestination>>(emptyList()) }
  var sessionStatus by remember { mutableStateOf("Connecting…") }
  // Why the design could not be opened, or null while it still might. Distinct from [sessionStatus]
  // because that string is only ever read from inside the editor, which is exactly the branch a
  // refused open never reaches — so the reason needs somewhere to live that the failure path can
  // draw. Null is "not settled yet" and must stay that way: it is what keeps the first moments of
  // a normal load from rendering as a failure.
  var openFailure by remember { mutableStateOf<ServiceErrorV1?>(null) }
  var catalogRecovery by
    remember(config.designId) { mutableStateOf<CatalogUpgradePreviewV1?>(null) }
  var catalogRecoveryLoading by remember(config.designId) { mutableStateOf(false) }
  var catalogRecoveryAttempted by remember(config.designId) { mutableStateOf(false) }
  var catalogRecoveryApplying by remember(config.designId) { mutableStateOf(false) }
  var catalogRecoveryError by remember(config.designId) { mutableStateOf<String?>(null) }
  var updates by remember { mutableStateOf<UiBuilderProtocolUpdateClient?>(null) }
  var authoritativeGeneration by remember { mutableStateOf(0) }
  val inspectionPublisher = remember(scope) { CoalescingInspectionPublisher(scope) }
  var selectedNodeId by remember { mutableStateOf(config.selectors.nodeId) }
  // What `?revision=` did, once the answer is known: null until the design has been asked for, and
  // afterwards both the revision the link named and whether it could be shown.
  var revisionPin by remember(config.designId) { mutableStateOf<DesignRevisionPin?>(null) }
  // Which panel is showing and which conversation is open in it. Held here only so the page can
  // publish what the URL's selectors actually did — the editor owns both, and neither is
  // authoritative state this host reads back.
  var inspectorMode by remember(config.designId) { mutableStateOf(EditorInspectorMode.Properties) }
  var openThreadId by remember(config.designId) { mutableStateOf(config.selectors.threadId) }
  // The thread the *address bar* names right now, which is not the same as the one parsed at
  // startup: a fragment-only navigation never re-enters this app. Held as state so the editor can
  // follow it, and re-read from `location` on every change rather than from the event, so Back and
  // Forward are answered by the same path as a link press.
  var linkedThreadId by remember(config.designId) { mutableStateOf(config.selectors.threadId) }
  // How many times the *browser* has changed the fragment, which is a different question from what
  // the fragment now says. This host clears [linkedThreadId] itself when the reader opens another
  // thread — the address bar has to stop naming the old one — and the editor must not read that
  // housekeeping as a navigation and undo the selection that caused it. Only the loop below counts,
  // and `replaceState` fires no `hashchange`, so nothing this host does can reach it.
  var threadNavigations by remember(config.designId) { mutableStateOf(0) }
  // What this account may open, asked once and read by two very different things: the page
  // destinations a design's own navigation offers (this catalog only — a page cannot navigate to a
  // watch face) and the home screen's list of everything, which is not catalog-scoped because the
  // question there is "what was I working on", not "what can this screen link to".
  var homeDesigns by remember { mutableStateOf(emptyList<UiBuilderHomeDesign>()) }
  // A live host owns shared folders beside its design state. Browser storage remains the fallback
  // for an older host and for offline/local work; neither form changes a document revision.
  var homeFolders by remember { mutableStateOf(readHomeFolders()) }
  var serverFoldersAvailable by remember { mutableStateOf(false) }
  val homeFolderHost = remember { BrowserHomeFolderHost() }
  LaunchedEffect(http, config.catalogSystemId) {
    runCatching { homeFolderHost.load() }
      .getOrNull()
      ?.let {
        homeFolders = it
        serverFoldersAvailable = true
      }
    val result = http.execute(ListDesignsRequestV1(cursor = null, limit = 200))
    val listed =
      ((result as? UiBuilderHttpResult.Response)?.response as? DesignsResponseV1)?.designs.orEmpty()
    pageDestinations =
      listed
        .filter { it.catalogPin.systemId == config.catalogSystemId }
        .map { UiBuilderPageDestination(it.designId, it.title) }
    homeDesigns =
      listed
        .sortedByDescending { it.updatedAtEpochMillis ?: 0L }
        .map {
          UiBuilderHomeDesign(
            designId = it.designId,
            title = it.title,
            catalogSystemId = it.catalogPin.systemId,
            folder = homeFolders[it.designId],
            updatedLabel =
              it.updatedAtEpochMillis
                ?.let { at -> "updated ${formatLocalDateTime(at.toDouble())}" }
                .orEmpty(),
          )
        }
  }
  LaunchedEffect(config.designId) {
    while (true) {
      val hash = awaitHashChange()
      linkedThreadId = parseDesignUrlSelectors(null, hash).threadId
      threadNavigations += 1
    }
  }
  var catalogQuery by remember { mutableStateOf("") }
  var activeCatalogSystemId by remember { mutableStateOf(config.catalogSystemId) }
  // Which component packs are on, remembered per catalog in this browser. A setting rather than
  // document state: the same design opened by a collaborator shows their palette, not yours.
  var enabledPacks by
    remember(config.catalogSystemId) { mutableStateOf(readEnabledPacks(config.catalogSystemId)) }
  // Which components the reader pinned to the top of the palette, remembered per catalog in this
  // browser, like the pack switches. Null is "never said", which is what lets the catalog's own
  // defaults keep reaching a reader who has not customised anything.
  var pinnedComponents by
    remember(config.catalogSystemId) {
      mutableStateOf(readPinnedComponents(config.catalogSystemId))
    }
  var presenceState by remember { mutableStateOf(UiBuilderPresenceState()) }
  var socketState by remember { mutableStateOf(BrowserUiBuilderSocketState.CONNECTING) }
  // Which snapshot may be shown, and which revision the next command claims as its base. See
  // [UiBuilderLiveSessionSync]: without it a burst of edits raced its own round trips and the
  // canvas dropped most of them.
  val sync = remember(config.designId) { UiBuilderLiveSessionSync() }
  // The newest snapshot held back while the operator's own edits are still queued, displayed once
  // the queue drains.
  var heldSnapshot by remember(config.designId) { mutableStateOf<SnapshotResponseV1?>(null) }
  // Edits wait here rather than each opening its own request. Unbounded because dropping one would
  // lose an edit the canvas is already showing; a burst is twenty, not twenty thousand.
  val submissions = remember(config.designId) { Channel<EditorSubmission>(Channel.UNLIMITED) }
  DisposableEffect(submissions) { onDispose { submissions.close() } }

  fun displaySnapshot(response: SnapshotResponseV1) {
    authoritativeSequence = response.snapshot.state.lastSequence
    recordAuthoritativeReceipt(
      response.snapshot.state.document.revision.toInt(),
      response.snapshot.state.lastSequence,
    )
    authoritativeDocument = response.snapshot.state.document
    document = authoritativeDocument?.toRendererDocument()
    presenceState = presenceState.replace(response.snapshot.presence, browserNowMillis())
    authoritativeGeneration += 1
    sessionStatus =
      "$activeCatalogSystemId · ${sessionModeLabel(config, localSession)} · ${config.actorId}/${config.clientId} · seq ${response.snapshot.state.lastSequence}"
  }

  fun acceptSnapshot(response: SnapshotResponseV1) {
    when (
      sync.receiveSnapshot(
        sequence = response.snapshot.state.lastSequence,
        revision = response.snapshot.state.document.revision.toInt(),
      )
    ) {
      SnapshotDisposition.DISPLAY -> {
        heldSnapshot = null
        displaySnapshot(response)
      }
      // Presence still moves on, because who else is here is not part of the document and holding
      // it back would blank the collaborator cursors for the length of the burst.
      SnapshotDisposition.DEFER -> {
        heldSnapshot = response
        presenceState = presenceState.replace(response.snapshot.presence, browserNowMillis())
      }
      SnapshotDisposition.STALE -> Unit
    }
  }

  suspend fun syncSnapshot(reason: String) {
    sessionStatus = reason
    when (val result = http.execute(OpenDesignRequestV1(config.designId))) {
      is UiBuilderHttpResult.Response -> {
        val response = result.response as? SnapshotResponseV1
        if (response == null) sessionStatus = "Live error · unexpected snapshot response"
        else acceptSnapshot(response)
      }
      is UiBuilderHttpResult.ServiceError -> sessionStatus = "Live error · ${result.error.message}"
      is UiBuilderHttpResult.SnapshotRequired ->
        sessionStatus = "Snapshot required · ${result.error.message}"
    }
  }

  fun refreshSnapshot(reason: String) {
    scope.launch { syncSnapshot(reason) }
  }

  /**
   * Drains the edit queue one submission at a time.
   *
   * Serialized on purpose. Each command claims the revision the *previous* one produced, which is
   * what keeps its insertion anchor — the node that command added — resolvable at the base it
   * names; and one request at a time cannot be overtaken by the next.
   */
  LaunchedEffect(config.designId, http) {
    for (submission in submissions) {
      try {
        val expectedRevision = sync.baseRevision
        if (expectedRevision == null) {
          // Unreachable in practice — the editor is not composed until the first snapshot has
          // landed — and it says so rather than dropping the edit silently if it ever is.
          sessionStatus = "Live error · no authoritative revision to edit from"
        } else {
          sessionStatus = "Saving revision $expectedRevision…"
          val request =
            ApplyOperationRequestV1(
              submission.toProtocolSubmission(
                actorId = config.actorId,
                clientId = config.clientId,
                authoritativeRevision = expectedRevision,
              )
            )
          when (val result = http.execute(request)) {
            is UiBuilderHttpResult.Response -> {
              val response = result.response as? OperationOutcomeResponseV1
              sessionStatus =
                if (response == null) "Live error · unexpected operation response"
                else "Accepted · syncing authoritative revision…"
              syncSnapshot(sessionStatus)
            }
            is UiBuilderHttpResult.ServiceError -> {
              sessionStatus = "Rejected · ${result.error.message}"
              syncSnapshot(sessionStatus)
            }
            is UiBuilderHttpResult.SnapshotRequired ->
              syncSnapshot("Snapshot recovery · ${result.error.message}")
          }
        }
      } finally {
        sync.completeSubmission()
      }
      if (sync.releaseDeferredSnapshot()) {
        heldSnapshot?.let { held ->
          heldSnapshot = null
          displaySnapshot(held)
        }
      }
    }
  }

  // Remembered in the browser in local mode, so the Screen inspector still offers device frames on
  // a reload with nothing behind it. The presets are a static file the server derives from a
  // JVM-only catalog, which is why they cross the wire at all.
  LaunchedEffect(localSession) { devicePresets = loadDevicePresets(localSession?.text) }

  // Asked once per design rather than per revision: the answer is about another host's library,
  // not about this document, and re-asking on every keystroke would put an HTTP round trip behind
  // the editing loop for a fact that changes when somebody merges to the project — not when
  // somebody types. The findings that a later edit invalidates are dropped by the reducer.
  var componentDrift by
    remember(config.designId) { mutableStateOf(emptyList<ComponentDriftFinding>()) }
  // Re-asked when the revision on screen changes, because the answer is about the components *that*
  // revision imported. Not re-asked per edit: the library moves when somebody merges to the
  // project, not when somebody types, and a round trip behind every keystroke would buy nothing.
  val pinnedRevision = revisionPin?.takeIf { it.pinned }?.requested
  LaunchedEffect(config.designId, pinnedRevision) {
    componentDrift = loadComponentDrift(config.designId, pinnedRevision)
  }

  // The reference overlay's browser half: the file picker, the paste listener, the snapshot and
  // the store behind them all. Rebuilt only when the design changes, because it is addressed to
  // one design.
  val references = remember(config.designId) { BrowserReferenceHost(config.designId, http) }
  // Built once the catalog is known, because what the menu offers is what the catalog's renderer
  // can draw; null until then, which is a toolbar without an Export button rather than one that
  // promises formats it has not checked.
  var latestEditorDocument by remember(config.designId) { mutableStateOf<UiBuilderDocument?>(null) }
  var documentPreviewAvailable by remember(config.designId) { mutableStateOf(false) }
  var exportHost by remember(config.designId) { mutableStateOf<UiBuilderExportHost?>(null) }
  var restoredReference by remember(config.designId) { mutableStateOf<RestoredReference?>(null) }
  var referenceStatus by remember(config.designId) { mutableStateOf<String?>(null) }
  // What was last written, so a settings drag can take the cheap route and a new picture cannot.
  var storedReference by remember(config.designId) { mutableStateOf<ReferenceOverlayState?>(null) }
  var pendingReference by remember(config.designId) { mutableStateOf<ReferenceOverlayState?>(null) }
  var pastedReference by remember(config.designId) { mutableStateOf<ReferenceImage?>(null) }

  // Nothing is persisted until this has finished. Without the gate, the editor's own empty
  // starting state reaches `onStateChanged` before the stored one arrives and is written over it —
  // which would delete a design's reference by opening the design.
  var referenceLoaded by remember(config.designId) { mutableStateOf(false) }
  LaunchedEffect(config.designId, config.localStorage) {
    // A reference picture is a design asset the server stores, and a local design has no server to
    // store one with. Pictures are also the one thing that would not fit: `localStorage` holds a
    // few megabytes for this whole origin, and a pasted screenshot is most of that on its own.
    if (config.localStorage) {
      referenceStatus = LOCAL_REFERENCE_UNAVAILABLE
      return@LaunchedEffect
    }
    installReferenceBridge()
    restoredReference = references.load()
    storedReference =
      restoredReference?.let {
        ReferenceOverlayState(
          image = it.image,
          settings = it.settings,
          pieces = it.pieces,
          marks = it.marks,
        )
      }
        // An untouched editor over a design that stored nothing is not a change, so the baseline
        // is the empty state rather than null: opening such a design sends no request at all.
        ?: ReferenceOverlayState()
    referenceLoaded = true
  }

  // The comments panel's browser half: the REST calls and the feed the panel watches. Rebuilt only
  // when the design changes, because a discussion is addressed to one design.
  val commentHost = remember(config.designId) { BrowserCommentHost(config.designId) }
  var commentBoard by remember(config.designId) { mutableStateOf(DesignCommentBoard()) }
  // Whether the discussion has been delivered at all, which is not the same question as whether it
  // has anything in it. A design nobody has commented on answers with an empty board, so an empty
  // board cannot be read as "the thread this link names is gone" until something has arrived.
  var commentBoardLoaded by remember(config.designId) { mutableStateOf(false) }
  var commentStatus by remember(config.designId) { mutableStateOf<String?>(null) }

  // One socket for the life of the design. It sends the current board on connect, so there is no
  // fetch beside it to reconcile against — the load below is only the fallback for a host that
  // refuses the upgrade, where the panel is then a snapshot rather than a feed.
  DisposableEffect(config.designId, config.localStorage) {
    // A discussion is a thing several people have, and a design only this browser holds has nobody
    // to have it with. The panel stays, empty, saying so.
    if (config.localStorage) {
      commentStatus = LOCAL_COMMENTS_UNAVAILABLE
      return@DisposableEffect onDispose {}
    }
    val watch =
      commentHost.watch(
        onBoard = { board ->
          commentBoard = board
          commentBoardLoaded = true
          commentStatus = null
        },
        onDropped = {
          commentStatus = "The comment feed dropped. Reload to watch this discussion again."
        },
      )
    onDispose { watch.close() }
  }
  LaunchedEffect(config.designId, config.localStorage) {
    if (config.localStorage) return@LaunchedEffect
    commentHost.load()?.let { board ->
      // Only if the socket has not already delivered something newer: the two race by design and
      // the sequence is what settles it, rather than whichever answer happened to arrive last.
      if (board.sequence > commentBoard.sequence) commentBoard = board
      commentBoardLoaded = true
    }
  }

  // One paste listener for the life of the design, re-armed after every catch. Pasting is the
  // gesture Figma's own "copy as PNG" leaves you holding, so it goes straight to the base picture
  // rather than behind a menu.
  LaunchedEffect(config.designId, config.localStorage) {
    if (config.localStorage) return@LaunchedEffect
    while (true) {
      when (val outcome = references.awaitPaste()) {
        is ReferenceImportOutcome.Imported -> {
          pastedReference = outcome.image
          referenceStatus = null
        }
        is ReferenceImportOutcome.Refused -> referenceStatus = outcome.reason
        ReferenceImportOutcome.Cancelled -> Unit
      }
    }
  }

  // Debounced, because the overlay's settings change once per pointer sample while a slider is
  // dragged and each of those would otherwise be a request. The picture itself is only re-sent
  // when it actually changed; see `BrowserReferenceHost.save`.
  LaunchedEffect(pendingReference) {
    if (config.localStorage) return@LaunchedEffect
    val candidate = pendingReference ?: return@LaunchedEffect
    delay(REFERENCE_SAVE_DEBOUNCE_MILLIS)
    val imagesChanged =
      storedReference?.image?.id != candidate.image?.id ||
        storedReference?.pieces?.map { it.image.id } != candidate.pieces.map { it.image.id }
    referenceStatus = references.save(candidate, imagesChanged)
    if (referenceStatus == null) storedReference = candidate
  }

  LaunchedEffect(config) {
    bootPhase("Opening the design")
    // Form-factor order — Mobile, Wear, RemoteCompose — however the host lists them: the chooser
    // is a "what am I making" question, not a catalog registry.
    fun installCatalogList(availableCatalogs: List<CatalogCapabilityV1>) {
      catalogCapabilities = availableCatalogs
      newDesignCatalogs =
        availableCatalogs.mapNotNull(::newDesignCatalog).sortedBy {
          NEW_DESIGN_CATALOG_ORDER.indexOf(it.systemId)
        }
    }
    if (config.localStorage || config.startWithNewDesign) {
      // A local session can be asked for a catalog this browser has never seen — the first visit
      // to an origin with the network already gone. That is a sentence, not a crash: the live
      // session has a server behind it and can let the failure take the page down, and a local one
      // has to explain itself because there is nothing else left to ask.
      val availableCatalogs =
        if (!config.localStorage) loadLiveCatalogs(http)
        else
          try {
            loadLiveCatalogs(http)
          } catch (failure: Exception) {
            sessionStatus = "Local · ${failure.message ?: "no catalog is available offline"}"
            markReady()
            return@LaunchedEffect
          }
      installCatalogList(availableCatalogs)
      if (config.startWithNewDesign) return@LaunchedEffect
    } else {
      // Beside the open, not ahead of it. The list is every catalog's full capability record —
      // around a megabyte for the three — and only the New design menu reads it; the design being
      // opened brings its own catalog in its snapshot. Waiting for it put a round trip and that
      // megabyte's parse between a person and their design. A failure still takes the page down,
      // as it did when it came first: this child's exception cancels the effect.
      launch { installCatalogList(loadLiveCatalogs(http)) }
    }
    fun installCatalog(capability: CatalogCapabilityV1, revision: Long?) {
      activeCatalogSystemId = capability.benchmark.catalogSystemId
      enabledPacks = readEnabledPacks(activeCatalogSystemId)
      pinnedComponents = readPinnedComponents(activeCatalogSystemId)
      catalog =
        CapabilityCatalogParser.parse(
          Json.encodeToJsonElement(CatalogCapabilityV1.serializer(), capability)
        )
      documentPreviewAvailable =
        RemoteDocumentExportSupport.documentFormat?.let {
          RemoteDocumentExportSupport.supports(capability.exportCapabilities, it)
        } == true
      // PNG and Remote document exports can submit current drafts without saving. SVG still
      // requires a saved design.
      //
      // And where there is one, it is pinned to the same revision the canvas is drawing, because
      // the export routes render on request: without it the Export menu would answer a question
      // about history with the picture of the head, which is the one thing the banner promises it
      // is not showing.
      exportHost =
        BrowserExportHost(
          designId = config.designId,
          supportsLinks = !config.localStorage,
          suppliedDocument = {
            val current = latestEditorDocument ?: document
            current?.takeIf {
              UiBuilderBuildFeatures.remoteCompose &&
                (config.localStorage ||
                  (revision == null && authoritativeDocument?.toUiBuilderDocument() != it))
            }
          },
          formats =
            exportFormatsFor(
              svg = !config.localStorage && capability.exportCapabilities.svg,
              png =
                capability.exportCapabilities.png &&
                  (!config.localStorage || UiBuilderBuildFeatures.remoteCompose),
              json =
                RemoteDocumentExportSupport.jsonFormat?.let {
                  RemoteDocumentExportSupport.supports(capability.exportCapabilities, it)
                } == true,
              rc =
                RemoteDocumentExportSupport.documentFormat?.let {
                  RemoteDocumentExportSupport.supports(capability.exportCapabilities, it)
                } == true,
            ),
          revision = revision,
        )
    }
    // `?revision=` first, because a design pinned to a committed revision is a different opening:
    // one snapshot, no socket, no presence. A revision the service will not answer for — trimmed
    // out of the retained window, or one this design never reached — is *not* a failure to open.
    // The link is stale, the design is not, so the editor falls through to the live design and the
    // banner says which of the two happened. See [DesignRevisionPin].
    config.selectors.revision?.let { requested ->
      val pinned =
        (http.execute(GetSnapshotRequestV1(designId = config.designId, revision = requested))
            as? UiBuilderHttpResult.Response)
          ?.response as? SnapshotResponseV1
      revisionPin = DesignRevisionPin(requested = requested, pinned = pinned != null)
      if (pinned != null) {
        // The catalog the *pinned* document was resolved against, not the one the catalog list
        // offers today. A design whose catalog pin moved between revisions is validated and drawn
        // with the capabilities it actually had, which is the whole claim a historical view makes.
        installCatalog(pinned.snapshot.catalog, requested)
        // Presence belongs to the living design. This page holds no socket and never will, so the
        // avatars and selection outlines the snapshot happens to carry are other people editing a
        // document this canvas is not showing — drawn over history for as long as they take to
        // expire, and pointing at node ids from the head revision.
        acceptSnapshot(pinned.copy(snapshot = pinned.snapshot.copy(presence = emptyList())))
        canonicalizeUiBuilderUrl(config.designId, config.selectors)
        sessionStatus = "Revision $requested · read-only"
        markReady()
        return@LaunchedEffect
      }
    }
    val openResult = UiBuilderLiveSessionApi(config.designId, http).open()
    when (val result = openResult) {
      is UiBuilderHttpResult.Response -> {
        val response = result.response as? SnapshotResponseV1
        if (response == null) {
          // Same dead end as a refused open, and it used to end on the same white page: the
          // service answered with something that is not a snapshot, so there is no document to
          // draw and no service error to quote either. Synthesised as INTERNAL because that is
          // what it is — this host's fault, not the caller's.
          sessionStatus = "Live error · unexpected open response"
          openFailure =
            ServiceErrorV1(ServiceErrorCodeV1.INTERNAL, "the design could not be opened")
          return@LaunchedEffect
        }
        bootPhase("Drawing the design")
        installCatalog(response.snapshot.catalog, null)
        acceptSnapshot(response)
        // Without the revision: the page opened at head, whatever the link asked for, and an
        // address bar still naming an unavailable revision would be the one lie the banner is
        // there to prevent.
        canonicalizeUiBuilderUrl(config.designId, config.selectors.copy(revision = null))
        // A local design has no updates to subscribe to: the only writer is this page, and it has
        // already seen everything it wrote. Reported as connected because that is what it is —
        // the service is one call away — rather than leaving the editor offering a reconnect for a
        // socket that was never going to exist.
        if (config.localStorage) {
          socketState = BrowserUiBuilderSocketState.CONNECTED
          return@LaunchedEffect
        }
        val client =
          UiBuilderProtocolUpdateClient(
            designId = config.designId,
            endpoint = config.webSocketEndpoint,
            initialAfterSequence = response.snapshot.state.lastSequence,
            transport = BrowserUiBuilderWebSocketTransport { socketState = it },
          ) { update ->
            when (update) {
              is UiBuilderClientUpdate.Snapshot -> {
                recordProtocolReceipt(
                  kind = "snapshot",
                  revision = update.update.snapshot.state.document.revision.toInt(),
                  sequence = update.update.snapshot.state.lastSequence,
                )
                recordAuthoritativeReceipt(
                  update.update.snapshot.state.document.revision.toInt(),
                  update.update.snapshot.state.lastSequence,
                )
                authoritativeDocument = update.update.snapshot.state.document
                document = authoritativeDocument?.toRendererDocument()
                presenceState =
                  presenceState.replace(
                    update.update.snapshot.presence,
                    browserNowMillis(),
                  )
                authoritativeGeneration += 1
                sessionStatus =
                  "Live · ${config.actorId}/${config.clientId} · seq ${update.update.snapshot.state.lastSequence}"
              }
              is UiBuilderClientUpdate.Delta -> {
                val revision =
                  update.update.delta.operations.lastOrNull()?.outcome?.committedRevision?.toInt()
                    ?: -1
                recordProtocolReceipt(
                  kind = "delta",
                  revision = revision,
                  sequence = update.update.delta.throughSequence,
                )
                val projectionStartedAt = monotonicNow()
                // Not while the operator's own edits are still queued: projecting a collaborator's
                // property write onto the displayed document would rebuild the editor from a
                // document those edits are not in yet. The fetch below answers with a snapshot,
                // which the deferral holds until the queue drains.
                val candidate =
                  if (sync.pendingSubmissions > 0) null
                  else
                    document?.let { rendererDocument ->
                      authoritativeDocument?.preparePropertyDelta(
                        rendererDocument = rendererDocument,
                        delta = update.update.delta,
                      )
                    }
                recordPerformancePhase(
                  name = "propertyDeltaProjection",
                  revision = revision,
                  startedAtMs = projectionStartedAt,
                  completedAtMs = monotonicNow(),
                )
                val hashStartedAt = monotonicNow()
                val projected = candidate?.takeIf { it.hasVerifiedHash() }
                val verified = projected != null
                recordPerformancePhase(
                  name = "propertyDeltaHash",
                  revision = revision,
                  startedAtMs = hashStartedAt,
                  completedAtMs = monotonicNow(),
                )
                recordPerformancePhase(
                  name =
                    if (verified) "verifiedPropertyDeltaAccepted"
                    else "verifiedPropertyDeltaFallback",
                  revision = revision,
                  startedAtMs = projectionStartedAt,
                  completedAtMs = monotonicNow(),
                )
                // The projected document is only displayed when the session agrees it is the
                // newest thing seen — a snapshot fetch racing this delta can already have carried
                // the design past it, and drawing the delta then would be a rollback.
                val disposition = projected?.let {
                  sync.receiveSnapshot(
                    sequence = update.update.delta.throughSequence,
                    revision = it.rendererDocument.revision,
                  )
                }
                if (projected != null && disposition == SnapshotDisposition.DISPLAY) {
                  recordAuthoritativeReceipt(
                    projected.rendererDocument.revision,
                    update.update.delta.throughSequence,
                  )
                  heldSnapshot = null
                  authoritativeDocument = projected.protocolDocument
                  document = projected.rendererDocument
                  authoritativeGeneration += 1
                  sessionStatus =
                    "Live · ${config.actorId} · seq ${update.update.delta.throughSequence}"
                } else {
                  // Unverified, held back behind the operator's own queue, or already overtaken:
                  // ask for a snapshot rather than guessing, and let the deferral decide when it
                  // may be shown.
                  refreshSnapshot("Syncing remote edits…")
                }
              }
              is UiBuilderClientUpdate.Outcome -> refreshSnapshot("Confirming operation…")
              is UiBuilderClientUpdate.SnapshotRequired ->
                refreshSnapshot("Snapshot recovery · after ${update.afterSequence ?: 0}")
              is UiBuilderClientUpdate.Presence -> {
                presenceState = presenceState.apply(update.update.update, browserNowMillis())
              }
            }
          }
        updates = client
        client.connect()
      }
      is UiBuilderHttpResult.ServiceError -> {
        sessionStatus = "Live error · ${result.error.message}"
        openFailure = result.error
      }
      is UiBuilderHttpResult.SnapshotRequired -> {
        sessionStatus = "Snapshot required · ${result.error.message}"
        openFailure = result.error
      }
    }
  }
  val activeUpdates = updates
  DisposableEffect(activeUpdates) { onDispose { activeUpdates?.close() } }

  LaunchedEffect(openFailure, config.designId) {
    if (
      config.localStorage ||
        openFailure?.code != ServiceErrorCodeV1.CATALOG_UNAVAILABLE ||
        catalogRecovery != null ||
        catalogRecoveryLoading
    ) {
      return@LaunchedEffect
    }
    catalogRecoveryLoading = true
    catalogRecoveryError = null
    runCatching { fetchCatalogRecovery(config.designId) }
      .onSuccess { catalogRecovery = it }
      .onFailure { catalogRecoveryError = it.message ?: "Catalog recovery could not be previewed" }
    catalogRecoveryLoading = false
    catalogRecoveryAttempted = true
  }

  LaunchedEffect(socketState) {
    publishSocketState(socketState.name.lowercase())
    if (socketState == BrowserUiBuilderSocketState.DISCONNECTED) {
      sessionStatus = "Disconnected · reconnect to resume collaboration"
    }
  }

  LaunchedEffect(config, socketState, selectedNodeId, document?.revision) {
    // Nobody to tell. Presence is who else is in the design, and a design in this browser has no
    // "else".
    if (config.localStorage) return@LaunchedEffect
    if (socketState != BrowserUiBuilderSocketState.CONNECTED) return@LaunchedEffect
    while (true) {
      val currentDocument = document
      if (currentDocument != null) {
        http.execute(
          UpdatePresenceRequestV1(
            designId = config.designId,
            presence =
              PresenceV1(
                actorId = config.actorId,
                clientId = config.clientId,
                displayName = config.displayName,
                colorArgbHex = config.colorArgbHex,
                selectedNodeIds = listOfNotNull(selectedNodeId),
                observedRevision = currentDocument.revision.toLong(),
              ),
          )
        )
      }
      delay(UI_BUILDER_PRESENCE_HEARTBEAT_MILLIS)
    }
  }

  LaunchedEffect(Unit) {
    while (true) {
      delay(UI_BUILDER_PRESENCE_HEARTBEAT_MILLIS)
      presenceState = presenceState.expire(browserNowMillis())
    }
  }

  val loadedDocument = document
  val loadedCatalog = catalog
  // Asked once the authoring catalog is known, because the answer depends on it: a catalog that
  // does not offer `remote-compose/document` has nowhere to put a published document, and asking
  // its serving catalog for one would be a request whose answer could not be used.
  var remoteComposeSources by remember { mutableStateOf(emptyList<RemoteComposeSource>()) }
  LaunchedEffect(loadedCatalog) {
    remoteComposeSources =
      if (loadedCatalog?.componentsById?.containsKey(REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID) == true)
        loadRemoteComposeSources(activeCatalogSystemId)
      else emptyList()
  }
  /**
   * Making a design, by whichever of the two routes this session has.
   *
   * A server design is created by the New design form, whose `303` the browser follows, so the
   * design's permalink is what ends up in history. A local design has nowhere to POST: the page
   * seeds the document itself from the same shared [UiBuilderNewDesignSeed] the server uses, writes
   * it to this browser, rewrites the URL and re-enters the editor. One seed, two places to put the
   * result.
   */
  val createDesign: (String, String, String, List<NewDesignState>) -> Unit =
    { catalogSystemId, designId, templateId, state ->
      if (localSession == null) {
        navigateToNewDesign(catalogSystemId, designId, templateId, encodeNewDesignStates(state))
      } else {
        scope.launch {
          val failure =
            createLocalDesign(
              session = localSession,
              catalogs = catalogCapabilities,
              catalogSystemId = catalogSystemId,
              designId = designId,
              templateId = templateId,
              state = state,
            )
          if (failure != null) {
            sessionStatus = "Local error · $failure"
          } else {
            canonicalizeUiBuilderUrl(designId, DesignUrlSelectors())
            onOpenDesign(
              config.copy(
                catalogSystemId = catalogSystemId,
                designId = designId,
                startWithNewDesign = false,
              )
            )
          }
        }
      }
    }

  // Taking the design into this browser, and bringing it home again.
  //
  // Two halves of one act, and each is offered only where it means something: a design already kept
  // here has nowhere to be taken, and a design created here has no server to go home to. The rules
  // both follow — the fork point, the base chain, what a refusal means — are
  // `docs/design/UI_BUILDER_DESIGN_PORTABILITY.md`.
  val storedRecord =
    remember(config.designId, config.localStorage, localSession) {
      localSession?.store?.read(config.designId)
    }
  val takeOffline: (() -> Unit)? =
    if (localSession != null) null
    else
      authoritativeDocument?.let { wire ->
        {
          val outcome =
            takeDesignOffline(
              wire = wire,
              catalogSystemId = activeCatalogSystemId,
              sequence = authoritativeSequence,
            )
          if (outcome != null) {
            sessionStatus = "Local error · $outcome"
          } else {
            enterLocalDesignUrl(config.designId)
            onOpenDesign(config.copy(localStorage = true, startWithNewDesign = false))
          }
        }
      }
  val syncToServer: (() -> Unit)? =
    storedRecord?.origin?.let { origin ->
      {
        scope.launch {
          sessionStatus = "Syncing to ${origin.server}…"
          val syncClient =
            UiBuilderProtocolHttpClient(
              actorId = config.actorId,
              endpoint = config.httpEndpoint,
              transport = BrowserUiBuilderHttpTransport(),
              requestIds = MonotonicUiBuilderRequestIds("${config.clientId}-sync"),
            )
          val result =
            LocalDesignSyncBack(
                actorId = config.actorId,
                clientId = config.clientId,
                execute = { request -> syncClient.execute(request) },
              )
              .sync(storedRecord)
          sessionStatus = "$activeCatalogSystemId · ${syncStatus(result)}"
        }
      }
    }

  if (config.startWithNewDesign && newDesignCatalogs.isNotEmpty()) {
    UiBuilderNewDesignScreen(
      catalogs = newDesignCatalogs,
      initialCatalogSystemId = activeCatalogSystemId,
      // A design kept in this browser has no server index behind it, and nothing on the server to
      // copy: the home screen then shows the create panel alone, which is what it always was.
      designs = if (localSession == null) homeDesigns else emptyList(),
      onOpenDesign = if (localSession == null) ::navigateToDesign else null,
      onCopyDesign =
        if (localSession == null) {
          { source -> navigateToCopyDesign(source, NewDesignNames.random()) }
        } else null,
      onBrowseDesigns = if (localSession == null) ::navigateToDesignsIndex else null,
      onMoveDesign =
        if (localSession == null) {
          { designId, folder ->
            if (serverFoldersAvailable) {
              scope.launch {
                runCatching { homeFolderHost.move(designId, folder) }
                  .getOrNull()
                  ?.let { stored ->
                    homeFolders = stored
                    homeDesigns = homeDesigns.map { design ->
                      design.copy(folder = stored[design.designId])
                    }
                  }
              }
            } else {
              homeFolders =
                homeFolders.toMutableMap().apply {
                  if (folder == null) remove(designId) else put(designId, folder)
                }
              writeHomeFolders(homeFolders)
              homeDesigns = homeDesigns.map {
                if (it.designId == designId) it.copy(folder = folder) else it
              }
            }
          }
        } else null,
      onCreate = createDesign,
    )
    LaunchedEffect(newDesignCatalogs) { markReady() }
    return
  }
  if (loadedDocument != null && loadedCatalog != null) {
    // A `#thread=` naming no conversation on this board, once the board is in a position to say so.
    // Answered the way `?node=` answers an unknown layer — a sentence rather than a failure — but
    // it needs the wait: the discussion arrives over its own socket well after the design does, so
    // asking earlier would call every thread link stale for the first moments of every page.
    val staleThreadId = linkedThreadId?.takeIf {
      commentBoardLoaded && commentBoard.threads.none { thread -> thread.id == it }
    }
    // The address bar stops naming it, and so does the state this page publishes for the harness
    // and the extension. A fragment pointing at a conversation that is not there is the same lie
    // an unavailable `?revision=` is not allowed to tell.
    // Deliberately not clearing [linkedThreadId] here, unlike the close path below: the notice is
    // derived from it, and a link naming a thread that does not exist has no card to keep on screen
    // anyway — the exemption that path is protecting against needs a card to exempt.
    LaunchedEffect(staleThreadId) {
      if (staleThreadId != null) {
        dropDesignUrlFragment()
        openThreadId = null
      }
    }
    val collaborators = presenceState.collaborators(config.actorId)
    LaunchedEffect(collaborators) {
      publishPresenceManifest(
        Json.encodeToString(collaborators.map(UiBuilderCollaborator::actorId)),
        Json.encodeToString(collaborators.map(UiBuilderCollaborator::selectedNodeIds)),
      )
    }
    // The record this host generates this catalog's exports from, for the code pane and the
    // problems panel. Without it they judge every catalog by the record embedded in this build —
    // `m3-catalog`'s authored one — and a published catalog's components are not in it, so the
    // pane reported "no component" for what the export writes. See `UiBuilderEditorReducer`.
    //
    // Fetched separately rather than carried on the capability document, and after the design is
    // on screen rather than before: it is the largest thing this page reads and nothing draws with
    // it. A host that serves none, and a fetch that fails, leave the editor exactly where it was.
    var catalogRecord by remember(loadedCatalog) { mutableStateOf<ComponentRecordFile?>(null) }
    LaunchedEffect(loadedCatalog) {
      catalogRecord = fetchCatalogRecord(loadedCatalog.benchmark.catalogSystemId)
    }
    UiBuilderEditor(
      document = loadedDocument,
      catalog = loadedCatalog,
      catalogRecord = catalogRecord,
      pageDestinations = pageDestinations,
      onNavigatePage = { pageKey -> navigateToUiBuilderPage(config.catalogSystemId, pageKey) },
      actorId = config.actorId,
      clientId = config.clientId,
      operationIdPrefix = config.operationIdPrefix,
      sessionLabel = sessionStatus,
      // No Reconnect while a revision is pinned. There is no session to reconnect: the pinned page
      // opened one snapshot and holds no socket, and the button's own handler would fetch the head
      // and replace the document under a banner still naming the revision — the exact lie the
      // banner exists to prevent.
      onReconnect =
        if (revisionPin?.pinned == true) null
        else {
          {
            updates?.reconnect()
            refreshSnapshot("Reconnecting…")
          }
        },
      onSubmission = { submission ->
        // Queued rather than sent: the revision this command claims, and the order it reaches the
        // server in, are the drain loop's to decide.
        sync.enqueueSubmission()
        if (submissions.trySend(submission).isFailure) {
          sync.completeSubmission()
          sessionStatus = "Live error · the edit queue is closed"
        }
      },
      authoritativeGeneration = authoritativeGeneration,
      authoritativeRevisionFor = { nodeId ->
        authoritativeDocument?.takeIf { it.nodes.containsKey(nodeId) }?.revision
      },
      initialSelectedNodeId = selectedNodeId,
      // `#thread=` wins the panel where a link names both: one dock is open at a time, and a link
      // that names a conversation is a link to read it. `?node=` still selects the layer, which is
      // what makes "the thread about this button, beside the button" one URL.
      initialInspectorMode =
        if (config.selectors.threadId != null) EditorInspectorMode.Comments
        else EditorInspectorMode.Properties,
      initialInspectorOpen = config.selectors.nodeId != null || config.selectors.threadId != null,
      linkedThreadId = linkedThreadId,
      threadNavigations = threadNavigations,
      onSelectedThreadChanged = {
        openThreadId = it
        // The fragment stops naming a thread as soon as the reader closes it or opens another, and
        // the state that mirrors it has to go with it. `replaceState` fires no `hashchange`, so
        // nothing else would clear this — and a stale value here keeps feeding the panel a
        // `revealThreadId`, which is what exempts a resolved card from being hidden. The card would
        // then stay on screen for the rest of the session, after the URL had stopped naming it.
        if (it != linkedThreadId) {
          dropDesignUrlFragment()
          linkedThreadId = null
        }
      },
      revisionPin = revisionPin,
      // Only where there is somewhere to go. A revision that could not be shown left the page on
      // the latest design already, and a button offering to take you where you are is a button
      // that teaches the banner cannot be trusted.
      onGoToLatest = revisionPin?.takeIf { it.pinned }?.let { { goToLatestRevision() } },
      openingNotice =
        listOfNotNull(
            config.selectors.nodeId
              ?.takeIf { !loadedDocument.nodes.containsKey(it) }
              ?.let { "This link names a layer this design does not have: $it" },
            staleThreadId?.let { "This link names a conversation this design does not have: $it" },
          )
          .takeIf { it.isNotEmpty() }
          ?.joinToString(" "),
      // Withheld for a design the path form cannot name. The service stores any id that is not
      // blank, while this editor refuses to start on a design named in the path unless the id is
      // path-safe, so such a design is reachable only through the legacy query form — and a link
      // to it would hand its recipient a page that will not open. See [isDesignUrlPathSafe].
      onCopyDesignLink =
        if (!isDesignUrlPathSafe(config.designId)) null
        else { selectors -> copyDesignLink(designUrlPath(config.designId, selectors)) },
      initialCatalogQuery = catalogQuery,
      initialEnabledPacks = enabledPacks,
      initialPinnedComponents = pinnedComponents,
      collaborators = collaborators,
      componentDrift = componentDrift,
      devicePresets = devicePresets,
      newDesignCatalogs = newDesignCatalogs,
      onCreateDesign = createDesign,
      onBrowseDesigns = if (localSession == null) ::navigateToDesignsIndex else null,
      onHelp = ::openUiBuilderGuide,
      onCopyAiPrompt =
        if (localSession != null || !isDesignUrlPathSafe(config.designId)) null
        else {
          {
            copyAiPrompt(
              openCodeUiBuilderPrompt(
                mcpEndpoint = "${pageOrigin().trimEnd('/')}/mcp",
                designUrl = shareableUrl(designUrlPath(config.designId)),
                designId = config.designId,
              )
            )
          }
        },
      onTakeOffline = takeOffline,
      onSyncToServer = syncToServer,
      exportHost = exportHost,
      onRequestDocumentPreview =
        if (!documentPreviewAvailable) null
        else
          { expected ->
            if (config.localStorage || authoritativeDocument?.toUiBuilderDocument() != expected) {
              UiBuilderDocumentPreview.Ready(
                revision = expected.revision,
                documentBase64 =
                  fetchBase64(
                    "$UI_BUILDER_DOCUMENT_EXPORT_PATH/export.rc",
                    Json.encodeToString(expected.toDesignDocumentV1()),
                  ),
                saved = false,
              )
            } else {
              UiBuilderDocumentPreview.Ready(
                revision = expected.revision,
                documentBase64 =
                  fetchBase64(
                    "$UI_BUILDER_LIVE_EXPORT_PATH/${encodeUriComponent(expected.id)}/export.rc?revision=${expected.revision}"
                  ),
              )
            }
          },
      restoredReference = restoredReference,
      onPickReference = { references.pickFile() },
      // The third lane that renders on request, and the one easiest to miss: this one *keeps* what
      // it renders, as the reference the canvas is traced against. Snapshotting a pinned page
      // without the revision would lay the head over history and then persist it.
      onSnapshotDesign = {
        references.snapshotDesign(revisionPin?.takeIf { it.pinned }?.requested)
      },
      referenceStatus = referenceStatus,
      pastedReference = pastedReference,
      comments = commentBoard,
      commentStatus = commentStatus,
      onPostComment = { draft ->
        scope.launch { commentStatus = commentHost.post(draft, config.displayName) }
      },
      onResolveCommentThread = { threadId, resolved ->
        scope.launch { commentStatus = commentHost.resolve(threadId, resolved) }
      },
      onStateChanged = {
        latestEditorDocument = it.document
        // The address bar stops naming a node the moment the selection moves off it, so a URL
        // copied later — or restored by the browser tomorrow — cannot point at a layer nobody has
        // been looking at.
        if (config.selectors.nodeId != null && it.selectedNodeId != config.selectors.nodeId) {
          dropDesignUrlQuery(DESIGN_URL_NODE_KEY)
        }
        selectedNodeId = it.selectedNodeId
        inspectorMode = it.inspectorMode
        catalogQuery = it.catalogQuery
        if (it.enabledPacks != enabledPacks) {
          enabledPacks = it.enabledPacks
          writeEnabledPacks(activeCatalogSystemId, it.enabledPacks)
        }
        if (it.pinnedComponents != pinnedComponents) {
          pinnedComponents = it.pinnedComponents
          writePinnedComponents(activeCatalogSystemId, it.pinnedComponents)
        }
        // Persisted from here rather than from each control, so every route that changes the
        // overlay — a slider, a stroke, a flatten, a paste — is stored by one path.
        if (referenceLoaded && it.reference != storedReference) pendingReference = it.reference
        publishEditorState(it)
      },
      onCanvasMetrics = ::publishEditorCanvasMetrics,
      onCanvasBoundsChanged = ::publishEditorCanvasBounds,
      onDropTargetChanged = ::publishEditorDropTarget,
      // `candidate` predates catalog-delivered runtimes. Keep its in-process canvas until every
      // deployed catalog has been verified through the hosted editor and native-export lanes; it
      // names no immutable archive, so asking the runtime route for it can only return 404.
      canvasRenderer =
        if (
          loadedDocument.catalogPin["nativeRuntimeId"]?.jsonPrimitive?.contentOrNull == "candidate"
        ) {
          null
        } else {
          { rendered, surface, selectedNodeId, selectionEnabled, onNodeSelected, onInspection ->
            CatalogRuntimeCanvas(
              rendered,
              surface,
              selectedNodeId,
              selectionEnabled,
              onNodeSelected,
              onInspection,
            )
          }
        },
      onInspectionSnapshot = inspectionPublisher::publish,
      onInspectionInvalidated = { collector ->
        inspectionPublisher.offer(collector, loadedDocument.revision)
      },
      // The native render is a real Compose render the server performs from the stored design, so
      // it is the one pane a design this server has never seen cannot fill.
      //
      // Where it can, it is pinned like the export lane rather than withheld, which is what it has
      // to be on a catalog whose Wasm canvas is only a stand-in. Withholding it left `wear-m3`
      // drawing Material 3 lookalikes under a banner naming a revision — not a rough picture of the
      // right document but a faithful picture of the wrong component library, which is the one
      // thing a historical view must not be. The route takes a revision for exactly this.
      //
      // The host container shape rides along for the same reason the revision does: the pane has to
      // draw the frame the canvas beside it is drawing, and a render that picked its own would be
      // the one disagreement this pane must not invent.
      // The live half of the same lane. One instance per session: the editor opens it when a
      // render names a session and closes it when that session changes or the pane goes away.
      onOpenNativeStream = { live -> BrowserNativeStream(live) },
      onRequestNativeRender = { hostShape ->
        if (config.localStorage) UiBuilderNativeRender(failure = LOCAL_NATIVE_RENDER_UNAVAILABLE)
        else
          requestNativeRender(
            config.designId,
            revisionPin?.takeIf { it.pinned }?.requested,
            hostShape,
          )
      },
      remoteComposeSources = remoteComposeSources,
      resolveRemoteComposeDocument = { source ->
        fetchBase64(catalogAssetPath(activeCatalogSystemId, "/render/${source.id}.rc"))
      },
      resolveRemoteComposeThumbnail = { source ->
        val encoded =
          fetchBase64(catalogAssetPath(activeCatalogSystemId, "/render/${source.id}.png"))
        Image.makeFromEncoded(Base64.decode(encoded)).toComposeImageBitmap()
      },
      // The same fetch, for a URL the *design* names rather than one the palette built. Which URLs
      // are reachable is [sameOriginRequestUrl]'s rule and not a second policy written here: it
      // resolves against the page and throws on anything that leaves this origin, so a design
      // pointing at another host draws that refusal as its own diagnostic rather than quietly
      // sending this page's token somewhere it does not belong.
      resolveRemoteComposeUrl = { url -> fetchBase64(url) },
      // The design's own uploaded pictures, from the route beside the design API that stores
      // them. Same-origin like everything else here, and read as this page's actor, so a design
      // one may not open has no pictures one may fetch.
      resolveDesignAsset = { assetKey ->
        Base64.decode(fetchBase64("/api/ui-builder/v1/designs/${config.designId}/assets/$assetKey"))
      },
      // Same-origin, like every other request this page makes: `sameOriginRequestUrl` refuses the
      // rest, and a builder that made an exception for animation URLs would be a page fetching
      // arbitrary third-party JSON into a design. An animation served from elsewhere is pasted
      // into the element's `json` instead, which is the same bytes by a route the host can see.
      loadLottieAnimation = { url -> fetchText(url) },
    )
    LaunchedEffect(loadedDocument.revision) { markReady() }
    // What the URL's selectors resolved to, for the harness and for anybody debugging a link that
    // did not do what its author expected. Published rather than inferred from pixels: "the node
    // is selected" and "the node is drawn with an outline" are different claims, and only the
    // first one is what a selector promises.
    LaunchedEffect(revisionPin, selectedNodeId, openThreadId, inspectorMode) {
      publishDesignSelectors(
        revision = revisionPin?.requested?.toString().orEmpty(),
        revisionPinned = revisionPin?.pinned == true,
        nodeId = selectedNodeId.orEmpty(),
        threadId = openThreadId.orEmpty(),
        inspectorMode = inspectorMode.name,
      )
    }
  } else {
    // The branch that did not exist. Without it a refused open emitted nothing at all: the guard
    // above is the only thing this composable draws, so a null document was a white page for as
    // long as the tab stayed open, with the service's own explanation sitting unread in
    // `sessionStatus`. Nothing about the failure was hidden — it was simply never rendered.
    val failure = openFailure
    if (failure != null) {
      UiBuilderUnavailableScreen(
        designId = config.designId,
        catalogSystemId = activeCatalogSystemId,
        reason = failure.message,
        code = failure.code,
        recovery =
          catalogRecovery?.let { preview ->
            UiBuilderCatalogRecoveryUi(
              sourceRevision = preview.sourceCatalogPin.catalogRevision,
              targetRevision = preview.targetCatalogPin.catalogRevision,
              changeCount = preview.changes.size,
              issues = preview.issues.map { "${it.severity.name.lowercase()}: ${it.message}" },
              canApply = preview.status == CatalogUpgradePreviewStatusV1.READY,
              loading = catalogRecoveryApplying,
              error = catalogRecoveryError,
              onApply = {
                if (!catalogRecoveryApplying) {
                  catalogRecoveryApplying = true
                  catalogRecoveryError = null
                  scope.launch {
                    val command =
                      preview.catalogRecoveryCommand(
                        actorId = config.actorId,
                        clientId = config.clientId,
                      )
                    if (command == null) {
                      catalogRecoveryError = "The recovery preview produced no candidate document"
                      catalogRecoveryApplying = false
                      return@launch
                    }
                    val result = http.execute(ApplyOperationRequestV1(command))
                    val accepted =
                      ((result as? UiBuilderHttpResult.Response)?.response
                          as? OperationOutcomeResponseV1)
                        ?.outcome as? AcceptedOutcomeV1
                    if (accepted != null) {
                      reloadBrowserPage()
                    } else {
                      catalogRecoveryError =
                        when (result) {
                          is UiBuilderHttpResult.ServiceError -> result.error.message
                          is UiBuilderHttpResult.SnapshotRequired -> result.error.message
                          is UiBuilderHttpResult.Response -> "The catalog recovery was refused"
                        }
                      catalogRecoveryApplying = false
                    }
                  }
                }
              },
            )
          },
        recoveryLoading =
          catalogRecoveryLoading ||
            (failure.code == ServiceErrorCodeV1.CATALOG_UNAVAILABLE && !catalogRecoveryAttempted),
        recoveryError = catalogRecoveryError,
      )
      // Ready means settled, not successful. The harness waits on this attribute for 60 seconds
      // before failing, so leaving it unset on a design that will never open turns a precise
      // "catalog unavailable" into a timeout that says nothing. Only on a settled failure: while
      // `openFailure` is null the open may still succeed, and marking ready then would let the
      // harness assert against a page that had not finished loading.
      LaunchedEffect(failure, catalogRecoveryAttempted) {
        if (
          failure.code != ServiceErrorCodeV1.CATALOG_UNAVAILABLE ||
            config.localStorage ||
            catalogRecoveryAttempted
        ) {
          markReady()
        }
      }
    }
  }
}

@Serializable
internal data class BrowserCatalogRecoveryPayload(val preview: CatalogUpgradePreviewV1)
