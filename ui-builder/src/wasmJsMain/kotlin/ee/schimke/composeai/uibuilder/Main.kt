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
import ee.schimke.composeai.uibuilder.client.SnapshotDisposition
import ee.schimke.composeai.uibuilder.client.UiBuilderClientUpdate
import ee.schimke.composeai.uibuilder.client.UiBuilderHttpRequest
import ee.schimke.composeai.uibuilder.client.UiBuilderHttpResult
import ee.schimke.composeai.uibuilder.client.UiBuilderLiveSessionApi
import ee.schimke.composeai.uibuilder.client.UiBuilderLiveSessionSync
import ee.schimke.composeai.uibuilder.client.UiBuilderProtocolHttpClient
import ee.schimke.composeai.uibuilder.client.UiBuilderProtocolUpdateClient
import ee.schimke.composeai.uibuilder.client.canonicalDocumentHash
import ee.schimke.composeai.uibuilder.client.preparePropertyDelta
import ee.schimke.composeai.uibuilder.client.toProtocolSubmission
import ee.schimke.composeai.uibuilder.client.toRendererDocument
import ee.schimke.composeai.uibuilder.local.CachedLocalText
import ee.schimke.composeai.uibuilder.local.CachingLocalCatalogSource
import ee.schimke.composeai.uibuilder.local.LocalCatalogSource
import ee.schimke.composeai.uibuilder.local.LocalDesignStorageException
import ee.schimke.composeai.uibuilder.local.LocalDesignStore
import ee.schimke.composeai.uibuilder.local.LocalDesignSyncBack
import ee.schimke.composeai.uibuilder.local.LocalSyncResult
import ee.schimke.composeai.uibuilder.local.LocalUiBuilderHttpTransport
import ee.schimke.composeai.uibuilder.local.LocalUiBuilderService
import ee.schimke.composeai.uibuilder.local.localCheckoutRecord
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogsResponseV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.ErrorResponseV1
import ee.schimke.composeai.uibuilder.protocol.GetSnapshotRequestV1
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
import kotlinx.coroutines.channels.Channel
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
  /**
   * What in the design this URL means: a revision, a node, a thread.
   *
   * Parsed once, here, by [parseDesignUrlSelectors] in common code rather than by a `@JsFun` of its
   * own, so the grammar a link is written in has one definition and a test that does not need a
   * browser.
   */
  val selectors: DesignUrlSelectors,
  val actorId: String,
  val clientId: String,
  val httpEndpoint: String,
  val webSocketEndpoint: String,
  val startWithNewDesign: Boolean,
  val operationIdPrefix: String,
  val displayName: String,
  val colorArgbHex: String,
  /**
   * Whether this design lives in the browser rather than on the server.
   *
   * `?storage=local`, a query rather than a path segment, because it says *where the design is
   * kept* and not *which design* — the same distinction that keeps `actor` and `token` in the
   * query. The server never sees it: the app shell is served for the same catalog-scoped path
   * either way, and the page decides what to do with it.
   */
  val localStorage: Boolean,
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
  var sessionStatus by remember { mutableStateOf("Connecting…") }
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
  LaunchedEffect(config.designId) {
    while (true) {
      val hash = awaitHashChange()
      linkedThreadId = parseDesignUrlSelectors(null, hash).threadId
      threadNavigations += 1
    }
  }
  var catalogQuery by remember { mutableStateOf("") }
  // Which component packs are on, remembered per catalog in this browser. A setting rather than
  // document state: the same design opened by a collaborator shows their palette, not yours.
  var enabledPacks by
    remember(config.catalogSystemId) { mutableStateOf(readEnabledPacks(config.catalogSystemId)) }
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
      "${config.catalogSystemId} · ${sessionModeLabel(config, localSession)} · ${config.actorId}/${config.clientId} · seq ${response.snapshot.state.lastSequence}"
  }

  fun acceptSnapshot(response: SnapshotResponseV1) {
    require(response.snapshot.state.document.catalogPin.systemId == config.catalogSystemId) {
      "design ${config.designId} belongs to ${response.snapshot.state.document.catalogPin.systemId}, not ${config.catalogSystemId}"
    }
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

  // The reference overlay's browser half: the file picker, the paste listener, the snapshot and
  // the store behind them all. Rebuilt only when the design changes, because it is addressed to
  // one design.
  val references = remember(config.designId) { BrowserReferenceHost(config.designId, http) }
  // Built once the catalog is known, because what the menu offers is what the catalog's renderer
  // can draw; null until then, which is a toolbar without an Export button rather than one that
  // promises formats it has not checked.
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
    // A local session can be asked for a catalog this browser has never seen — the first visit to
    // an origin with the network already gone. That is a sentence, not a crash: the live session
    // has a server behind it and can let the failure take the page down, and a local one has to
    // explain itself because there is nothing else left to ask.
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
    // Form-factor order — Mobile, Wear, RemoteCompose — however the host lists them: the chooser
    // is a "what am I making" question, not a catalog registry.
    catalogCapabilities = availableCatalogs
    newDesignCatalogs =
      availableCatalogs.mapNotNull(::newDesignCatalog).sortedBy {
        NEW_DESIGN_CATALOG_ORDER.indexOf(it.systemId)
      }
    if (config.startWithNewDesign) return@LaunchedEffect
    val selectedCatalog =
      availableCatalogs.singleOrNull { it.benchmark.catalogSystemId == config.catalogSystemId }
        ?: error("UI builder is not enabled for catalog ${config.catalogSystemId}")
    fun installCatalog(capability: CatalogCapabilityV1, revision: Long?) {
      catalog =
        CapabilityCatalogParser.parse(
          Json.encodeToJsonElement(CatalogCapabilityV1.serializer(), capability)
        )
      // No Export menu for a local design: every format behind it is rendered by the server, from a
      // design the server does not have. The Code pane is unaffected — the Compose source is
      // generated in this page from the same exporter, which is why it keeps working offline.
      //
      // And where there is one, it is pinned to the same revision the canvas is drawing, because
      // the export routes render on request: without it the Export menu would answer a question
      // about history with the picture of the head, which is the one thing the banner promises it
      // is not showing.
      exportHost =
        if (config.localStorage) null
        else
          BrowserExportHost(
            designId = config.designId,
            formats =
              exportFormatsFor(
                svg = capability.exportCapabilities.svg,
                png = capability.exportCapabilities.png,
              ),
            revision = revision,
          )
    }
    installCatalog(selectedCatalog, null)
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
        canonicalizeUiBuilderUrl(config.catalogSystemId, config.designId, config.selectors)
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
          sessionStatus = "Live error · unexpected open response"
          return@LaunchedEffect
        }
        acceptSnapshot(response)
        // Without the revision: the page opened at head, whatever the link asked for, and an
        // address bar still naming an unavailable revision would be the one lie the banner is
        // there to prevent.
        canonicalizeUiBuilderUrl(
          config.catalogSystemId,
          config.designId,
          config.selectors.copy(revision = null),
        )
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
        loadRemoteComposeSources(config.catalogSystemId)
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
            canonicalizeUiBuilderUrl(catalogSystemId, designId, DesignUrlSelectors())
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
              catalogSystemId = config.catalogSystemId,
              sequence = authoritativeSequence,
            )
          if (outcome != null) {
            sessionStatus = "Local error · $outcome"
          } else {
            enterLocalDesignUrl(config.catalogSystemId, config.designId)
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
          sessionStatus = "${config.catalogSystemId} · ${syncStatus(result)}"
        }
      }
    }

  if (config.startWithNewDesign && newDesignCatalogs.isNotEmpty()) {
    UiBuilderNewDesignScreen(
      catalogs = newDesignCatalogs,
      initialCatalogSystemId = config.catalogSystemId,
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
    UiBuilderEditor(
      document = loadedDocument,
      catalog = loadedCatalog,
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
        if (!isDesignUrlPathSafe(config.catalogSystemId, config.designId)) null
        else
          { selectors ->
            copyDesignLink(designUrlPath(config.catalogSystemId, config.designId, selectors))
          },
      initialCatalogQuery = catalogQuery,
      initialEnabledPacks = enabledPacks,
      collaborators = collaborators,
      devicePresets = devicePresets,
      newDesignCatalogs = newDesignCatalogs,
      onCreateDesign = createDesign,
      onHelp = ::openUiBuilderGuide,
      onTakeOffline = takeOffline,
      onSyncToServer = syncToServer,
      exportHost = exportHost,
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
          writeEnabledPacks(config.catalogSystemId, it.enabledPacks)
        }
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
        fetchBase64(catalogAssetPath(config.catalogSystemId, "/render/${source.id}.rc"))
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
  }
}

