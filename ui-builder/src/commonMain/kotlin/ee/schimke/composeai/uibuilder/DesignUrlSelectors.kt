package ee.schimke.composeai.uibuilder

/**
 * What a design URL points at inside the design, beyond which design it is. All three selectors are
 * optional and additive.
 *
 * [threadId] is read from the URL fragment, never the query, so a private discussion's id does not
 * reach server logs, proxies or referrers. Links built by [designUrlPath] never carry identity keys
 * or a token.
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
 * What `?revision=` did: which revision was asked for and whether it is on screen. A revision that
 * cannot be shown opens the living design with a banner rather than refusing the link.
 */
data class DesignRevisionPin(
  /** The revision the URL asked for. */
  val requested: Long,
  /** True while that revision is what the canvas is drawing. */
  val pinned: Boolean,
) {
  /**
   * A pinned revision is history, so document edits are refused; comments, marks and panels still
   * work.
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
 * Identity and transport values carried from one URL to the next. One list read by both sides: the
 * canonical rewrite keeps exactly these and [designUrlPath] writes none of them. `token` is absent
 * because the browse credential lives in a cookie.
 */
val DESIGN_URL_IDENTITY_KEYS: List<String> =
  listOf(
    "actor",
    "clientId",
    "displayName",
    "color",
    "endpoint",
    "updatesEndpoint",
    // Not identity, but the open page must keep it (dropping it would switch to the server's design
    // of the same id) and a shared link must not carry it (the browser-local design is not there).
    "storage",
  )

/**
 * Reads the selectors from `location.search` and `location.hash` (leading `?` / `#` optional).
 * Tolerant: unknown keys are ignored and invalid values dropped. Only the thread is read from the
 * fragment.
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
 * Whether the path form can name this design. The service accepts any non-blank id, but the editor
 * and app shell only route this shape, so callers withhold Copy link rather than emit a broken
 * address.
 */
fun isDesignUrlPathSafe(designId: String): Boolean =
  PATH_SAFE_ID.matches(designId) &&
    designId.substringAfterLast('.', "").lowercase() !in DESIGN_PATH_ASSET_EXTENSIONS

private val PATH_SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

/**
 * Suffixes the app shell routes as asset requests, mirroring the server's list (pinned by a test).
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
 * The canonical, root-relative URL for one design with only the selectors a reader needs. Order is
 * fixed so the same selection always yields the same string. The design must be
 * [isDesignUrlPathSafe].
 */
fun designUrlPath(
  designId: String,
  selectors: DesignUrlSelectors = DesignUrlSelectors(),
): String {
  require(designId.isNotBlank()) { "a design link needs a design id" }
  require(isDesignUrlPathSafe(designId)) {
    "the path form cannot name this design; see isDesignUrlPathSafe"
  }
  require(selectors.revision == null || selectors.revision >= 0) {
    "a design link revision must not be negative"
  }
  val path = "/ui-builder/${encodeUrlComponent(designId)}"
  val query = buildList {
    selectors.revision?.let { add("$DESIGN_URL_REVISION_KEY=$it") }
    selectors.nodeId?.let { add("$DESIGN_URL_NODE_KEY=${encodeUrlComponent(it)}") }
  }
  val fragment =
    selectors.threadId?.let { "#$DESIGN_URL_THREAD_KEY=${encodeUrlComponent(it)}" }.orEmpty()
  return path + (if (query.isEmpty()) "" else query.joinToString("&", prefix = "?")) + fragment
}

/** `a=1&b=2` as a map, last value wins; malformed pairs are skipped. */
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

/** A non-negative revision. Zero is valid: it names the state the design was created in. */
private fun String.toRevisionOrNull(): Long? = trim().toLongOrNull()?.takeIf { it >= 0 }

/**
 * A node or thread id, untrimmed and uncapped, so it round-trips with [designUrlPath]; only blank
 * is refused, matching the service.
 */
private fun String.toSelectorIdOrNull(): String? = takeIf { it.isNotBlank() }

/**
 * Percent-encoding matching JavaScript's `encodeURIComponent`, hand-written so JVM tests and the
 * Wasm build produce identical bytes.
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
 * The inverse of [encodeUrlComponent], plus `+` as a space. Malformed escapes are left as written.
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
        // Two hex digits only: `toIntOrNull(16)` alone would also accept a sign, as in `%-1`.
        val decoded =
          value.substring(index + 1, index + 3).takeIf { it.all(::isHexDigit) }?.toIntOrNull(16)
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

private fun isHexDigit(char: Char): Boolean =
  char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'

private const val UNRESERVED = "-_.!~*'()"

private const val HEX = "0123456789ABCDEF"
