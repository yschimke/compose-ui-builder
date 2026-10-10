package ee.schimke.composeai.uibuilder.guidelines

import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import ee.schimke.composeai.uibuilder.export.hostSpec
import ee.schimke.composeai.uibuilder.export.isWearWidget
import kotlinx.serialization.json.JsonPrimitive

/**
 * One picture a guidelines request attaches, before anything has drawn it: what kind it is, the
 * frame it is drawn in, and the environment keys the renderer is given to draw it there.
 *
 * The host draws each frame with its own renderer — compose-preview-server natively — so the plan
 * is shared and the pixels are not: the editor and the server ask about the same frames.
 */
data class DesignGuidelineFrame(
  val kind: String,
  val widthDp: Int,
  val heightDp: Int,
  /** Written over the document's `environment` before it is drawn. */
  val environment: Map<String, JsonPrimitive>,
  /** The catalog's own words for this picture; null for the built-in description of [kind]. */
  val description: String? = null,
  /**
   * The platform the design is for (`wear`, `mobile`, `glasses`, …), so the built-in description
   * only speaks of a watch, an edge button or a round screen where the design is on one.
   */
  val platform: String? = null,
  /** For an unrolled picture: whether [heightDp] is the measured content, not a multiple. */
  val coversWholeContent: Boolean = false,
) {
  /** How the user message introduces this picture, as picture [index] (1-based). */
  fun describe(index: Int): String {
    val size = "${widthDp}×${heightDp}dp"
    description?.let {
      return "Picture $index ($kind picture, $size): $it"
    }
    return when (kind) {
      DesignGuidelinePicture.DEVICE ->
        "Picture $index (device picture): the design on its own device at $size, its first " +
          "frame, scrolled to the top. On a scrolling screen, content running off the bottom " +
          "continues when the user scrolls and is not clipped." +
          (if (platform == WEAR_PLATFORM)
            " Wear keeps the edge button hidden until the list reaches its end."
          else "")
      DesignGuidelinePicture.UNROLLED ->
        if (coversWholeContent)
          "Picture $index (unrolled picture): the same design on a canvas $size, tall enough " +
            "for its whole list, so it sits at the end of the list with the edge button " +
            "revealed. Judge clipping and the end of the list here; no watch is this tall."
        else
        // A fixed multiple of the screen cannot promise the whole list: a longer one is cut by
        // the canvas edge, which is not clipping in the design.
        "Picture $index (unrolled picture): the same design on a canvas $size, several " +
            "screens tall, so a list up to that long sits at its end with the edge button " +
            "revealed. A list longer than this canvas is cut at the picture's bottom edge; that " +
            "is the canvas, not clipping in the design. Judge clipping inside components here."
      DesignGuidelinePicture.PHONE ->
        "Picture $index (phone picture): the design in a compact window, a phone in portrait at " +
          "$size."
      DesignGuidelinePicture.TABLET ->
        "Picture $index (tablet picture): the same design in an expanded window, a tablet in " +
          "landscape at $size. Judge the adaptive rules by comparing it with the phone picture."
      DesignGuidelinePicture.WIDGET_SAMSUNG ->
        // Neutral on purpose: saying the ends cut content made models report clipping that the
        // picture does not show (the live Golden Tiles Timer, 3 of 3 runs).
        "Picture $index (Samsung widget picture): the widget in the container Samsung's Wear " +
          "launcher gives it, $size with semicircular ends. Content is cut only where it " +
          "actually reaches into those ends; look before you judge."
      DesignGuidelinePicture.WIDGET_PIXEL_WATCH ->
        "Picture $index (Pixel Watch widget picture): the widget in the container the Pixel " +
          "Watch launcher gives it, $size with rounded corners. Content is cut only where it " +
          "actually reaches into those corners."
      else -> "Picture $index: the design at $size."
    }
  }
}

private const val WEAR_PLATFORM = "wear"

/** The pictures a guidelines request should attach for a design, by platform and surface. */
object DesignGuidelineFrames {
  /** A phone in portrait: the compact width class every phone layout is judged at. */
  const val PHONE_WIDTH_DP: Int = 412
  const val PHONE_HEIGHT_DP: Int = 915

  /** A tablet in landscape: well inside the expanded width class (840dp and over). */
  const val TABLET_WIDTH_DP: Int = 1280
  const val TABLET_HEIGHT_DP: Int = 800

