@file:OptIn(
  kotlin.js.ExperimentalWasmJsInterop::class,
  kotlinx.serialization.ExperimentalSerializationApi::class,
)

package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ee.schimke.composeai.uibuilder.canvas.LocalLovelaceCardPreviews
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorGeneratedCode
import ee.schimke.composeai.uibuilder.editor.EditorPane
import ee.schimke.composeai.uibuilder.editor.EditorSelectionRequest
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorTheme
import ee.schimke.composeai.uibuilder.editor.UiBuilderHostAction
import ee.schimke.composeai.uibuilder.editor.UiBuilderHostChrome
import ee.schimke.composeai.uibuilder.editor.fileDesigns
import ee.schimke.composeai.uibuilder.export.LovelaceConfigExporter
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.json.Json

/**
 * The host bridge: the design belongs to the page that embeds this editor (e.g. a VS Code webview
 * editing a `.uid` file), which owns saving, undo, revert and diff. JVM hosts use
 * `OfflineUiBuilderSession.projectDocument` in-process; here the same edges cross `postMessage`.
 *
 * The page selects this mode by defining `globalThis.composeUiBuilderHost` before the module loads
 * (see [hostBridgeEnabled]). Then:
 * - **in**, as a window `message` event: `{ type: "compose-ui-builder/open", ... }` (see
 *   [HostOpenMessage]) replaces the design, sent on open and whenever the file changes underneath;
 *   `{ type: "compose-ui-builder/select", nodeId }` selects a layer; `{ type:
 *   "compose-ui-builder/invoke", id }` runs a toolbar or rail control.
 * - **out**, through `composeUiBuilderHost.postMessage`: `ready` once listening; `changed` with the
 *   whole document after each edit; `chrome` with the toolbar and rail controls the host draws
 *   (`UiBuilderHostChrome`); `selection` with the selected id (empty for none); `generated-code`
 *   when the host invokes `dock.code`; `error` when an `open` can't be read; `open-link` for URLs,
 *   since a webview can't navigate.
 *
 * The page may define `composeUiBuilderHost.readTheme()` and send `{ type:
 * "compose-ui-builder/theme", theme }` on theme changes; see [HostThemeMessage].
 * `composeUiBuilderHost.role` is `editor` (default: canvas and inspector) or `preview`
 * (devices-and-configurations view, follows `open`s, never publishes); see [HostBridgeRole].
 *
 * Nothing here fetches: the catalog arrives with the document, since relative fetches resolve
 * against the webview page, not the archive (see `sameOriginRequestUrl`). The document is written
 * with the IntelliJ plugin's JSON options so both IDEs save identical bytes.
 */
