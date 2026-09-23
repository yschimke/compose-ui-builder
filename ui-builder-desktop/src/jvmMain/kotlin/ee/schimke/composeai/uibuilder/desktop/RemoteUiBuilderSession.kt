package ee.schimke.composeai.uibuilder.desktop

import ee.schimke.composeai.uibuilder.DesignCommentDraft
import ee.schimke.composeai.uibuilder.EditorSubmission
import ee.schimke.composeai.uibuilder.UiBuilderDocument
import ee.schimke.composeai.uibuilder.UiBuilderNativeRender
import ee.schimke.composeai.uibuilder.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.client.MonotonicUiBuilderRequestIds
import ee.schimke.composeai.uibuilder.client.UiBuilderClientConnection
import ee.schimke.composeai.uibuilder.client.UiBuilderClientUpdate
import ee.schimke.composeai.uibuilder.client.UiBuilderHttpRequest
import ee.schimke.composeai.uibuilder.client.UiBuilderHttpResponse
import ee.schimke.composeai.uibuilder.client.UiBuilderHttpResult
import ee.schimke.composeai.uibuilder.client.UiBuilderHttpTransport
import ee.schimke.composeai.uibuilder.client.UiBuilderProtocolHttpClient
import ee.schimke.composeai.uibuilder.client.UiBuilderProtocolUpdateClient
import ee.schimke.composeai.uibuilder.client.UiBuilderWebSocketRequest
import ee.schimke.composeai.uibuilder.client.UiBuilderWebSocketTransport
import ee.schimke.composeai.uibuilder.client.toProtocolSubmission
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignsResponseV1
import ee.schimke.composeai.uibuilder.protocol.ListDesignsRequestV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.OperationOutcomeResponseV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import java.awt.Desktop
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class RemoteUiBuilderDesign(
  val designId: String,
  val title: String,
  val catalogSystemId: String,
  val updatedAtEpochMillis: Long?,
)

/** An approved server connection that can browse designs and open any selected one. */
class RemoteUiBuilderConnection
private constructor(
  val serverOrigin: URI,
  internal val actorId: String,
  internal val token: String,
  internal val serverHttp: RemoteServerHttp,
) {
  val mcpEndpoint: URI = serverOrigin.resolve("/mcp")

  suspend fun listDesigns(): List<RemoteUiBuilderDesign> {
    val client = protocolClient("intellij-browser-${UUID.randomUUID()}")
    val listed = mutableListOf<RemoteUiBuilderDesign>()
    var cursor: String? = null
    do {
      val page =
        when (val result = client.execute(ListDesignsRequestV1(cursor = cursor, limit = 200))) {
          is UiBuilderHttpResult.Response ->
            result.response as? DesignsResponseV1
              ?: error("unexpected response while listing remote designs")
          is UiBuilderHttpResult.ServiceError -> error(result.error.message)
          is UiBuilderHttpResult.SnapshotRequired -> error(result.error.message)
        }
      listed +=
        page.designs.map {
          RemoteUiBuilderDesign(
            designId = it.designId,
            title = it.title,
            catalogSystemId = it.catalogPin.systemId,
            updatedAtEpochMillis = it.updatedAtEpochMillis,
          )
        }
      cursor = page.nextCursor
    } while (cursor != null)
    return listed.sortedByDescending { it.updatedAtEpochMillis ?: 0L }
  }

  fun openDesign(design: RemoteUiBuilderDesign): RemoteUiBuilderSession =
    RemoteUiBuilderSession(this, design.designId, design.catalogSystemId)

  internal fun protocolClient(clientId: String): UiBuilderProtocolHttpClient =
    UiBuilderProtocolHttpClient(
      actorId = actorId,
      endpoint = "/api/ui-builder/v1/requests",
      transport = UiBuilderHttpTransport(serverHttp::protocolPost),
      requestIds = MonotonicUiBuilderRequestIds(clientId),
    )

  companion object {
    suspend fun connect(server: String): RemoteUiBuilderConnection {
      val origin = validatedServerOrigin(server)
      val serverHttp = RemoteServerHttp(origin)
      val token = serverHttp.authorize()
      serverHttp.token = token
      return RemoteUiBuilderConnection(origin, serverHttp.identity(), token, serverHttp)
    }
  }
}

