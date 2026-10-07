package ee.schimke.composeai.uibuilder.export

/**
 * `RemoteTimeText`: the time, curved along the top of its bounds, with optional text either side —
 * the Wear time text a tile or widget shows at twelve o'clock.
 *
 * Not in the embedded `remote-m3` record, which is generated from the catalog's sheets and has no
 * row for it, so it is declared here, once, the way [UiDrawing] declares the drawing vocabulary:
 * the runtime builds its palette entry from [PROPERTIES], the canvas stand-in and the Remote
 * emitter read the same names.
 *
 * The time itself is the player's — `RemoteTimeDefaults.defaultTimeString()`, live on the device —
 * so it is not a property. The canvas shows the design's fixed preview time in its place.
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
