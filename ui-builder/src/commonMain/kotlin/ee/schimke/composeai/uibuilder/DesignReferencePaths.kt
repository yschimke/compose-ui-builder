package ee.schimke.composeai.uibuilder

/**
 * The same-origin routes a URL written into a design may be fetched from, per kind of reference.
 * Designs are other people's data fetched as the viewer with the page's token, so beyond
 * same-origin each kind is held to the routes that serve it:
 * - a Remote Compose document: `/render/<id>.rc`, `/<system>/render/<id>.rc`, or an ingested
 *   document's `/d/<id>/raw`;
 * - a Lottie animation: an ingested document's `/d/<id>/raw`.
 *
 * Anything else is refused and drawn as that reference's failure.
 */
internal enum class DesignReferenceKind(private val routes: List<Regex>, val label: String) {
  RemoteComposeDocument(
    listOf(Regex("""/(?:$SEGMENT/)?render/$SEGMENT\.rc"""), INGESTED_DOCUMENT),
    "Remote Compose document",
  ),
  LottieAnimation(listOf(INGESTED_DOCUMENT), "Lottie animation");

  /**
   * Whether [path], the resolved `pathname` of a same-origin URL, is one of this kind's routes. An
   * escaped `/` or `\` in a segment is refused, since a decoding server would see a different path.
   */
  fun allows(path: String): Boolean =
    !ESCAPED_SEPARATOR.containsMatchIn(path) && routes.any { it.matches(path) }
}

private const val SEGMENT = """[^/\\.][^/\\]*"""
private val INGESTED_DOCUMENT = Regex("""/d/$SEGMENT/raw""")
private val ESCAPED_SEPARATOR = Regex("%(?:2[fF]|5[cC]|2[eE])")
