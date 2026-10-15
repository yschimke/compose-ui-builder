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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import ee.schimke.composeai.uibuilder.editor.CommentNotificationsController
import ee.schimke.composeai.uibuilder.editor.CommentNotificationsState
import ee.schimke.composeai.uibuilder.editor.DesignCommentBoard
import ee.schimke.composeai.uibuilder.editor.DesignReview
import ee.schimke.composeai.uibuilder.editor.DesignSuggestions
import ee.schimke.composeai.uibuilder.editor.EditorExportFormat
import ee.schimke.composeai.uibuilder.editor.EditorInspectorMode
import ee.schimke.composeai.uibuilder.editor.EditorLibraryComponent
import ee.schimke.composeai.uibuilder.editor.EditorNoticeAction
import ee.schimke.composeai.uibuilder.editor.EditorSubmission
import ee.schimke.composeai.uibuilder.editor.UI_BUILDER_PRESENCE_HEARTBEAT_MILLIS
import ee.schimke.composeai.uibuilder.editor.UiBuilderAgentHost
import ee.schimke.composeai.uibuilder.editor.UiBuilderAgentPreferences
import ee.schimke.composeai.uibuilder.editor.UiBuilderCatalogRecoveryUi
import ee.schimke.composeai.uibuilder.editor.UiBuilderChatController
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
import ee.schimke.composeai.uibuilder.editor.agentChatContext
import ee.schimke.composeai.uibuilder.editor.catalogRecoveryCommand
import ee.schimke.composeai.uibuilder.editor.exportFormatsFor
import ee.schimke.composeai.uibuilder.editor.newDesignCatalogs as orderedNewDesignCatalogs
import ee.schimke.composeai.uibuilder.editor.refusing
import ee.schimke.composeai.uibuilder.editor.revealedNewDesignCatalogs
import ee.schimke.composeai.uibuilder.editor.withTemplatePreviews
import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import ee.schimke.composeai.uibuilder.export.NewDesignNames
import ee.schimke.composeai.uibuilder.export.NewDesignState
import ee.schimke.composeai.uibuilder.export.RemoteDocumentExportSupport
import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.encodeNewDesignStates
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineController
import ee.schimke.composeai.uibuilder.guidelines.LocalDesignGuidelineCheck
import ee.schimke.composeai.uibuilder.inspector.UiBuilderPageDestination
import ee.schimke.composeai.uibuilder.local.BrowserStorageEstimate
import ee.schimke.composeai.uibuilder.local.EditorEditRoute
import ee.schimke.composeai.uibuilder.local.LocalDesignStore
import ee.schimke.composeai.uibuilder.local.LocalDesignSyncBack
import ee.schimke.composeai.uibuilder.local.LocalPersistence
import ee.schimke.composeai.uibuilder.local.LocalUiBuilderHttpTransport
import ee.schimke.composeai.uibuilder.local.browserHomeDesigns
import ee.schimke.composeai.uibuilder.local.editorEditRoute
import ee.schimke.composeai.uibuilder.local.isWriteRefusal
import ee.schimke.composeai.uibuilder.local.localStorageNotice
import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradePreviewStatusV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradePreviewV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.Image

/**
 * Resolves the server's view of this caller before the session starts. The server derives the actor
 * (operator token, GitHub session or agent grant) and rejects mismatches; the default guess is used
 * only when the endpoint is unreachable.
 */
@Composable
internal fun LiveSessionApp(startupIdentity: Deferred<ServerIdentity>) {
  var config by remember { mutableStateOf<LiveSessionConfig?>(null) }
  var failure by remember { mutableStateOf<String?>(null) }
  // A server that lets nobody look without signing in, and says where to: the page offers that
  // rather than an editor that cannot load anything.
  var signInRequired by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(startupIdentity) {
    bootPhase("Checking who you are")
    try {
      val identity = startupIdentity.await()
      if (
        identity.authenticationRequired &&
          identity.signInUrl != null &&
          !localDesignStorageRequested()
      ) {
        signInRequired = identity.signInUrl
        return@LaunchedEffect
      }
      config = liveSessionConfig(identity)
    } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
      throw cancelled
    } catch (thrown: Throwable) {
      // Show the failure on the page instead of leaving it blank.
      failure = thrown.message ?: thrown.toString()
    }
  }
  signInRequired?.let { signInUrl ->
    LaunchedEffect(Unit) { dismissBootScreen() }
    Column(
      Modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
      horizontalAlignment = Alignment.Start,
    ) {
      Text("Sign in to open this design", style = MaterialTheme.typography.titleMedium)
      Text(
        "This server shows designs to people who have signed in with GitHub.",
        style = MaterialTheme.typography.bodyMedium,
      )
      Button(onClick = { navigateTo(signInUrl) }) { Text("Sign in with GitHub") }
    }
    return
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
  // Only local mode opens a design without navigating: it re-enters the editor with a new config. A
  // server design goes through the New design form's `303`.
  config?.let { current -> LiveSessionApp(current) { config = it } }
}

