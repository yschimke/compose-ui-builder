@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.ui.ExperimentalComposeUiApi::class,
  kotlin.js.ExperimentalWasmJsInterop::class,
)

package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.validateCapabilities
import ee.schimke.composeai.uibuilder.client.BrowserUiBuilderHttpTransport
import ee.schimke.composeai.uibuilder.client.BrowserUiBuilderSocketState
import ee.schimke.composeai.uibuilder.client.BrowserUiBuilderWebSocketTransport
import ee.schimke.composeai.uibuilder.client.MonotonicUiBuilderRequestIds
import ee.schimke.composeai.uibuilder.client.UiBuilderClientUpdate
import ee.schimke.composeai.uibuilder.client.UiBuilderHttpRequest
import ee.schimke.composeai.uibuilder.client.UiBuilderHttpResult
import ee.schimke.composeai.uibuilder.client.UiBuilderLiveSessionApi
import ee.schimke.composeai.uibuilder.client.UiBuilderProtocolHttpClient
import ee.schimke.composeai.uibuilder.client.UiBuilderProtocolUpdateClient
import ee.schimke.composeai.uibuilder.client.preparePropertyDelta
import ee.schimke.composeai.uibuilder.client.toProtocolSubmission
import ee.schimke.composeai.uibuilder.client.toRendererDocument
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogsResponseV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.ListCatalogsRequestV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.OperationOutcomeResponseV1
import ee.schimke.composeai.uibuilder.protocol.PresenceV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import ee.schimke.composeai.uibuilder.protocol.UpdatePresenceRequestV1
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.Image

/**
 * Let the editor own the right button.
 *
 * The editor answers a right-click on a layer with its own menu, and the browser answers the same
 * press with the page menu drawn on top of it. Only one of them can be the one that opens.
 */
@JsFun(
  """() => {
  const host = document.getElementById('composeApp');
  if (host) host.addEventListener('contextmenu', (event) => event.preventDefault());
}"""
)
private external fun suppressBrowserContextMenu()

fun main() {
  val rendererRuntimeId = sandboxRendererRuntimeId()
  if (rendererRuntimeId.isNotEmpty()) {
    MainScope().launch {
      val fixture =
        Json.parseToJsonElement(fetchText("jetcaster-discover-operations-v1.json")).jsonObject
      val fixtureDocument = UiBuilderReducer.replay(fixture).document
      // The checked-in benchmark predates retained runtimes and carries the placeholder
      // `candidate` pin. This isolated transport fixture gives that document the exact runtime
      // selected by the test shell; production documents arrive with this pin already persisted.
      val document =
        fixtureDocument.copy(
          catalogPin =
            JsonObject(
              fixtureDocument.catalogPin +
                ("nativeRuntimeId" to kotlinx.serialization.json.JsonPrimitive(rendererRuntimeId))
            )
        )
      mountSandboxRenderer(
        rendererRuntimeId,
        inspectionJson.encodeToString(UiBuilderDocument.serializer(), document),
      )
    }
    return
  }
  suppressBrowserContextMenu()
  ComposeViewport(viewportContainerId = "composeApp") {
    if (liveSessionEnabled()) LiveSessionApp() else VisualFixtureApp(captureMode())
  }
}

@JsFun(
  """() => {
    const value = new URLSearchParams(globalThis.location.search).get('rendererRuntimeId') || '';
    if (value && (!/^[A-Za-z0-9._-]+$/.test(value) || value === 'latest' || value === 'current')) {
      throw new Error('rendererRuntimeId must be an exact safe runtime id');
    }
    return value;
  }"""
)
private external fun sandboxRendererRuntimeId(): String

/**
 * Minimal editor-side vertical slice for the isolated runtime. The iframe owns design pixels; the
 * absolutely positioned sibling owns selection geometry and never participates in renderer layout.
 * Semantic actions target inspected Compose nodes by stable id; the sibling overlay remains
 * pointer-inert and outside the renderer's Compose tree.
 */
