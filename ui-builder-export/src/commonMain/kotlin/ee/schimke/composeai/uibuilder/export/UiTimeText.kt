package ee.schimke.composeai.uibuilder.export

/**
 * `RemoteTimeText`: the time curved along the top of its bounds, with optional text either side.
 * Declared here (the embedded `remote-m3` record has no row for it), like [UiDrawing]: the runtime,
 * canvas and Remote emitter read [PROPERTIES]. The time itself is the player's, so it is not a
 * property; the canvas shows the fixed preview time.
 */
object UiTimeText {
  const val ID: String = "remote-m3/remote-time-text"

  /** What `RemoteTimeText` puts between the time and the text beside it when none is given. */
  const val DEFAULT_SEPARATOR: String = "·"

  val PROPERTIES: List<UiDrawing.Property> =
    listOf(
      UiDrawing.Property.Text(
        "leadingText",
        "Text before the time, e.g. a date. None when absent.",
      ),
      UiDrawing.Property.Text("trailingText", "Text after the time. None when absent."),
      UiDrawing.Property.Text(
        "separator",
        "What sits between the time and the text beside it. `·` when absent.",
      ),
      UiDrawing.Property.Number(
        "textSizeSp",
        UiDrawing.Unit.SP,
        "Text size. The time text style's when absent.",
      ),
      UiDrawing.Property.Color(
        "color",
        "Text colour. The theme's onBackground when absent.",
      ),
    )

  /** The whole line as the player assembles it: leading, separator, time, separator, trailing. */
  fun line(time: String, leading: String?, trailing: String?, separator: String?): String {
    val between = separator ?: DEFAULT_SEPARATOR
    return buildString {
      if (!leading.isNullOrEmpty()) append(leading).append(between)
      append(time)
      if (!trailing.isNullOrEmpty()) append(between).append(trailing)
    }
  }
}
