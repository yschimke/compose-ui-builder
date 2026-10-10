package ee.schimke.composeai.uibuilder

/**
 * Where the editor's PAGES live on this host: `/ui-builder/` everywhere by default, and `/` on a
 * builder host serving the editor at its root (compose-preview-server's `--ui-builder-host-root`,
 * which says so in a `<meta name="ui-builder-base-path">` on the editor page).
 *
 * Only pages move. The versioned bundle, the renderer runtimes, the service worker, the
 * `/api/ui-builder/…` API and the form POSTs (`/ui-builder/designs`, `/ui-builder/designs/copy`)
 * stay under `/ui-builder/` on every host, which the server keeps serving there — so this is the
 * one value navigations and shared links are built from, and nothing else reads it.
 */
const val DEFAULT_UI_BUILDER_BASE_PATH: String = "/ui-builder/"

/**
 * [raw] (the meta's content, or null when the page has none) as a path with exactly one leading and
 * one trailing slash. Anything that is not a plain path — empty, a full URL, a protocol-relative
 * `//host` — falls back to [DEFAULT_UI_BUILDER_BASE_PATH], so a malformed meta can never send a
 * navigation off this origin.
 *
 * So does a backslash, a query or fragment character, or a control character: a browser parsing an
 * http(s) URL reads `\` as `/` and drops tabs and newlines, so `/\evil.example/` and
 * `/<tab>/evil.example/` are both `//evil.example/` by the time a navigation is built from them.
 */
fun normalizeUiBuilderBasePath(raw: String?): String {
  val value = raw?.trim().orEmpty()
  // Nothing but slashes is the root (as ui-builder-boot.js reads it), not a protocol-relative URL.
  if (value.isNotEmpty() && value.all { it == '/' }) return "/"
  if (
    value.isEmpty() ||
      !value.startsWith("/") ||
      value.startsWith("//") ||
      "://" in value ||
      value.any { it == '\\' || it == '?' || it == '#' || it.code < 0x20 || it.code == 0x7f }
  ) {
    return DEFAULT_UI_BUILDER_BASE_PATH
  }
  val segments = value.split('/').filter(String::isNotEmpty)
  return if (segments.isEmpty()) "/" else segments.joinToString("/", prefix = "/", postfix = "/")
}

/**
 * The design a page [pathname] names under [basePath], or `""` when it names none: exactly one
 * segment after the base, which is not the designs index. `/ui-builder/<id>` under the default
 * base, `/<id>` at the root.
 */
fun designIdFromUiBuilderPath(pathname: String, basePath: String): String {
  if (!pathname.startsWith(basePath)) return ""
  val segments = pathname.removePrefix(basePath).split('/').filter(String::isNotEmpty)
  if (segments.size != 1 || segments[0] == UI_BUILDER_DESIGNS_SEGMENT) return ""
  return decodeUrlComponent(segments[0])
}

/** A page under [basePath], each of [segments] percent-encoded: `/ui-builder/<id>/access`. */
fun uiBuilderPagePath(basePath: String, vararg segments: String): String =
  basePath + segments.joinToString("/") { encodeUrlComponent(it) }

/** The designs index, under every base. */
const val UI_BUILDER_DESIGNS_SEGMENT: String = "designs"
