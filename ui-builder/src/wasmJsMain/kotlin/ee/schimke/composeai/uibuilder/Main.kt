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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.validateCapabilities
import ee.schimke.composeai.uibuilder.client.BrowserUiBuilderHttpTransport
import ee.schimke.composeai.uibuilder.client.BrowserUiBuilderWebSocketTransport
import ee.schimke.composeai.uibuilder.client.MonotonicUiBuilderRequestIds
import ee.schimke.composeai.uibuilder.client.UiBuilderClientUpdate
import ee.schimke.composeai.uibuilder.client.UiBuilderHttpResult
import ee.schimke.composeai.uibuilder.client.UiBuilderLiveSessionApi
import ee.schimke.composeai.uibuilder.client.UiBuilderProtocolHttpClient
import ee.schimke.composeai.uibuilder.client.UiBuilderProtocolUpdateClient
import ee.schimke.composeai.uibuilder.client.toProtocolDocument
import ee.schimke.composeai.uibuilder.client.toProtocolSubmission
import ee.schimke.composeai.uibuilder.client.toRendererDocument
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.OperationOutcomeResponseV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun main() {
  ComposeViewport(viewportContainerId = "composeApp") {
    if (liveSessionEnabled()) LiveSessionApp() else VisualFixtureApp(captureMode())
  }
}

private data class LiveSessionConfig(
  val designId: String,
  val actorId: String,
  val clientId: String,
  val httpEndpoint: String,
  val webSocketEndpoint: String,
  val createIfMissing: Boolean,
  val operationIdPrefix: String,
)

