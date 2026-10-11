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
 * Same-origin `GET` for icon outlines, with ordinary cache semantics. Pinned (`v=…`) URLs are
 * answered `immutable`, so they're served from cache anyway; `force-cache` would only make the
 * unpinned bootstrap request skip its `If-None-Match` and keep a stale pin after a release.
 * `same-origin` credentials carry the session.
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
