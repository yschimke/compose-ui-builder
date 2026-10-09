@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder.renderer.sdk

import ee.schimke.composeai.uibuilder.UiBuilderFontRegistry
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.Promise
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The fonts a catalog's own runtime draws a design's typefaces with, resolved the way the editor's
 * canvas resolves them.
 *
 * A family the runtime ships comes from `fonts/fonts.json` beside the runtime page, where a
 * catalog's renderer build copies compose-ui-builder's `assets/rc-fonts`. Anything else comes from
 * the host's Google Fonts route, `/api/fonts/google/{family}/{weight}`, which fetches the file once
 * and answers CORS-open: a runtime frame is sandboxed with an opaque origin, so it can make no
 * credentialed request and could not reach `fonts.gstatic.com` through the page's policy anyway. A
 * host without the route answers 404 and the family stays on the platform face. A glyph no font
 * here has goes the same way ([routeFallbackFontsThroughHost]).
 *
 * Provide it around the document with `ProvideUiBuilderFonts`, and read a theme host's families
 * with `rememberThemeRoleFamilies`.
 */
@OptIn(ExperimentalEncodingApi::class)
fun catalogRuntimeFontRegistry(): UiBuilderFontRegistry {
  routeFallbackFontsThroughHost()
  return UiBuilderFontRegistry(
    scope = MainScope(),
    readManifest = { fetchBytes("fonts/fonts.json").decodeToString() },
    readFont = { file -> fetchBytes("fonts/$file") },
    readRemoteFont = { family, weight ->
      fetchBytes("$GOOGLE_FONTS_ROUTE/${encodeComponent(family)}/$weight")
    },
  )
}

/** Where a host serves a Google Fonts family's TrueType file at one weight. */
const val GOOGLE_FONTS_ROUTE: String = "/api/fonts/google"

@OptIn(ExperimentalEncodingApi::class)
private suspend fun fetchBytes(url: String): ByteArray =
  suspendCancellableCoroutine { continuation ->
    fetchBase64(url)
      .then { value ->
        if (continuation.isActive) continuation.resume(Base64.decode(value.toString()))
        null
      }
      .catch { error ->
        if (continuation.isActive) {
          continuation.resumeWithException(IllegalStateException(error.toString()))
        }
        null
      }
  }

@JsFun(
  """(url) => fetch(url).then((response) => {
    if (!response.ok) throw new Error('HTTP ' + response.status + ' for ' + url);
    return response.arrayBuffer();
  }).then((buffer) => {
    const bytes = new Uint8Array(buffer);
    let binary = '';
    for (let i = 0; i < bytes.length; i += 0x8000) {
      binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    }
    return btoa(binary);
  })"""
)
private external fun fetchBase64(url: String): Promise<JsString>

@JsFun("(value) => encodeURIComponent(value)")
private external fun encodeComponent(value: String): String