/** A collaborative IntelliJ session backed by a compose-preview server. */
class RemoteUiBuilderSession
internal constructor(
  val connection: RemoteUiBuilderConnection,
  val designId: String,
  catalogSystemId: String,
) : UiBuilderSession {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val submissions = Channel<EditorSubmission>(Channel.UNLIMITED)
  private val refreshMutex = Mutex()
  private val mutableSnapshot = MutableStateFlow<SnapshotResponseV1?>(null)
  override val snapshot = mutableSnapshot.asStateFlow()
  private val mutableFailure = MutableStateFlow<String?>(null)
  override val failure = mutableFailure.asStateFlow()
  private val protocol: UiBuilderProtocolHttpClient
  private var updates: UiBuilderProtocolUpdateClient? = null

  override var catalog: CapabilityCatalog =
    OfflineCatalog.forSystem(catalogSystemId).capabilityCatalog()
    private set

  override val actorId: String = connection.actorId
  override val clientId: String = "intellij-${UUID.randomUUID()}"
  override val operationIdPrefix: String = clientId
  override val nativeRenderAvailable: Boolean = false
  override val commentsAvailable: Boolean = true
  private val commentClient = RemoteCommentClient(connection, designId)
  override val comments = commentClient.board
  override val commentStatus = commentClient.status

  init {
    require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}").matches(designId)) {
      "remote design id must be path-safe"
    }
    protocol = connection.protocolClient(clientId)
    scope.launch {
      try {
        refresh()
        connectUpdates()
        consumeSubmissions()
      } catch (failure: Exception) {
        mutableFailure.value = failure.message ?: "remote UI Builder connection failed"
      }
    }
  }

  override fun submit(submission: EditorSubmission) {
    submissions.trySend(submission)
  }

  override fun postComment(draft: DesignCommentDraft) {
    commentClient.post(draft)
  }

  override fun resolveCommentThread(threadId: String, resolved: Boolean) {
    commentClient.resolve(threadId, resolved)
  }

  private suspend fun consumeSubmissions() {
    for (submission in submissions) {
      val baseRevision =
        mutableSnapshot.value?.snapshot?.state?.document?.revision?.toInt() ?: continue
      when (
        val result =
          protocol.execute(
            ApplyOperationRequestV1(
              submission.toProtocolSubmission(actorId, clientId, baseRevision)
            )
          )
      ) {
        is UiBuilderHttpResult.Response ->
          if (result.response is OperationOutcomeResponseV1) refresh()
          else mutableFailure.value = "unexpected response while saving the remote design"
        is UiBuilderHttpResult.ServiceError -> mutableFailure.value = result.error.message
        is UiBuilderHttpResult.SnapshotRequired -> refresh()
      }
    }
  }

  private suspend fun refresh() = refreshMutex.withLock {
    when (val result = protocol.execute(OpenDesignRequestV1(designId))) {
      is UiBuilderHttpResult.Response -> {
        val opened =
          result.response as? SnapshotResponseV1
            ?: error("unexpected response while opening the remote design")
        catalog =
          CapabilityCatalogParser.parse(
            connection.serverHttp.json.encodeToString(opened.snapshot.catalog)
          )
        mutableSnapshot.value = opened
        mutableFailure.value = null
      }
      is UiBuilderHttpResult.ServiceError -> error(result.error.message)
      is UiBuilderHttpResult.SnapshotRequired -> error(result.error.message)
    }
  }

  private fun connectUpdates() {
    val sequence = mutableSnapshot.value?.snapshot?.state?.lastSequence
    updates =
      UiBuilderProtocolUpdateClient(
          designId = designId,
          endpoint = "/api/ui-builder/v1/designs/{designId}/updates",
          initialAfterSequence = sequence,
          transport =
            JavaUiBuilderWebSocketTransport(
              connection.serverOrigin,
              connection.token,
              connection.serverHttp.http,
            ),
        ) { update ->
          when (update) {
            is UiBuilderClientUpdate.Presence,
            is UiBuilderClientUpdate.Outcome -> Unit
            is UiBuilderClientUpdate.Snapshot,
            is UiBuilderClientUpdate.Delta -> scope.launch { runCatching { refresh() } }
            is UiBuilderClientUpdate.SnapshotRequired ->
              scope.launch {
                runCatching { refresh() }
                updates?.reconnect()
              }
          }
        }
        .also(UiBuilderProtocolUpdateClient::connect)
  }

  override suspend fun renderNative(
    document: UiBuilderDocument,
    hostShape: WearWidgetHostShape,
  ): UiBuilderNativeRender? = null

  override fun close() {
    commentClient.close()
    updates?.close()
    submissions.close()
    scope.cancel()
  }
}

internal class RemoteServerHttp(val origin: URI) {
  val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
  val json: Json = Json { ignoreUnknownKeys = true }
  var token: String? = null

