@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder.renderer.sdk

/**
 * Send Compose's text-fallback downloads to the host's Noto route instead of `fonts.gstatic.com`.
 *
 * When no loaded font has a glyph (`∞`, `☀`, a CJK character), Compose for web picks the Noto slice
 * that covers it and fetches `https://fonts.gstatic.com/s/<path>` itself, with no way to point it
 * elsewhere. Every page this bundle runs in admits only its own origin in `connect-src`, so each of
 * those fetches was a CSP error and the glyph a tofu box. The host serves the same slice at
 * [NOTO_FALLBACK_ROUTE]`/<path>`, fetched once and kept, as it does a Google Fonts family at
 * [GOOGLE_FONTS_ROUTE]; this rewrites the fetch to it. A host without the route answers 404, and
 * the fetch then goes to gstatic as it did before, which a page that allows it still completes.
 *
 * Idempotent, and called by both font registries' factories so every editor and runtime page has it
 * before its first frame lays out text.
 */
fun routeFallbackFontsThroughHost() {
  installFallbackFontRoute(GSTATIC_FALLBACK_BASE, "$NOTO_FALLBACK_ROUTE/")
}

/** Where a host serves the Noto slice Compose would fetch from [GSTATIC_FALLBACK_BASE]. */
const val NOTO_FALLBACK_ROUTE: String = "/api/fonts/noto"

/** Compose's `FONT_FALLBACK_BASE_URL`. */
internal const val GSTATIC_FALLBACK_BASE: String = "https://fonts.gstatic.com/s/"

@JsFun(
  """(from, to) => {
    if (globalThis.__uiBuilderFallbackFontRoute) return;
    globalThis.__uiBuilderFallbackFontRoute = true;
    const fetch = globalThis.fetch.bind(globalThis);
    globalThis.fetch = (input, init) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : null;
      if (url === null || !url.startsWith(from)) return fetch(input, init);
      const original = () => fetch(input, init);
      return fetch(to + url.slice(from.length), init)
        .then((response) => (response.ok ? response : original()), original);
    };
  }"""
)
private external fun installFallbackFontRoute(from: String, to: String)
