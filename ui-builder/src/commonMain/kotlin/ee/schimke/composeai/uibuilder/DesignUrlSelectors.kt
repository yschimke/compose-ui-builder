package ee.schimke.composeai.uibuilder

/**
 * What a design URL says it means, beyond which design it is.
 *
 * The canonical URL for a design is `/ui-builder/<catalog>/<designId>`, and the only thing it says
 * is which catalog and which design. Identity and transport — `actor`, `clientId`, `token`,
 * `endpoint` — live in the query because they configure *who* is editing. Neither says **what** in
 * the design a link means, so "look at this thread" could not be pasted into a chat and an agent
 * told "the button on the checkout design" had to search for it.
 *
 * These three selectors are that missing half, and they are additive: a URL carrying none of them
 * opens exactly what it opened before.
 *
 * - [revision] pins the design to one committed revision, shown read-only.
 * - [nodeId] selects one layer as the design opens.
 * - [threadId] opens the Talk panel on one conversation.
 *
 * ### Why the thread is a fragment and the other two are not
 *
 * [revision] and [nodeId] are read as a query; [threadId] is read from the URL **fragment**, and
 * the difference is deliberate rather than cosmetic. A fragment is never sent to the server: it
 * does not reach the request line, the access log, a proxy, or a referrer header. A thread id is
 * the one selector that names a *discussion* — the private half of a private design — so the id of
 * a conversation somebody linked to should not accumulate in logs that outlive the link. The
 * revision and the node are properties of the document the reader is about to be served anyway.
 *
 * ### What is never in one of these URLs
 *
 * A link built by [designUrlPath] carries the path and the selectors and nothing else — no value
 * from [DESIGN_URL_IDENTITY_KEYS], and above all no token. A shared link is an address, never a
 * credential; whoever opens it presents their own, which is the rule the export lane's Copy link
 * already follows.
 */
data class DesignUrlSelectors(
  /** A committed revision to show read-only, or null for the living design. */
  val revision: Long? = null,
  /** A node to select as the design opens, or null. */
  val nodeId: String? = null,
  /** A comment thread to open the Talk panel on, or null. */
  val threadId: String? = null,
) {
  val isEmpty: Boolean
    get() = revision == null && nodeId == null && threadId == null
}

/**
 * What `?revision=` did to this editor: which revision was asked for, and which one is on screen.
 *
 * Two fields rather than one, because "the link named a revision" and "you are looking at it" are
 * different facts and the banner has to say which. A revision that was trimmed out of the retained
 * window, or that a design never reached, cannot be shown — and refusing to open the design at all
 * would be the worst possible answer to a stale link somebody pasted a month ago. So the editor
 * opens the living design and the banner says why, which is the same rule the catalog-less design
 * URL follows: answer the question the reader actually has.
 */
data class DesignRevisionPin(
  /** The revision the URL asked for. */
  val requested: Long,
  /** True while that revision is what the canvas is drawing. */
  val pinned: Boolean,
) {
  /**
   * Whether the design may be edited.
   *
   * A pinned revision is history: an edit to it would either have to fork the design or be applied
   * to the head it is not showing, and both are worse than a canvas that says it is read-only. Only
   * *document* edits are refused — a comment, a reference mark and a panel are not the design.
   */
  val readOnly: Boolean
    get() = pinned
}

/** `?revision=<n>` — the committed revision a link names. */
const val DESIGN_URL_REVISION_KEY: String = "revision"

/** `?node=<nodeId>` — the layer a link names. */
const val DESIGN_URL_NODE_KEY: String = "node"

/** `#thread=<threadId>` — the conversation a link names, in the fragment. */
const val DESIGN_URL_THREAD_KEY: String = "thread"

/**
 * The identity and transport values the editor carries from one URL to the next.
 *
 * One list, read by both sides of the same question: the browser's canonical rewrite copies exactly
 * these forward, and [designUrlPath] writes none of them. Two lists would eventually disagree, and
 * the way they would disagree is a token riding along in a link somebody pastes into a chat.
 * `token` is in it because the *page's own* URL keeps its credential across the rewrite.
 */
val DESIGN_URL_IDENTITY_KEYS: List<String> =
  listOf(
    "token",
    "actor",
    "clientId",
    "displayName",
    "color",
    "endpoint",
    "updatesEndpoint",
    // Not an identity, but it belongs here on both counts this list decides. The open page must
    // keep it, because a rewrite that dropped it would move the tab from the design this browser
    // holds to the *server's* design of the same id without saying so; and a shared link must not
    // carry it, because the design it names does not exist in the browser that opens the link.
    "storage",
  )

