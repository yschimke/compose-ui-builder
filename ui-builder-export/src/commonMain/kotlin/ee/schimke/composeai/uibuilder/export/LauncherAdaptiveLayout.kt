package ee.schimke.composeai.uibuilder.export

/**
 * A launcher widget's adaptive layout (`remote-widgets`' `AdaptiveLayout`): up to three layouts at
 * launcher grid sizes, of which the widget shows the best fit via a `RemoteStateLayout`. The export
 * just calls the catalog's composable; this repeats the choice for the editing canvas, and must
 * match Glance's `SizeMode.Responsive` pick exactly:
 * - a slot is a breakpoint only when the design filled it; with none filled nothing draws;
 * - two slots at one size are one breakpoint, the earlier slot's;
 * - of the breakpoints that fit the frame, the closest (squared distance between the corners) wins,
 *   ties to the smaller; when none fits, the smallest.
 */
object LauncherAdaptiveLayout {
  /**
   * The canvas adapter a catalog names for its adaptive layout, so the builder learns no catalog id
   * (`"canvas": "launcher/adaptive-layout"`); without it, a placeholder draws.
   */
  const val CANVAS_ADAPTER: String = "launcher/adaptive-layout"

  /** A slot, the property naming its grid size, and the size it has when that property is unset. */
  data class Breakpoint(
    val slot: String,
    val sizeProperty: String,
    val default: LauncherWidgetGrid.Size,
  )

  val BREAKPOINTS: List<Breakpoint> =
    listOf(
      Breakpoint("compact", "compactSize", LauncherWidgetGrid.Size(2, 1)),
      Breakpoint("medium", "mediumSize", LauncherWidgetGrid.Size(4, 1)),
      Breakpoint("expanded", "expandedSize", LauncherWidgetGrid.Size(4, 2)),
    )

  /** Slack, in dp, for a frame a fraction of a dp short of a breakpoint; the catalog's value. */
  const val FIT_TOLERANCE_DP: Float = 0.5f

  /**
   * The slot shown in a [widthDp] × [heightDp] frame.
   *
   * @param sizeLabel the design's value of a size property, or null when it set none.
   * @param filled whether the design put anything in a slot.
   */
  fun visibleSlot(
    widthDp: Float,
    heightDp: Float,
    sizeLabel: (String) -> String?,
    filled: (String) -> Boolean,
  ): String {
    val breakpoints =
      BREAKPOINTS.filter { filled(it.slot) }
        .map {
          it.slot to (sizeLabel(it.sizeProperty)?.let(LauncherWidgetGrid::parse) ?: it.default)
        }
        .distinctBy { it.second }
        .sortedWith(
          compareBy(
            { it.second.widthDp * it.second.heightDp },
            { it.second.widthDp },
            { it.second.heightDp },
          )
        )
    if (breakpoints.isEmpty()) return BREAKPOINTS.first().slot
    val fitting = breakpoints.filter {
      it.second.widthDp <= widthDp + FIT_TOLERANCE_DP &&
        it.second.heightDp <= heightDp + FIT_TOLERANCE_DP
    }
    val chosen =
      fitting.minByOrNull { (_, size) ->
        val dx = widthDp - size.widthDp
        val dy = heightDp - size.heightDp
        dx * dx + dy * dy
      } ?: breakpoints.first()
    return chosen.first
  }
}
