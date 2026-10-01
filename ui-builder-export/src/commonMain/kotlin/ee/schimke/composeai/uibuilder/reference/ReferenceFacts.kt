package ee.schimke.composeai.uibuilder.reference

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What an attached picture *is*, measured against the frame it is being compared with.
 *
 * A picture arrives as pixels with no unit. Before any comparison means anything the editor has to
 * answer three questions about it, and this is where they are answered once so the panel, the
 * fitting and the matcher cannot answer them differently:
 *
 * 1. **At what density was it drawn?** A Figma export at 2× and a Pixel screenshot at 2.625× of the
 *    same 411 dp screen are 822 and 1080 pixels wide. Said by the picture where it can say it (an
 *    `@2x` file name, a Figma export scale), otherwise inferred from the frame.
 * 2. **How big is it in dp?** Pixels over density — the size the operator actually reasons in.
 * 3. **Is it the screen, or a piece of one?** A full-screen mock compares against the whole frame;
 *    a 328 × 56 dp button crop compared against the whole frame is a picture stretched over the
 *    wrong thing, and every pixel of the difference it reports is noise.
 *
 * Pure and platform-free: the inputs are two sizes and a name.
 */
data class ReferenceFacts(
  val widthPx: Int,
  val heightPx: Int,
  /** The frame the comparison is against, in dp. */
  val frameWidthDp: Float,
  val frameHeightDp: Float,
  /** The density the picture says it was drawn at — `@2x`, a Figma export scale — or null. */
  val declaredDensity: Float?,
  /** The density the design itself renders at (its environment), for "same device" detection. */
  val designDensity: Float,
) {
  val known: Boolean
    get() = widthPx > 0 && heightPx > 0 && frameWidthDp > 0f && frameHeightDp > 0f

  /** Picture aspect over frame aspect; 1 means the same shape. */
  val aspectRatioDelta: Float
    get() = if (!known) 1f else (widthPx.toFloat() / heightPx) / (frameWidthDp / frameHeightDp)

  /** Whether the picture is the frame's shape, within what rounding to whole pixels explains. */
  val sameShape: Boolean
    get() = known && abs(aspectRatioDelta - 1f) <= SAME_SHAPE_TOLERANCE

  /**
   * The density this picture is read at.
   *
   * Declared wins: a picture that says it is 2× is 2×, whatever the frame. Otherwise a picture of
   * the frame's shape, or taller (a scrolled capture, or one with system bars), is the frame drawn
   * at `widthPx / frameWidthDp` — screens are captured edge to edge across their width. Anything
   * else — a crop of no stated scale — is assumed to be at the design's own density, which is what
   * a screenshot of a component on the target device is.
   */
  val density: Float
    get() =
      declaredDensity
        ?: when {
          !known -> 1f
          screenShaped -> snapDensity(widthPx / frameWidthDp)
          else -> designDensity.takeIf { it > 0f } ?: 1f
        }

  /** Where [density] came from, in words for the panel. */
  val densitySource: String
    get() =
      when {
        declaredDensity != null -> "declared"
        !known -> "unknown"
        screenShaped -> "inferred from the frame width"
        else -> "assumed the design's"
      }

  /** The frame's shape, or narrower for its height: a screen read across its width. */
  private val screenShaped: Boolean
    get() =
      known &&
        aspectRatioDelta <= 1f + SAME_SHAPE_TOLERANCE &&
        // A narrow strip is also "taller for its width", and reading it across the frame's width
        // would call a 100 px sidebar a screen at a quarter density. Only a density a device has.
        (widthPx / frameWidthDp) in PLAUSIBLE_SCREEN_DENSITY

  val widthDp: Float
    get() = widthPx / density

  val heightDp: Float
    get() = heightPx / density

  /** The Android bucket [density] falls in, or null for one between buckets. */
  val densityBucket: String?
    get() = DENSITY_BUCKETS.firstOrNull { abs(it.first - density) < 0.01f }?.second

  val kind: ReferenceKind
    get() =
      when {
        !known -> ReferenceKind.Unknown
        sameShape && declaredDensity == null -> ReferenceKind.Screen
        // Declared density: a screen only if it really covers the frame at that density.
        sameShape && abs(widthDp - frameWidthDp) <= frameWidthDp * SAME_SHAPE_TOLERANCE ->
          ReferenceKind.Screen
        // As wide as the frame and taller: a screen with more in it than the frame shows.
        abs(widthDp - frameWidthDp) <= frameWidthDp * SAME_SHAPE_TOLERANCE &&
          heightDp > frameHeightDp -> ReferenceKind.TallScreen
        widthDp <= frameWidthDp * 1.02f && heightDp <= frameHeightDp * 1.02f -> ReferenceKind.Region
        else -> ReferenceKind.Mismatched
      }

  /** Whether the picture was drawn for the same device the design renders at. */
  val sameDeviceDensity: Boolean
    get() = abs(density - designDensity) < 0.01f

  /**
   * The fit that lines this picture up with the frame dp for dp, or null when no fit can.
   *
   * [ReferenceFit.Contain] is the old default and stays right for an exact screen. A tall screen
   * has to be fitted across its width and pinned to the top, or contain shrinks it until nothing
   * lines up; a region is only comparable at its actual size.
   */
  val recommendedFit: ReferenceFit?
    get() =
      when (kind) {
        ReferenceKind.Screen -> ReferenceFit.Contain
        ReferenceKind.TallScreen -> ReferenceFit.Width
        ReferenceKind.Region -> ReferenceFit.Actual
        ReferenceKind.Mismatched,
        ReferenceKind.Unknown -> null
      }

  /**
   * Whether pixel comparison — Difference mode, the measured diff, matching a layer — can answer
   * anything under [fit].
   *
   * Overlay and Split always can: a person can judge a translucent picture at any scale. A
   * per-pixel subtraction can only when one dp of the picture lands on one dp of the frame, which
   * is the fit's job — and which no fit achieves for a picture of a different shape.
   */
  fun pixelComparable(fit: ReferenceFit): Boolean =
    when (kind) {
      ReferenceKind.Screen -> true
      ReferenceKind.TallScreen -> fit != ReferenceFit.Contain
      ReferenceKind.Region -> fit == ReferenceFit.Actual
      ReferenceKind.Mismatched,
      ReferenceKind.Unknown -> false
    }

  /** One sentence on whether and how to compare, for the panel. */
  fun advice(fit: ReferenceFit): String =
    when (kind) {
      ReferenceKind.Unknown ->
        "The picture has no pixel size this editor could read, so only Overlay is meaningful."
      ReferenceKind.Screen ->
        if (sameDeviceDensity)
          "The same screen at the design's own density: every comparison holds."
        else
          "The same shape as the frame. Pixel comparisons hold; text drawn at " +
            "${density.label()}× may anti-alias differently from the canvas."
      ReferenceKind.TallScreen ->
        if (fit == ReferenceFit.Contain)
          "Taller than the frame by ${(heightDp - frameHeightDp).roundToInt()} dp — likely system " +
            "bars or scrolled content. Fit it to the width to compare."
        else
          "Fitted to the width and pinned to the top; the ${(heightDp - frameHeightDp).roundToInt()}" +
            " dp below the frame is not compared."
      ReferenceKind.Region ->
        if (fit == ReferenceFit.Actual)
          "A ${widthDp.roundToInt()} × ${heightDp.roundToInt()} dp piece, at its actual size. " +
            "Nudge it over the part it shows, or match a layer against it."
        else
          "A ${widthDp.roundToInt()} × ${heightDp.roundToInt()} dp piece, not a screen: stretched " +
            "over the frame every pixel differs. Show it at actual size to compare."
      ReferenceKind.Mismatched ->
        "A different shape from the frame (${aspectRatioDelta.percentDelta()}) and bigger than " +
          "it: compare by eye with Overlay. Pixel differences would be noise."
    }

  companion object {
    /** Rounding a frame to whole pixels at 4× moves its aspect by well under one percent. */
    const val SAME_SHAPE_TOLERANCE: Float = 0.015f

    private val PLAUSIBLE_SCREEN_DENSITY = 0.74f..4.5f

    /**
     * The densities Android ships, and Figma's export scales, with the name each is known by.
     *
     * Snapping to one is what turns "2.6247" into the 2.625 a Pixel actually is; a value nowhere
     * near one is left as measured rather than forced onto the nearest.
     */
    val DENSITY_BUCKETS: List<Pair<Float, String>> =
      listOf(
        0.75f to "ldpi",
        1f to "mdpi",
        1.5f to "hdpi",
        2f to "xhdpi",
        2.625f to "420dpi",
        2.75f to "440dpi",
        3f to "xxhdpi",
        3.5f to "560dpi",
        4f to "xxxhdpi",
      )

    fun snapDensity(measured: Float): Float {
      if (!measured.isFinite() || measured <= 0f) return 1f
      val nearest = DENSITY_BUCKETS.minBy { abs(it.first - measured) }.first
      return if (abs(nearest - measured) <= nearest * 0.02f) nearest
      else (measured * 1000f).roundToInt() / 1000f
    }
  }
}

