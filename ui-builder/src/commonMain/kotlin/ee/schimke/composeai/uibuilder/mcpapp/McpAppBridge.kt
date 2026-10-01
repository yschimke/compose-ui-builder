package ee.schimke.composeai.uibuilder.mcpapp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The file an MCP App host opened this editor on: the `FileInput` of an OpenAI file-extension
 * entrypoint (`{ file: { name, resourceUri } }`).
 *
 * [resourceUri] is opaque — a `host-resource://` URI the host resolves, never a path — and it is
 * the only URI this editor may write.
 */
data class McpAppFile(val name: String, val resourceUri: String)

/** A `resources/read` of the opened file, with the host's `_meta["openai/resource"]`. */
data class McpAppFileContents(
  val text: String,
  /** The version this text is; what the next write names as `ifMatch`. */
  val etag: String?,
  /** Only `true` lets the editor write. Absent means read-only, as the spec defaults it. */
  val writable: Boolean,
)

/** `openai/resources/write`'s three outcomes. */
sealed interface McpAppWriteOutcome {
  data class Saved(val etag: String) : McpAppWriteOutcome

  /** Not saved: the file changed since the version the write named. [etag] is the current one. */
  data class Conflict(val etag: String) : McpAppWriteOutcome

  /** Not saved: the host's size limit, in UTF-8 bytes. */
  data class TooLarge(val maxBytes: Long) : McpAppWriteOutcome
}

/** What `ui/initialize` told the editor about the host. */
data class McpAppHost(
  val name: String,
  /** `hostCapabilities.experimental["openai/resource"]`: file resources, reads and writes. */
  val fileResources: Boolean,
  /** `hostCapabilities.updateModelContext`: whether a selection can become model context. */
  val modelContext: Boolean,
  /** `hostCapabilities.message`: whether the app may post a message to the conversation. */
  val messages: Boolean = false,
)

/**
 * Everything the editor asks of an MCP App host, as the editor needs it rather than as JSON-RPC.
 *
 * [McpAppJsonRpcBridge] is the real one; a test drives the editor's session against a fake, which
 * is the point of the seam: load, save, conflict, read-only and external change are decided in
 * [McpAppDesignSession], and none of it needs a browser to check.
 */
interface McpAppBridge {
  suspend fun read(uri: String): McpAppFileContents

  suspend fun write(uri: String, text: String, ifMatch: String?): McpAppWriteOutcome

  suspend fun subscribe(uri: String)

  suspend fun unsubscribe(uri: String)

  /** `ui/update-model-context`: replaces whatever this app supplied before. */
  suspend fun updateModelContext(context: McpAppModelContext)

  /** `ui/message`: posts a user message to the conversation. */
  suspend fun sendMessage(message: McpAppMessage)
}

/**
 * One JSON-RPC connection to the host: `window.parent.postMessage` in the browser.
 *
 * Requests the host sends the app (`ping`, `ui/resource-teardown`) are answered by the transport
 * itself; notifications arrive at [McpAppJsonRpcBridge.handleNotification].
 */
interface McpAppTransport {
  /** Sends a request and returns its `result`, or throws with the `error`'s message. */
  suspend fun request(method: String, params: JsonObject): JsonElement

  fun notify(method: String, params: JsonObject)
}

/**
 * [McpAppBridge] over the MCP Apps JSON-RPC dialect, with the OpenAI extensions it uses.
 *
 * - MCP Apps: `ui/initialize` then `ui/notifications/initialized`; `ui/notifications/tool-input`
 *   carries the `FileInput`; `resources/read`, `resources/subscribe` and
 *   `notifications/resources/updated` are the host's, for the opened file.
 * - OpenAI extensions (`openai/mcp-extensions` `docs/spec.md`): `_meta["openai/resource"]` on the
 *   read, asking for `representation: "text"` and returning `etag` and `writable`;
 *   `openai/resources/write`, with `ifMatch`; and `openai/title` on a model-context text block.
 */
class McpAppJsonRpcBridge(private val transport: McpAppTransport) : McpAppBridge {
  private val fileListeners = mutableListOf<(McpAppFile) -> Unit>()
  private val updateListeners = mutableListOf<(String) -> Unit>()

