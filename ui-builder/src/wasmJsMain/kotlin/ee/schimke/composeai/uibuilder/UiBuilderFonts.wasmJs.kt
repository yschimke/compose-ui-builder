@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.renderer.sdk.routeFallbackFontsThroughHost
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.MainScope

/**
 * The vendored families as this page serves them: `fonts/fonts.json` and its files beside the
 * bundle (both builds copy `assets/rc-fonts`). Resolved against the bundle's module URL, so fonts
 * load from the host's immutable content-addressed prefix (`/ui-builder/v/<digest>/`); pages
 * without such a module (the renderer runtime) resolve against themselves.
 *
 * Families the manifest doesn't list come from the host's Google Fonts route
 * ([GOOGLE_FONTS_ROUTE]), since `connect-src` only admits this origin; a host without it answers
 * 404 and the family stays default. Missing glyphs go the same way
 * ([routeFallbackFontsThroughHost]). Requests go through [sameOriginRequestUrl].
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
