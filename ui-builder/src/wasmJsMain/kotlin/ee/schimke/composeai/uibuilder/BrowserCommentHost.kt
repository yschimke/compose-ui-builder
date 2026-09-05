@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.client.browserUiBuilderWebSocketUrl
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json

/**
 * The browser half of the comments panel: read the discussion, post into it, and watch it change.
 *
 * Everything unportable lives here, the way [BrowserReferenceHost] holds the file picker and the
 * paste listener: `:ui-builder`'s editor is common Compose with no `fetch` and no `WebSocket`, and
 * it takes a [DesignCommentBoard] and asks no further questions.
 *
 * ### Why there is a socket and not a poll
 *
 * The point of comments on a design is that two people — or a person and an agent — are not looking
 * at the page at the same moment. A panel that refreshed every few seconds would be a panel that is
 * usually wrong and always asking; [watch] holds one socket and the host pushes. The server's
 * comment feed is the same one an agent waits on through `ui_builder_await_comments`, so a reply
 * typed in the browser reaches a waiting agent, and an agent's answer appears in the panel, without
 * either of them polling for the other.
 */
internal class BrowserCommentHost(private val designId: String) {
  /** What has been said, or null when the host would not say — a refusal, a dropped connection. */
  suspend fun load(): DesignCommentBoard? {
    val response = commentRequest("GET", commentsPath(), null)
    if (response.status != 200) return null
    return response.body.toBoardOrNull()
  }

  /**
   * Say something. Returns null on success, or a sentence for the panel.
   *
   * The board that comes back is deliberately dropped: the socket delivers it to every watcher
   * including this one, and taking the reply here as well would show a comment twice on the way in
   * and let the panel drift from what the server told everybody else.
   */
  suspend fun post(draft: DesignCommentDraft, displayName: String): String? {
    val response =
      commentRequest(
        "POST",
        commentsPath(),
        commentJson.encodeToString(
          CommentPostWire.serializer(),
          CommentPostWire(
            threadId = draft.threadId,
            anchor = draft.anchor?.toWire(),
            body = draft.body,
            displayName = displayName,
          ),
        ),
      )
    return response.refusalOrNull("posting that comment")
  }

  /** Close a thread, or reopen it. Returns null on success, or a sentence for the panel. */
  suspend fun resolve(threadId: String, resolved: Boolean): String? {
    val response =
      commentRequest(
        "POST",
        "${commentsPath()}/$threadId/resolution",
        commentJson.encodeToString(
          CommentResolutionWire.serializer(),
          CommentResolutionWire(resolved),
        ),
      )
    return response.refusalOrNull("resolving that thread")
  }

  /**
   * Every change to this design's discussion, until the returned handle is closed.
   *
   * The socket sends the current board on connect, so a caller does not fetch and subscribe and
   * then reconcile the two — which is the shape that loses a comment posted in between.
   */
  fun watch(onBoard: (DesignCommentBoard) -> Unit, onDropped: () -> Unit): CommentWatch {
    val url =
      browserUiBuilderWebSocketUrl(
        endpoint = "/api/ui-builder/v1/designs/{designId}/comments/updates",
        designId = designId,
        afterSequence = "",
        hasAfterSequence = false,
      )
    val socket =
      openCommentSocket(
        url = url,
        onTextMessage = { text -> text.toBoardOrNull()?.let(onBoard) },
        onDisconnected = onDropped,
      )
    return CommentWatch { closeCommentSocket(socket) }
  }

  private fun commentsPath() = "/api/ui-builder/v1/designs/$designId/comments"

  private suspend fun commentRequest(
    method: String,
    url: String,
    body: String?,
  ): CommentHttpResponse {
    val encoded =
      try {
        awaitCommentString(
          commentFetch(method, sameOriginRequestUrl(url), body ?: "", body != null)
        )
      } catch (_: Exception) {
        return CommentHttpResponse(0, "")
      }
    return try {
      commentJson.decodeFromString(CommentHttpResponse.serializer(), encoded)
    } catch (_: Exception) {
      CommentHttpResponse(0, "")
    }
  }

