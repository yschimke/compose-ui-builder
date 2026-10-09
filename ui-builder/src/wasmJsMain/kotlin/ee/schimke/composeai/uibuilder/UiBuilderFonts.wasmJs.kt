@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.renderer.sdk.routeFallbackFontsThroughHost
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.MainScope

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
 * A family the manifest does not list comes from the host's Google Fonts route
 * ([GOOGLE_FONTS_ROUTE]) instead. The page's own `connect-src` admits only this origin, so the
 * browser cannot fetch `fonts.gstatic.com` itself; the host fetches the file once, keeps it, and
 * answers same-origin. A host without the route answers 404, and the family stays on the default
 * face exactly as it did before. A glyph no font here has goes the same way
 * ([routeFallbackFontsThroughHost]).
 *
 * Same-origin and token-carrying like every other request the page makes ([sameOriginRequestUrl]).
 */
@OptIn(ExperimentalEncodingApi::class)
fun browserFontRegistry(baseUrl: String = bundleFontsBaseUrl()): UiBuilderFontRegistry {
  routeFallbackFontsThroughHost()
  return UiBuilderFontRegistry(
    scope = MainScope(),
    readManifest = { fetchText("${baseUrl}fonts.json") },
    readFont = { file -> Base64.decode(fetchBase64("$baseUrl$file")) },
    readRemoteFont = { family, weight ->
      Base64.decode(fetchBase64(googleFontUrl(family, weight)))
    },
    // The fonts.google.com catalogue, shipped beside the vendored manifest for the picker.
    readRemoteFamilies = { fetchText("${baseUrl}google-fonts.txt") },
  )
}

/** Where a host serves a Google Fonts family's TrueType file at one weight. */
internal const val GOOGLE_FONTS_ROUTE = "/api/fonts/google"

internal fun googleFontUrl(family: String, weight: Int): String =
  "$GOOGLE_FONTS_ROUTE/${encodeUriComponent(family)}/$weight"

@JsFun(
  """() => {
    const module = document.querySelector('script[type="module"][src$="uiBuilder.mjs"]');
    return module ? new URL('fonts/', module.src).href : 'fonts/';
  }"""
)
private external fun bundleFontsBaseUrl(): String
