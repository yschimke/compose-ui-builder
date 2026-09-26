@file:OptIn(
  kotlin.js.ExperimentalWasmJsInterop::class,
  kotlinx.serialization.ExperimentalSerializationApi::class,
)

package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorGeneratedCode
import ee.schimke.composeai.uibuilder.editor.EditorPane
import ee.schimke.composeai.uibuilder.editor.EditorSelectionRequest
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorTheme
import ee.schimke.composeai.uibuilder.editor.UiBuilderHostAction
import ee.schimke.composeai.uibuilder.editor.UiBuilderHostChrome
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The host bridge: the design belongs to the page that embeds this editor, not to this page.
 *
 * Every other mode owns its document somewhere this page can reach — a server, the browser's
 * storage, a bundled fixture. An IDE that opens a checked-in `.uid` file owns it in the IDE: the
 * file is the source of truth, the IDE's buffer is what is dirty, and saving, undoing, reverting
 * and diffing are the IDE's business. The JVM hosts get that by calling
 * `OfflineUiBuilderSession.projectDocument` in-process. A browser engine hosted by an IDE — a VS
 * Code webview — cannot, so the same two edges cross `postMessage` instead.
 *
 * The page selects this mode by defining `globalThis.composeUiBuilderHost` before the module loads
 * (see [hostBridgeEnabled]). Then:
 * - **in**, as a window `message` event, `{ type: "compose-ui-builder/open", ... }` — see
 *   [HostOpenMessage]. Every `open` replaces the design: the host sends one when the file opens and
 *   again whenever the file changes under the editor (an external edit, a `git checkout`, the text
 *   editor beside this one). `{ type: "compose-ui-builder/select", nodeId }` selects a layer, for a
 *   host that draws its own layer tree, and `{ type: "compose-ui-builder/invoke", id }` runs one of
 *   the editor's toolbar or rail controls.
 * - **out**, through `composeUiBuilderHost.postMessage`: `ready` once the listener is installed,
 *   `changed` with the whole document after each edit, `chrome` with the toolbar and rail controls
 *   the host draws in place of the editor's own (`UiBuilderHostChrome`; the editor role draws
 *   neither), `selection` with the selected layer's id (empty for none), `generated-code` with the
 *   export Kotlin when the host invokes `dock.code`, `error` when an `open` cannot be read, and
 *   `open-link` for a URL the host should open, since a webview cannot navigate.
 *
 * The page may also define `composeUiBuilderHost.readTheme()` and send `{ type:
 * "compose-ui-builder/theme", theme }` when its theme changes, to draw the editor's own UI in the
 * host's colours; see [HostThemeMessage].
 *
 * `composeUiBuilderHost.role` picks which half of the IntelliJ plugin's split a page is: `editor`
 * (the default) is the canvas and inspector, and `preview` is the devices-and-configurations view,
 * which only follows `open`s and never publishes. See [HostBridgeRole].
 *
 * Nothing here fetches. The catalog arrives with the document, because a webview serves this
 * archive from a resource origin that is not its own, and a relative `fetch` resolves against the
 * page rather than the archive; see `sameOriginRequestUrl`.
 *
 * The document is written with the IntelliJ plugin's JSON options, so the same design saved from
 * either IDE is the same bytes.
 */