@JsFun(
  """(revision, revisionPinned, nodeId, threadId, inspectorMode) => {
    globalThis.__uiBuilderDesignSelectors = {
      revision, revisionPinned, nodeId, threadId, inspectorMode
    };
    const dataset = document.documentElement.dataset;
    dataset.uiBuilderSelectedNode = nodeId;
    dataset.uiBuilderSelectedThread = threadId;
    dataset.uiBuilderInspectorMode = inspectorMode;
    dataset.uiBuilderPinnedRevision = revisionPinned ? revision : '';
  }"""
)
private external fun publishDesignSelectors(
  revision: String,
  revisionPinned: Boolean,
  nodeId: String,
  threadId: String,
  inspectorMode: String,
)

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
private suspend fun requestNativeRender(
  designId: String,
  revision: Long? = null,
  hostShape: WearWidgetHostShape = WearWidgetHostShape.Default,
): UiBuilderNativeRender {
  val response =
    BrowserUiBuilderHttpTransport()
      .post(
        UiBuilderHttpRequest(
          // The revision rides in the query, as it does on the export routes: absent means the
          // current committed revision, which is what an unpinned editor asks for.
          endpoint =
            "/api/ui-builder/v1/designs/$designId/native-preview" +
              (revision?.let { "?revision=$it" } ?: ""),
          contentType = "application/json",
          // The host container to frame a widget in. In the body rather than the query beside the
          // revision, because it says what to draw rather than which version to read; a host that
          // predates the field ignores it and draws the squircle, which is what it drew before.
          body = "{\"hostShape\":\"${hostShape.id}\"}",
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

/**
 * Everything a design kept in this browser needs, assembled once per session.
 *
 * The three pieces are deliberately separate objects. [store] is the browser's keys and knows
 * nothing about catalogs; [catalogs] is network-first with the last successful answer kept, which
 * is what lets an offline reload open a design at all; [service] is the reducer above both,
 * answering the same v1 requests the server answers. [text] is the same network-first rule for the
 * two static files a local session still needs — the device presets and the seed fixture.
 */
private class BrowserLocalSession(config: LiveSessionConfig) {
  private val storage = BrowserLocalDesignStorage()
  val store: LocalDesignStore = LocalDesignStore(storage)
  val catalogs: CachingLocalCatalogSource =
    CachingLocalCatalogSource(
      storage,
      LocalCatalogSource {
        loadLiveCatalogs(
          UiBuilderProtocolHttpClient(
            actorId = config.actorId,
            endpoint = config.httpEndpoint,
            transport = BrowserUiBuilderHttpTransport(),
            requestIds = MonotonicUiBuilderRequestIds("${config.clientId}-catalog"),
          )
        )
      },
    )
  val text: CachedLocalText = CachedLocalText(storage)
  val service: LocalUiBuilderService =
    LocalUiBuilderService(store, catalogs, clock = ::browserNowMillis)

  /** True once this session has fallen back to what the browser stored, which is "offline". */
  val offline: Boolean
    get() = catalogs.servedFromStorage
}

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
      selectors = parseDesignUrlSelectors(locationSearch(), locationHash()),
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
      localStorage = localDesignStorageRequested(),
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
private suspend fun loadDevicePresets(cache: CachedLocalText?): List<UiBuilderDevicePreset> =
  try {
    devicePresetJson
      .decodeFromString(
        DevicePresetsPayload.serializer(),
        cache?.text(DEVICE_PRESETS_PATH) { fetchText(it) } ?: fetchText(DEVICE_PRESETS_PATH),
      )
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

/** The chooser's order. Anything not named here (there is nothing today) sorts first. */
private val NEW_DESIGN_CATALOG_ORDER = listOf("m3-catalog", "wear-m3", "remote-m3")

/**
 * Labelled by what a person is making — a phone screen, a watch screen, a RemoteCompose widget —
 * rather than by the catalog that draws it. "Material 3" and "Wear Material 3" told an M3 reader
 * the truth and everyone else nothing about which chip to press.
 */
private fun newDesignCatalog(catalog: CatalogCapabilityV1): UiBuilderNewDesignCatalog? =
  when (catalog.benchmark.catalogSystemId) {
    "m3-catalog" ->
      UiBuilderNewDesignCatalog(
        systemId = "m3-catalog",
        label = "Mobile",
        platform = UiBuilderCatalogPlatform.from(catalog.statusSemantics),
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
        label = "RemoteCompose",
        platform = UiBuilderCatalogPlatform.from(catalog.statusSemantics),
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
        label = "Wear",
        platform = UiBuilderCatalogPlatform.from(catalog.statusSemantics),
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
    // A catalog this build has no templates for — one an operator enabled that the chooser has
    // never heard of. It still gets a card, named after itself, with the blank starting point the
    // server's seed gives every unknown catalog, rather than silently missing from the chooser.
    else ->
      UiBuilderNewDesignCatalog(
        systemId = catalog.benchmark.catalogSystemId,
        label =
          catalog.benchmark.catalogSystemId
            .split('-', '_', '.')
            .filter(String::isNotEmpty)
            .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercaseChar) },
        platform = UiBuilderCatalogPlatform.from(catalog.statusSemantics),
        templates =
          listOf(
            UiBuilderNewDesignTemplate(
              id = "blank",
              label = "Blank screen",
              supportingText = "An empty starting point for this catalog.",
            )
          ),
      )
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
 *
 * The selectors survive too, and they are the reason this takes arguments rather than reading the
 * URL for itself. They say **what in the design** the link means, so a legacy `?designId=` URL that
 * also named a node has to arrive at the path form still naming it — a rewrite that dropped them
 * would silently turn a link to one layer into a link to the design. `revision` and `node` are set
 * from the values the page opened with; the fragment is carried across verbatim, because it never
 * left the browser to begin with.
 */
@JsFun(
  """(catalogSystemId, designId, carried, revision, node) => {
    const current = new URL(globalThis.location.href);
    const path = '/ui-builder/' + encodeURIComponent(catalogSystemId) + '/' +
      encodeURIComponent(designId);
    const next = new URL(path, current.origin);
    carried.split(',').forEach((name) => {
      const value = current.searchParams.get(name);
      if (value !== null) next.searchParams.set(name, value);
    });
    if (revision) next.searchParams.set('revision', revision);
    if (node) next.searchParams.set('node', node);
    next.hash = current.hash;
    if (next.toString() !== current.toString()) {
      globalThis.history.replaceState(null, '', next.toString());
    }
  }"""
)
private external fun canonicalizeUiBuilderUrlWith(
  catalogSystemId: String,
  designId: String,
  carried: String,
  revision: String,
  node: String,
)

/**
 * The identity query this page keeps, named once in [DESIGN_URL_IDENTITY_KEYS] rather than twice
 * here — the rewrite that keeps it and the link builder that refuses it have to agree, and the way
 * two hand-kept lists disagree is a token in a link somebody pasted into a chat.
 */
private fun canonicalizeUiBuilderUrl(
  catalogSystemId: String,
  designId: String,
  selectors: DesignUrlSelectors,
) =
  canonicalizeUiBuilderUrlWith(
    catalogSystemId = catalogSystemId,
    designId = designId,
    carried = DESIGN_URL_IDENTITY_KEYS.joinToString(","),
    revision = selectors.revision?.toString().orEmpty(),
    node = selectors.nodeId.orEmpty(),
  )

@JsFun("() => globalThis.location.search") private external fun locationSearch(): String

@JsFun("() => globalThis.location.hash") private external fun locationHash(): String

/**
 * The next time the address bar's fragment changes, whatever changed it.
 *
 * A fragment-only navigation — a second `#thread=` link followed from inside the open design, or
 * Back over one — is a *same-document* navigation: the browser does not reload, the Wasm app is
 * never re-entered, and the config parsed at startup keeps naming the thread that was open. Without
 * this the address bar and the panel disagree, which is the one failure a permalink cannot have.
 *
 * One promise per change rather than a persistent callback, because that is what a Kotlin/Wasm
 * caller can await, and it is the shape the paste listener already uses: re-armed by the loop that
 * consumed the last one.
 */
@JsFun(
  """() => new Promise((resolve) => {
    globalThis.addEventListener(
      'hashchange',
      () => resolve(globalThis.location.hash),
      { once: true },
    );
  })"""
)
private external fun awaitHashChangePromise(): Promise<JsString>

private suspend fun awaitHashChange(): String = suspendCancellableCoroutine { continuation ->
  awaitHashChangePromise()
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

/**
 * Takes one selector back out of the address bar, without a round trip and without a history entry.
 *
 * The address bar has to stop naming a node the moment somebody selects a different one, or the URL
 * they copy next — or that their browser restores tomorrow — points at a layer they have not been
 * looking at. `replaceState` rather than `pushState` for the same reason the canonical rewrite uses
 * it: changing selection is not navigation, and Back should leave the design.
 */
@JsFun(
  """(name) => {
    const current = new URL(globalThis.location.href);
    if (!current.searchParams.has(name)) return;
    current.searchParams.delete(name);
    globalThis.history.replaceState(null, '', current.toString());
  }"""
)
private external fun dropDesignUrlQuery(name: String)

/** The same, for `#thread=`. Setting an empty hash drops the `#` with it. */
@JsFun(
  """() => {
    const current = new URL(globalThis.location.href);
    if (!current.hash) return;
    current.hash = '';
    globalThis.history.replaceState(null, '', current.toString());
  }"""
)
private external fun dropDesignUrlFragment()

/**
 * Leaves a pinned revision for the design as it stands.
 *
 * A real navigation rather than a `replaceState`: the pinned page holds a snapshot of one committed
 * revision and no socket, so the head has to be fetched and subscribed to from a clean start. The
 * identity query rides along untouched, which is what makes this the same session rather than a
 * second sign-in.
 */
@JsFun(
  """() => {
    const current = new URL(globalThis.location.href);
    current.searchParams.delete('revision');
    globalThis.location.assign(current.toString());
  }"""
)
private external fun goToLatestRevision()

/**
 * The same canonical URL, now naming this browser as where the design is kept.
 *
 * `replaceState` rather than a navigation: the page already has the app and the design is already
 * written, so reloading to reach the local mode would cost a round trip to a server the operator
 * may be about to leave behind. What the URL is for is the *next* visit.
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
    next.searchParams.set('storage', 'local');
    globalThis.history.replaceState(null, '', next.toString());
  }"""
)
private external fun enterLocalDesignUrl(catalogSystemId: String, designId: String)

/** The origin this page was served from, which is the server a design taken offline came from. */
@JsFun("""() => globalThis.location.origin""") private external fun pageOrigin(): String

/**
 * Submit the New design form: a real `POST`, whose `303` the browser follows to the permalink.
 *
 * A form rather than `fetch`, because only a form submission makes the redirect a navigation —
 * `fetch` would follow the `303` itself and hand back the editor's HTML, leaving the page on the
 * URL the creation was requested from. The identity query rides on the action URL, where the server
 * reads it to authenticate the write and to carry it into the permalink it redirects to.
 */
@JsFun(
  """(catalogSystemId, designId, templateId, state, carried) => {
    const current = new URL(globalThis.location.href);
    const action = new URL(
      '/ui-builder/' + encodeURIComponent(catalogSystemId),
      current.origin,
    );
    carried.split(',').forEach((name) => {
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
private external fun navigateToNewDesignWith(
  catalogSystemId: String,
  designId: String,
  templateId: String,
  state: String,
  carried: String,
)

/** See [canonicalizeUiBuilderUrl]: one list of identity keys, read by both rewrites. */
private fun navigateToNewDesign(
  catalogSystemId: String,
  designId: String,
  templateId: String,
  state: String,
) =
  navigateToNewDesignWith(
    catalogSystemId = catalogSystemId,
    designId = designId,
    templateId = templateId,
    state = state,
    carried = DESIGN_URL_IDENTITY_KEYS.joinToString(","),
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

/**
 * The component packs remembered as on for [catalogSystemId], from this browser's storage.
 *
 * A browser setting rather than a server one, and per catalog rather than per design: which shelves
 * a palette shows is a preference of the person at the keyboard, not a fact about the document, and
 * it is the same answer for every design of one catalog. Storage can be absent or refuse — a
 * private window, a blocked origin — and either reads as "nothing remembered".
 */
private fun readEnabledPacks(catalogSystemId: String): Set<String> =
  readBrowserSetting(enabledPacksKey(catalogSystemId))
    .split(',')
    .map(String::trim)
    .filterTo(mutableSetOf(), String::isNotEmpty)

private fun writeEnabledPacks(catalogSystemId: String, packs: Set<String>) {
  writeBrowserSetting(enabledPacksKey(catalogSystemId), packs.sorted().joinToString(","))
}

private fun enabledPacksKey(catalogSystemId: String): String = "ui-builder.packs.$catalogSystemId"

@JsFun(
  """(key) => {
    try {
      return globalThis.localStorage?.getItem(key) ?? '';
    } catch (e) {
      return '';
    }
  }"""
)
private external fun readBrowserSetting(key: String): String

@JsFun(
  """(key, value) => {
    try {
      if (value === '') globalThis.localStorage?.removeItem(key);
      else globalThis.localStorage?.setItem(key, value);
    } catch (e) {}
  }"""
)
private external fun writeBrowserSetting(key: String, value: String)

/**
 * What the status line calls this session: live against the server, or this browser's own copy.
 *
 * The offline spelling is not cosmetic. A local session that is *also* offline has fallen back to a
 * catalog remembered from an earlier visit, and an author should be told that the palette in front
 * of them is a memory rather than what the server serves today.
 */
private fun sessionModeLabel(config: LiveSessionConfig, session: BrowserLocalSession?): String =
  when {
    !config.localStorage -> "Live"
    session?.offline == true -> "This browser · offline"
    else -> "This browser"
  }

/**
 * Seeds one design into this browser, or says why it could not be.
 *
 * The seed comes from [UiBuilderNewDesignSeed], the same object the server's New design form runs,
 * so "a blank Wear screen" means one thing whichever route made it. The operations fixture it reads
 * the environment from is a static file, so it goes through the browser's remembered-text cache and
 * a second design can be made with the network gone.
 */
private suspend fun createLocalDesign(
  session: BrowserLocalSession,
  catalogs: List<CatalogCapabilityV1>,
  catalogSystemId: String,
  designId: String,
  templateId: String,
  state: List<NewDesignState>,
): String? {
  val catalog =
    catalogs.firstOrNull { it.benchmark.catalogSystemId == catalogSystemId }
      ?: return "this browser has no stored $catalogSystemId catalog to pin a new design to"
  val document =
    try {
      UiBuilderNewDesignSeed.document(
        designId = designId,
        catalogSystemId = catalogSystemId,
        templateId = templateId,
        catalogRevision = catalog.benchmark.catalogRevision,
        nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
        fixture =
          Json.parseToJsonElement(session.text.text(NEW_DESIGN_FIXTURE_PATH) { fetchText(it) })
            .jsonObject,
        state = state,
      )
    } catch (failure: Exception) {
      return failure.message ?: "the design could not be seeded"
    }
  val response = session.service.create(document)
  return (response as? ErrorResponseV1)?.error?.message
}

/**
 * Copies the design the server is serving into this browser, with the fork point it forked at.
 *
 * The digest and the document come from the same answer on purpose: it is the server's own
 * `documentHash` for that revision, so the claim "this copy forked from revision N of that design"
 * is checkable when it comes home rather than merely asserted.
 *
 * Refuses a design id this browser already holds, for the reason create refuses to replace: two
 * histories under one name is the one thing a later sync could not sort out.
 */
private fun takeDesignOffline(
  wire: DesignDocumentV1,
  catalogSystemId: String,
  sequence: Long,
): String? {
  val store = LocalDesignStore(BrowserLocalDesignStorage())
  if (store.read(wire.id) != null) {
    return "this browser already holds a design called ${wire.id}"
  }
  val document =
    wire.toRendererDocument() ?: return "this design does not fit the editor's own document shape"
  return try {
    store.write(
      localCheckoutRecord(
        document = document,
        documentDigest = wire.canonicalDocumentHash(),
        catalogSystemId = catalogSystemId,
        sequence = sequence,
        server = pageOrigin(),
        nowEpochMillis = browserNowMillis(),
      )
    )
    null
  } catch (failure: LocalDesignStorageException) {
    failure.message ?: "this browser refused to store the design"
  }
}

/** The status line a sync leaves behind: never silent, and never only "done". */
private fun syncStatus(result: LocalSyncResult): String =
  when (result) {
    is LocalSyncResult.NotLinked ->
      "This design was made in this browser, so there is nothing to sync it into — publish it as a new design instead"
    is LocalSyncResult.ForkPointGone ->
      "Sync refused · the server no longer keeps revision ${result.revision}, which this copy forked from — publish it as a new design instead"
    is LocalSyncResult.ForkPointDisagrees ->
      "Sync refused · revision ${result.revision} on the server is not the document this copy forked from"
    is LocalSyncResult.Unreachable -> "Sync failed · ${result.message} — nothing was sent"
    is LocalSyncResult.Refused -> "Sync refused · ${result.code}: ${result.message}"
    is LocalSyncResult.Replayed ->
      (if (result.report.complete) "Synced · " else "Synced in part · ") + result.report.summary()
  }

/** The operations fixture every new design reads its environment from, beside the Wasm bundle. */
private const val NEW_DESIGN_FIXTURE_PATH = "jetcaster-discover-operations-v1.json"

private const val LOCAL_COMMENTS_UNAVAILABLE =
  "Comments need the server. This design is kept in this browser, so there is nobody to discuss it with yet."

private const val LOCAL_REFERENCE_UNAVAILABLE =
  "Reference pictures need the server. This design is kept in this browser, which has room for the document but not for screenshots."

private const val LOCAL_NATIVE_RENDER_UNAVAILABLE =
  "A native render is drawn by the server from the stored design, and this design is kept in this browser."