  suspend fun authorize(): String {
    val opened =
      request(
        target = origin.resolve("/agent-access/request"),
        method = "POST",
        body =
          """{"label":"Compose UI Builder IntelliJ","scope":"live","capabilities":["ui-builder-read","ui-builder-write","ui-builder-export"]}""",
        authenticated = false,
      )
    require(opened.statusCode() in 200..299) {
      "server cannot start UI Builder authentication (HTTP ${opened.statusCode()})"
    }
    val response = json.parseToJsonElement(opened.body()).jsonObject
    val approvalUrl = response.requiredString("approveUrl")
    if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(approvalUrl))
    val requestId = response.requiredString("requestId")
    val deviceSecret = response.requiredString("deviceSecret")
    var retryAfterSeconds = response.requiredLong("pollIntervalSeconds")
    val deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos()
    while (System.nanoTime() < deadline) {
      delay(retryAfterSeconds.coerceAtLeast(1) * 1_000)
      val poll =
        request(
          target = sameOriginTarget(origin, URI(response.requiredString("pollUrl"))),
          method = "POST",
          body = json.encodeToString(RemoteDevicePollRequest(requestId, deviceSecret)),
          authenticated = false,
        )
      require(poll.statusCode() in 200..299) { "server stopped the authentication request" }
      val answer = json.parseToJsonElement(poll.body()).jsonObject
      when (answer.requiredString("status")) {
        "approved" -> return answer.requiredString("token")
        "pending" -> Unit
        else -> error("server declined UI Builder authentication")
      }
      retryAfterSeconds =
        answer["retryAfterSeconds"]?.jsonPrimitive?.longOrNull ?: retryAfterSeconds
    }
    error("UI Builder authentication timed out")
  }

  suspend fun identity(): String {
    val response = request(origin.resolve("/api/ui-builder/v1/identity"), "GET", "")
    require(response.statusCode() == 200) { "server could not resolve the authenticated actor" }
    return json.decodeFromString(IdentityPayload.serializer(), response.body()).actorId.takeIf {
      it.isNotBlank()
    } ?: error("server returned an empty actor identity")
  }

  suspend fun protocolPost(request: UiBuilderHttpRequest): UiBuilderHttpResponse {
    val response = request(origin.resolve(request.endpoint), "POST", request.body)
    return UiBuilderHttpResponse(response.statusCode(), response.body())
  }

  suspend fun commentRequest(target: String, method: String, body: String): RemoteCommentResponse {
    val response = request(origin.resolve(target), method, body)
    return RemoteCommentResponse(response.statusCode(), response.body())
  }

  private suspend fun request(
    target: URI,
    method: String,
    body: String,
    authenticated: Boolean = true,
  ): HttpResponse<String> =
    withContext(Dispatchers.IO) {
      val builder =
        HttpRequest.newBuilder(target)
          .timeout(Duration.ofMinutes(2))
          .header("Accept", "application/json")
          .header("Content-Type", "application/json")
      if (authenticated) {
        val credential = requireNotNull(token) { "UI Builder authentication was not completed" }
        builder.header("Authorization", "Bearer $credential")
        builder.header("X-Compose-Preview-Token", credential)
      }
      val publisher =
        if (method == "GET") HttpRequest.BodyPublishers.noBody()
        else HttpRequest.BodyPublishers.ofString(body)
      http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
    }

  private fun JsonObject.requiredString(name: String): String =
    get(name)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
      ?: error("authentication response has no $name")

  private fun JsonObject.requiredLong(name: String): Long =
    get(name)?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
      ?: error("authentication response has no positive $name")
}

private class JavaUiBuilderWebSocketTransport(
  private val origin: URI,
  private val token: String,
  private val http: HttpClient,
) : UiBuilderWebSocketTransport {
  override fun open(
    request: UiBuilderWebSocketRequest,
    onTextMessage: (String) -> Unit,
  ): UiBuilderClientConnection {
    val path = request.endpoint.replace("{designId}", request.designId)
    val query = request.afterSequence?.let { "?afterSequence=$it" }.orEmpty()
    val httpUri = origin.resolve(path + query)
    val webSocketUri =
      URI(
        if (httpUri.scheme == "https") "wss" else "ws",
        httpUri.userInfo,
        httpUri.host,
        httpUri.port,
        httpUri.path,
        httpUri.query,
        null,
      )
    val socket =
      http
        .newWebSocketBuilder()
        .header("Authorization", "Bearer $token")
        .header("X-Compose-Preview-Token", token)
        .buildAsync(webSocketUri, TextWebSocketListener(onTextMessage))
        .join()
    return UiBuilderClientConnection { socket.sendClose(WebSocket.NORMAL_CLOSURE, "closed") }
  }
}

private class TextWebSocketListener(private val onTextMessage: (String) -> Unit) :
  WebSocket.Listener {
  private val text = StringBuilder()

  override fun onOpen(webSocket: WebSocket) {
    webSocket.request(1)
  }

  override fun onText(
    webSocket: WebSocket,
    data: CharSequence,
    last: Boolean,
  ): CompletionStage<*>? {
    text.append(data)
    if (last) {
      onTextMessage(text.toString())
      text.clear()
    }
    webSocket.request(1)
    return null
  }
}

@Serializable private data class IdentityPayload(val actorId: String = "")

@Serializable
private data class RemoteDevicePollRequest(
  val requestId: String,
  val deviceSecret: String,
  val waitSeconds: Int = 30,
)
