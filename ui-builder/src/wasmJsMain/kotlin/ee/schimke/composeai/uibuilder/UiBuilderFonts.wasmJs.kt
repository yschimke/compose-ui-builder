@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.MainScope

internal actual fun platformFont(identity: String, data: ByteArray, weight: FontWeight): Font =
  androidx.compose.ui.text.platform.Font(identity, data, weight)

/**
 * The vendored families as this page serves them: `fonts/fonts.json` and its files, beside the
 * bundle, which is where both the editor's and the renderer runtime's builds copy
 * `assets/rc-fonts`.
 *
 * Resolved against the bundle's own module URL rather than the page: a host serves the editor
 * document at `/ui-builder/<design>` but its assets under an immutable, content-addressed prefix
 * (`/ui-builder/v/<digest>/`, the module's `src` after the host rewrites the shell), so fonts from
 * there are cached for good and never revalidated, where the unprefixed copies are `no-cache`. A
 * page with no such module (the renderer runtime, whose own path is already immutable) resolves
 * against itself.
 *
 * Same-origin and token-carrying like every other request the page makes ([sameOriginRequestUrl]).
 */
@OptIn(ExperimentalEncodingApi::class)
fun browserFontRegistry(baseUrl: String = bundleFontsBaseUrl()): UiBuilderFontRegistry =
  UiBuilderFontRegistry(
    scope = MainScope(),
    readManifest = { fetchText("${baseUrl}fonts.json") },
    readFont = { file -> Base64.decode(fetchBase64("$baseUrl$file")) },
  )

@JsFun(
  """() => {
    const module = document.querySelector('script[type="module"][src$="uiBuilder.mjs"]');
    return module ? new URL('fonts/', module.src).href : 'fonts/';
  }"""
)
private external fun bundleFontsBaseUrl(): String
