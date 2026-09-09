package ee.schimke.composeai.uibuilder

/**
 * A host container shape a Wear widget is drawn inside.
 *
 * The frame around a widget belongs to the **host**, not to the widget: the launcher draws it from
 * `WearWidgetParams`, and the same `WearWidgetDocument` appears inside every shape the platform
 * ships. That is why a generated file emits a `@Preview` per shape rather than picking one, and it
 * is why this is a *view* over a design rather than something a design authors — switching it asks
 * "what does this widget look like in the other frame the host might draw", which is a question
 * about the host and not an edit.
 *
 * All three shapes upstream ships. Round was left out when this enum was written, on the stated
 * grounds that its spec moves per screen diameter as well as per size — but so does the squircle's
 * (166×60dp and 200×60dp at Small; 166×96dp and 200×108dp at Large), and this editor has always
 * drawn the widest of those without anyone calling it a problem. The rule that already resolves the
 * squircle resolves the round container too, so the exclusion was answering a question the table
 * had answered from the start. See [hostSpec] for what "widest" means and why.
 *
 * @property id the wire spelling, which is what a native-render request carries and what a stored
 *   editor preference would hold. Lowercase and stable; the label is free to be re-worded.
 * @property label how the shape is named to a person, spelled the way
 *   `androidx.glance.wear.tooling.preview` spells it so a designer reading the generated `@Preview`
 *   sees the same word the editor's control used.
 */
enum class WearWidgetHostShape(val id: String, val label: String) {
  /** The host default, and the frame every shipped widget template is authored against. */
  Squircle("squircle", "Squircle"),

  /**
   * Square corners and a wider, shorter content box.
   *
   * Not a radius swap on the squircle — the content box and the padding differ on both axes — and
   * it is the render recommended as the image for the widget picker editor
   * (yschimke/compose-preview-server#587), which is why a designer needs to see their widget in it.
   */
  Rectangular("rectangular", "Rectangular"),

  /**
   * Fully round: a corner radius of 999dp, which every renderer clamps to a stadium.
   *
   * The most cramped of the three by a distance — at Large it reserves only 160×136dp of content
   * inside 35/16dp of padding, against the squircle's 200×108dp inside a uniform 8dp — because the
   * frame has to fit inside a circle rather than beside one. A widget that fills the squircle
   * comfortably is the one most likely to clip here, which is the case worth being able to look at.
   */
  Round("round", "Round");

  /** The shipped `WidgetPreviewParams` provider carrying this shape's spec at [size]. */
  fun paramsProviderFor(size: WearWidgetScaffoldSize): String =
    when (this) {
      Squircle ->
        when (size) {
          WearWidgetScaffoldSize.Small -> "SquircleSmallWidgetPreviewParams"
          WearWidgetScaffoldSize.Large -> "SquircleLargeWidgetPreviewParams"
        }
      Rectangular ->
        when (size) {
          WearWidgetScaffoldSize.Small -> "RectangularSmallWidgetPreviewParams"
          WearWidgetScaffoldSize.Large -> "RectangularLargeWidgetPreviewParams"
        }
      Round ->
        when (size) {
          WearWidgetScaffoldSize.Small -> "RoundSmallWidgetPreviewParams"
          WearWidgetScaffoldSize.Large -> "RoundLargeWidgetPreviewParams"
        }
    }

  companion object {
    /** The shape a design is drawn in when nothing has chosen one. */
    val Default: WearWidgetHostShape = Squircle

    /** The shape [id] names, or [Default] for an unknown or absent one. */
    fun fromId(id: String?): WearWidgetHostShape =
      entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: Default
  }
}

/**
 * One host container footprint: the content box, the padding around it, and the corner radius.
 *
 * All five in dp, and all five the host's. A widget cannot choose any of them — the launcher hands
 * them in as `WearWidgetParams` — so this table is a transcription of what upstream publishes
 * rather than a set of defaults anything here is free to tune.
 *
 * @property contentWidthDp the box the widget's own content is laid out in, inside the padding.
 * @property horizontalPaddingDp per edge, not the total: the frame is content plus twice this.
 */
data class WearWidgetHostSpec(
  val contentWidthDp: Int,
  val contentHeightDp: Int,
  val horizontalPaddingDp: Float,
  val verticalPaddingDp: Float,
  val cornerRadiusDp: Float,
) {
  /** The whole frame the container occupies — what a `@Preview` canvas and the editor both size. */
  val frameWidthDp: Int
    get() = (contentWidthDp + 2f * horizontalPaddingDp).toInt()

  val frameHeightDp: Int
    get() = (contentHeightDp + 2f * verticalPaddingDp).toInt()
}