private fun mountSandboxRenderer(runtimeId: String, documentJson: String): Unit =
  js(
    """(async function () {
      const protocolVersion = 1;
      const schema = 'compose-ui-builder-renderer/v1';
      const root = '/ui-builder/runtime/' + encodeURIComponent(runtimeId) + '/';
      const response = await fetch(root + 'runtime-manifest.json', {
        credentials: 'same-origin', headers: { Accept: 'application/json' }
      });
      if (!response.ok) throw new Error('runtime manifest HTTP ' + response.status);
      const manifest = await response.json();
      if (manifest.schema !== 'compose-ui-builder-runtime/v1' ||
          manifest.runtimeId !== runtimeId || manifest.protocolVersion !== protocolVersion ||
          typeof manifest.entrypoint !== 'string' ||
          !/^[A-Za-z0-9._/-]+$/.test(manifest.entrypoint) ||
          manifest.entrypoint.split('/').some((part) => !part || part === '.' || part === '..')) {
        throw new Error('pinned runtime manifest does not match the editor protocol');
      }

      const shell = document.getElementById('composeApp');
      shell.replaceChildren();
      shell.style.position = 'relative';
      const frame = document.createElement('iframe');
      frame.id = 'ui-builder-renderer-frame';
      frame.title = 'Native Compose design renderer';
      frame.sandbox = 'allow-scripts';
      frame.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;border:0;background:transparent';
      frame.src = root + manifest.entrypoint;
      const overlay = document.createElement('div');
      overlay.id = 'ui-builder-renderer-overlay';
      overlay.setAttribute('aria-hidden', 'true');
      overlay.style.cssText = 'position:absolute;inset:0;pointer-events:none;overflow:hidden';
      shell.append(frame, overlay);

      let sequence = 0;
      let initialized = false;
      let initializeTimer = null;
      const pending = new Map();
      const responses = new Map();
      const rendererGeometry = () => {
        const frameRect = frame.getBoundingClientRect();
        const shellRect = shell.getBoundingClientRect();
        return {
          offsetX: frameRect.left - shellRect.left,
          offsetY: frameRect.top - shellRect.top,
          scaleX: frameRect.width / frame.clientWidth,
          scaleY: frameRect.height / frame.clientHeight,
        };
      };
      const rendererToShell = (x, y) => {
        const geometry = rendererGeometry();
        return {
          x: geometry.offsetX + x * geometry.scaleX,
          y: geometry.offsetY + y * geometry.scaleY,
        };
      };
      const request = (type, payload) => {
        const requestId = 'browser-' + (++sequence);
        const document = type === 'renderDocument' ? payload?.document : null;
        const action = type === 'dispatchAction' ? payload : null;
        pending.set(requestId, {
          type,
          documentId: document?.id ?? action?.documentId,
          documentRevision: document?.revision ?? action?.documentRevision,
        });
        frame.contentWindow.postMessage(JSON.stringify({
          schema, protocolVersion, runtimeId, requestId, type, payload: payload || {}
        }), '*'); // opaque sandbox origins require `*`; source and response origin are checked.
        return requestId;
      };
      const finiteBound = (value) => Number.isFinite(value) && Math.abs(value) <= 1000000;
      const validBounds = (bounds) => bounds == null || (
        finiteBound(bounds.x) && finiteBound(bounds.y) &&
        finiteBound(bounds.width) && finiteBound(bounds.height) &&
        bounds.width >= 0 && bounds.height >= 0
      );
      const validInspection = (inspection, expected) => {
        if (!inspection || inspection.schema !== 'compose-ui-builder-inspection/v1' ||
            inspection.documentId !== expected.documentId ||
            inspection.documentRevision !== expected.documentRevision ||
            inspection.coordinateSpace !== 'root-render-pixels' ||
            inspection.coordinatePrecision !== '1/64px' ||
            !Array.isArray(inspection.nodes) || inspection.nodes.length === 0 ||
            inspection.nodes.length > 10000 || !Array.isArray(inspection.slots) ||
            inspection.slots.length > 20000 || !inspection.generation ||
            inspection.generation.key !== inspection.documentId + '@' + inspection.documentRevision ||
            !Number.isInteger(inspection.generation.stabilityFrames) ||
            inspection.generation.stabilityFrames < 1 || inspection.generation.stabilityFrames > 120)
          return false;
        for (const field of ['expectedAuthoredNodeIds', 'expectedAuthoredTextNodeIds',
                             'measuredNodeIds', 'measuredTextNodeIds']) {
          if (!Array.isArray(inspection.generation[field]) ||
              inspection.generation[field].length > 10000) return false;
        }
        const ids = new Set();
        for (const node of inspection.nodes) {
          if (!node || typeof node.nodeId !== 'string' || !node.nodeId ||
              node.nodeId.length > 512 || ids.has(node.nodeId) || !validBounds(node.bounds) ||
              !node.semantics || !Array.isArray(node.semantics.actions) ||
              node.semantics.actions.length > 64) return false;
          if (node.text && (!Number.isInteger(node.text.lineCount) || node.text.lineCount < 0 ||
              !finiteBound(node.text.firstBaselineY) || !finiteBound(node.text.lastBaselineY)))
            return false;
          ids.add(node.nodeId);
        }
        return inspection.slots.every((slot) => slot &&
          typeof slot.parentNodeId === 'string' && slot.parentNodeId &&
          typeof slot.slotName === 'string' &&
          Array.isArray(slot.childNodeIds) && slot.childNodeIds.length <= 10000 &&
          Array.isArray(slot.measuredChildNodeIds) && slot.measuredChildNodeIds.length <= 10000 &&
          validBounds(slot.bounds));
      };
      const drawOverlay = (inspection) => {
        overlay.replaceChildren();
        const geometry = rendererGeometry();
        for (const node of inspection.nodes || []) {
          if (!node.bounds) continue;
          const marker = document.createElement('div');
          marker.dataset.nodeId = node.nodeId;
          marker.style.cssText = 'position:absolute;box-sizing:border-box;border:1px solid transparent';
          const topLeft = rendererToShell(node.bounds.x, node.bounds.y);
          marker.style.left = topLeft.x + 'px';
          marker.style.top = topLeft.y + 'px';
          marker.style.width = (node.bounds.width * geometry.scaleX) + 'px';
          marker.style.height = (node.bounds.height * geometry.scaleY) + 'px';
          overlay.append(marker);
        }
        globalThis.__uiBuilderSandboxInspection = inspection;
        globalThis.__uiBuilderSandboxOverlayCount = overlay.childElementCount;
      };
      addEventListener('message', (event) => {
        if (event.source !== frame.contentWindow || event.origin !== 'null' || typeof event.data !== 'string') return;
        let message;
        try { message = JSON.parse(event.data); } catch { return; }
        if (message.schema !== schema || message.protocolVersion !== protocolVersion ||
            message.runtimeId !== runtimeId || !pending.has(message.requestId)) return;
        const expected = pending.get(message.requestId);
        const expectedResponse = expected.type === 'initialize' ? 'initialized' :
          expected.type === 'renderDocument' ? 'rendered' :
          expected.type === 'dispatchAction' ? 'actionDispatched' : null;
        if (message.type !== 'error' && message.type !== expectedResponse) return;
        if ((message.type === 'rendered' || message.type === 'actionDispatched') &&
            !validInspection(message.payload?.inspection, expected)) return;
        pending.delete(message.requestId);
        responses.set(message.requestId, message);
        if (message.type === 'initialized') {
          if (initialized) return;
          initialized = true;
          if (initializeTimer !== null) clearInterval(initializeTimer);
          request('renderDocument', { document: JSON.parse(documentJson) });
        } else if (message.type === 'rendered') {
          drawOverlay(message.payload.inspection);
          document.documentElement.dataset.uiBuilderSandboxReady = 'true';
        } else if (message.type === 'actionDispatched') {
          drawOverlay(message.payload.inspection);
        } else if (message.type === 'error') {
          globalThis.__uiBuilderSandboxLastError = message.payload;
        }
      });
      frame.addEventListener('load', () => {
        request('initialize');
        initializeTimer = setInterval(() => {
          if (!initialized) request('initialize');
        }, 250);
      });
      globalThis.__uiBuilderSandboxDispatchAction = (payload) => request('dispatchAction', payload);
      globalThis.__uiBuilderSandboxResponse = (requestId) => responses.get(requestId) || null;
      globalThis.__uiBuilderSandboxActivateNode = (nodeId) => {
        const inspection = globalThis.__uiBuilderSandboxInspection;
        return request('dispatchAction', {
          documentId: inspection.documentId,
          documentRevision: inspection.documentRevision,
          nodeId,
          kind: 'activate',
        });
      };
      globalThis.__uiBuilderSandboxScrollNodeBy = (nodeId, deltaY) => {
        const inspection = globalThis.__uiBuilderSandboxInspection;
        return request('dispatchAction', {
          documentId: inspection.documentId,
          documentRevision: inspection.documentRevision,
          nodeId,
          kind: 'scrollBy',
          deltaX: 0,
          deltaY,
        });
      };
    })()"""
  )

private data class LiveSessionConfig(
  val catalogSystemId: String,
  val designId: String,
  val actorId: String,
  val clientId: String,
  val httpEndpoint: String,
  val webSocketEndpoint: String,
  val startWithNewDesign: Boolean,
  val operationIdPrefix: String,
  val displayName: String,
  val colorArgbHex: String,
)

/**
 * Resolves the server's view of this caller before the session starts.
 *
 * The page cannot name its own actor: the server derives it from the operator token, the GitHub
 * session or a presented agent grant, and rejects any request whose declared actor differs. So the
 * editor asks first, and only falls back to the historical guess when the endpoint is unreachable
 * (an older server, or a static host with no live session behind it).
 */
@Composable
private fun LiveSessionApp() {
  var config by remember { mutableStateOf<LiveSessionConfig?>(null) }
  LaunchedEffect(Unit) { config = liveSessionConfig(resolveServerActorId()) }
  config?.let { LiveSessionApp(it) }
}