@Composable
internal fun HostBridgeApp() {
  val role = remember { hostBridgeRole() }
  var opened by remember { mutableStateOf<HostOpenedDesign?>(null) }
  var selectionRequest by remember { mutableStateOf<EditorSelectionRequest?>(null) }
  // The editor's toolbar and rails, drawn by the host (see UiBuilderHostChrome). One instance for
  // the page, so an `invoke` that lands between two `open`s still finds the current handlers.
  // The host's colours for the editor's own UI, read once and then followed as the host's theme
  // changes; the editor keeps its own where the host sends none (see HostBridgeTheme.kt).
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
    var generation = 0
    var selections = 0
    listenForHostMessages(
      onOpen = { messageJson ->
        runCatching { openHostDesign(messageJson, ++generation) }
          .onSuccess { design ->
            opened = design
            // A seeded design exists only here until the host has it: send it back at once, so
            // the empty file the host opened becomes the design the person is looking at.
            if (design.seeded && role == HostBridgeRole.Editor) {
              postHostChanged(encodeHostDesign(design.document))
            }
          }
          .onFailure { postHostError(it.message ?: it::class.simpleName ?: "unreadable design") }
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
  key(design.generation) {
    when (role) {
      HostBridgeRole.Editor ->
        UiBuilderEditor(
          document = design.document,
          catalog = design.catalog,
          sessionLabel = design.label,
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
          // The split the IntelliJ plugin makes: the editor tab is the canvas, and the devices
          // and configurations are a view of their own beside it (the `preview` role). The layer
          // tree is the host's too, so it starts closed here; its rail still opens it.
          initialPanes = setOf(EditorPane.Editor),
          availablePanes = setOf(EditorPane.Editor),
          openDefaultPreview = false,
          initialInspectorOpen = true,
        )
      // The same component with only its Preview pane, as the IntelliJ Preview tool window runs
      // it. It never publishes: the editor beside it owns the design, and this view follows the
      // `open`s the host forwards from it.
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

/** Which half of the split a host page is: `composeUiBuilderHost.role`, `editor` by default. */
internal enum class HostBridgeRole {
  Editor,
  Preview,
}

private fun hostBridgeRole(): HostBridgeRole =
  if (hostBridgeRoleName() == "preview") HostBridgeRole.Preview else HostBridgeRole.Editor

/**
 * What a host sends to open a design.
 *
 * [capabilities] is the capability JSON of the catalog the design pins — the host reads it from
 * this archive's root, `<systemId>-capabilities-v1.json`. [document] is `DesignDocumentV1` JSON, or
 * empty for a file that has no design yet, in which case [seed] says what to start it as.
 */
@Serializable
internal data class HostOpenMessage(
  val document: String = "",
  val capabilities: String,
  val label: String = "",
  val seed: HostDesignSeed? = null,
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
) {
  private var published: UiBuilderDocument = document
  private var publishedSelection: String? = null

  /** Tells the host which layer is selected, so a tree it draws beside the canvas can follow. */
  fun publishSelectionIfChanged(nodeId: String?) {
    if (nodeId == publishedSelection) return
    publishedSelection = nodeId
    postHostSelection(nodeId.orEmpty())
  }

  /**
   * Sends [current] to the host when it differs from what the host already has.
   *
   * Called on every state change, most of which are a selection or a panel rather than an edit, so
   * the identity check comes first: the reducer only builds a new document for an edit.
   */
  fun publishIfChanged(current: UiBuilderDocument) {
    if (current === published) return
    val changed = current != published
    published = current
    if (changed) postHostChanged(encodeHostDesign(current))
  }
}

internal fun openHostDesign(messageJson: String, generation: Int): HostOpenedDesign {
  val message = hostMessageJson.decodeFromString(HostOpenMessage.serializer(), messageJson)
  val catalog = CapabilityCatalogParser.parse(message.capabilities)
  val seed = message.seed
  val document =
    if (message.document.isNotBlank()) {
      decodeHostDesign(message.document)
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
  )
}

internal fun decodeHostDesign(documentJson: String): UiBuilderDocument {
  val wire = hostDesignJson.decodeFromString(DesignDocumentV1.serializer(), documentJson)
  require(wire.schema in HOST_DESIGN_SCHEMAS) {
    "unsupported design schema '${wire.schema}'; this editor reads " +
      HOST_DESIGN_SCHEMAS.joinToString()
  }
  return wire.toUiBuilderDocument()
}

internal fun encodeHostDesign(document: UiBuilderDocument): String =
  hostDesignJson.encodeToString(DesignDocumentV1.serializer(), document.toDesignDocumentV1()) + "\n"

/** The declarations a checked-in design may carry, as the IntelliJ plugin accepts them. */
private val HOST_DESIGN_SCHEMAS =
  setOf("compose-ui-builder-document/v1", "compose-ui-builder-document/v1-candidate")

/** `UiBuilderProjectService.projectDesignJson`, so both IDEs write a design identically. */
private val hostDesignJson = Json {
  classDiscriminator = "type"
  encodeDefaults = true
  explicitNulls = true
  ignoreUnknownKeys = true
  prettyPrint = true
  prettyPrintIndent = "  "
}

/** The envelope only; a newer host may send fields this editor has no use for. */
private val hostMessageJson = Json { ignoreUnknownKeys = true }

private const val UI_BUILDER_GUIDE_URL =
  "https://github.com/yschimke/compose-ui-builder/blob/main/docs/UI_BUILDER_GETTING_STARTED.md"

/**
 * Whether the embedding page declared itself a host by defining `globalThis.composeUiBuilderHost`.
 *
 * An object rather than a query parameter because a VS Code webview owns its document URL: the
 * query is VS Code's, and a host cannot add to it.
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
 * Hands each `open` to [onOpen] as JSON, so the envelope is decoded once, in Kotlin; each `select`
 * to [onSelect]; and each `invoke` to [onInvoke].
 *
 * Only the embedding host speaks this protocol, so only messages from `window.parent` are read. In
 * a VS Code webview that is the frame VS Code relays `webview.postMessage` through; for a top-level
 * page (a harness, or a host posting to its own window) `window.parent` is the window itself.
 * Anything else, such as a frame the editor itself embeds, is ignored.
 */
@JsFun(
  """(onOpen, onSelect, onInvoke) => {
  globalThis.addEventListener('message', (event) => {
    if (event.source !== globalThis.parent) return;
    const data = event.data;
    if (!data) return;
    if (data.type === 'compose-ui-builder/open') onOpen(JSON.stringify(data));
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

private fun postHostChanged(documentJson: String) =
  postToHost(hostMessage("compose-ui-builder/changed", "document", documentJson))

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
 * [UiBuilderHostAction] as JSON. A wire class here rather than `@Serializable` on the common model:
 * the shape is this bridge's contract, and it should not move because a field was added to the
 * editor's model for another host.
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