  /** The handshake. Throws when the host speaks a protocol version this editor does not. */
  suspend fun initialize(appVersion: String): McpAppHost {
    val result =
      transport
        .request(
          "ui/initialize",
          buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            putJsonObject("appInfo") {
              put("name", "compose-ui-builder")
              put("version", appVersion)
            }
            putJsonObject("appCapabilities") {
              putJsonArray("availableDisplayModes") {
                add(JsonPrimitive("fullscreen"))
                add(JsonPrimitive("inline"))
              }
            }
          },
        )
        .jsonObject
    val version = result.string("protocolVersion")
    require(version == PROTOCOL_VERSION) {
      "the host speaks MCP Apps protocol '$version'; this editor speaks '$PROTOCOL_VERSION'"
    }
    val capabilities = result["hostCapabilities"] as? JsonObject ?: JsonObject(emptyMap())
    val experimental = capabilities["experimental"] as? JsonObject ?: JsonObject(emptyMap())
    transport.notify("ui/notifications/initialized", JsonObject(emptyMap()))
    return McpAppHost(
      name = (result["hostInfo"] as? JsonObject)?.string("name").orEmpty(),
      fileResources = experimental[OPENAI_RESOURCE] != null,
      modelContext = capabilities["updateModelContext"] != null,
      messages = capabilities["message"] != null,
    )
  }

  /** Called with each `FileInput` the host sends in `ui/notifications/tool-input`. */
  fun onFile(listener: (McpAppFile) -> Unit) {
    fileListeners += listener
  }

  /** Called with the URI of each `notifications/resources/updated`. */
  fun onResourceUpdated(listener: (String) -> Unit) {
    updateListeners += listener
  }

  /** Routes one host notification. Unknown methods are ignored, as the spec allows. */
  fun handleNotification(method: String, params: JsonObject) {
    when (method) {
      "ui/notifications/tool-input" ->
        parseFileInput(params)?.let { file -> fileListeners.forEach { it(file) } }
      "notifications/resources/updated" ->
        params.string("uri")?.let { uri -> updateListeners.forEach { it(uri) } }
    }
  }

  override suspend fun read(uri: String): McpAppFileContents {
    val result =
      transport
        .request(
          "resources/read",
          buildJsonObject {
            put("uri", uri)
            putJsonObject("_meta") {
              putJsonObject(OPENAI_RESOURCE) { put("representation", "text") }
            }
          },
        )
        .jsonObject
    val contents = result["contents"]?.jsonArray.orEmpty().map { it.jsonObject }
    val content =
      contents.firstOrNull { it.string("uri") == uri }
        ?: contents.firstOrNull()
        ?: error("the host returned no contents for the design file")
    val text =
      content.string("text")
        ?: content.string("blob")?.let(::decodeBase64Utf8)
        ?: error("the host returned the design file with neither text nor blob")
    val meta = (content["_meta"] as? JsonObject)?.get(OPENAI_RESOURCE) as? JsonObject
    return McpAppFileContents(
      text = text,
      etag = meta?.string("etag")?.takeIf { it.isNotEmpty() },
      writable = (meta?.get("writable") as? JsonPrimitive)?.booleanOrNull == true,
    )
  }

  override suspend fun write(uri: String, text: String, ifMatch: String?): McpAppWriteOutcome {
    val result =
      transport
        .request(
          WRITE_METHOD,
          buildJsonObject {
            put("uri", uri)
            put("text", text)
            if (!ifMatch.isNullOrEmpty()) put("ifMatch", ifMatch)
          },
        )
        .jsonObject
    return when (val outcome = result.string("outcome")) {
      "saved" -> McpAppWriteOutcome.Saved(result.string("etag").orEmpty())
      "conflict" -> McpAppWriteOutcome.Conflict(result.string("etag").orEmpty())
      "too-large" ->
        McpAppWriteOutcome.TooLarge(
          (result["maxBytes"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong() ?: -1
        )
      else -> error("the host answered a write with an unknown outcome '$outcome'")
    }
  }

  override suspend fun subscribe(uri: String) {
    transport.request("resources/subscribe", buildJsonObject { put("uri", uri) })
  }

  override suspend fun unsubscribe(uri: String) {
    transport.request("resources/unsubscribe", buildJsonObject { put("uri", uri) })
  }

  override suspend fun updateModelContext(context: McpAppModelContext) {
    transport.request("ui/update-model-context", context.toParams())
  }

  override suspend fun sendMessage(message: McpAppMessage) {
    transport.request("ui/message", message.toParams())
  }

  /** `ui/open-link`: the frame cannot navigate, so the host opens the editor's help for it. */
  suspend fun openLink(url: String) {
    transport.request("ui/open-link", buildJsonObject { put("url", url) })
  }

  companion object {
    /** The MCP Apps protocol version this bridge was written against. */
    const val PROTOCOL_VERSION: String = "2026-01-26"
    const val OPENAI_RESOURCE: String = "openai/resource"
    const val WRITE_METHOD: String = "openai/resources/write"

    /** `{ arguments: { file: { name, resourceUri } } }`, or null for any other tool input. */
    fun parseFileInput(params: JsonObject): McpAppFile? {
      val file = (params["arguments"] as? JsonObject)?.get("file") as? JsonObject ?: return null
      val name = file.string("name")?.takeIf { it.isNotEmpty() } ?: return null
      val uri = file.string("resourceUri")?.takeIf { it.isNotBlank() } ?: return null
      return McpAppFile(name, uri)
    }
  }
}

private fun JsonObject.string(key: String): String? =
  (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun decodeBase64Utf8(blob: String): String =
  kotlin.io.encoding.Base64.decode(blob).decodeToString()