@Composable
private fun LiveSessionApp(
  config: LiveSessionConfig,
  onOpenDesign: (LiveSessionConfig) -> Unit = {},
) {
  val scope = rememberCoroutineScope()
  // The store, the catalogs it falls back on, and the service answering this page's requests. Null
  // in a server-backed session.
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
  // Whether this design has been served. Until it is (the New design screen, an open still in
  // flight, or one refused for access), the design's own routes have nothing to answer but 404, so
  // nothing below asks them.
  val designServed = authoritativeDocument != null
  // The durable cursor the server last answered with; recorded as part of a fork point when a
  // design is taken offline.
  var authoritativeSequence by remember { mutableStateOf(0L) }
  var catalog by remember { mutableStateOf<CapabilityCatalog?>(null) }
  val initialDesignLayout = remember(config) { CompletableDeferred<Unit>() }
  var newDesignCatalogs by remember { mutableStateOf<List<UiBuilderNewDesignCatalog>>(emptyList()) }
  // The chooser's answer is a form-factor summary; seeding a design locally needs the catalog's
  // own revision and runtime id, which only the capability document carries.
  var catalogCapabilities by remember { mutableStateOf<List<CatalogCapabilityV1>>(emptyList()) }
  var devicePresets by remember { mutableStateOf<List<UiBuilderDevicePreset>>(emptyList()) }
  var pageDestinations by remember { mutableStateOf<List<UiBuilderPageDestination>>(emptyList()) }
  var sessionStatus by remember { mutableStateOf("Connecting…") }
  var designVisibility by
    remember(config.designId) { mutableStateOf<DesignVisibilityPayload?>(null) }
  LaunchedEffect(config.designId, localSession, designServed) {
    if (
      designServed &&
        localSession == null &&
        !config.startWithNewDesign &&
        config.designVisibilitySupported
    ) {
      while (true) {
        try {
          designVisibility =
            Json { ignoreUnknownKeys = true }
              .decodeFromString(
                DesignVisibilityPayload.serializer(),
                fetchText(
                  "/api/ui-builder/v1/designs/${encodeUriComponent(config.designId)}/visibility"
                ),
              )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          designVisibility = null
        }
        delay(15_000L)
      }
    }
  }
  // Why the design could not be opened, or null while it still might. Separate from
  // [sessionStatus], which only the editor (never reached on failure) reads. Null must mean "not
  // settled yet".
  var openFailure by remember { mutableStateOf<ServiceErrorV1?>(null) }
  var catalogRecovery by
    remember(config.designId) { mutableStateOf<CatalogUpgradePreviewV1?>(null) }
  var catalogRecoveryLoading by remember(config.designId) { mutableStateOf(false) }
  var catalogRecoveryAttempted by remember(config.designId) { mutableStateOf(false) }
  var catalogRecoveryApplying by remember(config.designId) { mutableStateOf(false) }
  var catalogRecoveryError by remember(config.designId) { mutableStateOf<String?>(null) }
  var updates by remember { mutableStateOf<UiBuilderProtocolUpdateClient?>(null) }
  // What this browser's storage did with the last edit of a design kept here, when that is worth
  // saying: a refused write, or another tab having written the same design. Null while all is well.
  var browserStorageProblem by remember(config.designId) { mutableStateOf<String?>(null) }
  var changedInAnotherTab by remember(config.designId) { mutableStateOf(false) }
  DisposableEffect(localSession, config.designId) {
    val session = localSession
    val stop =
      if (session == null) null
      else
        BrowserLocalStorageBackend.onExternalChange { key ->
          if (!key.startsWith(LocalDesignStore.DESIGN_KEY_PREFIX)) return@onExternalChange
          val designId = key.removePrefix(LocalDesignStore.DESIGN_KEY_PREFIX)
          if (session.service.changedElsewhere(designId) && designId == config.designId) {
            changedInAnotherTab = true
            browserStorageProblem =
              "This design was changed in another tab. Edits here are no longer saved — reload " +
                "to pick up that tab's changes."
          }
        }
    onDispose { stop?.invoke() }
  }
  // IndexedDB refuses a write after the edit that made it has been answered, so its refusal
  // arrives on its own and is said the same way a synchronous one is.
  DisposableEffect(localSession) {
    val stop =
      if (localSession == null) null
      else
        BrowserLocalStorageBackend.onWriteFailure { reason ->
          browserStorageProblem = "Not saved in this browser: $reason"
        }
    onDispose { stop?.invoke() }
  }
  var authoritativeGeneration by remember { mutableStateOf(0) }
  val inspectionPublisher = remember(scope) { CoalescingInspectionPublisher(scope) }
  var selectedNodeId by remember { mutableStateOf(config.selectors.nodeId) }
  // What `?revision=` did, once the answer is known: null until the design has been asked for, and
  // afterwards both the revision the link named and whether it could be shown.
  var revisionPin by remember(config.designId) { mutableStateOf<DesignRevisionPin?>(null) }
  // Which panel is showing and which conversation is open. Held here only to publish what the URL's
  // selectors did; the editor owns both.
  var inspectorMode by remember(config.designId) { mutableStateOf(EditorInspectorMode.Properties) }
  var openThreadId by remember(config.designId) { mutableStateOf(config.selectors.threadId) }
  // The thread the address bar names right now (fragment-only navigations don't re-enter the app).
  // Re-read from `location` on each change so Back/Forward and link presses share one path.
  var linkedThreadId by remember(config.designId) { mutableStateOf(config.selectors.threadId) }
  // How many times the browser changed the fragment. Distinct from [linkedThreadId], which this
  // host also clears itself; only the `hashchange` loop counts, and `replaceState` fires none.
  var threadNavigations by remember(config.designId) { mutableStateOf(0) }
  // What this account may open, asked once. Used for the design's page destinations (this catalog
  // only) and the home screen's list (all catalogs).
  var homeDesigns by remember { mutableStateOf(emptyList<UiBuilderHomeDesign>()) }
  // A live host owns shared folders beside its design state. Browser storage remains the fallback
  // for an older host and for offline/local work; neither form changes a document revision.
  var homeFolders by remember { mutableStateOf(readHomeFolders()) }
  var serverFoldersAvailable by remember { mutableStateOf(false) }
  val homeFolderHost = remember { BrowserHomeFolderHost() }
  LaunchedEffect(http, config.catalogSystemId) {
    runCatchingCancellable { homeFolderHost.load() }
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
            revision = it.revision,
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
  // Components the reader pinned to the top of the palette, per catalog in this browser. Null means
  // "never said", so the catalog defaults apply.
  var pinnedComponents by
    remember(config.catalogSystemId) {
      mutableStateOf(readPinnedComponents(config.catalogSystemId))
    }
  var presenceState by remember { mutableStateOf(UiBuilderPresenceState()) }
  val agentPreferencesState =
    remember(config.designId) { mutableStateOf(readAgentPreferences(config.designId)) }
  var agentPreferences by agentPreferencesState
  var activeAgents by
    remember(config.designId) { mutableStateOf<List<UiBuilderCollaborator>?>(null) }
  LaunchedEffect(config.designId, localSession, config.selectors.revision, designServed) {
    if (
      !designServed ||
        localSession != null ||
        config.startWithNewDesign ||
        config.selectors.revision != null
    )
      return@LaunchedEffect
    while (true) {
      try {
        activeAgents = fetchAgentPresence(config.designId)
        // Read the shared state after the network suspension, including edits saved before
        // the next recomposition. Capturing a preference value here would erase those edits.
        val latestPreferences = agentPreferencesState.value
        if (activeAgents?.isNotEmpty() == true && !latestPreferences.connectedBefore) {
          val updated = latestPreferences.copy(connectedBefore = true)
          if (saveAgentPreferences(config.designId, updated) == null) agentPreferences = updated
        }
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        activeAgents = null
      }
      delay(UI_BUILDER_PRESENCE_HEARTBEAT_MILLIS)
    }
  }
  var socketState by remember { mutableStateOf(BrowserUiBuilderSocketState.CONNECTING) }
  // Which snapshot may be shown and which revision the next command claims as its base; see
  // [UiBuilderLiveSessionSync].
  val sync = remember(config.designId) { UiBuilderLiveSessionSync() }
  // The newest snapshot held back while the operator's own edits are still queued, displayed once
  // the queue drains.
  var heldSnapshot by remember(config.designId) { mutableStateOf<SnapshotResponseV1?>(null) }
  // Edits wait here rather than each opening its own request. Unbounded because dropping one would
  // lose an edit the canvas is already showing; a burst is twenty, not twenty thousand.
  val submissions = remember(config.designId) { Channel<EditorSubmission>(Channel.UNLIMITED) }
  // An edit refused for who sent it (expired session, revoked access) rather than its content. Kept
  // as the first edit of a browser copy, which the fork below picks up.
  var refusedForAccess by remember(config.designId) { mutableStateOf<RefusedEdit?>(null) }
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
   * Drains the edit queue one submission at a time. Serialized so each command claims the revision
   * the previous one produced, keeping insertion anchors resolvable.
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
              // The local service answers the edit and the storage write together; a write this
              // browser refused leaves the edit on screen and must not leave it unsaid.
              when (val persistence = localSession?.service?.lastPersistence) {
                is LocalPersistence.Refused ->
                  browserStorageProblem = "Not saved in this browser: ${persistence.reason}"
                is LocalPersistence.Compacted ->
                  if (!changedInAnotherTab) browserStorageProblem = null
                LocalPersistence.Stored -> if (!changedInAnotherTab) browserStorageProblem = null
                null -> Unit
              }
              syncSnapshot(sessionStatus)
            }
            is UiBuilderHttpResult.ServiceError ->
              if (localSession == null && isWriteRefusal(result.error.code)) {
                // The first such refusal wins: every edit queued behind it is refused the same
                // way, and the copy is made once.
                if (refusedForAccess == null) {
                  refusedForAccess = RefusedEdit(submission, result.error.message)
                }
                sessionStatus = "Not saved · ${result.error.message}"
              } else {
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

  // The edit that made this copy, applied once the copy has opened. Once per session
  // (`carriedCommand` is in memory). Keyed on the on-screen document id, since only the copy's own
  // first snapshot gives it a base.
  var carriedApplied by remember(config.designId) { mutableStateOf(false) }
  val openedDesignId = authoritativeDocument?.id
  LaunchedEffect(config.designId, openedDesignId) {
    val carried = config.carriedCommand ?: return@LaunchedEffect
    val base = sync.carriedEditBase(config.designId, openedDesignId) ?: return@LaunchedEffect
    if (carriedApplied || localSession == null) return@LaunchedEffect
    carriedApplied = true
    val request =
      ApplyOperationRequestV1(
        carried.copy(designId = config.designId, baseRevision = base.toLong())
      )
    when (val result = http.execute(request)) {
      is UiBuilderHttpResult.Response -> syncSnapshot("Editing a copy in this browser")
      is UiBuilderHttpResult.ServiceError ->
        syncSnapshot("Copied, but the first edit was refused · ${result.error.message}")
      is UiBuilderHttpResult.SnapshotRequired -> syncSnapshot("Editing a copy in this browser")
    }
  }

  // Cached in local mode so device frames survive an offline reload.
  LaunchedEffect(localSession) { devicePresets = loadDevicePresets(localSession?.text) }

  // Asked once per design: the answer is about another host's library, which changes on merges, not
  // keystrokes. The reducer drops findings later edits invalidate.
  var componentDrift by
    remember(config.designId) { mutableStateOf(emptyList<ComponentDriftFinding>()) }
  // Re-asked when the revision on screen changes, since the answer concerns that revision's
  // imports.
  val pinnedRevision = revisionPin?.takeIf { it.pinned }?.requested
  // Moved on by a publish from this editor: the library it lists and the drift it reports both
  // changed, and nothing else would tell this page to read them again.
  var libraryGeneration by remember { mutableStateOf(0) }
  LaunchedEffect(config.designId, pinnedRevision, libraryGeneration, designServed) {
    if (!designServed) return@LaunchedEffect
    componentDrift = loadComponentDrift(config.designId, pinnedRevision)
  }
  // What the project shares, read once per design: the shelf only has to be as fresh as the
  // library, and an import fetches the symbol it places at the moment it is pressed.
  var componentLibrary by remember { mutableStateOf(emptyList<EditorLibraryComponent>()) }
  LaunchedEffect(config.designId, localSession, libraryGeneration) {
    componentLibrary = if (localSession == null) loadComponentLibrary() else emptyList()
  }

  // The reference overlay's browser half (file picker, paste listener, snapshot, store), rebuilt
  // per design.
  val references = remember(config.designId) { BrowserReferenceHost(config.designId, http) }
  // Built once the catalog is known, since the menu offers what the catalog's renderer can draw;
  // null means no Export button yet.
  var latestEditorDocument by remember(config.designId) { mutableStateOf<UiBuilderDocument?>(null) }
  var documentPreviewAvailable by remember(config.designId) { mutableStateOf(false) }
  var exportHost by remember(config.designId) { mutableStateOf<UiBuilderExportHost?>(null) }
  var restoredReference by remember(config.designId) { mutableStateOf<RestoredReference?>(null) }
  var referenceStatus by remember(config.designId) { mutableStateOf<String?>(null) }
  // What was last written, so a settings drag can take the cheap route and a new picture cannot.
  var storedReference by remember(config.designId) { mutableStateOf<ReferenceOverlayState?>(null) }
  var pendingReference by remember(config.designId) { mutableStateOf<ReferenceOverlayState?>(null) }
  var pastedReference by remember(config.designId) { mutableStateOf<ReferenceImage?>(null) }

  // Nothing is persisted until this has finished; otherwise the editor's empty initial state would
  // overwrite the stored reference.
  var referenceLoaded by remember(config.designId) { mutableStateOf(false) }
  LaunchedEffect(config.designId, config.localStorage, designServed) {
    // Local designs have no server to store reference pictures, and `localStorage` is too small for
    // them.
    if (config.localStorage) {
      referenceStatus = LOCAL_REFERENCE_UNAVAILABLE
      return@LaunchedEffect
    }
    // Local to the page and needed by the paste listener below, which is armed from the start: only
    // the stored picture has to wait for the design.
    installReferenceBridge()
    if (!designServed) return@LaunchedEffect
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
  // Whether the discussion has been delivered at all; an empty board can't mean "thread gone" until
  // something has arrived.
  var commentBoardLoaded by remember(config.designId) { mutableStateOf(false) }
  var commentStatus by remember(config.designId) { mutableStateOf<String?>(null) }

  // One socket for the life of the design; it sends the current board on connect. The load below is
  // only the fallback for hosts that refuse the upgrade (a snapshot, not a feed).
  DisposableEffect(config.designId, config.localStorage, designServed) {
    // A discussion is a thing several people have, and a design only this browser holds has nobody
    // to have it with. The panel stays, empty, saying so.
    if (config.localStorage) {
      commentStatus = LOCAL_COMMENTS_UNAVAILABLE
      return@DisposableEffect onDispose {}
    }
    if (!designServed) return@DisposableEffect onDispose {}
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
  LaunchedEffect(config.designId, config.localStorage, designServed) {
    if (config.localStorage || !designServed) return@LaunchedEffect
    commentHost.load()?.let { board ->
      // Only if the socket has not already delivered something newer: the two race by design and
      // the sequence is what settles it, rather than whichever answer happened to arrive last.
      if (board.sequence > commentBoard.sequence) commentBoard = board
      commentBoardLoaded = true
    }
  }

  // "Notify me about replies". Per page, since a push subscription belongs to the origin. Loading
  // only asks the server; the permission prompt comes only from the switch's click.
  val commentNotifications =
    remember(config.localStorage) {
      CommentNotificationsController(
        BrowserCommentNotificationHost(hostedByServer = !config.localStorage)
      )
    }
  val commentNotificationsState by commentNotifications.state.collectAsState()
  // Published only once decided, so a test reading `hidden` knows the server was asked — the state
  // starts hidden before anything has been.
  var commentNotificationsDecided by remember(commentNotifications) { mutableStateOf(false) }
  LaunchedEffect(commentNotifications) {
    commentNotifications.load()
    commentNotificationsDecided = true
  }
  LaunchedEffect(commentNotificationsState, commentNotificationsDecided) {
    if (!commentNotificationsDecided) return@LaunchedEffect
    publishCommentNotificationsState(
      when (val state = commentNotificationsState) {
        CommentNotificationsState.Hidden -> "hidden"
        CommentNotificationsState.InstallFirst -> "install-first"
        is CommentNotificationsState.Blocked -> "blocked"
        is CommentNotificationsState.Toggle -> if (state.on) "on" else "off"
      }
    )
  }

  // The review section's browser half: approvals and this person's verdict for the revision on
  // screen. Hidden on hosts without the review routes.
  val reviewHost = remember(config.designId) { BrowserReviewHost(config.designId) }
  var review by remember(config.designId) { mutableStateOf(DesignReview()) }
  var reviewAvailable by remember(config.designId) { mutableStateOf(false) }
  var reviewStatus by remember(config.designId) { mutableStateOf<String?>(null) }
  val reviewedRevision = latestEditorDocument?.revision ?: document?.revision
  LaunchedEffect(config.designId, config.localStorage, reviewedRevision, designServed) {
    if (config.localStorage || !designServed) return@LaunchedEffect
    reviewHost.load()?.let {
      review = it
      reviewAvailable = true
    }
  }

  // Suggestion mode's browser half: agent-proposed branches the person accepts (merge) or rejects
  // (archive); see `UI_BUILDER_BRANCHES.md` → Suggestions. Polled, since a suggestion doesn't move
  // the design; reloaded when the revision moves or a decision lands. Stops (hidden) on hosts
  // without the routes.
  val suggestionHost =
    remember(config.designId, http) { BrowserSuggestionHost(config.designId, http) }
  var suggestions by remember(config.designId) { mutableStateOf(DesignSuggestions()) }
  var suggestionsAvailable by remember(config.designId) { mutableStateOf(false) }
  var suggestionStatus by remember(config.designId) { mutableStateOf<String?>(null) }
  var suggestionsGeneration by remember(config.designId) { mutableStateOf(0) }
  LaunchedEffect(
    config.designId,
    config.localStorage,
    reviewedRevision,
    suggestionsGeneration,
    designServed,
  ) {
    if (config.localStorage || !designServed) return@LaunchedEffect
    while (true) {
      val listed = suggestionHost.list()
      if (listed == null) {
        if (!suggestionsAvailable) return@LaunchedEffect
      } else {
        suggestionsAvailable = true
        // A proposal's document is fetched once per version of it: the same operations are the
        // same document, and a suggestion its author has added to since is drawn again.
        val known = suggestions.open.associateBy { it.suggestionId }
        val drawn = listed.map { suggestion ->
          val previous = known[suggestion.suggestionId]
          if (previous?.document != null && previous.operationIds == suggestion.operationIds) {
            suggestion.copy(document = previous.document)
          } else {
            suggestion.copy(document = suggestionHost.document(suggestion.suggestionId))
          }
        }
        suggestions = suggestions.copy(open = drawn)
      }
      delay(SUGGESTION_POLL_INTERVAL_MILLIS)
    }
  }

  // One paste listener for the life of the design, re-armed after every catch; a paste (e.g.
  // Figma's "copy as PNG") goes straight to the base picture.
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

  // Debounced, since slider drags change settings per pointer sample. The picture is only re-sent
  // when it changed; see `BrowserReferenceHost.save`.
  LaunchedEffect(pendingReference) {
    if (config.localStorage) return@LaunchedEffect
    val candidate = pendingReference ?: return@LaunchedEffect
    // The server stores a design's reference, and would refuse this caller's write. The overlay
    // still works for this visit; it is said once rather than failing on every slider drag.
    if (!config.canWrite) {
      referenceStatus = "Reference changes last only for this visit. ${serverOnlyRefusal(config)}"
      return@LaunchedEffect
    }
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
    suspend fun afterInitialDesign() {
      // Inspection is delivered after layout (or by the pinned renderer's validated reply).
      // Keep optional chooser work off the first frame; still make it available if rendering fails.
      withTimeoutOrNull(5_000) { initialDesignLayout.await() }
      awaitBrowserPaintOpportunity()
    }
    suspend fun installCatalogList(availableCatalogs: List<CatalogCapabilityV1>) {
      val fixture = runCatchingCancellable {
        val path = "jetcaster-discover-operations-v1.json"
        Json.parseToJsonElement(localSession?.text?.text(path) { fetchText(it) } ?: fetchText(path))
          .jsonObject
      }
        .getOrNull()
      catalogCapabilities = availableCatalogs
      // A white-labelled host offers only its own catalogs; the server refuses the rest anyway.
      val offeredCatalogs = availableCatalogs.filter {
        uiBuilderBrand?.allows(it.benchmark.catalogSystemId) ?: true
      }
      newDesignCatalogs =
        orderedNewDesignCatalogs(
            offeredCatalogs,
            // Only the server seeds catalog-owned templates (it holds the published documents).
            // Browser-created designs use `createLocalDesign`'s built-in templates, so they get the
            // built-in cards.
            if (localSession == null && config.canWrite) catalogOwnership()
            else CatalogOwnership.NONE,
            NEW_DESIGN_CATALOG_ORDER,
            ::newDesignCatalog,
            // An unlisted catalog is offered only on its own page (`/ui-builder/<catalog>/`).
            revealed = revealedNewDesignCatalogs(config.urlCatalogSystemId),
          )
          .map { choice ->
            val capability = availableCatalogs.first {
              it.benchmark.catalogSystemId == choice.systemId
            }
            run {
              // Previews are decoration: a catalog whose record fails validation keeps its plain
              // entry instead of breaking the list. Catalog-owned cards have none, since their
              // seeds live on the server.
              if (fixture == null || choice.catalogOwned) choice
              else
                runCatching {
                    choice.withTemplatePreviews(
                      CapabilityCatalogParser.parse(
                        Json.encodeToJsonElement(CatalogCapabilityV1.serializer(), capability)
                      ),
                      fixture,
                    )
                  }
                  .onFailure { failure ->
                    println(
                      "compose-ui-builder: no template previews for ${choice.systemId}: $failure"
                    )
                  }
                  .getOrDefault(choice)
            }
          }
    }
    if (config.localStorage || config.startWithNewDesign) {
      // A local session may be asked for a catalog never cached (first offline visit); explain that
      // rather than crash.
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
      catalogCapabilities = availableCatalogs
      if (config.startWithNewDesign) {
        installCatalogList(availableCatalogs)
        return@LaunchedEffect
      }
      launch {
        afterInitialDesign()
        installCatalogList(availableCatalogs)
      }
    } else {
      launch {
        afterInitialDesign()
        try {
          installCatalogList(loadLiveCatalogs(http))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
          throw cancelled
        } catch (failure: Exception) {
          // The open brings its own catalog. A chooser outage must not cancel that design.
          println("compose-ui-builder: could not load the New design catalogs: $failure")
        }
      }
    }
    fun installCatalog(capability: CatalogCapabilityV1, revision: Long?) {
      activeCatalogSystemId = capability.benchmark.catalogSystemId
      enabledPacks = readEnabledPacks(activeCatalogSystemId)
      pinnedComponents = readPinnedComponents(activeCatalogSystemId)
      catalog =
        CapabilityCatalogParser.parse(
          Json.encodeToJsonElement(CatalogCapabilityV1.serializer(), capability)
        )
      val a2ui =
        UiBuilderCatalogPlatform.from(capability.statusSemantics) == UiBuilderCatalogPlatform.A2UI
      documentPreviewAvailable =
        RemoteDocumentExportSupport.documentFormat?.let {
          RemoteDocumentExportSupport.supports(capability.exportCapabilities, it)
        } == true
      // PNG and Remote document exports can submit current drafts without saving; SVG requires a
      // saved design. When pinned, exports use the canvas's revision rather than the head.
      val browserExportHost =
        BrowserExportHost(
          designId = config.designId,
          supportsLinks = !config.localStorage,
          // A2UI messages are lowered from the document on the server, without Remote Compose, so
          // that format submits the current draft whatever the Remote Compose flag says.
          suppliedDocument = { format ->
            val current = latestEditorDocument ?: document
            current?.takeIf {
              (UiBuilderBuildFeatures.remoteCompose || format == EditorExportFormat.A2uiJson) &&
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
                !a2ui &&
                  RemoteDocumentExportSupport.jsonFormat?.let {
                    RemoteDocumentExportSupport.supports(capability.exportCapabilities, it)
                  } == true,
              rc =
                !a2ui &&
                  RemoteDocumentExportSupport.documentFormat?.let {
                    RemoteDocumentExportSupport.supports(capability.exportCapabilities, it)
                  } == true,
              // The host's JSON flag, read without the Remote Compose one: for an A2UI catalog the
              // JSON it writes is the A2UI messages.
              a2uiJson = a2ui && capability.exportCapabilities.remoteJson,
            ),
          revision = revision,
        )
      // The server draws every export and will not for a caller who may not write. The menu stays,
      // each row saying why, rather than vanishing or failing on the press.
      exportHost =
        if (config.canWrite) browserExportHost
        else browserExportHost.refusing(serverOnlyRefusal(config))
    }
    // `?revision=` first: a pinned revision opens one snapshot, no socket, no presence. A revision
    // the service won't answer for (trimmed or never reached) falls through to the live design, and
    // the banner says which happened. See [DesignRevisionPin].
    config.selectors.revision?.let { requested ->
      val pinned =
        (http.execute(GetSnapshotRequestV1(designId = config.designId, revision = requested))
            as? UiBuilderHttpResult.Response)
          ?.response as? SnapshotResponseV1
      revisionPin = DesignRevisionPin(requested = requested, pinned = pinned != null)
      if (pinned != null) {
        // Use the catalog the pinned document was resolved against, not today's.
        installCatalog(pinned.snapshot.catalog, requested)
        // Presence belongs to the live design; drop it from a pinned snapshot.
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
          // The service answered with something that isn't a snapshot; synthesised as INTERNAL
          // since it is this host's fault.
          sessionStatus = "Live error · unexpected open response"
          openFailure =
            ServiceErrorV1(ServiceErrorCodeV1.INTERNAL, "the design could not be opened")
          return@LaunchedEffect
        }
        bootPhase("Drawing the design")
        installCatalog(response.snapshot.catalog, null)
        acceptSnapshot(response)
        // Drop the revision from the URL: the page opened at head.
        canonicalizeUiBuilderUrl(config.designId, config.selectors.copy(revision = null))
        // A local design has nothing to subscribe to (this page is the only writer); report it as
        // connected so no reconnect is offered.
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
                // Not while the operator's own edits are queued: projecting a collaborator's write
                // would rebuild from a document missing them. The snapshot fetch below is held by
                // the deferral until the queue drains.
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
                // Display the projection only if the session agrees it is newest; a racing snapshot
                // may already be past it.
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
                  // Unverified, deferred or overtaken: fetch a snapshot and let the deferral decide
                  // when to show it.
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
    runCatchingCancellable { fetchCatalogRecovery(config.designId) }
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
  // Asked once the authoring catalog is known; a catalog without `remote-compose/document` couldn't
  // use the answer.
  var remoteComposeSources by remember { mutableStateOf(emptyList<RemoteComposeSource>()) }
  LaunchedEffect(loadedCatalog) {
    remoteComposeSources =
      if (loadedCatalog?.componentsById?.containsKey(REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID) == true)
        loadRemoteComposeSources(activeCatalogSystemId)
      else emptyList()
  }
  /**
   * Making a design, by whichever of the two routes this session has. A server design is created by
   * the New design form (its `303` puts the permalink in history). A local design is seeded in-page
   * from the same [UiBuilderNewDesignSeed], written to this browser, and the URL rewritten.
   */
  val createPublicDesign: ((String, String, String, List<NewDesignState>) -> Unit)? =
    if (localSession == null && config.canWrite && config.designVisibilitySupported) {
      { catalog, design, template, state ->
        navigateToNewDesign(
          catalog,
          design,
          template,
          encodeNewDesignStates(state),
          publicRead = true,
        )
      }
    } else null
  val createDesign: (String, String, String, List<NewDesignState>) -> Unit =
    { catalogSystemId, designId, templateId, state ->
      if (localSession == null && config.canWrite) {
        navigateToNewDesign(catalogSystemId, designId, templateId, encodeNewDesignStates(state))
      } else {
        // In this browser: a local session, or a server that would refuse this caller's create.
        val intoBrowser = localSession == null
        scope.launch {
          val failure =
            createLocalDesign(
              session = localSession ?: BrowserLocalSession(config.copy(localStorage = true)),
              catalogs = catalogCapabilities,
              catalogSystemId = catalogSystemId,
              designId = designId,
              templateId = templateId,
              state = state,
            )
          if (failure != null) {
            sessionStatus = "Local error · $failure"
          } else {
            if (intoBrowser) enterLocalDesignUrl(designId)
            else canonicalizeUiBuilderUrl(designId, DesignUrlSelectors())
            onOpenDesign(
              config.copy(
                catalogSystemId = catalogSystemId,
                designId = designId,
                startWithNewDesign = false,
                localStorage = true,
              )
            )
          }
        }
      }
    }

  // Taking the design into this browser, and bringing it home again, each offered only where it
  // applies. Rules (fork point, base chain, refusals):
  // `docs/design/UI_BUILDER_DESIGN_PORTABILITY.md`.
  val storedRecord =
    remember(config.designId, config.localStorage, localSession) {
      localSession?.store?.read(config.designId)
    }
  // Making a read-only caller's copy. Guarded: edits that arrive while the copy is being written
  // belong to the copy's session, which is about to replace this one.
  var forking by remember(config.designId) { mutableStateOf(false) }
  val forkIntoBrowser: (EditorSubmission?, String?) -> Unit = { submission, because ->
    val wire = authoritativeDocument
    if (wire != null && !forking) {
      forking = true
      val copyId = NewDesignNames.random()
      val outcome = forkDesignIntoBrowser(wire, activeCatalogSystemId, copyId)
      if (outcome != null) {
        forking = false
        sessionStatus = "Local error · $outcome"
      } else {
        // Only an edit batch can start a copy: a fresh read-only session has nothing to undo.
        val carried =
          (submission as? EditorSubmission.Batch)?.toProtocolSubmission(
            actorId = config.actorId,
            clientId = config.clientId,
            authoritativeRevision = wire.revision.toInt(),
          ) as? DesignCommandV1
        enterLocalDesignUrl(copyId)
        onOpenDesign(
          config.copy(
            designId = copyId,
            localStorage = true,
            startWithNewDesign = false,
            carriedCommand = carried,
            copiedFromDesignId = config.designId,
            copiedBecause = because,
          )
        )
      }
    }
  }
  LaunchedEffect(refusedForAccess) {
    val refused = refusedForAccess ?: return@LaunchedEffect
    forkIntoBrowser(refused.submission, "The server did not save your last edit: ${refused.reason}")
  }
  val takeOffline: (() -> Unit)? =
    // Not for a caller who may not write: a checkout keeps this design's id so that it can sync
    // back, and the server would refuse that sync. Such a caller's edits already make a copy.
    if (localSession != null || !config.canWrite) null
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

  // A browser-made or copied design going to the server as a new design (a create under its own id,
  // never a merge). Only when the server would accept this caller's create; checkouts use
  // [syncToServer].
  val publishToServer: (() -> Unit)? =
    if (localSession == null || !config.canWrite || storedRecord?.origin != null) null
    else
      authoritativeDocument?.let { wire ->
        {
          scope.launch {
            sessionStatus = "Publishing ${wire.id} to the server…"
            val refusal = publishDesignToServer(wire)
            if (refusal == null) {
              // The browser copy stays where it is, in case the person wants it; the home screen
              // lists it with Delete beside it.
              navigateToDesign(wire.id)
            } else {
              sessionStatus = "Not published · $refusal"
            }
          }
        }
      }

  // The home screen's "In this browser" list: read straight from storage, which is cheap and
  // synchronous, and read again after a delete.
  var browserDesignSummaries by remember {
    mutableStateOf(LocalDesignStore(BrowserLocalDesignStorage()).list())
  }
  // Another tab adding, editing or deleting a design kept here changes this list too.
  DisposableEffect(Unit) {
    val stop = BrowserLocalStorageBackend.onExternalChange { key ->
      if (key.startsWith(LocalDesignStore.DESIGN_KEY_PREFIX)) {
        browserDesignSummaries = LocalDesignStore(BrowserLocalDesignStorage()).list()
      }
    }
    onDispose { stop() }
  }
  // The browser's own account of this origin's storage, for the "nearly full" notice.
  var browserStorageEstimate by remember { mutableStateOf<BrowserStorageEstimate?>(null) }
  LaunchedEffect(browserDesignSummaries) {
    browserStorageEstimate = BrowserLocalStorageBackend.estimate()
  }

  if (config.startWithNewDesign && newDesignCatalogs.isNotEmpty()) {
    val signIn =
      config.signInUrl
        ?.takeIf { localSession == null && !config.canWrite }
        ?.let { url -> EditorNoticeAction("Sign in with GitHub") { navigateTo(url) } }
    // A white-labelled host's templates are its catalog's, which only the server seeds: a design
    // kept in this browser would start from none of them, so ask for a sign-in instead.
    val signInToCreate = signIn?.takeIf { uiBuilderBrand != null }
    UiBuilderNewDesignScreen(
      catalogs = newDesignCatalogs,
      initialCatalogSystemId = activeCatalogSystemId,
      // A design kept in this browser has no server index behind it, and nothing on the server to
      // copy: the home screen then shows the create panel alone, which is what it always was.
      designs = if (localSession == null) homeDesigns else emptyList(),
      onOpenDesign = if (localSession == null) ::navigateToDesign else null,
      // Hidden for callers the server would refuse: copying needs the design's snapshot first (see
      // #342).
      onCopyDesign =
        if (localSession == null && config.canWrite) {
          { source -> navigateToCopyDesign(source, NewDesignNames.random()) }
        } else null,
      onBrowseDesigns = if (localSession == null) ::navigateToDesignsIndex else null,
      onMoveDesign =
        if (localSession == null) {
          { designId, folder ->
            if (serverFoldersAvailable) {
              scope.launch {
                runCatchingCancellable { homeFolderHost.move(designId, folder) }
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
      loadThumbnail =
        if (localSession == null) {
          { designId, revision ->
            val encoded =
              fetchBase64(
                "/api/ui-builder/v1/designs/${encodeUriComponent(designId)}/thumbnail.png" +
                  "?revision=$revision"
              )
            Image.makeFromEncoded(Base64.decode(encoded)).toComposeImageBitmap()
          }
        } else null,
      browserDesigns =
        browserHomeDesigns(browserDesignSummaries) { at -> formatLocalDateTime(at.toDouble()) },
      onOpenBrowserDesign = ::navigateToBrowserDesign,
      onDownloadBrowserDesign = { designId ->
        downloadBrowserDesign(designId)?.let { sessionStatus = "Local error · $it" }
      },
      browserDesignSaveLabel = remember { browserDesignSaveLabel() },
      onDeleteBrowserDesign = { designId ->
        LocalDesignStore(BrowserLocalDesignStorage()).delete(designId)
        browserDesignSummaries = LocalDesignStore(BrowserLocalDesignStorage()).list()
      },
      browserStorageNotice =
        listOfNotNull(
            BrowserLocalStorageBackend.unavailableReason?.let {
              "This browser's design database could not be opened ($it), so designs kept here " +
                "on an earlier visit are not shown."
            },
            localStorageNotice(browserDesignSummaries, estimate = browserStorageEstimate),
          )
          .joinToString(" ")
          .ifEmpty { null },
      // Said before anything is made, not discovered when it is: this account's new designs are
      // kept in this browser, and here is why and how to change that.
      accountNotice =
        if (localSession != null || config.canWrite || signInToCreate != null) null
        else
          listOfNotNull(
              "New designs you make here are kept in this browser.",
              config.writeDeniedReason,
            )
            .joinToString(" "),
      accountNoticeAction = signIn.takeIf { signInToCreate == null },
      signInToCreate = signInToCreate,
      onCreate = createDesign,
      onCreatePublic = createPublicDesign,
    )
    LaunchedEffect(newDesignCatalogs) { markReady() }
    return
  }
  if (loadedDocument != null && loadedCatalog != null) {
    // A `#thread=` naming no conversation on this board, decided only once the board has loaded (it
    // arrives on its own socket after the design).
    val staleThreadId = linkedThreadId?.takeIf {
      commentBoardLoaded && commentBoard.threads.none { thread -> thread.id == it }
    }
    // Clear the stale thread from the address bar and the published state. [linkedThreadId] is
    // deliberately kept: the notice derives from it, and there is no card to exempt.
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
    // The record this host generates this catalog's exports from, for the code pane and problems
    // panel; without it they judge by the embedded `m3-catalog` record. Fetched after the design is
    // on screen since it's large and nothing draws with it; failure leaves the editor as is. See
    // `UiBuilderEditorReducer`.
    var catalogRecord by remember(loadedCatalog) { mutableStateOf<ComponentRecordFile?>(null) }
    LaunchedEffect(loadedCatalog) {
      catalogRecord = fetchCatalogRecord(loadedCatalog.benchmark.catalogSystemId)
    }
    val chatDocument by rememberUpdatedState(loadedDocument)
    val chatInstructions by rememberUpdatedState(agentPreferences.instructions())
    val chatComments by rememberUpdatedState(commentBoard)
    val chatSelection by rememberUpdatedState(selectedNodeId)
    val browserChat =
      remember(config.designId, config.actorId) {
        UiBuilderChatController(
          BrowserChatHost(
            config.designId,
            config.actorId,
            monitoringAvailable = revisionPin?.pinned != true,
          ) {
            agentChatContext(chatDocument, chatSelection, chatInstructions, chatComments)
          },
          scope,
          actorId = config.actorId,
        )
      }
    DisposableEffect(browserChat) { onDispose { browserChat.stop() } }
    LaunchedEffect(browserChat, commentBoard, commentBoardLoaded, commentStatus) {
      browserChat.comments(commentBoard, available = commentBoardLoaded && commentStatus == null)
    }
    val agentHost =
      if (localSession != null || !isDesignUrlPathSafe(config.designId)) null
      else
        object : UiBuilderAgentHost {
          override val chat = browserChat
          override val preferences = agentPreferences
          override val agents = activeAgents
          override val viewers = collaborators

          override fun prompt(includeSetup: Boolean, instructions: String) =
            agentUiBuilderPrompt(
              "${pageOrigin().trimEnd('/')}/mcp",
              shareableUrl(designUrlPath(config.designId, basePath = uiBuilderBasePath)),
              config.designId,
              includeSetup,
              instructions,
            )

          override fun save(preferences: UiBuilderAgentPreferences): String? {
            val failure = saveAgentPreferences(config.designId, preferences)
            if (failure == null) agentPreferences = preferences
            return failure
          }

          override suspend fun copy(text: String) = copyAiPrompt(text)

          override fun openSetup() = openAgentSetup(AGENT_SETUP_URL)

          override fun connectVsCode() =
            connectAgentVsCode("${pageOrigin().trimEnd('/')}/mcp", UI_BUILDER_MCP_SERVER_NAME)
        }
    LaunchedEffect(config.designId, agentPreferences) {
      if (agentHost != null)
        publishAgentHandoff(
          config.designId,
          "${pageOrigin().trimEnd('/')}/mcp",
          AGENT_SETUP_URL,
          agentHost.prompt(false, ""),
        )
    }
    // The Issues panel's guidelines check, using the person's own OpenRouter key: requests go
    // straight to openrouter.ai, never via the design host. A sign-in return code is exchanged
    // first.
    val guidelines =
      remember(config.designId, config.localStorage) {
        DesignGuidelineController(
          BrowserGuidelineHost(config.designId, hostedByServer = !config.localStorage)
        )
      }
    LaunchedEffect(guidelines) { guidelines.completeSignIn() }
    // Read when the design opens (not when the Issues panel does) so the canvas overlay shows a
    // recorded finding at once.
    LaunchedEffect(guidelines) {
      guidelines.loadAccess()
      guidelines.loadShared()
    }
    CompositionLocalProvider(LocalDesignGuidelineCheck provides guidelines) {
      UiBuilderEditor(
        document = loadedDocument,
        agentHost = agentHost,
        catalog = loadedCatalog,
        catalogRecord = catalogRecord,
        pageDestinations = pageDestinations,
        onNavigatePage = { pageKey -> navigateToUiBuilderPage(config.catalogSystemId, pageKey) },
        actorId = config.actorId,
        clientId = config.clientId,
        operationIdPrefix = config.operationIdPrefix,
        sessionLabel = sessionStatus,
        visibilityLabel =
          if (localSession != null) "Private · this browser"
          else if (!config.designVisibilitySupported) null
          else
            when (designVisibility?.visibility) {
              "public" -> "Public (read only) · anyone with the link"
              "private" -> "Private · invited collaborators only"
              else -> "Visibility unavailable"
            },
        onManageVisibility =
          if (designVisibility?.canManage == true) ({ openDesignSharing(config.designId) })
          else null,
        // No Reconnect while a revision is pinned: there is no socket, and reconnecting would
        // replace the document with the head under the revision banner.
        onReconnect =
          if (revisionPin?.pinned == true) null
          else {
            {
              updates?.reconnect()
              refreshSnapshot("Reconnecting…")
            }
          },
        onSubmission = { submission ->
          // A server design this caller may read but not write: the edit is the moment it becomes
          // theirs, as a copy in this browser. Nothing is sent to a server that would refuse it.
          val route =
            editorEditRoute(
              keptInBrowser = localSession != null,
              canWrite = config.canWrite && designVisibility?.canWrite != false,
              revisionPinned = revisionPin?.pinned == true,
            )
          if (route == EditorEditRoute.BrowserCopy) {
            forkIntoBrowser(submission, null)
          } else {
            // Queued rather than sent: the revision this command claims, and the order it reaches
            // the server in, are the drain loop's to decide.
            sync.enqueueSubmission()
            if (submissions.trySend(submission).isFailure) {
              sync.completeSubmission()
              sessionStatus = "Live error · the edit queue is closed"
            }
          }
        },
        authoritativeGeneration = authoritativeGeneration,
        authoritativeRevisionFor = { nodeId ->
          authoritativeDocument?.takeIf { it.nodes.containsKey(nodeId) }?.revision
        },
        initialSelectedNodeId = selectedNodeId,
        // `#thread=` wins the panel where a link names both; `?node=` still selects the layer.
        initialInspectorMode =
          if (config.selectors.threadId != null) EditorInspectorMode.Comments
          else EditorInspectorMode.Properties,
        initialInspectorOpen = config.selectors.nodeId != null || config.selectors.threadId != null,
        linkedThreadId = linkedThreadId,
        threadNavigations = threadNavigations,
        onSelectedThreadChanged = {
          openThreadId = it
          // Clear the mirrored thread when the reader closes or switches threads: `replaceState`
          // fires no `hashchange`, and a stale value would keep a resolved card visible via
          // `revealThreadId`.
          if (it != linkedThreadId) {
            dropDesignUrlFragment()
            linkedThreadId = null
          }
        },
        revisionPin = revisionPin,
        // Only when the page is actually pinned somewhere other than latest.
        onGoToLatest = revisionPin?.takeIf { it.pinned }?.let { { goToLatestRevision() } },
        openingNotice =
          listOfNotNull(
              // First, because it is about the edits being made right now.
              browserStorageProblem,
              // A server design this caller may look at but not change. Said once, up front, rather
              // than discovered as a refused save.
              readOnlyNotice(config).takeIf { localSession == null },
              config.copiedBecause,
              config.copiedFromDesignId?.let {
                "Editing a copy in this browser. The original ($it) is unchanged, and this copy is " +
                  "kept only in this browser."
              },
              config.selectors.nodeId
                ?.takeIf { !loadedDocument.nodes.containsKey(it) }
                ?.let { "This link names a layer this design does not have: $it" },
              staleThreadId?.let {
                "This link names a conversation this design does not have: $it"
              },
            )
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" "),
        openingNoticeAction =
          (if (changedInAnotherTab) EditorNoticeAction("Reload") { reloadBrowserPage() } else null)
            ?: config.copiedFromDesignId?.let { source ->
              EditorNoticeAction("Open original") {
                navigateTo(uiBuilderPagePath(uiBuilderBasePath, source))
              }
            }
            ?: config.signInUrl
              ?.takeIf { localSession == null && !config.canWrite }
              ?.let { url -> EditorNoticeAction("Sign in") { navigateTo(url) } },
        // Withheld for a design id that isn't path-safe: the link would not open. See
        // [isDesignUrlPathSafe].
        onCopyDesignLink =
          if (!isDesignUrlPathSafe(config.designId)) null
          else
            { selectors ->
              val result =
                copyDesignLink(designUrlPath(config.designId, selectors, uiBuilderBasePath))
              // Only on a copy that landed: a failure message must not read as a sharing hint.
              if (designVisibility?.visibility == "private" && result.startsWith("Link copied"))
                "$result · Private: recipients need access from the owner."
              else result
            },
        initialCatalogQuery = catalogQuery,
        initialEnabledPacks = enabledPacks,
        initialPinnedComponents = pinnedComponents,
        collaborators = collaborators + activeAgents.orEmpty(),
        componentDrift = componentDrift,
        componentLibrary = componentLibrary,
        loadLibrarySymbol = if (localSession == null) ::loadLibrarySymbol else null,
        publishLibraryComponent = if (localSession == null) ::publishLibraryComponent else null,
        onLibraryChanged = { libraryGeneration += 1 },
        devicePresets = devicePresets,
        newDesignCatalogs = newDesignCatalogs,
        onCreateDesign = createDesign,
        onCreatePublicDesign = createPublicDesign,
        onBrowseDesigns = if (localSession == null) ::navigateToDesignsIndex else null,
        // A fork of the design as it is now, owned by whoever presses it: the copy route reads the
        // source as the caller, so this lends nothing a reader could not already open.
        onForkDesign =
          when {
            localSession != null -> null
            config.canWrite -> {
              { navigateToCopyDesign(config.designId, NewDesignNames.random()) }
            }
            // The server would refuse the copy route: the copy is made in this browser instead.
            else -> {
              { forkIntoBrowser(null, null) }
            }
          },
        onHelp = ::openUiBuilderGuide,
        onCopyAiPrompt =
          if (localSession != null || !isDesignUrlPathSafe(config.designId)) null
          else {
            {
              copyAiPrompt(
                agentUiBuilderPrompt(
                  mcpEndpoint = "${pageOrigin().trimEnd('/')}/mcp",
                  designUrl =
                    shareableUrl(designUrlPath(config.designId, basePath = uiBuilderBasePath)),
                  designId = config.designId,
                )
              )
            }
          },
        onTakeOffline = takeOffline,
        onSyncToServer = syncToServer,
        onPublishToServer = publishToServer,
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
        // This lane keeps what it renders (the reference the canvas is traced against), so it must
        // use the pinned revision.
        onSnapshotDesign = {
          references.snapshotDesign(revisionPin?.takeIf { it.pinned }?.requested)
        },
        onImportReferenceUrl = { url -> references.fetchUrl(url) },
        referenceStatus = referenceStatus,
        pastedReference = pastedReference,
        comments = commentBoard,
        commentStatus = commentStatus,
        commentNotifications = commentNotificationsState,
        onToggleCommentNotifications = {
          // Undispatched, so the permission request inside runs within this click's handler: the
          // browser only shows its prompt for a gesture, and Safari checks that strictly.
          scope.launch(start = CoroutineStart.UNDISPATCHED) { commentNotifications.toggle() }
        },
        onPostComment = { draft ->
          scope.launch { commentStatus = commentHost.post(draft, config.displayName) }
        },
        onResolveCommentThread = { threadId, resolved ->
          scope.launch { commentStatus = commentHost.resolve(threadId, resolved) }
        },
        review = review,
        reviewStatus = reviewStatus,
        suggestions = suggestions,
        suggestionStatus = suggestionStatus,
        onAcceptSuggestion =
          if (!suggestionsAvailable) null
          else
            { id ->
              suggestions.open
                .firstOrNull { it.suggestionId == id }
                ?.let { suggestion ->
                  scope.launch {
                    when (val result = suggestionHost.accept(suggestion)) {
                      is BrowserSuggestionHost.Result.Decided -> {
                        suggestions = suggestions.copy(lastOutcome = result.outcome)
                        suggestionStatus = null
                        suggestionsGeneration += 1
                      }
                      is BrowserSuggestionHost.Result.Failed -> suggestionStatus = result.reason
                    }
                  }
                }
            },
        onRejectSuggestion =
          if (!suggestionsAvailable) null
          else
            { id ->
              suggestions.open
                .firstOrNull { it.suggestionId == id }
                ?.let { suggestion ->
                  scope.launch {
                    when (val result = suggestionHost.reject(suggestion)) {
                      is BrowserSuggestionHost.Result.Decided -> {
                        suggestions = suggestions.copy(lastOutcome = result.outcome)
                        suggestionStatus = null
                        suggestionsGeneration += 1
                      }
                      is BrowserSuggestionHost.Result.Failed -> suggestionStatus = result.reason
                    }
                  }
                }
            },
        onDecide =
          if (!reviewAvailable) null
          else
            { verdict, note ->
              val revision = (latestEditorDocument ?: document)?.revision
              if (revision != null) {
                scope.launch {
                  when (
                    val result =
                      reviewHost.decide(revision.toLong(), verdict, note, config.displayName)
                  ) {
                    is BrowserReviewHost.Result.Recorded -> {
                      review = result.review
                      reviewStatus = null
                    }
                    is BrowserReviewHost.Result.Refused -> reviewStatus = result.reason
                  }
                }
              }
            },
        onStateChanged = {
          latestEditorDocument = it.document
          // Drop the node from the address bar once the selection moves off it.
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
        // `candidate` predates catalog-delivered runtimes and names no immutable archive; keep its
        // in-process canvas until every deployed catalog is verified through the hosted editor and
        // native-export lanes.
        canvasRenderer =
          if (
            loadedDocument.catalogPin["nativeRuntimeId"]?.jsonPrimitive?.contentOrNull ==
              "candidate"
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
        onInspectionSnapshot = {
          inspectionPublisher.publish(it)
          initialDesignLayout.complete(Unit)
        },
        onInspectionInvalidated = { collector ->
          inspectionPublisher.offer(collector, loadedDocument.revision)
          initialDesignLayout.complete(Unit)
        },
        // `onRequestNativeRender` (below): a server-side Compose render of the stored design,
        // pinned to the viewed revision and host shape so it matches the canvas (on `wear-m3` the
        // Wasm canvas is only a stand-in).
        // `onOpenNativeStream`: the live half of the same lane, one instance per session; the
        // editor opens it when a render names a session and closes it when that session changes or
        // the pane goes away.
        onOpenNativeStream = { live -> BrowserNativeStream(live) },
        onRequestNativeRender = { hostShape ->
          if (config.localStorage) UiBuilderNativeRender(failure = LOCAL_NATIVE_RENDER_UNAVAILABLE)
          else if (!config.canWrite) UiBuilderNativeRender(failure = serverOnlyRefusal(config))
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
        // For URLs the design names: held to Remote Compose document routes ([designReferenceUrl])
        // before [sameOriginRequestUrl] adds the token; anything else is drawn as the node's
        // diagnostic.
        resolveRemoteComposeUrl = { url ->
          fetchBase64(designReferenceUrl(url, DesignReferenceKind.RemoteComposeDocument))
        },
        // The design's own uploaded pictures, same-origin and read as this page's actor.
        resolveDesignAsset = { assetKey ->
          Base64.decode(
            fetchBase64("/api/ui-builder/v1/designs/${config.designId}/assets/$assetKey")
          )
        },
        // Same-origin, ingested-document route only ([designReferenceUrl]), so a design can't fetch
        // arbitrary JSON as this viewer; external animations are pasted into the element's `json`
        // instead.
        loadLottieAnimation = { url ->
          fetchText(designReferenceUrl(url, DesignReferenceKind.LottieAnimation))
        },
      )
    }
    LaunchedEffect(loadedDocument.revision) { markReady() }
    // Publish what the URL's selectors resolved to, for the harness and link debugging; "selected"
    // is a different claim from "drawn with an outline".
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
    // A refused open: draw the service's explanation instead of a blank page.
    val failure = openFailure
    if (failure != null) {
      UiBuilderUnavailableScreen(
        designId = config.designId,
        // Only a catalog the open actually installed. Before that, this is the page's default,
        // `m3-catalog`, and a Wear design that failed to open was captioned as an M3 one.
        catalogSystemId = activeCatalogSystemId.takeIf { catalog != null },
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
      // Ready means settled, not successful: mark it on a settled failure so the harness reports
      // the reason instead of timing out. Not while `openFailure` is null, since the open may still
      // succeed.
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

/** An edit the server refused for who sent it, and the server's reason. */
internal class RefusedEdit(val submission: EditorSubmission, val reason: String)

/**
 * Why a server-drawn or server-stored feature — export, native render, reference pictures — is not
 * available to a caller who may not write: the server's own reason where it gave one.
 */
internal fun serverOnlyRefusal(config: LiveSessionConfig): String =
  listOfNotNull(
      "The server does this only for accounts that can save designs here.",
      config.writeDeniedReason,
    )
    .joinToString(" ")

/**
 * What a caller who may read but not write is told when a server design opens: the server's own
 * reason when it gave one. Null when this caller may write, which is every older server.
 */
internal fun readOnlyNotice(config: LiveSessionConfig): String? =
  if (config.canWrite) null
  else
    listOfNotNull(
        "You can view this design, but changes will not be saved to this server.",
        config.writeDeniedReason,
      )
      .joinToString(" ")

/** How often an open editor polls for new suggestions (they don't arrive over the socket). */
private const val SUGGESTION_POLL_INTERVAL_MILLIS = 15_000L

@kotlinx.serialization.Serializable
private data class DesignVisibilityPayload(
  val visibility: String,
  val canWrite: Boolean,
  val canManage: Boolean,
)
