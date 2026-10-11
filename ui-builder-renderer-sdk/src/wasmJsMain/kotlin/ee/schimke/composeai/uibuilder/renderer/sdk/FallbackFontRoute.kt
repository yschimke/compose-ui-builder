@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder.renderer.sdk

/**
 * Send Compose's text-fallback downloads (Noto slices for glyphs no loaded font has) to the host's
 * [NOTO_FALLBACK_ROUTE]`/<path>` instead of `fonts.gstatic.com`, which the pages' CSP blocks. A
 * host without the route answers 404 and the fetch falls through to gstatic.
 *
 * Idempotent; called by both font registries before the first text layout. Other requests go to the
 * native `fetch` untouched (preserving Chromium's compiled-Wasm cache); the native function is kept
 * as `uiBuilderNativeFetch` for the web smoke test.
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
    if (globalThis.fetch.uiBuilderNativeFetch) return;
    const native = globalThis.fetch;
    const fetch = native.bind(globalThis);
    const routed = (input, init) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : null;
      if (url === null || !url.startsWith(from)) return fetch(input, init);
      const original = () => fetch(input, init);
      return fetch(to + url.slice(from.length), init)
        .then((response) => (response.ok ? response : original()), original);
    };
    routed.uiBuilderNativeFetch = native;
    globalThis.fetch = routed;
  }"""
)
private external fun installFallbackFontRoute(from: String, to: String)
