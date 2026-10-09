package ee.schimke.composeai.uibuilder.export

/**
 * `remote-widgets`' adaptive layout: up to three layouts, each authored at a launcher grid size, of
 * which the widget shows the one that fits it best (remote-m3-catalog's `AdaptiveLayout`).
 *
 * The widget makes that choice itself, during playback, through a `RemoteStateLayout`; the export
 * writes a plain call to the catalog's composable and nothing here takes part. This is the same
 * choice made for the editing canvas, so the slot drawn for a frame is the slot the widget shows at
 * that size — and the resizable launcher pane switches layout as it is dragged. It must stay
 * exactly the catalog's rule, which is Glance's `SizeMode.Responsive` pick:
 * - a slot is a breakpoint only when the design filled it — an omitted argument keeps the catalog's
 *   no-layout default — and a layout with none filled draws nothing;
 * - two slots at one size are one breakpoint, the earlier slot's;
 * - of the breakpoints that fit the frame, the closest (squared distance between the corners) wins,
 *   ties to the smaller; when none fits, the smallest.
 */
object LauncherAdaptiveLayout {
  const val COMPONENT_ID: String = "remote-widgets/adaptive-layout"

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
