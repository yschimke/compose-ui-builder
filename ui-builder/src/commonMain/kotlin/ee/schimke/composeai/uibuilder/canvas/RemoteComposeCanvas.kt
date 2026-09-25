@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ee.schimke.composeai.uibuilder.canvas

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.RcCustomComponentRegistry
import ee.schimke.composeai.rcplayer.compose.RcCustomContent
import ee.schimke.composeai.rcplayer.compose.RcPlayerTheme
import ee.schimke.composeai.rcplayer.compose.composeSupportReport
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import ee.schimke.composeai.uibuilder.export.REMOTE_COMPOSE_CUSTOM_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.optionalString
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val MAX_REMOTE_COMPOSE_BASE64_CHARS = 8 * 1024 * 1024

@Composable
internal fun RemoteComposeDocument(
  document: UiBuilderDocument,
  node: UiBuilderNode,
  modifier: Modifier,
  state: Map<String, String?>,
  onEvent: (RcPlayerEvent) -> Unit,
  slotContent: @Composable (String, Modifier) -> Unit,
) {
  val encoded = node.string("documentBase64")
  val url = node.string("documentUrl")
  val resolve = LocalRemoteComposeDocuments.current
  // Bytes win. A design that carries its own document has already been decided; reaching for the
  // network as well would make an offline reopen of a saved design depend on a host it does not
  // need, and would leave two answers to the question of what this node holds.
  val decoded =
    when {
      encoded.isNotBlank() -> remember(encoded) { decodeRemoteComposeDocument(encoded) }
      url.isNotBlank() -> resolve(url)
      else ->
        remember {
          Result.failure(
            IllegalArgumentException(
              "Remote Compose node needs either documentBase64 or documentUrl"
            )
          )
        }
    }
  if (decoded == null) {
    // Waiting, not broken — see [LocalRemoteComposeDocuments]. The URL is shown because it is the
    // only thing an author can act on while it is unresolved.
    RemoteComposeDiagnostic(message = "Loading $url", modifier = modifier, error = false)
    return
  }
  val rcDocument = decoded.getOrNull()
  if (rcDocument == null) {
    RemoteComposeDiagnostic(
      message = decoded.exceptionOrNull()?.message ?: "Remote Compose document is invalid",
      modifier = modifier,
    )
    return
  }

  val namedValues = remember(node.id) { mutableStateMapOf<String, RcNamedValue>() }
  val desiredNamedValues = node.remoteComposeNamedValues(state)
  SideEffect {
    if (namedValues.toMap() != desiredNamedValues) {
      namedValues.clear()
      namedValues.putAll(desiredNamedValues)
    }
  }
  val renderers =
    node.slots.keys.associateWith { slotName ->
      val content: RcCustomContent = { _, next -> slotContent(slotName, next) }
      content
    }
  val customComponents = RcCustomComponentRegistry(renderers)
  val missingCustomComponents =
    remember(rcDocument, customComponents.names) {
      rcDocument
        .composeSupportReport(availableCustomComponents = customComponents.names)
        .issues
        .filter { it.operation == "Custom" }
    }
  if (missingCustomComponents.isNotEmpty()) {
    RemoteComposeDiagnostic(
      message = missingCustomComponents.joinToString("\n") { it.detail },
      modifier = modifier,
    )
    return
  }
  val inheritedTheme =
    when (document.environment["theme"]?.jsonPrimitive?.contentOrNull) {
      "light" -> RcPlayerTheme.Light
      "dark" -> RcPlayerTheme.Dark
      else -> RcPlayerTheme.System
    }
  val theme =
    when (node.string("theme")) {
      "light" -> RcPlayerTheme.Light
      "dark" -> RcPlayerTheme.Dark
      "system" -> RcPlayerTheme.System
      else -> inheritedTheme
    }
  RcComposePlayer(
    document = rcDocument,
    modifier = modifier,
    theme = theme,
    namedValues = namedValues,
    onEvent = onEvent,
    customComponents = customComponents,
  )
}

/**
 * A captured inline subtree, played rather than described.
 *
 * ## Why the registry is built from the design and not from the document
 *
 * The captured document names its custom components by the string the generated body wrote —
 * `RemoteCustomComponent(name = "field")`, from the node's own `name` property — and the host is
 * what supplies the Compose that fills each one. So the two halves of that contract are the design
 * node and its `content` slot, and they are what this walks: every `remote-compose/custom` under
 * this node registers a renderer under its `name` that draws its own children.
 *
 * That is the same seam an embedded `remote-compose/document` uses through its named slots, reached
 * from the other side — and it is what makes a design nest Compose inside Remote Compose inside
 * Compose with a real player in the middle rather than a frame.
 *
 * ## The walk stops at a custom component
 *
 * What is under one is host content again, so its own descendants are not part of the remote
 * subtree and must not be searched for further custom components: a `remote-compose/custom` nested
 * inside another one's `content` belongs to whatever *that* content is, not to this document. The
 * same rule `RemoteScopes` applies when it decides which vocabulary a node is written in.
 */
