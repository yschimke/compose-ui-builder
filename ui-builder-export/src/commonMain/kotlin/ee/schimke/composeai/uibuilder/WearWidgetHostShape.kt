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
 * Two of the three upstream shapes. `Round` is deliberately absent: its spec moves the content box
 * per size *and* per screen diameter (150×120dp inside 29/16dp at one, 160×136dp inside 35/16dp at
 * the next), so a single canvas frame would have to pick a diameter and imply it was the only one.
 * Squircle and rectangular each ship one footprint per container size, which is a frame this editor
 * can draw honestly.
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
  Rectangular("rectangular", "Rectangular");

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
 * `WidgetPreviewParams` providers rather than guessed: the squircle pair is the 240dp-screen spec
 * the Wasm canvas has always drawn and `wear-m3-catalog`'s widget-container stickers hard-code, and
 * the rectangular pair is the single footprint each rectangular provider ships.
 *
 * |                   | content | padding (h/v) | radius |
 * |-------------------|---------|---------------|--------|
 * | Squircle Small    | 200×60  | 8 / 8         | 26     |
 * | Squircle Large    | 200×108 | 8 / 8         | 26     |
 * | Rectangular Small | 192×60  | 16 / 12       | 0      |
 * | Rectangular Large | 168×112 | 32 / 16       | 0      |
 *
 * One table read by both surfaces, which is the point of it existing. The canvas used to hard-code
 * 200×60 and 200×108 at its dispatch and keep the padding and radius as two private constants; the
 * native lane kept its own copy of the same four numbers. Two copies of one spec are two ways for
 * the picture and the render beside it to disagree, which is the disagreement the Native pane
 * exists to expose rather than to contain.
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
