@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

/**
 * The one place that decides what URL this page actually requests, shared by every `fetch` helper.
 *
 * Resolve against the page and refuse anything that leaves this origin. The page's credential isn't
 * copied onto the URL: a token-gated `compose-preview serve` trades `?token=` for an HttpOnly
 * cookie that same-origin requests send; a caller-built `?token=` is kept.
 *
 * In an MCP App (`McpAppHostApp.kt`) the frame's origin is the host sandbox, so the shell's `<base
 * href>` replaces the page: resolve against it and refuse anything outside
 * `composeUiBuilderMcpApp.assetBase` (or `catalogBase`, see `McpAppCatalogs`), except the shell's
 * `devicePresets` URL. No credential is involved there.
 */
@JsFun(
  """(url) => {
    const app = globalThis.composeUiBuilderMcpApp;
    if (app && app.assetBase) {
      const inside = new URL(url, document.baseURI);
      const allowed = [app.assetBase, app.catalogBase].filter(Boolean).some((base) => {
        const root = new URL(base, document.baseURI);
        return inside.origin === root.origin && inside.pathname.startsWith(root.pathname);
      });
      const presets = app.devicePresets && !app.devicePresets.startsWith('__')
        ? new URL(app.devicePresets, document.baseURI).href
        : null;
      if (!allowed && inside.href !== presets) {
        throw new Error('UI-builder MCP App requests must stay under the editor assets: ' + url);
      }
      return inside.toString();
    }
    const resolved = new URL(url, window.location.href);
    if (resolved.origin !== window.location.origin) {
      throw new Error('UI-builder requests must be same-origin: ' + url);
    }
    return resolved.toString();
  }"""
)
internal external fun sameOriginRequestUrl(url: String): String

/**
 * A path into the *serving* catalog's own routes (`/<catalog>/api/previews`,
 * `/<catalog>/render/…`). No token (the cookie authenticates), so it stays pure and testable in
 * [BrowserRequestUrlTest].
 */
internal fun catalogAssetPath(catalogSystemId: String, path: String): String =
  "/${encodeUriComponent(catalogSystemId)}$path"

@JsFun("(value) => encodeURIComponent(value)")
internal external fun encodeUriComponent(value: String): String

/**
 * [url], a URL written into a design, resolved against the page, or a refusal unless it is
 * same-origin and one of [kind]'s routes ([DesignReferenceKind.allows]). Narrows which paths get
 * the page's cookie.
 */
internal fun designReferenceUrl(url: String, kind: DesignReferenceKind): String {
  val resolved = resolveOnPage(url)
  val path = resolved?.let(::pathnameOf)
  require(path != null && kind.allows(path)) {
    "a design's ${kind.label} must be served from this server's own document routes: $url"
  }
  return resolved
}

@JsFun(
  """(url) => {
    try {
      const resolved = new URL(url, window.location.href);
      if (resolved.origin !== window.location.origin) return null;
      resolved.hash = '';
      return resolved.toString();
    } catch (e) {
      return null;
    }
  }"""
)
private external fun resolveOnPage(url: String): String?

@JsFun("(url) => new URL(url).pathname") private external fun pathnameOf(url: String): String
