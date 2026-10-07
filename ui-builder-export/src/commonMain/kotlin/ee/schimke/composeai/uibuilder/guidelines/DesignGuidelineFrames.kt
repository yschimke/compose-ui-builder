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
) {
  /** How the user message introduces this picture, as picture [index] (1-based). */
  fun describe(index: Int): String {
    val size = "${widthDp}×${heightDp}dp"
    return when (kind) {
      DesignGuidelinePicture.DEVICE ->
        "Picture $index (device picture): the design on its own device at $size, its first " +
          "frame, scrolled to the top. On a scrolling screen, content running off the bottom " +
          "continues when the user scrolls and is not clipped, and Wear keeps the edge button " +
          "hidden until the list reaches its end."
      DesignGuidelinePicture.UNROLLED ->
        "Picture $index (unrolled picture): the same design on a canvas $size, tall enough for " +
          "its whole list, so it sits at the end of the list with the edge button revealed. " +
          "Judge clipping and the end of the list here; no watch is this tall."
      DesignGuidelinePicture.PHONE ->
        "Picture $index (phone picture): the design in a compact window, a phone in portrait at " +
          "$size."
      DesignGuidelinePicture.TABLET ->
        "Picture $index (tablet picture): the same design in an expanded window, a tablet in " +
          "landscape at $size. Judge the adaptive rules by comparing it with the phone picture."
      DesignGuidelinePicture.WIDGET_SAMSUNG ->
        "Picture $index (Samsung widget picture): the widget in the container Samsung's Wear " +
          "launcher gives it, $size with fully rounded ends, so content near the left and right " +
          "edges is cut by the curve."
      DesignGuidelinePicture.WIDGET_PIXEL_WATCH ->
        "Picture $index (Pixel Watch widget picture): the widget in the container the Pixel " +
          "Watch launcher gives it, $size with rounded corners."
      else -> "Picture $index: the design at $size."
    }
  }
}

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
            )
          }
      platform == MOBILE ->
        listOf(
          sized(DesignGuidelinePicture.PHONE, PHONE_WIDTH_DP, PHONE_HEIGHT_DP),
          sized(DesignGuidelinePicture.TABLET, TABLET_WIDTH_DP, TABLET_HEIGHT_DP),
        )
      else ->
        listOfNotNull(
          DesignGuidelineFrame(DesignGuidelinePicture.DEVICE, width, height, emptyMap()),
          if (platform == WEAR && scrolls)
            sized(DesignGuidelinePicture.UNROLLED, width, height * UNROLLED_HEIGHT_FACTOR)
          else null,
        )
    }
  }

  private fun sized(kind: String, widthDp: Int, heightDp: Int) =
    DesignGuidelineFrame(
      kind,
      widthDp,
      heightDp,
      mapOf("widthDp" to JsonPrimitive(widthDp), "heightDp" to JsonPrimitive(heightDp)),
    )

  private fun widgetSize(document: UiBuilderDocument): WearWidgetScaffoldSize? {
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
