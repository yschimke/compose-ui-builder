package ee.schimke.composeai.uibuilder

/**
 * The same-origin routes a URL written into a design may be fetched from, per kind of reference.
 *
 * A design is data other people author, and the browser host fetches the URLs it names as the
 * viewer, with the page's token. Same-origin alone (`sameOriginRequestUrl`) still lets a design
 * point that request at any route on the server, so each kind of reference is held to the routes
 * that actually serve it:
 * - a Remote Compose document: a catalog's render lane, `/render/<id>.rc` or
 *   `/<system>/render/<id>.rc`, or an ingested document's `/d/<id>/raw`;
 * - a Lottie animation: an ingested document's `/d/<id>/raw`.
 *
 * Anything else is refused, which the editor draws as that reference's own failure.
 */
internal enum class DesignReferenceKind(private val routes: List<Regex>, val label: String) {
  RemoteComposeDocument(
    listOf(Regex("""/(?:$SEGMENT/)?render/$SEGMENT\.rc"""), INGESTED_DOCUMENT),
    "Remote Compose document",
  ),
  LottieAnimation(listOf(INGESTED_DOCUMENT), "Lottie animation");

  /**
   * Whether [path], the already-resolved `pathname` of a same-origin URL, is one of this kind's
   * routes. An escaped `/` or `\` in a segment is refused rather than second-guessed, since a
   * server that decodes it would see a different path than the one checked here.
   */
  fun allows(path: String): Boolean =
    !ESCAPED_SEPARATOR.containsMatchIn(path) && routes.any { it.matches(path) }
}

private const val SEGMENT = """[^/\\.][^/\\]*"""
private val INGESTED_DOCUMENT = Regex("""/d/$SEGMENT/raw""")
private val ESCAPED_SEPARATOR = Regex("%(?:2[fF]|5[cC]|2[eE])")
