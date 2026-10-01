package ee.schimke.composeai.uibuilder.reference

/**
 * What a link pasted into the reference panel points at.
 *
 * Parsed here, once, so every host recognises the same links and refuses the same ones with the
 * same words; *fetching* stays with the host, because whether this editor may reach the network —
 * and with whose credential — is the host's decision and not a fact about the link.
 */
sealed interface ReferenceUrl {
  val url: String

  /**
   * A frame in a Figma file. [nodeId] is in the API's `12:34` form — the browser's address bar
   * writes it `12-34` — or null for a link to the whole file, which has no single frame to render.
   */
  data class Figma(override val url: String, val fileKey: String, val nodeId: String?) :
    ReferenceUrl {
    /** A name the import can carry, with the export scale the host will ask Figma for. */
    fun importName(scale: Int): String =
      "Figma ${nodeId?.replace(':', '-') ?: fileKey}@${scale}x.png"
  }

  /** Anything else on http(s): fetched as an image and sniffed like a file would be. */
  data class Image(override val url: String) : ReferenceUrl

  /** Not something this panel can import, and why. */
  data class Unsupported(override val url: String, val reason: String) : ReferenceUrl
}

/**
 * [text] as a [ReferenceUrl].
 *
 * Figma links come in several shapes — `/file/`, `/design/`, `/proto/`, `/board/` and branch links
 * (`/design/<key>/branch/<branchKey>/…`, where the branch key is the file to ask for) — and every
 * one names its frame in a `node-id` query parameter. Only https is accepted for an image: the
 * editor runs inside an https page, and a plain-http fetch is one the browser refuses anyway.
 */
fun parseReferenceUrl(text: String): ReferenceUrl {
  val url = text.trim()
  val schemeEnd = url.indexOf("://")
  if (schemeEnd <= 0) return ReferenceUrl.Unsupported(url, "That is not a link.")
  val scheme = url.substring(0, schemeEnd).lowercase()
  if (scheme != "https" && scheme != "http") {
    return ReferenceUrl.Unsupported(url, "Only http(s) links can be imported.")
  }
  val rest = url.substring(schemeEnd + 3)
  val hostEnd = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
  val host = rest.substring(0, hostEnd).substringAfter('@').substringBefore(':').lowercase()
  val pathAndQuery = rest.substring(hostEnd)
  val path = pathAndQuery.substringBefore('#').substringBefore('?')
  val query = pathAndQuery.substringBefore('#').substringAfter('?', "")
  if (host == "figma.com" || host.endsWith(".figma.com")) {
    val segments = path.split('/').filter { it.isNotEmpty() }
    val kind = segments.getOrNull(0)
    if (kind !in FIGMA_FILE_KINDS) {
      return ReferenceUrl.Unsupported(url, "That Figma link is not a file or a frame.")
    }
    val branchIndex = segments.indexOf("branch")
    val fileKey =
      (if (branchIndex >= 0) segments.getOrNull(branchIndex + 1) else segments.getOrNull(1))
        ?.takeIf { FIGMA_KEY.matches(it) }
        ?: return ReferenceUrl.Unsupported(url, "That Figma link has no file key.")
    val nodeId =
      query
        .split('&')
        .firstOrNull { it.startsWith("node-id=") }
        ?.substringAfter('=')
        ?.replace("%3A", ":", ignoreCase = true)
        ?.replace('-', ':')
        ?.takeIf { FIGMA_NODE.matches(it) }
    return ReferenceUrl.Figma(url, fileKey, nodeId)
  }
  if (scheme != "https") return ReferenceUrl.Unsupported(url, "Only https images can be imported.")
  if (host.isEmpty()) return ReferenceUrl.Unsupported(url, "That link has no host.")
  return ReferenceUrl.Image(url)
}

/** The words for a Figma link a host cannot fetch: what to do instead, which is a paste away. */
const val FIGMA_PASTE_ADVICE: String =
  "In Figma, select the frame and Copy as PNG (Shift+Cmd/Ctrl+C), then paste it here — or " +
    "export it at 2x and import the file."

private val FIGMA_FILE_KINDS = setOf("file", "design", "proto", "board")
private val FIGMA_KEY = Regex("[A-Za-z0-9]{10,64}")
private val FIGMA_NODE = Regex("""I?\d+:\d+(;\d+:\d+)*""")
