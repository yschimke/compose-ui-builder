package ee.schimke.composeai.uibuilder.mcpapp

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Calls one of the MCP server's own tools through the host (`tools/call` from the app). */
fun interface McpAppToolCaller {
  /**
   * The tool result's `structuredContent`, or the JSON of its first text block when it has none.
   * Throws with the result's text when it is `isError`.
   */
  suspend fun callTool(name: String, arguments: JsonObject): JsonObject
}

/**
 * [McpAppBridge] for a design that lives on the MCP server, for hosts with no file resources
 * (Claude). The design is read and saved through the server's app-only editor tools, called via the
 * host, which forwards them with the connector's credential; see
 * `docs/design/UI_BUILDER_MCP_APP_HOST.md` → *Server designs*.
 *
 * The revision is the etag: a save names the revision it started from, and the server answers
 * `conflict` with the current one when somebody else (an agent in the chat, another editor) saved
 * in between. Model context and messages go to the host through [host], as in the file mode.
 */
class McpAppServerDesignBridge(
  private val tools: McpAppToolCaller,
  private val host: McpAppBridge,
) : McpAppBridge {
  /** The newest revision this editor has read or written: where following external edits starts. */
  private var knownRevision: Long? = null
  private var following = false

  override suspend fun read(uri: String): McpAppFileContents {
    val result = tools.callTool(READ_TOOL, designArguments(uri))
    val document =
      result["document"] as? JsonObject
        ?: error("the server returned the design without a document")
    val revision =
      result.long("revision") ?: error("the server returned the design without a revision")
    knownRevision = maxOf(knownRevision ?: revision, revision)
    return McpAppFileContents(
      text = document.toString(),
      etag = revision.toString(),
      writable = (result["writable"] as? JsonPrimitive)?.booleanOrNull == true,
    )
  }

  override suspend fun write(uri: String, text: String, ifMatch: String?): McpAppWriteOutcome {
    val baseRevision =
      ifMatch?.toLongOrNull()
        ?: error("a server design is saved against the revision it was read at")
    val document = runCatching {
      Json.parseToJsonElement(text).jsonObject
    }
      .getOrElse { error("the design is not a single DesignDocumentV1: ${it.message}") }
    val result =
      tools.callTool(
        WRITE_TOOL,
        buildJsonObject {
          put("designId", designId(uri))
          put("baseRevision", baseRevision)
          put("document", document)
        },
      )
    val revision = result.long("revision")
    return when (val outcome = result.string("outcome")) {
      "saved" -> {
        val saved = revision ?: error("the server saved the design but named no revision")
        knownRevision = maxOf(knownRevision ?: saved, saved)
        McpAppWriteOutcome.Saved(saved.toString())
      }
      "conflict" -> McpAppWriteOutcome.Conflict(revision?.toString().orEmpty())
      "read-only" -> McpAppWriteOutcome.ReadOnly
      "too-large" -> McpAppWriteOutcome.TooLarge(result.long("maxBytes") ?: -1)
      else -> error(result.string("message") ?: "the server answered a save with '$outcome'")
    }
  }

  /**
   * Checks that the server offers [AWAIT_TOOL] (a zero-second wait that can never be satisfied), so
   * the file bar only claims to follow external edits when it can. [followExternalEdits] does the
   * following.
   */
  override suspend fun subscribe(uri: String) {
    tools.callTool(
      AWAIT_TOOL,
      buildJsonObject {
        put("designId", designId(uri))
        put("afterRevision", Long.MAX_VALUE)
        put("waitSeconds", 0)
      },
    )
    following = true
  }

  override suspend fun unsubscribe(uri: String) {
    following = false
  }

  /**
   * Long-polls [AWAIT_TOOL] until [unsubscribe], calling [onUpdated] for each newer revision, which
   * the session handles like `notifications/resources/updated`: its own save's echo is recognised
   * by the revision, a clean design adopts the new one, a dirty one asks. A failed wait is retried
   * after a growing pause rather than given up on: a dropped connection must not silently stop
   * following.
   */
  suspend fun followExternalEdits(uri: String, onUpdated: suspend (String) -> Unit) {
    var failures = 0
    while (following) {
      val after = knownRevision
      if (after == null) {
        delay(FOLLOW_IDLE_MS)
        continue
      }
      val result = runCatching {
        tools.callTool(
          AWAIT_TOOL,
          buildJsonObject {
            put("designId", designId(uri))
            put("afterRevision", after)
            put("waitSeconds", AWAIT_SECONDS)
          },
        )
      }
        .getOrElse {
          if (it is kotlinx.coroutines.CancellationException) throw it
          failures++
          delay(minOf(FOLLOW_RETRY_MS * failures, FOLLOW_RETRY_MAX_MS))
          continue
        }
      failures = 0
      val revision = result.long("revision") ?: continue
      if ((result["changed"] as? JsonPrimitive)?.booleanOrNull == true && revision > after) {
        knownRevision = maxOf(knownRevision ?: revision, revision)
        if (following) onUpdated(uri)
      }
    }
  }

  override suspend fun updateModelContext(context: McpAppModelContext) =
    host.updateModelContext(context)

  override suspend fun sendMessage(message: McpAppMessage) = host.sendMessage(message)

  companion object {
    const val EDIT_TOOL: String = "ui_builder_edit_design"
    const val READ_TOOL: String = "ui_builder_editor_read"
    const val WRITE_TOOL: String = "ui_builder_editor_write"
    const val AWAIT_TOOL: String = "ui_builder_editor_await"
    private const val URI_PREFIX = "compose-ui-builder-design:"

    /**
     * The server's limit on one wait; a shorter one keeps well inside the host's request timeout.
     */
    const val AWAIT_SECONDS: Int = 25
    private const val FOLLOW_IDLE_MS = 250L
    private const val FOLLOW_RETRY_MS = 2_000L
    private const val FOLLOW_RETRY_MAX_MS = 30_000L

    /** The session's handle on a server design: opaque to it, like a `host-resource://` URI. */
    fun designFile(designId: String): McpAppFile = McpAppFile(designId, URI_PREFIX + designId)

    /** The server design [file] stands for, or null for a host file. */
    fun designIdOf(file: McpAppFile): String? =
      file.resourceUri.takeIf { it.startsWith(URI_PREFIX) }?.removePrefix(URI_PREFIX)

    /**
     * How an agent changes a node of [file]: in the `.uid` it can edit, or, for a server design,
     * through the UI-builder tools it already has, since it holds no file at all.
     */
    fun editInstruction(file: McpAppFile): String =
      when (val id = designIdOf(file)) {
        null -> "edit that node in the design file (a DesignDocumentV1 `.uid`)"
        else ->
          "edit that node with ui_builder_apply on design `$id` (the editor follows the change)"
      }

    /** Where an agent shows the result of a change, so the person need not look at the panel. */
    fun previewInstruction(file: McpAppFile): String =
      when (designIdOf(file)) {
        null -> "render_preview / render_matrix"
        else -> "ui_builder_view / ui_builder_render_design_matrix"
      }

    /**
     * `{ arguments: { designId } }` from `ui/notifications/tool-input`, or null for other input.
     */
    fun parseDesignInput(params: JsonObject): String? =
      ((params["arguments"] as? JsonObject)?.get("designId") as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf { it.isNotBlank() }

    private fun designId(uri: String): String {
      require(uri.startsWith(URI_PREFIX)) { "'$uri' is not a server design" }
      return uri.removePrefix(URI_PREFIX)
    }

    private fun designArguments(uri: String) = buildJsonObject { put("designId", designId(uri)) }
  }
}

/**
 * `tools/call` over the host's JSON-RPC connection: the result's `structuredContent`, falling back
 * to the JSON of its first text block. An `isError` result throws with its text.
 */
suspend fun McpAppTransport.callServerTool(name: String, arguments: JsonObject): JsonObject {
  val result =
    request(
        "tools/call",
        buildJsonObject {
          put("name", name)
          put("arguments", arguments)
        },
      )
      .jsonObject
  val texts =
    (result["content"] as? JsonArray).orEmpty().mapNotNull { block ->
      (block as? JsonObject)
        ?.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
        ?.let { (it["text"] as? JsonPrimitive)?.contentOrNull }
    }
  if ((result["isError"] as? JsonPrimitive)?.booleanOrNull == true) {
    error(texts.joinToString(" ").ifBlank { "$name failed" })
  }
  (result["structuredContent"] as? JsonObject)?.let {
    return it
  }
  return texts.firstNotNullOfOrNull { text ->
    runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
  } ?: error("$name returned no structured result")
}

private fun JsonObject.string(key: String): String? =
  (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.long(key: String): Long? =
  (this[key] as? JsonPrimitive)?.let {
    it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong()
  }
