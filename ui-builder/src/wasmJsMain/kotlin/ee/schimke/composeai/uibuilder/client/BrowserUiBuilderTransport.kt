@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder.client

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.serialization.Serializable

/** Same-origin browser `fetch` transport. Authentication remains owned by browser cookies. */
class BrowserUiBuilderHttpTransport : UiBuilderHttpTransport {
  override suspend fun post(request: UiBuilderHttpRequest): UiBuilderHttpResponse {
    val encoded =
      awaitBrowserPromise(
        fetchUiBuilder(
          endpoint = request.endpoint,
          contentType = request.contentType,
          body = request.body,
        )
      )
    val response = protocolJson.decodeFromString(BrowserHttpResponse.serializer(), encoded)
    return UiBuilderHttpResponse(response.statusCode, response.body)
  }
}

/** Browser `WebSocket` transport using the server's design path and exclusive query cursor. */
class BrowserUiBuilderWebSocketTransport : UiBuilderWebSocketTransport {
  override fun open(
    request: UiBuilderWebSocketRequest,
    onTextMessage: (String) -> Unit,
  ): UiBuilderClientConnection {
    val url =
      browserUiBuilderWebSocketUrl(
        endpoint = request.endpoint,
        designId = request.designId,
        afterSequence = request.afterSequence?.toString().orEmpty(),
        hasAfterSequence = request.afterSequence != null,
      )
    val socket = openUiBuilderWebSocket(url, onTextMessage)
    return UiBuilderClientConnection { closeUiBuilderWebSocket(socket) }
  }
}

@Serializable
private data class BrowserHttpResponse(
  val statusCode: Int,
  val body: String,
)

private suspend fun awaitBrowserPromise(promise: Promise<JsString>): String =
  suspendCoroutine { continuation ->
    promise
      .then { value ->
        continuation.resume(value.toString())
        null
      }
      .catch {
        continuation.resumeWithException(
          UiBuilderProtocolException(browserUiBuilderTransportFailureMessage())
        )
        null
      }
  }

private fun fetchUiBuilder(
  endpoint: String,
  contentType: String,
  body: String,
): Promise<JsString> =
  js(
    """(function () {
      var url = new URL(endpoint, window.location.href);
      if (url.origin !== window.location.origin) {
        throw new Error('UI-builder HTTP endpoint must be same-origin');
      }
      var pageToken = new URL(window.location.href).searchParams.get('token');
      if (pageToken && !url.searchParams.has('token')) url.searchParams.set('token', pageToken);
      return fetch(url.toString(), {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': contentType, 'Accept': 'application/json' },
      body: body
    }).then(function (response) {
      return response.text().then(function (responseBody) {
        return JSON.stringify({ statusCode: response.status, body: responseBody });
      });
      });
    })()"""
  )

internal fun browserUiBuilderTransportFailureMessage(): String =
  "UI-builder browser transport failed"

internal fun browserUiBuilderWebSocketUrl(
  endpoint: String,
  designId: String,
  afterSequence: String,
  hasAfterSequence: Boolean,
): String =
  js(
    """(function () {
      if (endpoint.indexOf('{designId}') === -1) {
        throw new Error('UI-builder WebSocket endpoint must contain {designId}');
      }
      var resolvedEndpoint = endpoint.replace('{designId}', encodeURIComponent(designId));
      var url = new URL(resolvedEndpoint, window.location.href);
      if (url.origin !== window.location.origin) {
        throw new Error('UI-builder WebSocket endpoint must be same-origin');
      }
      var pageToken = new URL(window.location.href).searchParams.get('token');
      if (pageToken && !url.searchParams.has('token')) url.searchParams.set('token', pageToken);
      if (url.protocol === 'http:') url.protocol = 'ws:';
      if (url.protocol === 'https:') url.protocol = 'wss:';
      if (hasAfterSequence) url.searchParams.set('afterSequence', afterSequence);
      else url.searchParams.delete('afterSequence');
      return url.toString();
    })()"""
  )

private fun openUiBuilderWebSocket(
  url: String,
  onTextMessage: (String) -> Unit,
): JsAny =
  js(
    """(function () {
      var socket = new WebSocket(url);
      socket.onmessage = function (event) { onTextMessage(String(event.data)); };
      return socket;
    })()"""
  )

private fun closeUiBuilderWebSocket(socket: JsAny): Unit = js("socket.close()")
