package ee.schimke.composeai.uibuilder.export

/**
 * A host container shape a Wear widget is drawn inside.
 *
 * The frame belongs to the host, not the widget — the launcher draws it from `WearWidgetParams` —
 * so this is a view over a design rather than something it authors, and the generated file emits a
 * `@Preview` per shape. See [hostSpec] for the footprints.
 *
 * @property id the stable lowercase wire spelling, carried by native-render requests and stored
 *   preferences.
 * @property label the name shown to people, spelled as `androidx.glance.wear.tooling.preview`
 *   spells it.
 */
enum class WearWidgetHostShape(val id: String, val label: String) {
  /** The broadest rectangular host is the editing frame; previews show every shipped shape. */
  Rectangular("rectangular", "Rectangular"),

  /**
   * Square corners and a wider, shorter content box; the recommended widget-picker render
   * (yschimke/compose-preview-server#587).
   */
  Squircle("squircle", "Squircle"),

  /**
   * Fully round (radius 999dp, clamped to a stadium). The most cramped shape, so the one most
   * likely to clip.
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
    /** The unconstrained rectangular host is the editing frame when nothing has chosen one. */
    val Default: WearWidgetHostShape = Rectangular

    /** The shape [id] names, or [Default] for an unknown or absent one. */
    fun fromId(id: String?): WearWidgetHostShape =
      entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: Default

    /**
     * The `environment` key naming the host shape a surface is drawn in. Set by the editor on the
     * copy of the document each surface is sent, so pinned-runtime panes frame correctly; never
     * stored.
     */
    const val ENVIRONMENT_KEY: String = "wearWidgetHostShape"
  }
}

/**
 * One host container footprint in dp, transcribed from upstream's `WearWidgetParams`; a widget
 * cannot choose any of it.
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
 * Most providers ship two entries, one per screen diameter; this takes the **widest**, matching the
 * generated `@Preview`'s `.maxBy { it.widthDp }`, so a design is judged against one frame.
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
 * The canvas and the native lane both read this table, so the two cannot disagree.
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

/** The squircle padding and radius at both sizes. */
const val SQUIRCLE_PADDING_DP: Float = 8f

const val SQUIRCLE_RADIUS_DP: Float = 26f

private const val RECTANGULAR_RADIUS_DP: Float = 0f

/**
 * Upstream's `WidgetPreviewConstants.CORNER_RADIUS_ROUND_DP`, deliberately not `frame / 2`: the
 * renderer clamps it, and widgets read the number the host actually sends.
 */
private const val ROUND_RADIUS_DP: Float = 999f