/**
 * What the host draws around a widget of this size in this shape.
 *
 * The numbers are `androidx.glance.wear:wear-tooling-preview`'s own, read out of the
 * `WidgetPreviewParams` providers rather than guessed.
 *
 * ## Why one footprint per shape and size, when a provider ships two
 *
 * Most providers yield **two** entries — one per screen diameter.
 * `SquircleSmallWidgetPreviewParams` carries 166×60dp and 200×60dp; `RoundLargeWidgetPreviewParams`
 * carries 150×120dp and 160×136dp. The rectangular pair is the exception, shipping one each.
 *
 * This table takes the **widest**, which is the same choice the generated `@Preview` makes with
 * `.maxBy { it.widthDp }`, and for the same reason: a design is authored against one frame, and
 * showing it in the constrained diameter as well would be two pictures where the question is "does
 * it fit". Widest is also the honest default — the frame a design is drawn in on the largest screen
 * the shape ships for — and picking by width rather than by position means the choice does not rest
 * on the order a provider happens to yield.
 *
 * Round was excluded from this table at first, on the grounds that its spec varies per diameter. It
 * does; so does the squircle's, and this rule had already resolved that. The exclusion was wrong
 * rather than conservative, and it is gone.
 *
 * |                   | content | padding (h/v) | radius | frame   |
 * |-------------------|---------|---------------|--------|---------|
 * | Squircle Small    | 200×60  | 8 / 8         | 26     | 216×76  |
 * | Squircle Large    | 200×108 | 8 / 8         | 26     | 216×124 |
 * | Rectangular Small | 192×60  | 16 / 12       | 0      | 224×84  |
 * | Rectangular Large | 168×112 | 32 / 16       | 0      | 232×144 |
 * | Round Small       | 200×60  | 15 / 8        | 999    | 230×76  |
 * | Round Large       | 160×136 | 35 / 16       | 999    | 230×168 |
 *
 * Round Small reserves the same 200×60dp content box as the squircle and pads it wider, so the two
 * differ by frame and radius rather than by room for content. Round Large is where the shapes part
 * company: 160×136dp is the least width any container gives a widget, because that frame has to fit
 * inside a circle rather than beside one.
 *
 * ## One table, read everywhere
 *
 * The canvas used to hard-code 200×60 and 200×108 at its dispatch and keep the padding and radius
 * as two private constants; the native lane kept its own copy of the same four numbers. Two copies
 * of one spec are two ways for the picture and the render beside it to disagree, which is the
 * disagreement the Native pane exists to expose rather than to contain.
 */
fun WearWidgetScaffoldSize.hostSpec(shape: WearWidgetHostShape): WearWidgetHostSpec =
  when (shape) {
    WearWidgetHostShape.Squircle ->
      when (this) {
        WearWidgetScaffoldSize.Small ->
          WearWidgetHostSpec(200, 60, SQUIRCLE_PADDING_DP, SQUIRCLE_PADDING_DP, SQUIRCLE_RADIUS_DP)
        WearWidgetScaffoldSize.Large ->
          WearWidgetHostSpec(200, 108, SQUIRCLE_PADDING_DP, SQUIRCLE_PADDING_DP, SQUIRCLE_RADIUS_DP)
      }
    WearWidgetHostShape.Rectangular ->
      when (this) {
        WearWidgetScaffoldSize.Small -> WearWidgetHostSpec(192, 60, 16f, 12f, RECTANGULAR_RADIUS_DP)
        WearWidgetScaffoldSize.Large ->
          WearWidgetHostSpec(168, 112, 32f, 16f, RECTANGULAR_RADIUS_DP)
      }
    WearWidgetHostShape.Round ->
      when (this) {
        WearWidgetScaffoldSize.Small -> WearWidgetHostSpec(200, 60, 15f, 8f, ROUND_RADIUS_DP)
        WearWidgetScaffoldSize.Large -> WearWidgetHostSpec(160, 136, 35f, 16f, ROUND_RADIUS_DP)
      }
  }

/**
 * The padding and radius every squircle provider carries, on both axes and at both sizes.
 *
 * Public because they are also what [WearWidgetCodeExporter] refuses a design for moving: the
 * exported file may only name a shipped provider, so a design that authored its own padding or
 * radius has no provider to point a `@Preview` at. Named here so the refusal and the frame it is
 * about read the same number.
 */
const val SQUIRCLE_PADDING_DP: Float = 8f

const val SQUIRCLE_RADIUS_DP: Float = 26f

/** Square corners, which is the whole of what makes the rectangular container rectangular. */
private const val RECTANGULAR_RADIUS_DP: Float = 0f

/**
 * Upstream's own spelling for "fully round", and deliberately not `frame / 2`.
 *
 * `WidgetPreviewConstants.CORNER_RADIUS_ROUND_DP` is 999: a radius no frame can reach, left to the
 * renderer to clamp to a stadium. Copying the literal keeps this table a transcription — a widget
 * that reads its `cornerRadiusDp` off the params sees the number the host actually sends — and it
 * is the value the inspector's radius editor already had to admit, which is why its range runs well
 * past any radius a frame could use (`CapabilityCatalog`).
 */
private const val ROUND_RADIUS_DP: Float = 999f
