@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.UiBuilderDevicePreset
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.mcpapp.McpAppCatalogs
import ee.schimke.composeai.uibuilder.mcpapp.McpAppDesignSession
import ee.schimke.composeai.uibuilder.mcpapp.McpAppDesignState
import ee.schimke.composeai.uibuilder.mcpapp.McpAppEditorScreen
import ee.schimke.composeai.uibuilder.mcpapp.McpAppFile
import ee.schimke.composeai.uibuilder.mcpapp.McpAppJsonRpcBridge
import ee.schimke.composeai.uibuilder.mcpapp.McpAppLayout
import ee.schimke.composeai.uibuilder.mcpapp.McpAppTransport
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The editor as an **MCP App** over a `.uid` file a host opened: ChatGPT or Codex desktop's OpenAI
 * file-extension entrypoint (compose-ui-builder#364, compose-preview-server#1235).
 *
 * The same idea as [HostBridgeApp] — the design is the host's file, not this page's — over the MCP
 * Apps JSON-RPC dialect instead of a bespoke `postMessage` protocol, and with the file rules the
 * host does not own itself: saving with `ifMatch`, conflicts, read-only files and external edits.
 * Those rules are [McpAppDesignSession]'s, in common code and tested against a fake host; this file
 * is the browser transport and the few controls a person needs to act on them.
 *
 * Selected when the page defines `globalThis.composeUiBuilderMcpApp` before the module loads, which
 * the MCP App shell (`ui-builder-web/src/mcp-app/ui-builder-mcp-app.html`) does. The shell points
 * `<base href>` at the origin serving this archive, so the modules, the Wasm and the catalogs load
 * from there; see `docs/design/UI_BUILDER_MCP_APP_HOST.md`.
 */
@Composable
internal fun McpAppHostApp() {
  val scope = rememberCoroutineScope()
  var failure by remember { mutableStateOf<String?>(null) }
  var design by remember { mutableStateOf<McpAppDesignState?>(null) }
  var session by remember { mutableStateOf<McpAppDesignSession?>(null) }
  var bridge by remember { mutableStateOf<McpAppJsonRpcBridge?>(null) }
  var catalog by remember { mutableStateOf<Pair<String, CapabilityCatalog>?>(null) }
  // Bumped on every edit the editor reports, so autosave and model context debounce on it.
  var editTick by remember { mutableIntStateOf(0) }
  var selectedNodeId by remember { mutableStateOf<String?>(null) }
  // The host's choice to start with (see McpAppLayout), then the person's, from the file bar.
  var layout by remember { mutableStateOf(McpAppLayout.parse(mcpAppLayout())) }
  LaunchedEffect(layout) { publishMcpAppLayout(layout.wireValue) }
  // Whether the host takes `ui/message`: the node menu offers Comment only where it can be sent.
  var hostTakesMessages by remember { mutableStateOf(false) }
  // The device frames, from the URL the shell names; without one the dock keeps its raw fields.
  var devicePresets by remember { mutableStateOf<List<UiBuilderDevicePreset>>(emptyList()) }
  LaunchedEffect(Unit) {
    mcpAppDevicePresetsUrl()?.let { devicePresets = loadDevicePresets(cache = null, path = it) }
  }
  val catalogs = remember {
    McpAppCatalogs(
      fetchAsset = ::fetchMcpAppAsset,
      hostedBaseUrl = mcpAppCatalogBase().ifBlank { null },
    )
  }

  LaunchedEffect(Unit) {
    val file = CompletableDeferred<McpAppFile>()
    lateinit var connection: McpAppJsonRpcBridge
    val transport =
      BrowserMcpAppTransport(
        onNotification = { method, params -> connection.handleNotification(method, params) },
        onTeardown = { scope.launch { session?.close() } },
      )
    connection = McpAppJsonRpcBridge(transport)
    connection.onFile { if (!file.isCompleted) file.complete(it) }
    connection.onResourceUpdated { uri -> scope.launch { session?.resourceUpdated(uri) } }
    bridge = connection
    bootPhase("Connecting to the host")
    val host = runCatching {
      connection.initialize(mcpAppVersion())
    }
      .getOrElse {
        failure = "This page is the UI Builder's MCP App and needs an MCP App host: ${it.message}"
        return@LaunchedEffect
      }
    hostTakesMessages = host.messages
    if (!host.fileResources) {
      failure =
        "${host.name.ifBlank { "This host" }} does not offer file resources " +
          "(hostCapabilities.experimental[\"openai/resource\"]), so it cannot open a .uid file here."
      return@LaunchedEffect
    }
    bootPhase("Waiting for the design file")
    val opened = file.await()
    bootPhase("Reading ${opened.name}")
    val created = McpAppDesignSession(connection, opened) { design = it }
    session = created
    created.open()
  }

  val state = design
  val systemId = (state?.document?.catalogPin?.get("systemId") as? JsonPrimitive)?.contentOrNull
  LaunchedEffect(systemId) {
    if (systemId == null || catalog?.first == systemId) return@LaunchedEffect
    bootPhase("Loading the $systemId catalog")
    runCatching { CapabilityCatalogParser.parse(catalogs.resolve(systemId).capabilitiesJson) }
      .onSuccess { catalog = systemId to it }
      .onFailure { failure = "Cannot open this design: ${it.message}" }
  }

  // Autosave: a writable file follows the editor a moment after the last edit. The session
  // decides whether a write is due (dirty, writable, no unresolved conflict).
  LaunchedEffect(editTick) {
    if (editTick == 0) return@LaunchedEffect
    delay(AUTOSAVE_DELAY_MS)
    session?.save()
  }
  // The selection, as model context, once it settles.
  LaunchedEffect(selectedNodeId, editTick) {
    delay(SELECTION_CONTEXT_DELAY_MS)
    session?.select(selectedNodeId)
  }

  val message = failure ?: state?.failure
  if (message != null) {
    McpAppMessage(message)
    LaunchedEffect(message) { dismissBootScreen() }
    return
  }
  val document = state?.document ?: return
  val loadedCatalog = catalog?.takeIf { it.first == systemId }?.second ?: return
  val mismatch = catalogPinMismatch(document, loadedCatalog)
  if (mismatch != null) {
    McpAppMessage(mismatch)
    LaunchedEffect(mismatch) { dismissBootScreen() }
    return
  }
  val current = session ?: return
  McpAppEditorScreen(
    state = state,
    session = current,
    catalog = loadedCatalog,
    layout = layout,
    onLayoutChange = { layout = it },
    commentsEnabled = hostTakesMessages,
    onEditorState = { editor ->
      publishEditorState(editor)
      if (current.edited(editor.document)) editTick++
      selectedNodeId = editor.selectedNodeId
    },
    onHelp = { scope.launch { runCatching { bridge?.openLink(MCP_APP_GUIDE_URL) } } },
    onEditorShown = { markReady() },
    devicePresets = devicePresets,
  )
}

@Composable
private fun McpAppMessage(message: String) {
  Surface(Modifier.fillMaxSize()) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Compose UI Builder", style = MaterialTheme.typography.titleMedium)
      Text(message, style = MaterialTheme.typography.bodyMedium)
    }
  }
}