@Composable
internal fun PlayedInlineRemoteContent(
  document: UiBuilderDocument,
  node: UiBuilderNode,
  captured: Result<RcDocument>,
  modifier: Modifier,
  slotContent: @Composable (String, Modifier) -> Unit,
) {
  val rcDocument = captured.getOrNull()
  if (rcDocument == null) {
    RemoteComposeDiagnostic(
      message =
        captured.exceptionOrNull()?.message
          ?: "the captured Remote Compose document could not be read",
      modifier = modifier,
    )
    return
  }
  val fills = remember(document, node.id) { document.customComponentFills(node) }
  val renderers = fills.mapValues { (_, fillIds) ->
    val content: RcCustomContent = { _, next ->
      Column(next) { fillIds.forEach { slotContent(it, Modifier.fillMaxWidth()) } }
    }
    content
  }
  val customComponents = RcCustomComponentRegistry(renderers)
  // The same preflight the embedded document runs, and it earns its place here for a sharper
  // reason: these bytes were generated from this design, so an unregistered name is a disagreement
  // between the emitter and the canvas rather than a document somebody else published. Saying which
  // name is missing is what turns that into something an author can act on.
  val missing =
    remember(rcDocument, customComponents.names) {
      rcDocument
        .composeSupportReport(availableCustomComponents = customComponents.names)
        .issues
        .filter { it.operation == "Custom" }
    }
  if (missing.isNotEmpty()) {
    RemoteComposeDiagnostic(message = missing.joinToString("\n") { it.detail }, modifier = modifier)
    return
  }
  val inherited =
    when (document.environment["theme"]?.jsonPrimitive?.contentOrNull) {
      "light" -> RcPlayerTheme.Light
      "dark" -> RcPlayerTheme.Dark
      else -> RcPlayerTheme.System
    }
  // The document's own shape, where it declares one, and this is the one place an inline node
  // differs from an embedded one on purpose. An embedded document is a node an author added and
  // sized: its modifiers are what they asked for, and overriding them with the bytes' aspect would
  // ignore the ask. An inline node was never sized *as a document* — the author drew a subtree, and
  // the only statement about how much room it wants is the one the capture wrote into the header.
  // Without this the player takes every pixel the column has left and the design's own content
  // below the remote content stops being drawn at all.
  val header = rcDocument.header
  val shaped =
    if (header.width > 0 && header.height > 0) {
      modifier.aspectRatio(header.width.toFloat() / header.height.toFloat())
    } else modifier
  RcComposePlayer(
    document = rcDocument,
    modifier = shaped,
    theme =
      when (node.string("theme")) {
        "light" -> RcPlayerTheme.Light
        "dark" -> RcPlayerTheme.Dark
        "system" -> RcPlayerTheme.System
        else -> inherited
      },
    customComponents = customComponents,
  )
}

/**
 * Every custom component in [host]'s remote subtree, as `name` to the node ids that fill it.
 *
 * A map rather than a list because that is what a registry is keyed by, and two nodes sharing one
 * name is a design decision rather than an error — the later one wins here, exactly as it would in
 * a registry built by hand.
 */
private fun UiBuilderDocument.customComponentFills(host: UiBuilderNode): Map<String, List<String>> {
  val fills = mutableMapOf<String, List<String>>()
  val seen = mutableSetOf<String>()
  fun walk(id: String) {
    if (!seen.add(id)) return
    val node = nodes[id] ?: return
    if (node.componentId == REMOTE_COMPOSE_CUSTOM_COMPONENT_ID) {
      val name = node.string("name")
      if (name.isNotEmpty()) fills[name] = node.slots["content"].orEmpty()
      // Deliberately not descended into: see the KDoc above.
      return
    }
    node.slots.values.flatten().forEach(::walk)
  }
  host.slots["content"].orEmpty().forEach(::walk)
  return fills
}

internal fun decodeRemoteComposeDocument(encoded: String): Result<RcDocument> = runCatching {
  require(encoded.isNotBlank()) { "Remote Compose documentBase64 is required" }
  require(encoded.length <= MAX_REMOTE_COMPOSE_BASE64_CHARS) {
    "Remote Compose documentBase64 exceeds the 8 MiB encoded limit"
  }
  RcDocumentCodec.decode(Base64.Default.decode(encoded))
}

private fun UiBuilderNode.remoteComposeNamedValues(
  state: Map<String, String?>
): Map<String, RcNamedValue> {
  val declarations = obj("namedValues")["value"] as? JsonObject ?: return emptyMap()
  return declarations
    .mapNotNull { (name, element) ->
      val declaration = element as? JsonObject ?: return@mapNotNull null
      val type = declaration.optionalString("type") ?: return@mapNotNull null
      val value = declaration["value"]?.jsonPrimitive
      val resolved =
        when (type) {
          "stateText" ->
            declaration.optionalString("variable")?.let(state::get)?.let(RcNamedValue::Text)
          "text" -> value?.contentOrNull?.let(RcNamedValue::Text)
          "float" -> value?.floatOrNull?.let(RcNamedValue::FloatValue)
          "integer" -> value?.intOrNull?.let(RcNamedValue::Integer)
          "long" -> value?.contentOrNull?.toLongOrNull()?.let(RcNamedValue::LongValue)
          "color" ->
            value?.contentOrNull?.let { color ->
              runCatching { RcNamedValue.Color(parseArgb(color).toInt()) }.getOrNull()
            }
          else -> null
        }
      resolved?.let { name to it }
    }
    .toMap()
}

internal fun RcPlayerEvent.bindingName(): String? =
  when (this) {
    is RcPlayerEvent.HostNamedAction -> name
    is RcPlayerEvent.HostAction -> "hostAction:$actionId"
    is RcPlayerEvent.HostActionMetadata -> "hostAction:$actionId"
    is RcPlayerEvent.DebugMessage -> null
  }

@Composable
private fun RemoteComposeDiagnostic(
  message: String,
  modifier: Modifier,
  /** False for a document that is merely not here yet, which is not the same as a broken one. */
  error: Boolean = true,
) {
  val container =
    if (error) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.surfaceVariant
  val content =
    if (error) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
  Surface(modifier, color = container) { Text(message, Modifier.padding(8.dp), color = content) }
}
