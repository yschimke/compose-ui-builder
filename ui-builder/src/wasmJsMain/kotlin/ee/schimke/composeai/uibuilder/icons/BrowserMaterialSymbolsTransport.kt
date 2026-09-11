@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder.icons

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Same-origin `GET` for icon outlines.
 *
 * Ordinary cache semantics, deliberately, though caching is the whole point of this route. A pinned
 * URL (`v=…`) is answered `immutable`, so the browser serves it from its own cache with no round
 * trip regardless — `force-cache` buys nothing there. What `force-cache` does buy is a stale hit on
 * the *unpinned* bootstrap request, returning a cached name list without ever sending its
 * `If-None-Match`: the client would then keep an old pin after a release, and every versioned URL
 * it built from that pin would be wrong too. So the validator is allowed to do its job here, and a
 * 304 costs a header exchange rather than the list.
 *
 * The editor is served by the host that serves these, so `same-origin` credentials carry whatever
 * the session already has.
 */
class BrowserMaterialSymbolsTransport : MaterialSymbolsTransport {
  override suspend fun get(url: String): MaterialSymbolsHttpResponse {
    val encoded = awaitIconPromise(fetchIcons(url))
    val response = JSON.decodeFromString(BrowserIconResponse.serializer(), encoded)
    return MaterialSymbolsHttpResponse(response.statusCode, response.body)
  }

  private companion object {
    val JSON = Json { ignoreUnknownKeys = true }
  }
}

@Serializable private data class BrowserIconResponse(val statusCode: Int, val body: String)

private fun fetchIcons(url: String): Promise<JsString> =
  js(
    """fetch(url, {
      method: 'GET',
      credentials: 'same-origin',
      headers: { 'Accept': 'application/json' },
      cache: 'default'
    }).then(function (response) {
      return response.text().then(function (responseBody) {
        return JSON.stringify({ statusCode: response.status, body: responseBody });
      });
    })"""
  )

private suspend fun awaitIconPromise(promise: Promise<JsString>): String =
  suspendCancellableCoroutine { continuation ->
    promise
      .then<JsString> { value ->
        continuation.resume(value.toString())
        value
      }
      .catch { error ->
        continuation.resumeWithException(IllegalStateException("icon fetch failed: $error"))
        error
      }
  }