@Composable
internal fun HostBridgeApp() {
  val role = remember { hostBridgeRole() }
  var previews by remember { mutableStateOf(JsonObject(emptyMap())) }
  var opened by remember { mutableStateOf<HostOpenedDesign?>(null) }
  // One count for the host's `open`s and this editor's own design switches, so neither reuses the
  // other's key and keeps an editor whose history was made against another design.
  var generation by remember { mutableStateOf(0) }
  var selectionRequest by remember { mutableStateOf<EditorSelectionRequest?>(null) }
  // The editor's toolbar and rails, drawn by the host (see UiBuilderHostChrome); one instance per
  // page so `invoke`s between `open`s find current handlers.
  // The host's colours for the editor's own UI, followed as the host's theme changes (see
  // HostBridgeTheme.kt).
  var hostTheme by remember { mutableStateOf(decodeHostTheme(readHostTheme())) }
  LaunchedEffect(Unit) {
    listenForHostTheme { json -> decodeHostTheme(json)?.let { hostTheme = it } }
  }
  val theme = hostTheme ?: UiBuilderEditorTheme.Default
  val hostChrome = remember {
    UiBuilderHostChrome { actions ->
      postHostChrome(hostChromeJson.encodeToString(HostChromeActionsSerializer, actions))
    }
  }
  LaunchedEffect(Unit) {
    var selections = 0
    listenForHostMessages(
      onOpen = { messageJson ->
        previews = JsonObject(emptyMap())
        runCatching { openHostDesign(messageJson, ++generation) }
          .onSuccess { design ->
            opened = design
            // A seeded design exists only here until the host has it: send it back at once, so
            // the empty file the host opened becomes the design the person is looking at.
            // Lovelace hosts also need an acknowledgement/export after host-side edits and undo.
            if (
              role == HostBridgeRole.Editor &&
                (design.seeded ||
                  design.catalog.platform ==
                    ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform.LOVELACE)
            ) {
              postHostChanged(encodeHostDesign(design.document))
            }
          }
          .onFailure { postHostError(it.message ?: it::class.simpleName ?: "unreadable design") }
      },
      onPreviews = { json ->
        runCatching { Json.parseToJsonElement(json).jsonObject }.onSuccess { previews = it }
      },
      onSelect = { nodeId -> selectionRequest = EditorSelectionRequest(nodeId, ++selections) },
      onInvoke = { id ->
        if (!hostChrome.invoke(id)) postHostError("no editor action '$id' right now")
      },
    )
    bootPhase("Waiting for the design")
    postHostReady()
  }
  val design = opened ?: return
  // A new `open` is a new design as far as the editor is concerned: its undo history, selection
  // and pending edits were made against the version that was just replaced.
  CompositionLocalProvider(LocalLovelaceCardPreviews provides previews) {
    key(design.generation) {
      when (role) {
        HostBridgeRole.Editor ->
          UiBuilderEditor(
            document = design.document,
            catalog = design.catalog,
            sessionLabel = design.label,
            // A switch is a change to the file like any edit: the host is sent the whole file,
            // and the editor opens the newly active design as it would a fresh `open`.
            fileDesigns =
              design.collection?.fileDesigns(
                onSelect = { id ->
                  runCatching { design.switchTo(id, ++generation) }
                    .onSuccess { opened = it }
                    .onFailure { postHostError("Not switched: ${it.message}") }
                }
              ),
            onStateChanged = { state ->
              publishEditorState(state)
              design.publishIfChanged(state.document)
              design.publishSelectionIfChanged(state.selectedNodeId)
            },
            onHelp = { postHostOpenLink(UI_BUILDER_GUIDE_URL) },
            selectionRequest = selectionRequest,
            hostChrome = hostChrome,
            onGeneratedCode = ::postHostGeneratedCode,
            theme = theme,
            // The IntelliJ split: this tab is the canvas; devices and configurations are the
            // `preview` role. The layer tree is the host's, so it starts closed.
            initialPanes = setOf(EditorPane.Editor),
            availablePanes = setOf(EditorPane.Editor),
            openDefaultPreview = false,
            initialInspectorOpen = true,
          )
        // Preview pane only, as the IntelliJ Preview tool window runs it; it never publishes and
        // follows the forwarded `open`s.
        HostBridgeRole.Preview ->
          UiBuilderEditor(
            document = design.document,
            catalog = design.catalog,
            sessionLabel = design.label,
            theme = theme,
            initialPanes = setOf(EditorPane.Preview),
            availablePanes = setOf(EditorPane.Preview),
            openDefaultPreview = false,
          )
      }
      LaunchedEffect(Unit) { markReady() }
    }
  }
}

/** Which half of the split a host page is: `composeUiBuilderHost.role`, `editor` by default. */
internal enum class HostBridgeRole {
  Editor,
  Preview,
}

private fun hostBridgeRole(): HostBridgeRole =
  if (hostBridgeRoleName() == "preview") HostBridgeRole.Preview else HostBridgeRole.Editor

/**
 * What a host sends to open a design. [capabilities] is the pinned catalog's capability JSON from
 * the host's published catalog source. [document] is `DesignDocumentV1` JSON, or empty for a new
 * file, in which case [seed] says what to start it as.
 */
@Serializable
internal data class HostOpenMessage(
  val document: String = "",
  val capabilities: String,
  val label: String = "",
  val seed: HostDesignSeed? = null,
  val lovelaceConfig: String = "",
)

/**
 * A new design, from the same templates as the web host's New design form and the IntelliJ plugin's
 * scratch editors ([UiBuilderNewDesignSeed]). [fixture] is this archive's
 * `jetcaster-discover-operations-v1.json`, which every template reads its environment from.
 */
@Serializable
internal data class HostDesignSeed(
  val designId: String,
  val templateId: String,
  val fixture: String,
)