/**
 * JSON-RPC 2.0 over `window.parent.postMessage`, as MCP Apps specifies it.
 *
 * Only messages whose source is the parent frame are read; a page opened on its own (no parent)
 * gets a clear failure from [McpAppJsonRpcBridge.initialize] instead of waiting forever. The host's
 * requests to the app are answered here: `ping`, and `ui/resource-teardown`, which stops following
 * the file.
 */
internal class BrowserMcpAppTransport(
  private val onNotification: (String, JsonObject) -> Unit,
  private val onTeardown: () -> Unit,
) : McpAppTransport {
  private val pending = mutableMapOf<Int, CancellableContinuation<JsonElement>>()
  private var nextId = 0

  init {
    listenForMcpAppMessages(::receive)
  }

  override suspend fun request(method: String, params: JsonObject): JsonElement {
    check(hasMcpAppParent()) { "the page is not inside an MCP App host" }
    return withTimeout(REQUEST_TIMEOUT_MS) {
      suspendCancellableCoroutine { continuation ->
        val id = ++nextId
        pending[id] = continuation
        continuation.invokeOnCancellation { pending.remove(id) }
        post(
          buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
          }
        )
      }
    }
  }

  override fun notify(method: String, params: JsonObject) {
    post(
      buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        put("params", params)
      }
    )
  }

  private fun receive(text: String) {
    val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
    val id = message["id"]?.takeUnless { it is JsonNull }
    val method = (message["method"] as? JsonPrimitive)?.contentOrNull
    val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
    when {
      method == null && id != null -> {
        val continuation = pending.remove(id.jsonPrimitive.intOrNull ?: return) ?: return
        val error = message["error"] as? JsonObject
        if (error != null) {
          val detail = (error["message"] as? JsonPrimitive)?.contentOrNull ?: error.toString()
          continuation.resumeWithException(IllegalStateException(detail))
        } else {
          continuation.resume(message["result"] ?: JsonNull)
        }
      }
      method != null && id != null -> answer(id, method)
      method != null -> onNotification(method, params)
    }
  }

  private fun answer(id: JsonElement, method: String) {
    val known =
      when (method) {
        "ping" -> true
        "ui/resource-teardown" -> {
          onTeardown()
          true
        }
        else -> false
      }
    post(
      buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        if (known) {
          put("result", JsonObject(emptyMap()))
        } else {
          put(
            "error",
            buildJsonObject {
              put("code", -32601)
              put("message", "Unsupported app method: $method")
            },
          )
        }
      }
    )
  }

  private fun post(message: JsonObject) = postToMcpAppHost(message.toString())
}