@Composable
private fun LiveSessionApp(config: LiveSessionConfig) {
  val scope = rememberCoroutineScope()
  val http =
    remember(config) {
      UiBuilderProtocolHttpClient(
        actorId = config.actorId,
        endpoint = config.httpEndpoint,
        transport = BrowserUiBuilderHttpTransport(),
        requestIds = MonotonicUiBuilderRequestIds(config.clientId),
      )
    }
  var document by remember { mutableStateOf<UiBuilderDocument?>(null) }
  var authoritativeDocument by remember { mutableStateOf<DesignDocumentV1?>(null) }
  var catalog by remember { mutableStateOf<CapabilityCatalog?>(null) }
  var newDesignCatalogs by remember { mutableStateOf<List<UiBuilderNewDesignCatalog>>(emptyList()) }
  var devicePresets by remember { mutableStateOf<List<UiBuilderDevicePreset>>(emptyList()) }
  var sessionStatus by remember { mutableStateOf("Connecting…") }
  var updates by remember { mutableStateOf<UiBuilderProtocolUpdateClient?>(null) }
  var authoritativeGeneration by remember { mutableStateOf(0) }
  val inspectionPublisher = remember(scope) { CoalescingInspectionPublisher(scope) }
  var selectedNodeId by remember { mutableStateOf<String?>(null) }
  var catalogQuery by remember { mutableStateOf("") }
  var presenceState by remember { mutableStateOf(UiBuilderPresenceState()) }
  var socketState by remember { mutableStateOf(BrowserUiBuilderSocketState.CONNECTING) }

  fun acceptSnapshot(response: SnapshotResponseV1) {
    require(response.snapshot.state.document.catalogPin.systemId == config.catalogSystemId) {
      "design ${config.designId} belongs to ${response.snapshot.state.document.catalogPin.systemId}, not ${config.catalogSystemId}"
    }
    recordAuthoritativeReceipt(
      response.snapshot.state.document.revision.toInt(),
      response.snapshot.state.lastSequence,
    )
    authoritativeDocument = response.snapshot.state.document
    document = authoritativeDocument?.toRendererDocument()
    presenceState = presenceState.replace(response.snapshot.presence, browserNowMillis())
    authoritativeGeneration += 1
    sessionStatus =
      "${config.catalogSystemId} · Live · ${config.actorId}/${config.clientId} · seq ${response.snapshot.state.lastSequence}"
  }

  fun refreshSnapshot(reason: String) {
    scope.launch {
      sessionStatus = reason
      when (val result = http.execute(OpenDesignRequestV1(config.designId))) {
        is UiBuilderHttpResult.Response -> {
          val response = result.response as? SnapshotResponseV1
          if (response == null) sessionStatus = "Live error · unexpected snapshot response"
          else acceptSnapshot(response)
        }
        is UiBuilderHttpResult.ServiceError ->
          sessionStatus = "Live error · ${result.error.message}"
        is UiBuilderHttpResult.SnapshotRequired ->
          sessionStatus = "Snapshot required · ${result.error.message}"
      }
    }
  }

  LaunchedEffect(Unit) { devicePresets = loadDevicePresets() }

  // The reference overlay's browser half: the file picker, the paste listener, the snapshot and
  // the store behind them all. Rebuilt only when the design changes, because it is addressed to
  // one design.
  val references = remember(config.designId) { BrowserReferenceHost(config.designId, http) }
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
  LaunchedEffect(config.designId) {
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
  var commentStatus by remember(config.designId) { mutableStateOf<String?>(null) }

  // One socket for the life of the design. It sends the current board on connect, so there is no
  // fetch beside it to reconcile against — the load below is only the fallback for a host that
  // refuses the upgrade, where the panel is then a snapshot rather than a feed.
  DisposableEffect(config.designId) {
    val watch =
      commentHost.watch(
        onBoard = { board ->
          commentBoard = board
          commentStatus = null
        },
        onDropped = {
          commentStatus = "The comment feed dropped. Reload to watch this discussion again."
        },
      )
    onDispose { watch.close() }
  }
  LaunchedEffect(config.designId) {
    commentHost.load()?.let { board ->
      // Only if the socket has not already delivered something newer: the two race by design and
      // the sequence is what settles it, rather than whichever answer happened to arrive last.
      if (board.sequence > commentBoard.sequence) commentBoard = board
    }
  }

  // One paste listener for the life of the design, re-armed after every catch. Pasting is the
  // gesture Figma's own "copy as PNG" leaves you holding, so it goes straight to the base picture
  // rather than behind a menu.
  LaunchedEffect(config.designId) {
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
    val candidate = pendingReference ?: return@LaunchedEffect
    delay(REFERENCE_SAVE_DEBOUNCE_MILLIS)
    val imagesChanged =
      storedReference?.image?.id != candidate.image?.id ||
        storedReference?.pieces?.map { it.image.id } != candidate.pieces.map { it.image.id }
    referenceStatus = references.save(candidate, imagesChanged)
    if (referenceStatus == null) storedReference = candidate
  }

  LaunchedEffect(config) {
    val availableCatalogs = loadLiveCatalogs(http)
    newDesignCatalogs = availableCatalogs.mapNotNull(::newDesignCatalog)
    if (config.startWithNewDesign) return@LaunchedEffect
    val selectedCatalog =
      availableCatalogs.singleOrNull { it.benchmark.catalogSystemId == config.catalogSystemId }
        ?: error("UI builder is not enabled for catalog ${config.catalogSystemId}")
    catalog =
      CapabilityCatalogParser.parse(
        Json.encodeToJsonElement(CatalogCapabilityV1.serializer(), selectedCatalog)
      )
    val openResult = UiBuilderLiveSessionApi(config.designId, http).open()
    when (val result = openResult) {
      is UiBuilderHttpResult.Response -> {
        val response = result.response as? SnapshotResponseV1
        if (response == null) {
          sessionStatus = "Live error · unexpected open response"
          return@LaunchedEffect
        }
        acceptSnapshot(response)
        canonicalizeUiBuilderUrl(config.catalogSystemId, config.designId)
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
                val candidate = document?.let { rendererDocument ->
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
                val verified = candidate?.hasVerifiedHash() == true
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
                if (!verified) {
                  refreshSnapshot("Syncing remote edits…")
                } else {
                  recordAuthoritativeReceipt(
                    candidate.rendererDocument.revision,
                    update.update.delta.throughSequence,
                  )
                  authoritativeDocument = candidate.protocolDocument
                  document = candidate.rendererDocument
                  authoritativeGeneration += 1
                  sessionStatus =
                    "Live · ${config.actorId} · seq ${update.update.delta.throughSequence}"
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
      is UiBuilderHttpResult.ServiceError -> sessionStatus = "Live error · ${result.error.message}"
      is UiBuilderHttpResult.SnapshotRequired ->
        sessionStatus = "Snapshot required · ${result.error.message}"
    }
  }
  val activeUpdates = updates
  DisposableEffect(activeUpdates) { onDispose { activeUpdates?.close() } }

  LaunchedEffect(socketState) {
    publishSocketState(socketState.name.lowercase())
    if (socketState == BrowserUiBuilderSocketState.DISCONNECTED) {
      sessionStatus = "Disconnected · reconnect to resume collaboration"
    }
  }

  LaunchedEffect(config, socketState, selectedNodeId, document?.revision) {
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
        loadRemoteComposeSources(config.catalogSystemId)
      else emptyList()
  }
  if (config.startWithNewDesign && newDesignCatalogs.isNotEmpty()) {
    UiBuilderNewDesignScreen(
      catalogs = newDesignCatalogs,
      initialCatalogSystemId = config.catalogSystemId,
      onCreate = { catalogSystemId, designId, templateId, state ->
        navigateToNewDesign(catalogSystemId, designId, templateId, encodeNewDesignStates(state))
      },
    )
    LaunchedEffect(newDesignCatalogs) { markReady() }
    return
  }
  if (loadedDocument != null && loadedCatalog != null) {
    val collaborators = presenceState.collaborators(config.actorId)
    LaunchedEffect(collaborators) {
      publishPresenceManifest(
        Json.encodeToString(collaborators.map(UiBuilderCollaborator::actorId)),
        Json.encodeToString(collaborators.map(UiBuilderCollaborator::selectedNodeIds)),
      )
    }
    UiBuilderEditor(
      document = loadedDocument,
      catalog = loadedCatalog,
      actorId = config.actorId,
      clientId = config.clientId,
      operationIdPrefix = config.operationIdPrefix,
      sessionLabel = sessionStatus,
      onReconnect = {
        updates?.reconnect()
        refreshSnapshot("Reconnecting…")
      },
      onSubmission = { submission ->
        val expectedRevision = document?.revision ?: return@UiBuilderEditor
        scope.launch {
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
              refreshSnapshot(sessionStatus)
            }
            is UiBuilderHttpResult.ServiceError -> {
              sessionStatus = "Rejected · ${result.error.message}"
              refreshSnapshot(sessionStatus)
            }
            is UiBuilderHttpResult.SnapshotRequired ->
              refreshSnapshot("Snapshot recovery · ${result.error.message}")
          }
        }
      },
      authoritativeGeneration = authoritativeGeneration,
      initialSelectedNodeId = selectedNodeId,
      initialCatalogQuery = catalogQuery,
      collaborators = collaborators,
      devicePresets = devicePresets,
      newDesignCatalogs = newDesignCatalogs,
      onCreateDesign = { catalogSystemId, designId, templateId, state ->
        navigateToNewDesign(catalogSystemId, designId, templateId, encodeNewDesignStates(state))
      },
      onHelp = ::openUiBuilderGuide,
      restoredReference = restoredReference,
      onPickReference = { references.pickFile() },
      onSnapshotDesign = { references.snapshotDesign() },
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
        selectedNodeId = it.selectedNodeId
        catalogQuery = it.catalogQuery
        // Persisted from here rather than from each control, so every route that changes the
        // overlay — a slider, a stroke, a flatten, a paste — is stored by one path.
        if (referenceLoaded && it.reference != storedReference) pendingReference = it.reference
        publishEditorState(it)
      },
      onCanvasMetrics = ::publishEditorCanvasMetrics,
      onCanvasBoundsChanged = ::publishEditorCanvasBounds,
      onDropTargetChanged = ::publishEditorDropTarget,
      onInspectionInvalidated = { collector ->
        inspectionPublisher.offer(collector, loadedDocument.revision)
      },
      onRequestNativeRender = { requestNativeRender(config.designId) },
      remoteComposeSources = remoteComposeSources,
      resolveRemoteComposeDocument = { source ->
        fetchBase64(catalogAssetPath(config.catalogSystemId, "/render/${source.id}.rc"))
      },
    )
    LaunchedEffect(loadedDocument.revision) { markReady() }
  }
}

/**
 * One native render of a design: the host compiles it and draws it with real Compose.
 *
 * Decoding happens here rather than in the editor because `wasmJs` and the JVM decode differently
 * and neither belongs in an editor composable — the editor takes an [ImageBitmap] and knows nothing
 * about base64 or HTTP.
 *
 * The three outcomes are kept apart on purpose. A 422 is the generator refusing the design and its
 * reasons are actionable; any other non-200 is this host failing, which is a different sentence;
 * and a 200 with no frame means the compile lane answered without one, which the editor says
 * plainly rather than showing an empty box.
 */
private suspend fun requestNativeRender(designId: String): UiBuilderNativeRender {
  val response =
    BrowserUiBuilderHttpTransport()
      .post(
        UiBuilderHttpRequest(
          endpoint = "/api/ui-builder/v1/designs/$designId/native-preview",
          contentType = "application/json",
          body = "{}",
        )
      )
  if (response.statusCode == 422) {
    val refusal =
      nativePreviewJson.decodeFromString(NativePreviewRefusal.serializer(), response.body)
    return UiBuilderNativeRender(refusals = refusal.reasons)
  }
  if (response.statusCode != 200) {
    return UiBuilderNativeRender(
      failure = "the host answered ${response.statusCode} to a native render request"
    )
  }
  val result = nativePreviewJson.decodeFromString(NativePreviewResult.serializer(), response.body)
  result.compileError?.let {
    return UiBuilderNativeRender(failure = it)
  }
  val encoded = result.imageBase64 ?: return UiBuilderNativeRender()
  // The compile lane's `image` is a `data:image/png;base64,…` URI, because that is what the
  // playground page puts straight into an `<img src>`. Strict Base64 rejects the prefix, so a frame
  // that arrived intact used to surface as a decode failure. Tolerant of both spellings rather than
  // pinned to one: the field is named for its payload, and the prefix is the wrapper.
  val payload = encoded.substringAfterLast("base64,")
  return UiBuilderNativeRender(
    image = Image.makeFromEncoded(Base64.decode(payload)).toComposeImageBitmap(),
    nodeBounds =
      result.nodeBounds.mapValues { (_, box) ->
        UiBuilderNativeNodeBounds(x = box.x, y = box.y, width = box.width, height = box.height)
      },
  )
}

/** Tolerant: a field added to the native-render payload must not blank the pane. */
private val nativePreviewJson = Json { ignoreUnknownKeys = true }

@kotlinx.serialization.Serializable
private data class NativePreviewResult(
  val imageBase64: String? = null,
  val taggedNodeIds: List<String> = emptyList(),
  /** Design node id → its box on the frame, in the frame's own pixels. See `nodeBounds` there. */
  val nodeBounds: Map<String, NativePreviewNodeBounds> = emptyMap(),
  val compileError: String? = null,
)

@kotlinx.serialization.Serializable
private data class NativePreviewNodeBounds(
  val x: Int = 0,
  val y: Int = 0,
  val width: Int = 0,
  val height: Int = 0,
)

@kotlinx.serialization.Serializable
private data class NativePreviewRefusal(val reasons: List<String> = emptyList())

@Composable
private fun VisualFixtureApp(mode: String) {
  if (mode == "reference") {
    ConfettiReference()
    LaunchedEffect(Unit) { markReady() }
    return
  }

  var document by remember { mutableStateOf<UiBuilderDocument?>(null) }
  var catalog by remember { mutableStateOf<CapabilityCatalog?>(null) }
  LaunchedEffect(Unit) {
    val isJetcaster = mode.startsWith("jetcaster-") || mode.startsWith("interactive-editor")
    val fixtureName =
      if (isJetcaster) "jetcaster-discover-operations-v1.json"
      else "confetti-schedule-operations-v1.json"
    val fixture = Json.parseToJsonElement(fetchText(fixtureName)).jsonObject
    val replayed = UiBuilderReducer.replay(fixture).document
    if (isJetcaster) {
      val catalogSource = fetchText("m3-catalog-capabilities-v1.json")
      val validation =
        validateCapabilities(
          replayed,
          catalogSource,
        )
      require(validation.structurallyValid) {
        validation.issues.joinToString(prefix = "invalid Jetcaster design: ") { it.message }
      }
      publishCapabilityDiagnostics(
        validation.structurallyValid,
        validation.wasmRenderable,
        validation.plannedOrUnsupported
          .map { it.componentId }
          .distinct()
          .sorted()
          .joinToString(","),
      )
      catalog = CapabilityCatalogParser.parse(catalogSource)
    }
    document = replayed
  }
  document?.let {
    if (mode.startsWith("interactive-editor")) {
      catalog?.let { loadedCatalog ->
        UiBuilderEditor(
          document = it,
          catalog = loadedCatalog,
          onStateChanged = ::publishEditorState,
          onCanvasMetrics = ::publishEditorCanvasMetrics,
          onCanvasBoundsChanged = ::publishEditorCanvasBounds,
          onDropTargetChanged = ::publishEditorDropTarget,
          onInspectionSnapshot = { snapshot ->
            publishInspection(inspectionJson.encodeToString(snapshot))
          },
          showSelectionOverlay = mode != "interactive-editor-clean",
          // The clean lane exists to prove the editor draws the design the same as the harness
          // does, pixel for pixel. Framing it would compare a resample, so that one mode opens
          // pinned at 1:1 while every editing surface opens framed.
          initialCanvasZoom = if (mode == "interactive-editor-clean") 1f else null,
          onHelp = ::openUiBuilderGuide,
        )
      }
    } else {
      UiBuilderSurface(
        it,
        editorOverlay = mode == "editor" || mode == "jetcaster-editor",
        selectedNodeId = if (mode == "jetcaster-editor") "discover-grid" else null,
        onInspectionSnapshot = { snapshot ->
          publishInspection(inspectionJson.encodeToString(snapshot))
        },
      )
    }
    LaunchedEffect(it.revision) { markReady() }
  }
}

/** Independent developer-authored oracle for the pinned compact Confetti Schedule screen. */
@Composable
private fun ConfettiReference() {
  var selectedTrack by remember { mutableStateOf<String?>(null) }
  MaterialTheme {
    Scaffold(
      modifier = Modifier.fillMaxSize(),
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      topBar = {
        CenterAlignedTopAppBar(
          colors =
            TopAppBarDefaults.topAppBarColors(
              containerColor = Color.Transparent,
              scrolledContainerColor = Color.Transparent,
            ),
          title = {
            Text(
              "KotlinConf 2023",
              Modifier.padding(horizontal = 8.dp),
              style = MaterialTheme.typography.titleLarge,
              maxLines = 2,
              textAlign = TextAlign.Center,
            )
          },
        )
      },
    ) { contentPadding ->
      Column(Modifier.padding(contentPadding).fillMaxSize()) {
        LazyRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
          item {
            FilterChip(
              selected = selectedTrack == null,
              onClick = { selectedTrack = null },
              label = { Text("All") },
            )
          }
          item { TrackChip("droidCon", Color(0xFF00FF4F), selectedTrack) { selectedTrack = it } }
          item { TrackChip("swiftCon", Color(0xFFFF375F), selectedTrack) { selectedTrack = it } }
          item { TrackChip("flutterCon", Color(0xFF42A5F5), selectedTrack) { selectedTrack = it } }
          item { TrackChip("reactCon", Color(0xFF61DAFB), selectedTrack) { selectedTrack = it } }
        }
        PrimaryTabRow(selectedTabIndex = 0, modifier = Modifier.fillMaxWidth()) {
          Tab(
            selected = true,
            onClick = {},
            text = {
              Text(
                "Thu 13 Apr",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
              )
            },
          )
          Tab(
            selected = false,
            onClick = {},
            text = { Text("Fri 14 Apr", style = MaterialTheme.typography.titleSmall) },
          )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
          item { TimeHeader("14:00") }
          item {
            ScheduleListItem(
              accent = Color(0xFF00C853),
              headline = "Confetti: building a Kotlin Multiplatform conference app",
              speaker = "John O'Reilly, Martin Bonnin",
              metadata = "Effectenbeurszaal  ·  Kotlin  ·  Multiplatform",
              bookmarked = true,
            )
          }
          item { HorizontalDivider(Modifier.padding(start = 16.dp)) }
          item { TimeHeader("14:50") }
          item {
            ScheduleListItem(
              accent = Color(0xFF42A5F5),
              headline = "Compose tips in 5 minutes",
              speaker = "Sebastian Aigner",
              metadata = "14:50–14:55  ·  Effectenbeurszaal  ·  Lightning",
              bookmarked = false,
            )
          }
          item { HorizontalDivider(Modifier.padding(start = 16.dp)) }
          item { TimeHeader("15:00") }
          item {
            Surface(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
              color = MaterialTheme.colorScheme.surfaceContainerLow,
              shape = RoundedCornerShape(12.dp),
            ) {
              Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
              ) {
                Icon(
                  Icons.Filled.Coffee,
                  contentDescription = null,
                  Modifier.size(24.dp),
                  tint = MaterialTheme.colorScheme.primary,
                )
                Column {
                  Text(
                    "Coffee Break",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                  )
                  Text(
                    "Foyer · Level 1",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun TrackChip(
  name: String,
  color: Color,
  selectedTrack: String?,
  onSelectedTrack: (String?) -> Unit,
) {
  FilterChip(
    selected = selectedTrack == name,
    onClick = { onSelectedTrack(if (selectedTrack == name) null else name) },
    label = { Text(name) },
    leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(color)) },
  )
}

@Composable
private fun TimeHeader(label: String) {
  Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
    Row(
      Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
      Icon(
        Icons.Filled.AccessTime,
        contentDescription = null,
        Modifier.size(18.dp),
        tint = MaterialTheme.colorScheme.primary,
      )
      Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

@Composable
private fun ScheduleListItem(
  accent: Color,
  headline: String,
  speaker: String,
  metadata: String,
  bookmarked: Boolean,
) {
  ListItem(
    modifier =
      Modifier.fillMaxWidth().drawBehind {
        drawRect(accent, size = Size(3.dp.toPx(), size.height))
      },
    headlineContent = {
      Text(
        headline,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
      )
    },
    supportingContent = {
      Column {
        Text(
          speaker,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          metadata,
          Modifier.padding(top = 6.dp),
          style = MaterialTheme.typography.labelSmall,
        )
      }
    },
    trailingContent = {
      Icon(
        imageVector = if (bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint =
          if (bookmarked) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    },
  )
}

/**
 * The Remote Compose documents the *serving* catalog of the same name publishes.
 *
 * The two catalogs share an id by construction — `/ui-builder/remote-m3/` authors against the
 * capability adapter named `remote-m3`, and `/remote-m3/` serves the published catalog of the same
 * name from the same box — so the palette needs no second piece of configuration to find its
 * content. A box serving one without the other simply gets an empty palette.
 *
 * A failure is not fatal, for the same reason [loadDevicePresets]'s is not: the builder without a
 * Remote Compose palette is where it was before this existed, while a builder that refuses to open
 * because a catalog listing 404'd is worse than one that opens with one panel missing.
 */
private suspend fun loadRemoteComposeSources(catalogSystemId: String): List<RemoteComposeSource> =
  try {
    parseRemoteComposeSources(fetchText(catalogAssetPath(catalogSystemId, "/api/previews")))
  } catch (failure: Throwable) {
    emptyList()
  }

/** Bytes rather than text: a Remote Compose document is a binary wire format. */
private suspend fun fetchBase64(url: String): String = suspendCancellableCoroutine { continuation ->
  fetchBase64Promise(sameOriginRequestUrl(url))
    .then { value ->
      if (continuation.isActive) continuation.resume(value.toString())
      null
    }
    .catch { error ->
      if (continuation.isActive) {
        continuation.resumeWithException(IllegalStateException(error.toString()))
      }
      null
    }
}

@JsFun(
  """(url) => fetch(url).then((response) => {
    if (!response.ok) throw new Error('HTTP ' + response.status);
    return response.arrayBuffer();
  }).then((buffer) => {
    const bytes = new Uint8Array(buffer);
    let binary = '';
    // Chunked: `String.fromCharCode(...bytes)` spreads every byte as an argument, and a document of
    // any size overflows the call stack.
    for (let offset = 0; offset < bytes.length; offset += 8192) {
      binary += String.fromCharCode.apply(null, bytes.subarray(offset, offset + 8192));
    }
    return btoa(binary);
  })"""
)
private external fun fetchBase64Promise(url: String): Promise<JsString>

private suspend fun fetchText(url: String): String = suspendCancellableCoroutine { continuation ->
  fetchTextPromise(sameOriginRequestUrl(url))
    .then { value ->
      if (continuation.isActive) continuation.resume(value.toString())
      null
    }
    .catch { error ->
      if (continuation.isActive) {
        continuation.resumeWithException(IllegalStateException(error.toString()))
      }
      null
    }
}

@JsFun(
  """(url) => fetch(url).then((response) => {
    if (!response.ok) throw new Error('HTTP ' + response.status);
    return response.text();
  })"""
)
private external fun fetchTextPromise(url: String): Promise<JsString>

@JsFun("() => new URLSearchParams(globalThis.location.search).get('mode') || 'interactive-editor'")
private external fun captureMode(): String

@JsFun(
  """() => {
    const params = new URLSearchParams(globalThis.location.search);
    return params.get('session') === 'live' || !params.has('mode');
  }"""
)
private external fun liveSessionEnabled(): Boolean

private fun liveSessionConfig(serverActorId: String?): LiveSessionConfig {
  val catalogSystemId = liveConfigValue("catalog", uiBuilderCatalogFromPath())
  val defaultDesignId =
    if (catalogSystemId == "m3-catalog") "jetcaster-discover"
    else "$catalogSystemId-jetcaster-discover"
  // `/ui-builder/<catalog>/<designId>` is the canonical form. The `?designId=` query still works
  // — bookmarks and automation written against it must not break — and the path wins where both
  // are present. Neither creates anything: a GET opens a design, and bringing one into existence
  // is the `POST` the New design form submits, or a `PUT` of the design's own API resource.
  val pathDesignId = uiBuilderDesignFromPath()
  val designNamedInPath = pathDesignId.isNotEmpty()
  return LiveSessionConfig(
      catalogSystemId = catalogSystemId,
      designId =
        if (designNamedInPath) pathDesignId else liveConfigValue("designId", defaultDesignId),
      actorId = liveConfigValue("actor", serverActorId ?: "browser-user"),
      clientId = liveConfigValue("clientId", "browser-editor"),
      httpEndpoint = liveConfigValue("endpoint", "/api/ui-builder/v1/requests"),
      webSocketEndpoint =
        liveConfigValue(
          "updatesEndpoint",
          "/api/ui-builder/v1/designs/{designId}/updates",
        ),
      startWithNewDesign = !designNamedInPath && !liveConfigPresent("designId"),
      operationIdPrefix = "${liveConfigValue("clientId", "browser-editor")}-${livePageNonce()}",
      displayName =
        liveConfigValue("displayName", serverActorId?.substringAfterLast(':') ?: "Browser user"),
      colorArgbHex = liveConfigValue("color", "#FF6574CD"),
    )
    .also {
      require(Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(it.catalogSystemId)) {
        "live catalog must be a safe catalog id"
      }
      require(it.designId.isNotBlank()) { "live designId must not be blank" }
      require(!designNamedInPath || Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(it.designId)) {
        "a design named in the path must be path-safe"
      }
      require(it.actorId.isNotBlank()) { "live actor must not be blank" }
      require(it.clientId.isNotBlank()) { "live clientId must not be blank" }
      require(Regex("#[0-9A-Fa-f]{8}").matches(it.colorArgbHex)) { "live color must be #AARRGGBB" }
    }
}

/**
 * The Screen inspector's device frames, read from the server.
 *
 * The server derives them from `DeviceDimensions`, the JVM-only catalog the render lane resolves
 * against; `wasmJs` cannot depend on it, which is exactly why this crosses the wire instead of
 * being a constant in `:ui-builder`. A failure is not fatal — the inspector falls back to the raw
 * width/height/density fields, which is where it was before the menu existed.
 */
private suspend fun loadDevicePresets(): List<UiBuilderDevicePreset> =
  try {
    devicePresetJson
      .decodeFromString(DevicePresetsPayload.serializer(), fetchText(DEVICE_PRESETS_PATH))
      .presets
      .map {
        UiBuilderDevicePreset(
          id = it.id,
          label = it.label,
          group = it.group,
          widthDp = it.widthDp,
          heightDp = it.heightDp,
          density = it.density,
        )
      }
  } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    emptyList()
  }

/**
 * The actor id the server authenticated this page as, or `null` when it will not say.
 *
 * Not fatal on its own: the caller keeps the historical default so an unauthenticated page still
 * renders and reports the server's own error, rather than failing to mount at all.
 */
private suspend fun resolveServerActorId(): String? =
  try {
    identityJson
      .decodeFromString(IdentityPayload.serializer(), fetchText(IDENTITY_PATH))
      .actorId
      .takeIf { it.isNotBlank() }
  } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    null
  }

/**
 * How long a settings change waits before it is stored.
 *
 * Long enough that dragging a slider from one end to the other is one request, short enough that
 * closing the tab straight after a nudge keeps it. The pictures do not go through this timer twice
 * — an unchanged picture takes the settings route, which carries no bytes.
 */
private const val REFERENCE_SAVE_DEBOUNCE_MILLIS = 600L

private const val IDENTITY_PATH = "/api/ui-builder/v1/identity"

/** Tolerant for the same reason as the presets: a new identity field must not blank the actor. */
private val identityJson = Json { ignoreUnknownKeys = true }

@kotlinx.serialization.Serializable private data class IdentityPayload(val actorId: String = "")

private const val DEVICE_PRESETS_PATH = "/api/ui-builder/v1/device-presets"

/** Tolerant on purpose: a server that learns a new preset field must not blank the whole menu. */
private val devicePresetJson = Json { ignoreUnknownKeys = true }

@kotlinx.serialization.Serializable
private data class DevicePresetsPayload(val presets: List<DevicePresetWire> = emptyList())

@kotlinx.serialization.Serializable
private data class DevicePresetWire(
  val id: String,
  val label: String,
  val group: String,
  val widthDp: Int,
  val heightDp: Int,
  val density: Double,
)

private suspend fun loadLiveCatalogs(http: UiBuilderProtocolHttpClient): List<CatalogCapabilityV1> =
  when (val result = http.execute(ListCatalogsRequestV1)) {
    is UiBuilderHttpResult.Response -> {
      val catalogs =
        result.response as? CatalogsResponseV1 ?: error("unexpected list-catalogs response")
      catalogs.catalogs
    }
    is UiBuilderHttpResult.ServiceError -> error(result.error.message)
    is UiBuilderHttpResult.SnapshotRequired -> error(result.error.message)
  }

private fun newDesignCatalog(catalog: CatalogCapabilityV1): UiBuilderNewDesignCatalog? =
  when (catalog.benchmark.catalogSystemId) {
    "m3-catalog" ->
      UiBuilderNewDesignCatalog(
        systemId = "m3-catalog",
        label = "Material 3",
        templates =
          listOf(
            UiBuilderNewDesignTemplate(
              id = "blank",
              label = "Blank screen",
              supportingText = "A Material scaffold with an empty content container.",
            )
          ),
      )
    "remote-m3" ->
      UiBuilderNewDesignCatalog(
        systemId = "remote-m3",
        label = "Remote Material 3",
        templates =
          listOf(
            UiBuilderNewDesignTemplate(
              id = "wear-widget-small",
              label = "Small widget",
              supportingText = "216×76dp host with a single content slot.",
            ),
            UiBuilderNewDesignTemplate(
              id = "wear-widget-large",
              label = "Large widget",
              supportingText = "216×124dp host with a single content slot.",
            ),
          ) +
            // The two worked samples, after the empty scaffolds rather than before them: a blank
            // host is what someone starting their own widget wants, and a sample is what someone
            // asking "can this express a real one?" wants.
            WearWidgetSample.entries.map {
              UiBuilderNewDesignTemplate(
                id = it.templateId,
                label = it.label,
                supportingText = it.supportingText,
              )
            },
      )
    "wear-m3" ->
      UiBuilderNewDesignCatalog(
        systemId = "wear-m3",
        label = "Wear Material 3",
        templates =
          listOf(
            UiBuilderNewDesignTemplate(
              id = UiBuilderNewDesignSeed.WEAR_SCREEN_TEMPLATE,
              label = "Wear screen",
              supportingText =
                "A ScreenScaffold with its clock and scroll indicator, over an empty list.",
            ),
            // After the empty one, for the reason the widget samples come after the empty hosts: a
            // blank scaffold is what somebody starting their own screen wants, and the worked list
            // is what somebody asking "does this match a real Wear render?" wants.
            UiBuilderNewDesignTemplate(
              id = UiBuilderNewDesignSeed.WEAR_LIST_TEMPLATE,
              label = "Activity list",
              supportingText = "Six title cards under a list header, row for row the reference's.",
            ),
          ),
      )
    else -> null
  }

@JsFun(
  """() => {
    const parts = globalThis.location.pathname.split('/').filter(Boolean);
    return parts[0] === 'ui-builder' && parts.length > 1 ? parts[1] : 'm3-catalog';
  }"""
)
private external fun uiBuilderCatalogFromPath(): String

@JsFun(
  """() => {
    const parts = globalThis.location.pathname.split('/').filter(Boolean);
    if (parts[0] !== 'ui-builder' || parts.length < 3) return '';
    return decodeURIComponent(parts[2]);
  }"""
)
private external fun uiBuilderDesignFromPath(): String

/**
 * The canonical URL for one design: `/ui-builder/<catalog>/<designId>`.
 *
 * Only the identity and transport values survive as a query — they configure *who* is editing, not
 * *what*. `session`, `create`, `designId`, `template` and `state` do not: the first three are
 * implied by the path, and the last two only ever described how a design that now exists was
 * seeded.
 */
@JsFun(
  """(catalogSystemId, designId) => {
    const current = new URL(globalThis.location.href);
    const path = '/ui-builder/' + encodeURIComponent(catalogSystemId) + '/' +
      encodeURIComponent(designId);
    const next = new URL(path, current.origin);
    ['token', 'actor', 'clientId', 'displayName', 'color', 'endpoint', 'updatesEndpoint']
      .forEach((name) => {
        const value = current.searchParams.get(name);
        if (value !== null) next.searchParams.set(name, value);
      });
    if (next.toString() !== current.toString()) {
      globalThis.history.replaceState(null, '', next.toString());
    }
  }"""
)
private external fun canonicalizeUiBuilderUrl(catalogSystemId: String, designId: String)

/**
 * Submit the New design form: a real `POST`, whose `303` the browser follows to the permalink.
 *
 * A form rather than `fetch`, because only a form submission makes the redirect a navigation —
 * `fetch` would follow the `303` itself and hand back the editor's HTML, leaving the page on the
 * URL the creation was requested from. The identity query rides on the action URL, where the server
 * reads it to authenticate the write and to carry it into the permalink it redirects to.
 */
@JsFun(
  """(catalogSystemId, designId, templateId, state) => {
    const current = new URL(globalThis.location.href);
    const action = new URL(
      '/ui-builder/' + encodeURIComponent(catalogSystemId),
      current.origin,
    );
    ['token', 'actor', 'clientId', 'displayName', 'color', 'endpoint', 'updatesEndpoint']
      .forEach((name) => {
        const value = current.searchParams.get(name);
        if (value !== null) action.searchParams.set(name, value);
      });
    const form = globalThis.document.createElement('form');
    form.method = 'post';
    form.action = action.toString();
    const field = (name, value) => {
      const input = globalThis.document.createElement('input');
      input.type = 'hidden';
      input.name = name;
      input.value = value;
      form.appendChild(input);
    };
    field('designId', designId);
    field('template', templateId);
    if (state && state !== '[]') field('state', state);
    globalThis.document.body.appendChild(form);
    form.submit();
  }"""
)
private external fun navigateToNewDesign(
  catalogSystemId: String,
  designId: String,
  templateId: String,
  state: String,
)

@JsFun(
  """() => globalThis.open('https://github.com/yschimke/compose-preview-server/blob/main/docs/UI_BUILDER_GETTING_STARTED.md', '_blank', 'noopener,noreferrer')"""
)
private external fun openUiBuilderGuide()

@JsFun(
  """(name, fallback) => {
    const value = new URLSearchParams(globalThis.location.search).get(name);
    return value === null ? fallback : value;
  }"""
)
private external fun liveConfigValue(name: String, fallback: String): String

@JsFun("(name) => new URLSearchParams(globalThis.location.search).has(name)")
private external fun liveConfigPresent(name: String): Boolean

@JsFun(
  """(name) => {
    const value = new URLSearchParams(globalThis.location.search).get(name);
    return value === '1' || value === 'true';
  }"""
)
private external fun liveConfigFlag(name: String): Boolean

@JsFun("() => globalThis.crypto.randomUUID()") private external fun livePageNonce(): String

private fun browserNowMillis(): Long = browserNow().toLong()

@JsFun("() => Date.now()") private external fun browserNow(): Double

@JsFun("() => document.documentElement.setAttribute('data-ui-builder-ready', 'true')")
private external fun markReady()

@JsFun(
  """(kind, revision, sequence) => {
    const state = globalThis.__uiBuilderPerformance || {
      schema: 'compose-ui-builder-performance/v1',
      protocolReceipts: [], authoritativeReceipts: [], canvasApplies: [], cleanRenders: [], phases: []
    };
    state.protocolReceipts.push({ kind, revision, sequence, receivedAtMs: performance.now() });
    if (state.protocolReceipts.length > 512) state.protocolReceipts.shift();
    globalThis.__uiBuilderPerformance = state;
  }"""
)
private external fun recordProtocolReceipt(kind: String, revision: Int, sequence: Long)

@JsFun(
  """(revision, sequence) => {
    const state = globalThis.__uiBuilderPerformance || {
      schema: 'compose-ui-builder-performance/v1',
      protocolReceipts: [], authoritativeReceipts: [], canvasApplies: [], cleanRenders: [], phases: []
    };
    state.authoritativeReceipts.push({
      revision, sequence, receivedAtMs: performance.now(), consumed: false
    });
    if (state.authoritativeReceipts.length > 512) state.authoritativeReceipts.shift();
    globalThis.__uiBuilderPerformance = state;
  }"""
)
private external fun recordAuthoritativeReceipt(revision: Int, sequence: Long)

@JsFun("() => performance.now()") private external fun monotonicNow(): Double

@JsFun(
  """(name, revision, startedAtMs, completedAtMs) => {
    const state = globalThis.__uiBuilderPerformance;
    if (!state) return;
    state.phases = state.phases || [];
    state.phases.push({
      name, revision, startedAtMs, completedAtMs, durationMs: completedAtMs - startedAtMs
    });
    if (state.phases.length > 512) state.phases.shift();
  }"""
)
private external fun recordPerformancePhase(
  name: String,
  revision: Int,
  startedAtMs: Double,
  completedAtMs: Double,
)

@JsFun(
  """(structurallyValid, wasmRenderable, pendingIds) => {
    globalThis.__uiBuilderCapabilityValidation = {
      structurallyValid,
      wasmRenderable,
      plannedOrUnsupportedComponentIds: pendingIds ? pendingIds.split(',') : []
    };
  }"""
)
private external fun publishCapabilityDiagnostics(
  structurallyValid: Boolean,
  wasmRenderable: Boolean,
  pendingIds: String,
)

@JsFun(
  """(actorIdsJson, selectionsJson) => {
    globalThis.__uiBuilderPresence = {
      actorIds: JSON.parse(actorIdsJson),
      selections: JSON.parse(selectionsJson)
    };
    document.documentElement.dataset.uiBuilderCollaborators = String(globalThis.__uiBuilderPresence.actorIds.length);
  }"""
)
private external fun publishPresenceManifest(actorIds: String, selections: String)

@JsFun(
  """(state) => {
    globalThis.__uiBuilderSocketState = state;
    document.documentElement.dataset.uiBuilderSocketState = state;
  }"""
)
private external fun publishSocketState(state: String)

@JsFun(
  """(json) => {
    const manifest = JSON.parse(json);
    const token = (globalThis.__uiBuilderInspectionToken || 0) + 1;
    globalThis.__uiBuilderInspectionToken = token;
    manifest.generation.completed = false;
    globalThis.__uiBuilderInspection = manifest;
    let canvasApplied = false;
    const settle = (frames) => requestAnimationFrame(() => {
      const performanceState = globalThis.__uiBuilderPerformance;
      if (performanceState && !canvasApplied) {
        canvasApplied = true;
        const receipt = [...performanceState.authoritativeReceipts]
          .reverse()
          .find((candidate) =>
            candidate.revision === manifest.documentRevision && !candidate.consumed
          );
        if (receipt) {
          receipt.consumed = true;
          const completedAtMs = performance.now();
          performanceState.canvasApplies.push({
            revision: manifest.documentRevision,
            receiptAtMs: receipt.receivedAtMs,
            completedAtMs,
            latencyMs: completedAtMs - receipt.receivedAtMs
          });
          if (performanceState.canvasApplies.length > 512) performanceState.canvasApplies.shift();
        }
      }
      if (globalThis.__uiBuilderInspectionToken !== token) return;
      if (frames > 1) {
        settle(frames - 1);
        return;
      }
      manifest.generation.completed = true;
      globalThis.__uiBuilderInspection = manifest;
      document.documentElement.dataset.uiBuilderInspectionGeneration = manifest.generation.key;
      if (performanceState) {
        const completedAtMs = performance.now();
        const cleanRender = {
          revision: manifest.documentRevision,
          completedAtMs,
          generationKey: manifest.generation.key
        };
        performanceState.cleanRenders.push(cleanRender);
        if (performanceState.cleanRenders.length > 512) performanceState.cleanRenders.shift();
        if (!performanceState.interactive) performanceState.interactive = cleanRender;
      }
    });
    settle(manifest.generation.stabilityFrames);
  }"""
)
private external fun publishInspection(json: String)

private val inspectionJson = Json { encodeDefaults = true }

/**
 * Compose reports node bounds synchronously for every laid-out node. Publishing a complete encoded
 * manifest for every callback blocks the browser render opportunity with repeated whole-document
 * serialization. Yielding once coalesces that burst while retaining the latest complete snapshot;
 * [publishInspection] still places the canvas marker only after the subsequent animation frame.
 */
private class CoalescingInspectionPublisher(private val scope: CoroutineScope) {
  private var pending: UiBuilderInspectionCollector? = null
  private var job: Job? = null

  fun offer(collector: UiBuilderInspectionCollector, revision: Int) {
    pending = collector
    if (job?.isActive == true) return
    val invalidatedAt = monotonicNow()
    recordPerformancePhase(
      name = "inspectionInvalidated",
      revision = revision,
      startedAtMs = invalidatedAt,
      completedAtMs = invalidatedAt,
    )
    job = scope.launch {
      yield()
      val latest = pending ?: return@launch
      pending = null
      val startedAt = monotonicNow()
      val snapshot = latest.snapshot()
      val encoded = inspectionJson.encodeToString(snapshot)
      recordPerformancePhase(
        name = "inspectionEncode",
        revision = snapshot.documentRevision,
        startedAtMs = startedAt,
        completedAtMs = monotonicNow(),
      )
      publishInspection(encoded)
    }
  }
}

private fun publishEditorState(state: UiBuilderEditorState) {
  val selectedText =
    state.selectedNodeId
      ?.let(state.document.nodes::get)
      ?.properties
      ?.get("text")
      ?.jsonObject
      ?.get("value")
      ?.jsonPrimitive
      ?.contentOrNull
      .orEmpty()
  val selectedIconKey =
    state.selectedNodeId
      ?.let(state.document.nodes::get)
      ?.properties
      ?.get("iconKey")
      ?.jsonObject
      ?.get("value")
      ?.jsonPrimitive
      ?.contentOrNull
      .orEmpty()
  val mainBackgroundChildren =
    state.document.nodes["main-background"]?.slots?.get("children").orEmpty().joinToString(",")
  val outcome =
    when (state.lastOutcome) {
      null -> "idle"
      is CommandOutcome.Accepted -> "accepted"
      is CommandOutcome.Rejected -> "rejected:${state.lastOutcome.code}"
    }
  val rejection = state.lastOutcome as? CommandOutcome.Rejected
  val environment = state.document.screenEnvironmentSettings()
  publishEditorManifest(
    revision = state.document.revision,
    nodeCount = state.document.nodes.size,
    selectedNodeId = state.selectedNodeId.orEmpty(),
    catalogQuery = state.catalogQuery,
    operationSequence = state.operationSequence,
    outcome = outcome,
    selectedText = selectedText,
    selectedIconKey = selectedIconKey,
    mainBackgroundChildren = mainBackgroundChildren,
    documentHash = sha256Hex(canonicalDocument(state.document)),
    outcomeNodeId = rejection?.nodeId.orEmpty(),
    outcomeField = rejection?.field.orEmpty(),
    outcomeMessage = rejection?.message.orEmpty(),
    widthDp = environment.widthDp,
    heightDp = environment.heightDp,
    density = environment.density,
    fontScale = environment.fontScale,
    locale = environment.locale,
    theme = environment.theme.wireValue,
    layoutDirection = environment.layoutDirection.wireValue,
  )
}

@JsFun(
  """(revision, nodeCount, selectedNodeId, catalogQuery, operationSequence, outcome, selectedText, selectedIconKey, mainBackgroundChildren, documentHash, outcomeNodeId, outcomeField, outcomeMessage, widthDp, heightDp, density, fontScale, locale, theme, layoutDirection) => {
    globalThis.__uiBuilderEditor = {
      revision,
      nodeCount,
      selectedNodeId,
      catalogQuery,
      operationSequence,
      outcome,
      selectedText,
      selectedIconKey,
      mainBackgroundChildren: mainBackgroundChildren ? mainBackgroundChildren.split(',') : [],
      documentHash,
      outcomeNodeId,
      outcomeField,
      outcomeMessage,
      environment: { widthDp, heightDp, density, fontScale, locale, theme, layoutDirection }
    };
    document.documentElement.dataset.uiBuilderEditorRevision = String(revision);
  }"""
)
private external fun publishEditorManifest(
  revision: Int,
  nodeCount: Int,
  selectedNodeId: String,
  catalogQuery: String,
  operationSequence: Int,
  outcome: String,
  selectedText: String,
  selectedIconKey: String,
  mainBackgroundChildren: String,
  documentHash: String,
  outcomeNodeId: String,
  outcomeField: String,
  outcomeMessage: String,
  widthDp: Int,
  heightDp: Int,
  density: Double,
  fontScale: Double,
  locale: String,
  theme: String,
  layoutDirection: String,
)

@JsFun(
  """(sourceWidthDp, sourceHeightDp, scale) => {
    globalThis.__uiBuilderEditorCanvas = {
      ...(globalThis.__uiBuilderEditorCanvas || {}), sourceWidthDp, sourceHeightDp, scale
    };
  }"""
)
private external fun publishEditorCanvasMetrics(
  sourceWidthDp: Int,
  sourceHeightDp: Int,
  scale: Float,
)

private fun publishEditorCanvasBounds(bounds: androidx.compose.ui.geometry.Rect) {
  publishEditorCanvasBoundsValues(bounds.left, bounds.top, bounds.right, bounds.bottom)
}

@JsFun(
  """(left, top, right, bottom) => {
    const current = globalThis.__uiBuilderEditorCanvas || {};
    globalThis.__uiBuilderEditorCanvas = {
      ...current,
      bounds: { left, top, right, bottom, width: right - left, height: bottom - top }
    };
  }"""
)
private external fun publishEditorCanvasBoundsValues(
  left: Float,
  top: Float,
  right: Float,
  bottom: Float,
)

@JsFun(
  """(hovered, label) => {
    globalThis.__uiBuilderEditorDropTarget = { hovered, label };
  }"""
)
private external fun publishEditorDropTarget(hovered: Boolean, label: String)