internal class HostOpenedDesign(
  val document: UiBuilderDocument,
  val catalog: CapabilityCatalog,
  val label: String,
  val generation: Int,
  val seeded: Boolean,
  private val fileContext: OpenedUidDesign? = null,
) {
  private var published: UiBuilderDocument = document
  private var publishedSelection: String? = null

  /** The file's top-level designs when it holds several; [document] is the active one. */
  val collection: UidDesignCollection?
    get() = fileContext?.collection

  /**
   * Makes [id] the active design: sends the host the file with it active — carrying the last
   * published edit — and returns the design to open in this one's place.
   */
  fun switchTo(id: String, generation: Int): HostOpenedDesign {
    val context = requireNotNull(fileContext) { "this design is not one of several in a file" }
    val text = context.switchTo(id, published)
    val next = UidDesignFiles.open(text)
    postHostChanged(text)
    return HostOpenedDesign(
      document = next.document,
      catalog = catalog,
      label = label,
      generation = generation,
      seeded = false,
      fileContext = next,
    )
  }

  /** Tells the host which layer is selected, so a tree it draws beside the canvas can follow. */
  fun publishSelectionIfChanged(nodeId: String?) {
    if (nodeId == publishedSelection) return
    publishedSelection = nodeId
    postHostSelection(nodeId.orEmpty())
  }

  /**
   * Sends [current] to the host when it differs from what the host already has. Identity is checked
   * first, since most state changes aren't edits.
   */
  fun publishIfChanged(current: UiBuilderDocument) {
    if (current === published) return
    val changed = current != published
    if (changed) {
      runCatching { fileContext?.encode(current) ?: encodeHostDesign(current) }
        .onSuccess { text ->
          published = current
          postHostChanged(text)
        }
        .onFailure { postHostError("Not saved: ${it.message}") }
    }
  }
}

internal fun openHostDesign(messageJson: String, generation: Int): HostOpenedDesign {
  val message = hostMessageJson.decodeFromString(HostOpenMessage.serializer(), messageJson)
  val catalog = CapabilityCatalogParser.parse(message.capabilities)
  val seed = message.seed
  val fileContext = message.document.takeIf { it.isNotBlank() }?.let(UidDesignFiles::open)
  val document =
    if (message.lovelaceConfig.isNotBlank()) {
      require(
        catalog.platform == ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform.LOVELACE
      )
      LovelaceConfigExporter.import(
        Json.parseToJsonElement(message.lovelaceConfig).jsonObject,
        "host-dashboard",
        buildJsonObject {
          put("systemId", catalog.benchmark.catalogSystemId)
          put("catalogRevision", catalog.benchmark.catalogRevision)
          put("capabilityDigest", "candidate")
          put("nativeRuntimeId", catalog.benchmark.nativeRuntimeId)
        },
        cardComponentIds = catalog.componentsById.keys,
      )
    } else if (message.document.isNotBlank()) {
      requireNotNull(fileContext).document
    } else {
      requireNotNull(seed) { "the design file is empty and the host sent no seed" }
      val systemId = catalog.benchmark.catalogSystemId
      require(seed.templateId in UiBuilderNewDesignSeed.templateIds(systemId)) {
        "catalog '$systemId' has no template '${seed.templateId}'"
      }
      UiBuilderNewDesignSeed.document(
        designId = seed.designId,
        catalogSystemId = systemId,
        templateId = seed.templateId,
        catalogRevision = catalog.benchmark.catalogRevision,
        nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
        fixture = Json.parseToJsonElement(seed.fixture).jsonObject,
      )
    }
  // Never a design against another catalog's components — see [catalogPinMismatch].
  catalogPinMismatch(document, catalog)?.let { throw IllegalArgumentException(it) }
  return HostOpenedDesign(
    document = document,
    catalog = catalog,
    label = message.label.ifBlank { document.title.ifBlank { document.id } },
    generation = generation,
    seeded = message.document.isBlank(),
    fileContext = fileContext,
  )
}

internal fun decodeHostDesign(documentJson: String): UiBuilderDocument =
  UidDesignFiles.decode(documentJson)

internal fun encodeHostDesign(document: UiBuilderDocument): String = UidDesignFiles.encode(document)

/** The envelope only; a newer host may send fields this editor has no use for. */
private val hostMessageJson = Json { ignoreUnknownKeys = true }

private const val UI_BUILDER_GUIDE_URL =
  "https://github.com/yschimke/compose-ui-builder/blob/main/docs/UI_BUILDER_GETTING_STARTED.md"

/**
 * Whether the embedding page declared itself a host by defining `globalThis.composeUiBuilderHost`;
 * an object rather than a query parameter because a VS Code webview owns its URL.
 */
@JsFun(
  """() => {
  const host = globalThis.composeUiBuilderHost;
  return !!host && typeof host.postMessage === 'function';
}"""
)
internal external fun hostBridgeEnabled(): Boolean

@JsFun("() => String(globalThis.composeUiBuilderHost?.role ?? 'editor')")
private external fun hostBridgeRoleName(): String

/**
 * Hands each `open` to [onOpen] as JSON, each `select` to [onSelect], and each `invoke` to
 * [onInvoke]. Only messages from `window.parent` are read (VS Code's relay frame, or the window
 * itself at top level).
 */