/**
 * Reads the three selectors out of one URL's query and fragment.
 *
 * Tolerant by construction, because every input is somebody else's link: an unknown key is ignored
 * rather than refused, a `revision` that is not a non-negative number is dropped rather than
 * failing the page, and a value that is blank or absurdly long is treated as absent. A selector
 * that names something this design does not have is **not** this function's problem — an unknown
 * node id is still returned here and answered by the editor with a notice, because "there is no
 * such layer" is a sentence a reader needs and a parse failure is not.
 *
 * Both arguments take the browser's own spelling, with or without their leading `?` and `#`, so a
 * caller can hand over `location.search` and `location.hash` unmodified.
 *
 * The fragment is read for [DESIGN_URL_THREAD_KEY] only. A `revision` or `node` written into the
 * fragment is ignored rather than honoured: those two are query keys, and a URL that worked by
 * accident in one spelling is one that breaks when the server learns to read them.
 */
fun parseDesignUrlSelectors(query: String?, fragment: String?): DesignUrlSelectors {
  val queryValues = parseUrlPairs(query?.removePrefix("?"))
  val fragmentValues = parseUrlPairs(fragment?.removePrefix("#"))
  return DesignUrlSelectors(
    revision = queryValues[DESIGN_URL_REVISION_KEY]?.toRevisionOrNull(),
    nodeId = queryValues[DESIGN_URL_NODE_KEY]?.toSelectorIdOrNull(),
    threadId = fragmentValues[DESIGN_URL_THREAD_KEY]?.toSelectorIdOrNull(),
  )
}

/**
 * Whether the path form can name this design and catalog at all.
 *
 * The two ends of a design URL do not agree on what an id may contain, and this is the seam. The
 * service stores any id that is not blank; the editor's own entry point refuses to start on a
 * design *named in the path* unless it matches this, and the app shell routes on the same shape. So
 * a design created through the protocol, MCP or the Design API with a space in its id is reachable
 * only through the legacy `?designId=` query — and a path-form link to it would hand its recipient
 * a 404 or a page that refuses to initialise.
 *
 * Asked before the link is offered rather than repaired afterwards: a builder that quietly emitted
 * a different *kind* of URL for some designs would be a second address form to keep working, and
 * the one thing worse than no Copy link is a Copy link that produces a broken address.
 */
fun isDesignUrlPathSafe(catalogSystemId: String, designId: String): Boolean =
  PATH_SAFE_ID.matches(catalogSystemId) &&
    PATH_SAFE_ID.matches(designId) &&
    designId.substringAfterLast('.', "").lowercase() !in DESIGN_PATH_ASSET_EXTENSIONS

private val PATH_SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

/**
 * Suffixes the app shell reads as a file rather than as a design, mirroring the server's own list.
 *
 * A design legitimately called `screen.png` matches the id pattern and is still unreachable by
 * path: the route treats a single segment ending in one of these as an asset request, so the link
 * 404s instead of opening the editor. Duplicated here rather than shared because the two live in
 * different modules and this is the smaller half of the seam; the cost of them drifting is a link
 * that does not open, which is what the test beside this pins.
 */
private val DESIGN_PATH_ASSET_EXTENSIONS =
  setOf(
    "css",
    "html",
    "ico",
    "js",
    "json",
    "map",
    "mjs",
    "otf",
    "png",
    "svg",
    "ttf",
    "txt",
    "wasm",
    "webp",
    "woff",
    "woff2",
  )

/**
 * The canonical URL for one design, carrying only what a reader needs to see the same thing.
 *
 * Path form rather than the legacy `?designId=` query, absolute-from-root rather than fully
 * qualified — the origin is the page's own, and a caller that needs an absolute URL resolves this
 * against it. The order is fixed (`revision` then `node`, then the fragment) so the same selection
 * always produces the same string: a link copied twice is the same link, which is what makes it
 * safe to paste into a pull request and compare.
 *
 * The catalog and design must be [isDesignUrlPathSafe]; a caller asks first and withholds the
 * affordance rather than handing over an address the editor would refuse to open.
 */
fun designUrlPath(
  catalogSystemId: String,
  designId: String,
  selectors: DesignUrlSelectors = DesignUrlSelectors(),
): String {
  require(catalogSystemId.isNotBlank()) { "a design link needs a catalog" }
  require(designId.isNotBlank()) { "a design link needs a design id" }
  require(isDesignUrlPathSafe(catalogSystemId, designId)) {
    "the path form cannot name this design; see isDesignUrlPathSafe"
  }
  val path = "/ui-builder/${encodeUrlComponent(catalogSystemId)}/${encodeUrlComponent(designId)}"
  val query = buildList {
    selectors.revision?.let { add("$DESIGN_URL_REVISION_KEY=$it") }
    selectors.nodeId?.let { add("$DESIGN_URL_NODE_KEY=${encodeUrlComponent(it)}") }
  }
  val fragment =
    selectors.threadId?.let { "#$DESIGN_URL_THREAD_KEY=${encodeUrlComponent(it)}" }.orEmpty()
  return path + (if (query.isEmpty()) "" else query.joinToString("&", prefix = "?")) + fragment
}