/** What kind of picture a reference is, relative to the frame. See [ReferenceFacts.kind]. */
enum class ReferenceKind(val label: String) {
  Screen("Screen"),
  TallScreen("Tall screen"),
  Region("Region"),
  Mismatched("Different shape"),
  Unknown("Unknown size"),
}

/**
 * How the base picture is placed in the frame before scale and nudge.
 *
 * [Contain] is the historic behaviour and the default — the whole picture visible, centred. [Width]
 * fits the picture's width to the frame and pins its top edge, which is how a tall capture lines up
 * with a screen. [Actual] draws it at its own size in dp, at the density [ReferenceFacts] reads it
 * at, from the top-left — which is the only placement under which a cropped component's pixels mean
 * anything against the canvas.
 */
enum class ReferenceFit(val wireValue: String, val label: String) {
  Contain("contain", "Fit"),
  Width("width", "Fit width"),
  Actual("actual", "Actual size");

  companion object {
    fun ofWire(value: String?): ReferenceFit =
      entries.firstOrNull { it.wireValue == value } ?: Contain
  }
}

/**
 * The density a picture's own name declares, or null.
 *
 * `card@2x.png` and `Frame 12 @3x.png` are how every design tool and every iOS asset catalog has
 * spelled export scale for a decade, so it is worth reading. Only the conventional suffix, right
 * before the extension, is honoured: a `2x` in the middle of a name is a name.
 */
fun declaredDensityFromName(name: String): Float? {
  val stem = name.substringBeforeLast('.').trimEnd()
  val match = DECLARED_DENSITY.find(stem) ?: return null
  return match.groupValues[1].toFloatOrNull()?.takeIf { it > 0f && it <= 8f }
}

private val DECLARED_DENSITY = Regex("""@\s?(\d+(?:\.\d+)?)x$""", RegexOption.IGNORE_CASE)

private fun Float.label(): String {
  val rounded = (this * 1000f).roundToInt() / 1000f
  return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString()
  else rounded.toString()
}

private fun Float.percentDelta(): String {
  val percent = ((this - 1f) * 100f).roundToInt()
  return if (percent >= 0) "+$percent% wider" else "${-percent}% narrower"
}