  /** How much taller than the device the unrolled picture of a scrolling screen is drawn. */
  const val UNROLLED_HEIGHT_FACTOR: Int = 4

  /**
   * The frames for [document] on [platform].
   *
   * - A Wear widget is drawn in the two launcher containers that frame it differently: Samsung's
   *   stadium and the Pixel Watch's rounded rectangle, at the widget's size (an adaptive widget at
   *   Large, where every slot shows).
   * - A Wear screen is drawn on its device and, when [scrolls], unrolled.
   * - A phone or tablet design is drawn at a phone and a tablet size, whatever size it was authored
   *   at, because the adaptive rules are about what changes between the two.
   * - Anything else is drawn on its own device.
   */
  fun plan(
    document: UiBuilderDocument,
    platform: String?,
    scrolls: Boolean = false,
    /**
     * The scrolling content's measured height, when the host knows it; sizes the unrolled frame.
     */
    contentHeightDp: Int? = null,
  ): List<DesignGuidelineFrame> {
    val width = document.environmentInt("widthDp")
    val height = document.environmentInt("heightDp")
    val widget = widgetSize(document)
    return when {
      widget != null ->
        listOf(
            WearWidgetHostShape.Round to DesignGuidelinePicture.WIDGET_SAMSUNG,
            WearWidgetHostShape.Squircle to DesignGuidelinePicture.WIDGET_PIXEL_WATCH,
          )
          .map { (shape, kind) ->
            val spec = widget.hostSpec(shape)
            DesignGuidelineFrame(
              kind,
              spec.frameWidthDp,
              spec.frameHeightDp,
              mapOf(
                "widthDp" to JsonPrimitive(spec.frameWidthDp),
                "heightDp" to JsonPrimitive(spec.frameHeightDp),
                WearWidgetHostShape.ENVIRONMENT_KEY to JsonPrimitive(shape.id),
              ),
              platform = platform,
            )
          }
      platform == MOBILE ->
        listOf(
          sized(DesignGuidelinePicture.PHONE, PHONE_WIDTH_DP, PHONE_HEIGHT_DP, platform),
          sized(DesignGuidelinePicture.TABLET, TABLET_WIDTH_DP, TABLET_HEIGHT_DP, platform),
        )
      else ->
        listOfNotNull(
          DesignGuidelineFrame(
            DesignGuidelinePicture.DEVICE,
            width,
            height,
            emptyMap(),
            platform = platform,
          ),
          if (platform == WEAR && scrolls) unrolled(width, height, contentHeightDp, platform)
          else null,
        )
    }
  }

  private fun sized(kind: String, widthDp: Int, heightDp: Int, platform: String?) =
    DesignGuidelineFrame(
      kind,
      widthDp,
      heightDp,
      mapOf("widthDp" to JsonPrimitive(widthDp), "heightDp" to JsonPrimitive(heightDp)),
      platform = platform,
    )

  /**
   * The unrolled frame: the measured content height when the host knows it (so the picture does
   * show the whole list), otherwise [factor] screens, whose description then says a longer list is
   * cut by the canvas.
   */
  internal fun unrolled(
    width: Int,
    height: Int,
    contentHeightDp: Int?,
    platform: String?,
    factor: Int = UNROLLED_HEIGHT_FACTOR,
  ): DesignGuidelineFrame {
    val measured = contentHeightDp?.takeIf { it > 0 }?.coerceAtLeast(height)
    val tall = measured ?: (height * factor)
    return DesignGuidelineFrame(
      DesignGuidelinePicture.UNROLLED,
      width,
      tall,
      mapOf("widthDp" to JsonPrimitive(width), "heightDp" to JsonPrimitive(tall)),
      platform = platform,
      coversWholeContent = measured != null,
    )
  }

  internal fun widgetSize(document: UiBuilderDocument): WearWidgetScaffoldSize? {
    if (!document.isWearWidget()) return null
    val root = document.roots.singleOrNull()?.let(document.nodes::get) ?: return null
    if (root.componentId == AdaptiveWearWidget.COMPONENT_ID) return WearWidgetScaffoldSize.Large
    return WearWidgetScaffoldSize.entries.firstOrNull { it.componentId == root.componentId }
  }

  private fun UiBuilderDocument.environmentInt(name: String): Int =
    (environment[name] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0

  private const val WEAR = "wear"
  private const val MOBILE = "mobile"
}
