@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

/**
 * The one place that decides what URL this page actually requests.
 *
 * ## Why this exists
 *
 * The editor had three `fetch` helpers — [BrowserCommentHost]'s, [BrowserReferenceHost]'s, and
 * `Main.kt`'s pair — and only the first copied the page's `?token=` onto its requests. The other
 * two carried a comment saying a same-origin cookie was credential enough. On a `compose-preview
 * serve` started with `--token` it is not: `/api/ui-builder/v1/identity`,
 * `/api/ui-builder/v1/device-presets` and `/api/ui-builder/v1/designs/<id>/reference` all answered
 * **401**, and every one of those failures is caught and defaulted, so the page still mounted with
 * a guessed actor, no device-preset menu and no reference overlay, saying nothing.
 *
 * Three copies of a rule is how two of them end up wrong, so there is now one, and the callers
 * differ only in what they do with the response.
 *
 * ## The rule
 *
 * Resolve against the page, refuse anything that leaves this origin, and carry the page's token
 * when the URL does not already name one. Explicit beats inherited: a caller that has built a
 * `?token=` of its own keeps it.
 */
@JsFun(
  """(url) => {
    const resolved = new URL(url, window.location.href);
    if (resolved.origin !== window.location.origin) {
      throw new Error('UI-builder requests must be same-origin: ' + url);
    }
    const pageToken = new URL(window.location.href).searchParams.get('token');
    if (pageToken && !resolved.searchParams.has('token')) {
      resolved.searchParams.set('token', pageToken);
    }
    return resolved.toString();
  }"""
)
internal external fun sameOriginRequestUrl(url: String): String

/**
 * A path into the *serving* catalog's own routes — `/<catalog>/api/previews`,
 * `/<catalog>/render/…`.
 *
 * Deliberately no token: [sameOriginRequestUrl] adds it on the way out, so this stays a pure
 * function of its two arguments and is the half [BrowserRequestUrlTest] can actually assert.
 *
 * It used to build the query itself, and both halves were written `${'$'}{…}` — which in an
 * ordinary Kotlin string emits a literal `$`, not an interpolation. The editor therefore requested
 * the path `/$%7BencodeUriComponent(catalogSystemId)%7D$path$query` and took a 404 every time. The
 * only caller is the Remote Compose palette, whose loader swallows failures by design, so the
 * symptom was a palette that was silently always empty on a box serving `remote-m3`.
 */
internal fun catalogAssetPath(catalogSystemId: String, path: String): String =
  "/${encodeUriComponent(catalogSystemId)}$path"

@JsFun("(value) => encodeURIComponent(value)")
internal external fun encodeUriComponent(value: String): String

/**
 * [url], a URL written into a design, resolved against the page, or a refusal unless it is
 * same-origin and one of [kind]'s routes ([DesignReferenceKind.allows]). The token is still added
 * by [sameOriginRequestUrl] on the way out; this only narrows which paths may receive it.
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
