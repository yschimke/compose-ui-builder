@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder.editor

import kotlin.coroutines.resume
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.suspendCancellableCoroutine

/** The clipboard's text, or an empty string when the browser has none or refuses. Never rejects. */
@JsFun(
  """() => {
    if (!navigator.clipboard || !navigator.clipboard.readText) return Promise.resolve('');
    return navigator.clipboard.readText().then((text) => text || '', () => '');
  }"""
)
private external fun readClipboardTextPromise(): Promise<JsString>

internal actual suspend fun readSystemClipboardText(): String? =
  suspendCancellableCoroutine { continuation ->
    readClipboardTextPromise()
      .then { value ->
        if (continuation.isActive) continuation.resume(value.toString().ifEmpty { null })
        null
      }
      .catch {
        if (continuation.isActive) continuation.resume(null)
        null
      }
  }

internal actual val readsSystemClipboardOnEveryPaste: Boolean = false
