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
 * The fonts a catalog's own runtime draws a design's typefaces with, resolved as the editor's
 * canvas does: shipped families from `fonts/fonts.json` (copied from `assets/rc-fonts`); others
 * from the host's CORS-open `/api/fonts/google/{family}/{weight}` route, since the sandboxed
 * opaque-origin frame cannot reach `fonts.gstatic.com`. Without the route, the platform face is
 * used ([routeFallbackFontsThroughHost] for missing glyphs).
 *
 * Provide with `ProvideUiBuilderFonts`; read theme host families with `rememberThemeRoleFamilies`.
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