@Composable
private fun LiveSessionApp() {
  val config = remember { liveSessionConfig() }
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
  var catalog by remember { mutableStateOf<CapabilityCatalog?>(null) }
  var sessionStatus by remember { mutableStateOf("Connecting…") }
  var updates by remember { mutableStateOf<UiBuilderProtocolUpdateClient?>(null) }
  var authoritativeGeneration by remember { mutableStateOf(0) }

  fun acceptSnapshot(response: SnapshotResponseV1) {
    document = response.snapshot.state.document.toRendererDocument()
    authoritativeGeneration += 1
    sessionStatus = "Live · ${config.actorId} · seq ${response.snapshot.state.lastSequence}"
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

  LaunchedEffect(config) {
    val catalogSource = fetchText("jetcaster-discover-capabilities-v1.json")
    catalog = CapabilityCatalogParser.parse(catalogSource)
    val openResult =
      UiBuilderLiveSessionApi(config.designId, http).openOrCreate(config.createIfMissing) {
        sessionStatus = "Creating ${config.designId} from the Jetcaster fixture…"
        val fixture =
          Json.parseToJsonElement(fetchText("jetcaster-discover-operations-v1.json")).jsonObject
        UiBuilderReducer.replay(fixture)
          .document
          .copy(id = config.designId, revision = 0)
          .toProtocolDocument()
      }
    when (val result = openResult) {
      is UiBuilderHttpResult.Response -> {
        val response = result.response as? SnapshotResponseV1
        if (response == null) {
          sessionStatus = "Live error · unexpected open response"
          return@LaunchedEffect
        }
        acceptSnapshot(response)
        val client =
          UiBuilderProtocolUpdateClient(
            designId = config.designId,
            endpoint = config.webSocketEndpoint,
            initialAfterSequence = response.snapshot.state.lastSequence,
            transport = BrowserUiBuilderWebSocketTransport(),
          ) { update ->
            when (update) {
              is UiBuilderClientUpdate.Snapshot -> {
                document = update.update.snapshot.state.document.toRendererDocument()
                authoritativeGeneration += 1
                sessionStatus =
                  "Live · ${config.actorId} · seq ${update.update.snapshot.state.lastSequence}"
              }
              is UiBuilderClientUpdate.Delta -> refreshSnapshot("Syncing remote edits…")
              is UiBuilderClientUpdate.Outcome -> refreshSnapshot("Confirming operation…")
              is UiBuilderClientUpdate.SnapshotRequired ->
                refreshSnapshot("Snapshot recovery · after ${update.afterSequence ?: 0}")
              is UiBuilderClientUpdate.Presence -> Unit
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

  val loadedDocument = document
  val loadedCatalog = catalog
  if (loadedDocument != null && loadedCatalog != null) {
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
      onStateChanged = ::publishEditorState,
      onCanvasMetrics = ::publishEditorCanvasMetrics,
      onCanvasBoundsChanged = ::publishEditorCanvasBounds,
      onDropTargetChanged = ::publishEditorDropTarget,
      onInspectionSnapshot = { snapshot ->
        publishInspection(inspectionJson.encodeToString(snapshot))
      },
    )
    LaunchedEffect(loadedDocument.revision) { markReady() }
  }
}

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
      val catalogSource = fetchText("jetcaster-discover-capabilities-v1.json")
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

private suspend fun fetchText(url: String): String = suspendCancellableCoroutine { continuation ->
  fetchTextPromise(url)
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

@JsFun("() => new URLSearchParams(globalThis.location.search).get('session') === 'live'")
private external fun liveSessionEnabled(): Boolean

private fun liveSessionConfig(): LiveSessionConfig =
  LiveSessionConfig(
      designId = liveConfigValue("designId", "jetcaster-discover"),
      actorId = liveConfigValue("actor", "browser-user"),
      clientId = liveConfigValue("clientId", "browser-editor"),
      httpEndpoint = liveConfigValue("endpoint", "/api/ui-builder/v1/requests"),
      webSocketEndpoint =
        liveConfigValue(
          "updatesEndpoint",
          "/api/ui-builder/v1/designs/{designId}/updates",
        ),
      createIfMissing = liveConfigFlag("create"),
      operationIdPrefix = "${liveConfigValue("clientId", "browser-editor")}-${livePageNonce()}",
    )
    .also {
      require(it.designId.isNotBlank()) { "live designId must not be blank" }
      require(it.actorId.isNotBlank()) { "live actor must not be blank" }
      require(it.clientId.isNotBlank()) { "live clientId must not be blank" }
    }

@JsFun(
  """(name, fallback) => {
    const value = new URLSearchParams(globalThis.location.search).get(name);
    return value === null ? fallback : value;
  }"""
)
private external fun liveConfigValue(name: String, fallback: String): String

@JsFun(
  """(name) => {
    const value = new URLSearchParams(globalThis.location.search).get(name);
    return value === '1' || value === 'true';
  }"""
)
private external fun liveConfigFlag(name: String): Boolean

@JsFun("() => globalThis.crypto.randomUUID()") private external fun livePageNonce(): String

@JsFun("() => document.documentElement.setAttribute('data-ui-builder-ready', 'true')")
private external fun markReady()

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
  """(json) => {
    const manifest = JSON.parse(json);
    const token = (globalThis.__uiBuilderInspectionToken || 0) + 1;
    globalThis.__uiBuilderInspectionToken = token;
    manifest.generation.completed = false;
    globalThis.__uiBuilderInspection = manifest;
    const settle = (frames) => requestAnimationFrame(() => {
      if (globalThis.__uiBuilderInspectionToken !== token) return;
      if (frames > 1) {
        settle(frames - 1);
        return;
      }
      manifest.generation.completed = true;
      globalThis.__uiBuilderInspection = manifest;
      document.documentElement.dataset.uiBuilderInspectionGeneration = manifest.generation.key;
    });
    settle(manifest.generation.stabilityFrames);
  }"""
)
private external fun publishInspection(json: String)

private val inspectionJson = Json { encodeDefaults = true }

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
  val mainBackgroundChildren =
    state.document.nodes["main-background"]?.slots?.get("children").orEmpty().joinToString(",")
  val outcome =
    when (state.lastOutcome) {
      null -> "idle"
      is CommandOutcome.Accepted -> "accepted"
      is CommandOutcome.Rejected -> "rejected:${state.lastOutcome.code}"
    }
  publishEditorManifest(
    revision = state.document.revision,
    nodeCount = state.document.nodes.size,
    selectedNodeId = state.selectedNodeId.orEmpty(),
    catalogQuery = state.catalogQuery,
    operationSequence = state.operationSequence,
    outcome = outcome,
    selectedText = selectedText,
    mainBackgroundChildren = mainBackgroundChildren,
    documentHash = sha256Hex(canonicalDocument(state.document)),
  )
}

@JsFun(
  """(revision, nodeCount, selectedNodeId, catalogQuery, operationSequence, outcome, selectedText, mainBackgroundChildren, documentHash) => {
    globalThis.__uiBuilderEditor = {
      revision,
      nodeCount,
      selectedNodeId,
      catalogQuery,
      operationSequence,
      outcome,
      selectedText,
      mainBackgroundChildren: mainBackgroundChildren ? mainBackgroundChildren.split(',') : [],
      documentHash
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
  mainBackgroundChildren: String,
  documentHash: String,
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
