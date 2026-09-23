package ee.schimke.composeai.uibuilder.host

import ee.schimke.composeai.uibuilder.DesignComment
import ee.schimke.composeai.uibuilder.DesignCommentAnchor
import ee.schimke.composeai.uibuilder.DesignCommentAuthorKind
import ee.schimke.composeai.uibuilder.DesignCommentBoard
import ee.schimke.composeai.uibuilder.DesignCommentDraft
import ee.schimke.composeai.uibuilder.DesignCommentThread
import java.net.URI
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The JVM counterpart to the browser's comment host for a design opened through a preview server.
 */
internal class RemoteCommentClient(
  private val connection: RemoteUiBuilderConnection,
  private val designId: String,
) : AutoCloseable {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var socket: WebSocket? = null
  private val mutableBoard = MutableStateFlow(DesignCommentBoard())
  val board = mutableBoard.asStateFlow()
  private val mutableStatus = MutableStateFlow<String?>(null)
  val status = mutableStatus.asStateFlow()

  init {
    scope.launch {
      load()
      connect()
    }
  }

  fun post(draft: DesignCommentDraft) {
    scope.launch {
      val response =
        connection.serverHttp.commentRequest(
          target = commentsPath(),
          method = "POST",
          body =
            commentJson.encodeToString(
              CommentPostWire.serializer(),
              CommentPostWire(
                threadId = draft.threadId,
                anchor = draft.anchor?.toWire(),
                body = draft.body,
                displayName = connection.actorId,
              ),
            ),
        )
      mutableStatus.value = response.refusalOrNull("posting that comment")
    }
  }

  fun resolve(threadId: String, resolved: Boolean) {
    scope.launch {
      val response =
        connection.serverHttp.commentRequest(
          target = "${commentsPath()}/$threadId/resolution",
          method = "POST",
          body =
            commentJson.encodeToString(
              CommentResolutionWire.serializer(),
              CommentResolutionWire(resolved),
            ),
        )
      mutableStatus.value = response.refusalOrNull("resolving that thread")
    }
  }

  private suspend fun load() {
    val response = connection.serverHttp.commentRequest(commentsPath(), "GET", "")
    if (response.status in 200..299) {
      response.body.toBoardOrNull()?.let { mutableBoard.value = it }
      mutableStatus.value = null
    } else {
      mutableStatus.value = response.refusalOrNull("loading comments")
    }
  }

  private fun connect() {
    val httpUri = connection.serverOrigin.resolve("${commentsPath()}/updates")
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
    socket =
      connection.serverHttp.http
        .newWebSocketBuilder()
        .header("Authorization", "Bearer ${connection.token}")
        .header("X-Compose-Preview-Token", connection.token)
        .buildAsync(webSocketUri, CommentSocketListener(::acceptBoard, ::feedDropped))
        .join()
  }

  private fun acceptBoard(text: String) {
    text.toBoardOrNull()?.let {
      if (it.sequence >= mutableBoard.value.sequence) mutableBoard.value = it
      mutableStatus.value = null
    }
  }

  private fun feedDropped() {
    mutableStatus.value =
      "The comment feed dropped. Reopen the design to watch this discussion again."
  }

  private fun commentsPath() = "/api/ui-builder/v1/designs/$designId/comments"

  override fun close() {
    socket?.sendClose(WebSocket.NORMAL_CLOSURE, "closed")
    scope.cancel()
  }
}

private class CommentSocketListener(
  private val onText: (String) -> Unit,
  private val onDropped: () -> Unit,
) : WebSocket.Listener {
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
      onText(text.toString())
      text.clear()
    }
    webSocket.request(1)
    return null
  }

  override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
    onDropped()
    return null
  }

  override fun onError(webSocket: WebSocket, error: Throwable) {
    onDropped()
  }
}

private fun String.toBoardOrNull(): DesignCommentBoard? = runCatching {
  commentJson.decodeFromString(CommentBoardWire.serializer(), this).toBoard()
}
  .getOrNull()

private fun RemoteCommentResponse.refusalOrNull(what: String): String? =
  if (status in 200..299) null
  else
    runCatching { commentJson.decodeFromString(CommentErrorWire.serializer(), body).message }
      .getOrNull()
      ?.takeIf(String::isNotBlank) ?: "The preview server answered $status while $what."

private fun DesignCommentAnchor.toWire() =
  CommentAnchorWire(markId = markId, nodeId = nodeId, x = x, y = y)

private fun CommentBoardWire.toBoard() =
  DesignCommentBoard(
    sequence = sequence,
    threads =
      threads.map { thread ->
        DesignCommentThread(
          id = thread.id,
          anchor = thread.anchor?.takeIf { !it.isEmpty() }?.toAnchor(),
          resolved = thread.resolved,
          resolvedBy = thread.resolvedBy,
          updatedAtEpochMillis = thread.updatedAtEpochMillis,
          comments =
            thread.comments.map {
              DesignComment(
                id = it.id,
                authorId = it.authorId,
                displayName = it.displayName,
                kind = DesignCommentAuthorKind.ofWire(it.authorKind),
                body = it.body,
                createdAtEpochMillis = it.createdAtEpochMillis,
              )
            },
        )
      },
  )

private fun CommentAnchorWire.isEmpty() = markId == null && nodeId == null && x == null && y == null

private fun CommentAnchorWire.toAnchor() =
  DesignCommentAnchor(markId = markId, nodeId = nodeId, x = x, y = y)

internal data class RemoteCommentResponse(val status: Int, val body: String)

private val commentJson = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
}

@Serializable private data class CommentErrorWire(val message: String = "")

@Serializable
private data class CommentBoardWire(
  val sequence: Long = 0,
  val threads: List<CommentThreadWire> = emptyList(),
)

@Serializable
private data class CommentThreadWire(
  val id: String = "",
  val anchor: CommentAnchorWire? = null,
  val resolved: Boolean = false,
  val resolvedBy: String? = null,
  val comments: List<CommentWire> = emptyList(),
  val updatedAtEpochMillis: Long = 0,
)

@Serializable
private data class CommentWire(
  val id: String = "",
  val authorId: String = "",
  val displayName: String = "",
  val authorKind: String = "human",
  val body: String = "",
  val createdAtEpochMillis: Long = 0,
)

@Serializable
private data class CommentAnchorWire(
  val markId: String? = null,
  val nodeId: String? = null,
  val x: Float? = null,
  val y: Float? = null,
)

@Serializable
private data class CommentPostWire(
  val threadId: String? = null,
  val anchor: CommentAnchorWire? = null,
  val body: String,
  val displayName: String,
)

@Serializable private data class CommentResolutionWire(val resolved: Boolean)
