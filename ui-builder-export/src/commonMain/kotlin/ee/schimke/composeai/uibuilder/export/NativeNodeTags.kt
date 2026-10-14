package ee.schimke.composeai.uibuilder.export

/**
 * How the native preview lane finds where a Remote Compose node drew.
 *
 * For ordinary Compose the lane compiles the generated source with `tagNodes = true`, which writes
 * `Modifier.testTag("<nodeId>")`, and reads `testTag` with `boundsInRoot` back from the semantics
 * tree. Remote Compose has no `testTag`: a `RemoteModifier` chain records no test tag, so a Wear
 * widget or other Remote content had no bounds at all. What it does record is accessibility
 * semantics: `RemoteModifier.semantics { contentDescription = "…".rs }` becomes an
 * `RcAccessibilitySemantics` operation, and the Compose Multiplatform player (`RcComposePlayer`)
 * turns that into a Compose semantics node on the component's own layout node, with that
 * component's bounds. So the native lane writes each node's [tag] as a content description, and
 * recovers the ids with [nodeIds].
 *
 * The tag is a content description only in the native lane's source, which is compiled, played and
 * thrown away. It is never written into an export a person keeps, where a screen reader would read
 * it aloud.
 */
public object NativeNodeTags {
  /** What marks a content description as a node tag rather than an authored description. */
  public const val PREFIX: String = "uib-node:"

  /** The content description that identifies [nodeId]. */
  public fun tag(nodeId: String): String = PREFIX + nodeId

  /**
   * The node ids tagged in [contentDescription], in order.
   *
   * A semantics node's description can hold several: the node's own (`Play`) beside its tag, or
   * every description merged from its children. They arrive joined by `, ` or rendered as a list
   * (`[Play, uib-node:a]`). Only values carrying [PREFIX] are returned, so an authored description
   * is never mistaken for a node. A node id containing `, ` cannot be recovered; design ids do not.
   */
  public fun nodeIds(contentDescription: String): List<String> {
    val text = contentDescription.trim()
    val unwrapped =
      if (text.startsWith("[") && text.endsWith("]")) text.substring(1, text.length - 1) else text
    return unwrapped
      .split(", ", "\n")
      .map { it.trim().removePrefix("[").removeSuffix("]").trim() }
      .filter { it.startsWith(PREFIX) && it.length > PREFIX.length }
      .map { it.removePrefix(PREFIX) }
  }

  /** The node ids tagged across several content descriptions, as a semantics node lists them. */
  public fun nodeIds(contentDescriptions: List<String>): List<String> =
    contentDescriptions.flatMap {
      nodeIds(it)
    }
}
