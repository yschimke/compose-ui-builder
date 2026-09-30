@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.local.LocalDesignStore
import ee.schimke.composeai.uibuilder.local.publishRefusal
import ee.schimke.composeai.uibuilder.local.publishableDocument
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json

/**
 * The browser half of keeping designs in this browser (#342): saving one as a file, and publishing
 * one to the server as a new design once the server will take it.
 */

/**
 * Saves the stored record for [designId] as `<designId>.ui-builder.json`. Null on success, else why
 * there was nothing to save.
 */
internal fun downloadBrowserDesign(designId: String): String? {
  val text =
    LocalDesignStore(BrowserLocalDesignStorage()).storedText(designId)
      ?: return "this browser no longer holds $designId"
  downloadText("$designId.ui-builder.json", text)
  return null
}

/**
 * Creates [document] on this page's server under its own id, through the create-only route. Null on
 * success, else the server's refusal in a sentence.
 */
internal suspend fun publishDesignToServer(document: DesignDocumentV1): String? {
  val publishable = publishableDocument(document)
  val url = sameOriginRequestUrl("/api/ui-builder/v1/designs/${encodeUriComponent(document.id)}")
  val answer =
    try {
      awaitStatusText(
        putCreatePromise(url, Json.encodeToString(DesignDocumentV1.serializer(), publishable))
      )
    } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
      throw cancelled
    } catch (failure: Throwable) {
      return "The server could not be reached: ${failure.message ?: failure}"
    }
  val status = answer.substringBefore('\n').toIntOrNull() ?: 0
  return publishRefusal(document.id, status, answer.substringAfter('\n', "").trim())
}

private suspend fun awaitStatusText(promise: Promise<JsString>): String =
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

/** `status + '\n' + body`, since the refusal's text is the part worth showing. */
@JsFun(
  """(url, body) => fetch(url, {
      method: 'PUT',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'If-None-Match': '*' },
      body,
    }).then((response) => response.text().then((text) => response.status + '\n' + text))"""
)
private external fun putCreatePromise(url: String, body: String): Promise<JsString>

@JsFun(
  """(filename, text) => {
    const objectUrl = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
    const anchor = document.createElement('a');
    anchor.href = objectUrl;
    anchor.download = filename;
    anchor.rel = 'noopener';
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
  }"""
)
private external fun downloadText(filename: String, text: String)

/** Opens a design this browser keeps, by a navigation: the home screen has no editor to reuse. */
@JsFun(
  """(designId) => {
    const current = new URL(globalThis.location.href);
    const next = new URL('/ui-builder/' + encodeURIComponent(designId), current.origin);
    ['token', 'actor', 'clientId', 'displayName', 'color', 'endpoint', 'updatesEndpoint']
      .forEach((name) => {
        const value = current.searchParams.get(name);
        if (value !== null) next.searchParams.set(name, value);
      });
    next.searchParams.set('storage', 'local');
    globalThis.location.assign(next.toString());
  }"""
)
internal external fun navigateToBrowserDesign(designId: String)
