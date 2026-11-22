package ee.schimke.composeai.uibuilder.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.takeFrom
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.utils.io.ByteReadChannel
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Forwards a request to the legacy UI-builder container unchanged: same method, path, query, body
 * and headers, **including `Host`**. The legacy server decides which surface to serve from the host
 * name (`ui.coo.ee` is the editor's root), and its GitHub sign-in reads its cookies and
 * `X-Forwarded-*` as Caddy sent them, so this hop must be transparent.
 */
internal class UpstreamProxy(private val upstream: Url, private val client: HttpClient) {

  suspend fun forward(call: ApplicationCall) {
    val method = call.request.httpMethod
    val target = upstream.toString() + call.request.uri
    val requestHeaders = call.request.headers
    val body: ByteReadChannel? = if (method.carriesBody()) call.receiveChannel() else null
    try {
      client
        .prepareRequest(target) {
          this.method = method
          copyForwardableHeaders(requestHeaders) { name, values -> headers.appendAll(name, values) }
          if (requestHeaders[HttpHeaders.XForwardedProto] == null) {
            header(HttpHeaders.XForwardedProto, call.request.origin.scheme)
          }
          if (body != null) {
            setBody(
              object : OutgoingContent.ReadChannelContent() {
                override val contentType: ContentType? =
                  requestHeaders[HttpHeaders.ContentType]?.let(ContentType::parse)
                override val contentLength: Long? =
                  requestHeaders[HttpHeaders.ContentLength]?.toLongOrNull()

                override fun readFrom(): ByteReadChannel = body
              },
            )
          }
        }
        .execute { response ->
          val responseHeaders = Headers.build {
            copyForwardableHeaders(response.headers) { name, values -> appendAll(name, values) }
          }
          val contentType = response.headers[HttpHeaders.ContentType]?.let(ContentType::parse)
          if (method == HttpMethod.Head || !response.status.allowsBody()) {
            // A HEAD answer's Content-Length is the size of the resource, not of this (empty)
            // body, so it is carried across rather than dropped with the other body headers.
            val headLength =
              if (method == HttpMethod.Head) {
                response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
              } else null
            call.respond(
              object : OutgoingContent.NoContent() {
                override val status: HttpStatusCode = response.status
                override val headers: Headers = responseHeaders
                override val contentType: ContentType? = contentType
                override val contentLength: Long? = headLength
              },
            )
          } else {
            val length = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            val channel = response.bodyAsChannel()
            call.respond(
              object : OutgoingContent.ReadChannelContent() {
                override val status: HttpStatusCode = response.status
                override val headers: Headers = responseHeaders
                override val contentType: ContentType? = contentType
                override val contentLength: Long? = length

                override fun readFrom(): ByteReadChannel = channel
              },
            )
          }
        }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      call.respondText(
        "ui-builder upstream unavailable: ${e::class.simpleName}",
        status = HttpStatusCode.BadGateway,
      )
    }
  }

  /** Bridges a WebSocket to the same path upstream, frame for frame, until either side closes. */
  suspend fun forwardWebSocket(session: DefaultWebSocketServerSession) {
    val call = session.call
    val requestHeaders = call.request.headers
    try {
      client.webSocket(
        request = {
          url.takeFrom(upstream.toString() + call.request.uri)
          url.protocol =
            if (upstream.protocol == URLProtocol.HTTPS) URLProtocol.WSS else URLProtocol.WS
          copyForwardableHeaders(requestHeaders, webSocket = true) { name, values ->
            headers.appendAll(name, values)
          }
        },
      ) {
        val upstreamSession = this
        coroutineScope {
          val toUpstream = launch {
            for (frame in session.incoming) upstreamSession.outgoing.send(frame.copy())
            upstreamSession.close(CloseReason(CloseReason.Codes.NORMAL, ""))
          }
          for (frame in upstreamSession.incoming) session.outgoing.send(frame.copy())
          toUpstream.cancel()
          val reason = upstreamSession.closeReason.await()
          session.close(reason ?: CloseReason(CloseReason.Codes.NORMAL, ""))
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      session.close(
        CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "ui-builder upstream unavailable"),
      )
    }
  }

  private fun HttpMethod.carriesBody(): Boolean =
    this != HttpMethod.Get && this != HttpMethod.Head && this != HttpMethod.Options

  private fun HttpStatusCode.allowsBody(): Boolean =
    value >= 200 && this != HttpStatusCode.NoContent && this != HttpStatusCode.NotModified

  internal companion object {
    /** RFC 9110 §7.6.1 connection-specific headers, which describe one hop and never cross it. */
    private val HOP_BY_HOP =
      setOf(
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "proxy-connection",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
      )

    /**
     * Calls [add] for each header that crosses this hop. Content-Type and Content-Length travel as
     * the body's own properties instead (both engines refuse them as plain headers). A WebSocket
     * handshake also drops its `Sec-WebSocket-*` headers: the client engine writes its own.
     */
    internal fun copyForwardableHeaders(
      from: Headers,
      webSocket: Boolean = false,
      add: (String, List<String>) -> Unit,
    ) {
      from.forEach { name, values ->
        val lower = name.lowercase()
        val skip =
          lower in HOP_BY_HOP ||
            lower == "content-length" ||
            lower == "content-type" ||
            (webSocket && lower.startsWith("sec-websocket-"))
        if (!skip) add(name, values)
      }
    }
  }
}