/**
 * `a=1&b=2` as a map, last value wins, tolerant of everything a real URL contains.
 *
 * Empty segments, a bare key with no `=`, and a value that will not percent-decode are all read as
 * far as they can be and otherwise skipped: this parses links people paste, not a wire format.
 */
private fun parseUrlPairs(raw: String?): Map<String, String> {
  if (raw.isNullOrEmpty()) return emptyMap()
  val values = mutableMapOf<String, String>()
  raw.split('&').forEach { pair ->
    if (pair.isEmpty()) return@forEach
    val separator = pair.indexOf('=')
    if (separator <= 0) return@forEach
    val key = decodeUrlComponent(pair.substring(0, separator))
    values[key] = decodeUrlComponent(pair.substring(separator + 1))
  }
  return values
}

/**
 * A revision is a whole number that is not negative, or it is not a revision.
 *
 * Zero is included, and deliberately: a design is created at revision 0 and that snapshot is
 * retained like any other, so `?revision=0` names the state the design was born in — the one a
 * reader asks for to see what a template started as. The export routes already accept it on the
 * same terms, and a parser that dropped it would answer that link by silently opening the live
 * editable design instead.
 */
private fun String.toRevisionOrNull(): Long? = trim().toLongOrNull()?.takeIf { it >= 0 }

/**
 * A node or thread id, or null where the URL named nothing usable.
 *
 * The value is returned as it was written, not trimmed. Only *blankness* is tested by trimming,
 * because that is the one thing the service tests too: a node id is rejected when it is blank and
 * accepted otherwise, so `" hero "` is an id a stored design can genuinely have. Returning the
 * trimmed form would make this parser disagree with [designUrlPath], which percent-encodes the id
 * exactly — the feature's own copied link would then select a different node, or none.
 *
 * Blankness is also the *only* thing an id is refused for. A length cap here looked like prudence
 * and was the same bug in another spelling: the service stores a node id of any length, and
 * [designUrlPath] writes out whatever the document holds, so a parser that dropped a long one would
 * make this feature emit links it cannot read back. How long a URL may be is the browser's rule to
 * enforce, on a URL it has already parsed and handed over.
 */
private fun String.toSelectorIdOrNull(): String? = takeIf { it.isNotBlank() }

/**
 * Percent-encoding for one URL component, matching JavaScript's `encodeURIComponent`.
 *
 * Written here rather than taken from a platform, because this function's output is compared
 * against a fixed expectation in a common test and read by a browser: the same input has to make
 * the same bytes on the JVM the test runs on and in the Wasm the editor ships as.
 */
internal fun encodeUrlComponent(value: String): String {
  val out = StringBuilder(value.length)
  value.encodeToByteArray().forEach { byte ->
    val code = byte.toInt() and 0xFF
    val char = code.toChar()
    if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in UNRESERVED) {
      out.append(char)
    } else {
      out.append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
    }
  }
  return out.toString()
}

/**
 * The inverse of [encodeUrlComponent], plus `+` as a space.
 *
 * `+` is decoded because a form-encoded query is what a browser produces from a submitted form and
 * this parser reads whatever arrives, not only what this code wrote. A malformed escape is left as
 * written rather than throwing: a link with a stray `%` in it should still open the design.
 */
internal fun decodeUrlComponent(value: String): String {
  if ('%' !in value && '+' !in value) return value
  val bytes = ArrayList<Byte>(value.length)
  var index = 0
  while (index < value.length) {
    val char = value[index]
    when {
      char == '+' -> {
        bytes.add(' '.code.toByte())
        index += 1
      }
      char == '%' && index + 2 < value.length -> {
        val decoded = value.substring(index + 1, index + 3).toIntOrNull(16)
        if (decoded == null) {
          bytes.add(char.code.toByte())
          index += 1
        } else {
          bytes.add(decoded.toByte())
          index += 3
        }
      }
      else -> {
        char.toString().encodeToByteArray().forEach(bytes::add)
        index += 1
      }
    }
  }
  return bytes.toByteArray().decodeToString()
}

private const val UNRESERVED = "-_.!~*'()"

private const val HEX = "0123456789ABCDEF"