@JsFun(
  """(onOpen, onSelect, onInvoke, onPreviews) => {
  globalThis.addEventListener('message', (event) => {
    if (event.source !== globalThis.parent) return;
    const data = event.data;
    if (!data) return;
    if (data.type === 'compose-ui-builder/lovelace-previews') onPreviews(typeof data.previews === 'string' ? data.previews : JSON.stringify(data.previews ?? {}));
    else if (data.type === 'compose-ui-builder/open') onOpen(JSON.stringify(data));
    else if (data.type === 'compose-ui-builder/select' && typeof data.nodeId === 'string') {
      onSelect(data.nodeId);
    } else if (data.type === 'compose-ui-builder/invoke' && typeof data.id === 'string') {
      onInvoke(data.id);
    }
  });
}"""
)
private external fun listenForHostMessages(
  onOpen: (String) -> Unit,
  onSelect: (String) -> Unit,
  onInvoke: (String) -> Unit,
  onPreviews: (String) -> Unit,
)

/** `{ type: "compose-ui-builder/chrome", actions: [...] }`, the actions as parsed JSON. */
@JsFun(
  """(actionsJson) => {
  const host = globalThis.composeUiBuilderHost;
  if (host && typeof host.postMessage === 'function') {
    host.postMessage({ type: 'compose-ui-builder/chrome', actions: JSON.parse(actionsJson) });
  }
}"""
)
private external fun postHostChrome(actionsJson: String)

private val hostChromeJson = Json { encodeDefaults = true }

@JsFun(
  """(message) => {
  const host = globalThis.composeUiBuilderHost;
  if (!host || typeof host.postMessage !== 'function') {
    console.error('compose-ui-builder: the host bridge needs globalThis.composeUiBuilderHost.postMessage');
    return;
  }
  host.postMessage(message);
}"""
)
private external fun postToHost(message: JsAny)

@JsFun("(type, key, value) => ({ type: type, [key]: value })")
private external fun hostMessage(type: String, key: String, value: String): JsAny

@JsFun("() => ({ type: 'compose-ui-builder/ready' })") private external fun readyMessage(): JsAny

private fun postHostReady() = postToHost(readyMessage())

private fun postHostChanged(documentJson: String) {
  postToHost(hostMessage("compose-ui-builder/changed", "document", documentJson))
  val document = runCatching { decodeHostDesign(documentJson) }.getOrNull() ?: return
  if (
    document.roots.singleOrNull()?.let { document.nodes[it]?.componentId } ==
      LovelaceConfigExporter.DASHBOARD
  ) {
    when (val exported = LovelaceConfigExporter.export(document)) {
      is LovelaceConfigExporter.Result.Emitted ->
        postToHost(hostMessage("compose-ui-builder/lovelace-config", "config", exported.source))
      is LovelaceConfigExporter.Result.Refused -> postHostError(exported.reasons.joinToString("; "))
    }
  }
}

private fun postHostSelection(nodeId: String) =
  postToHost(hostMessage("compose-ui-builder/selection", "nodeId", nodeId))

private fun postHostError(message: String) =
  postToHost(hostMessage("compose-ui-builder/error", "message", message))

private fun postHostOpenLink(url: String) =
  postToHost(hostMessage("compose-ui-builder/open-link", "url", url))

/** The host owns the destination: VS Code opens this in an untitled Kotlin document. */
private fun postHostGeneratedCode(code: EditorGeneratedCode) =
  when (code) {
    is EditorGeneratedCode.Source ->
      postToHost(hostMessage("compose-ui-builder/generated-code", "kotlin", code.kotlin))
    is EditorGeneratedCode.Refused ->
      postHostError("Generated code is unavailable: ${code.reasons.joinToString("; ")}")
  }

/**
 * [UiBuilderHostAction] as JSON, defined here so the bridge's contract doesn't move with the common
 * model.
 */
private object HostChromeActionsSerializer :
  kotlinx.serialization.KSerializer<List<UiBuilderHostAction>> {
  private val delegate =
    kotlinx.serialization.builtins.ListSerializer(HostChromeActionWire.serializer())
  override val descriptor = delegate.descriptor

  override fun serialize(
    encoder: kotlinx.serialization.encoding.Encoder,
    value: List<UiBuilderHostAction>,
  ) =
    delegate.serialize(
      encoder,
      value.map {
        HostChromeActionWire(
          it.id,
          it.label,
          it.group,
          it.icon,
          it.enabled,
          it.checked,
          it.badge,
          it.shortcut,
        )
      },
    )

  override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder) =
    error("the bridge only sends chrome")
}

@Serializable
private data class HostChromeActionWire(
  val id: String,
  val label: String,
  val group: String,
  val icon: String,
  val enabled: Boolean,
  val checked: Boolean?,
  val badge: Int,
  val shortcut: String,
)