/**
 * Whether the embedding page declared this an MCP App by defining
 * `globalThis.composeUiBuilderMcpApp`. Checked before [hostBridgeEnabled]: the two are different
 * hosts, and a page is one or the other.
 */
@JsFun(
  """() => {
  const app = globalThis.composeUiBuilderMcpApp;
  return !!app && typeof app === 'object';
}"""
)
internal external fun mcpAppEnabled(): Boolean

@JsFun("() => String(globalThis.composeUiBuilderMcpApp?.version ?? 'dev')")
private external fun mcpAppVersion(): String

/**
 * The shell's `layout`: `focused` or `full`, as compose-preview-server fills in
 * `__COMPOSE_UI_BUILDER_MCP_APP_LAYOUT__` from its `uiBuilderMcpAppLayout` setting. A page URL's
 * `?layout=` wins, for a person or a harness opening the shell by hand.
 */
@JsFun(
  """() => {
  const fromUrl = new URLSearchParams(globalThis.location?.search ?? '').get('layout');
  return String(fromUrl ?? globalThis.composeUiBuilderMcpApp?.layout ?? '');
}"""
)
private external fun mcpAppLayout(): String

/** The layout on show, as `data-ui-builder-layout` on the page, for a harness to read. */
@JsFun("(layout) => document.documentElement.setAttribute('data-ui-builder-layout', layout)")
private external fun publishMcpAppLayout(layout: String)

/**
 * The shell's `devicePresets`: a URL answering in the shape of a host's
 * `/api/ui-builder/v1/device-presets`, or null when the shell leaves its placeholder unfilled. A
 * server fills it with its own route; a static host can serve a copy of one.
 */
internal fun mcpAppDevicePresetsUrl(): String? =
  mcpAppDevicePresets().takeIf { it.isNotBlank() && !it.startsWith("__") }

@JsFun("() => String(globalThis.composeUiBuilderMcpApp?.devicePresets ?? '')")
private external fun mcpAppDevicePresets(): String

@JsFun("() => String(globalThis.composeUiBuilderMcpApp?.catalogBase ?? '')")
private external fun mcpAppCatalogBase(): String

@JsFun("() => globalThis.parent !== globalThis") private external fun hasMcpAppParent(): Boolean

@JsFun(
  """(onMessage) => {
  globalThis.addEventListener('message', (event) => {
    if (event.source !== globalThis.parent || globalThis.parent === globalThis) return;
    const data = event.data;
    if (!data || data.jsonrpc !== '2.0') return;
    onMessage(JSON.stringify(data));
  });
}"""
)
private external fun listenForMcpAppMessages(onMessage: (String) -> Unit)

@JsFun("(json) => globalThis.parent.postMessage(JSON.parse(json), '*')")
private external fun postToMcpAppHost(json: String)

/**
 * An asset of this archive — a capability catalog — resolved against the document's base, which the
 * MCP App shell points at the origin serving the archive (the host's own origin serves none of it).
 * [sameOriginRequestUrl] keeps it under that base.
 */
private suspend fun fetchMcpAppAsset(path: String): String =
  suspendCancellableCoroutine { continuation ->
    fetchMcpAppAssetPromise(sameOriginRequestUrl(path))
      .then { value ->
        if (continuation.isActive) continuation.resume(value.toString())
        null
      }
      .catch { error ->
        if (continuation.isActive) {
          continuation.resumeWithException(IllegalStateException("$path: $error"))
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
private external fun fetchMcpAppAssetPromise(url: String): Promise<JsString>

private const val AUTOSAVE_DELAY_MS = 1_200L
private const val SELECTION_CONTEXT_DELAY_MS = 250L
private const val REQUEST_TIMEOUT_MS = 60_000L

private const val MCP_APP_GUIDE_URL =
  "https://github.com/yschimke/compose-ui-builder/blob/main/docs/UI_BUILDER_GETTING_STARTED.md"