  private fun String.toBoardOrNull(): DesignCommentBoard? =
    try {
      commentJson.decodeFromString(CommentBoardWire.serializer(), this).toBoard()
    } catch (_: Exception) {
      null
    }
}

/** An open comment feed. Closed when the design changes or the editor goes away. */
internal fun interface CommentWatch {
  fun close()
}

/** Why the host would not do it, in a sentence, or null when it did. */
private fun CommentHttpResponse.refusalOrNull(what: String): String? {
  if (status in 200..299) return null
  return try {
    commentJson.decodeFromString(CommentErrorWire.serializer(), body).message
  } catch (_: Exception) {
    if (status == 0) "The host could not be reached while $what."
    else "The host answered $status while $what."
  }
}

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

/**
 * Tolerant on the way in, for the reason the reference payload is: a host that learns a new comment
 * field must not blank somebody's panel.
 */
private val commentJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
  explicitNulls = false
}

@kotlinx.serialization.Serializable
private data class CommentHttpResponse(val status: Int = 0, val body: String = "")

@kotlinx.serialization.Serializable private data class CommentErrorWire(val message: String = "")

@kotlinx.serialization.Serializable
private data class CommentBoardWire(
  val sequence: Long = 0,
  val threads: List<CommentThreadWire> = emptyList(),
)

@kotlinx.serialization.Serializable
private data class CommentThreadWire(
  val id: String = "",
  val anchor: CommentAnchorWire? = null,
  val resolved: Boolean = false,
  val resolvedBy: String? = null,
  val updatedAtEpochMillis: Long = 0,
  val comments: List<CommentWire> = emptyList(),
)

@kotlinx.serialization.Serializable
private data class CommentWire(
  val id: String = "",
  val authorId: String = "",
  val displayName: String = "",
  val authorKind: String = "human",
  val body: String = "",
  val createdAtEpochMillis: Long = 0,
)

@kotlinx.serialization.Serializable
private data class CommentAnchorWire(
  val markId: String? = null,
  val nodeId: String? = null,
  val x: Float? = null,
  val y: Float? = null,
)

@kotlinx.serialization.Serializable
private data class CommentPostWire(
  val threadId: String? = null,
  val anchor: CommentAnchorWire? = null,
  val body: String,
  val displayName: String = "",
)

@kotlinx.serialization.Serializable private data class CommentResolutionWire(val resolved: Boolean)

private suspend fun awaitCommentString(promise: Promise<JsString>): String =
  suspendCancellableCoroutine { continuation ->
    promise
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
 * One `fetch` with a method of our choosing, cookie-bearing like every other call this app makes.
 *
 * The same-origin check and the page token used to live inline here, and this was the only helper
 * of the three that had them. They are [sameOriginRequestUrl] now, applied by the caller, so the
 * reference and asset lanes get the same treatment instead of quietly going without.
 */
@JsFun(
  """(method, url, body, hasBody) => {
    return fetch(url, {
      method,
      credentials: 'same-origin',
      headers: hasBody
        ? { 'Content-Type': 'application/json', 'Accept': 'application/json' }
        : { 'Accept': 'application/json' },
      body: hasBody ? body : undefined,
    }).then((response) => response.text().then((text) => JSON.stringify({
      status: response.status,
      body: text,
    })));
  }"""
)
private external fun commentFetch(
  method: String,
  url: String,
  body: String,
  hasBody: Boolean,
): Promise<JsString>

@JsFun(
  """(url, onTextMessage, onDisconnected) => {
    const socket = new WebSocket(url);
    socket.onclose = () => { onDisconnected(); };
    socket.onerror = () => { onDisconnected(); };
    socket.onmessage = (event) => { onTextMessage(String(event.data)); };
    return socket;
  }"""
)
private external fun openCommentSocket(
  url: String,
  onTextMessage: (String) -> Unit,
  onDisconnected: () -> Unit,
): JsAny

@JsFun("(socket) => socket.close()") private external fun closeCommentSocket(socket: JsAny)
