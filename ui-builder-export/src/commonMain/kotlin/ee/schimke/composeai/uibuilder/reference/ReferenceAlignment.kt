package ee.schimke.composeai.uibuilder.reference

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Two pictures of one frame on one grid: the design as rendered and the reference as placed.
 * [samplesPerDp] grid pixels per dp. Built by `measureReference`, or by hand in tests; converts
 * between frame dp and grid pixels in exactly one place each way.
 */
class ReferenceMeasureFrame(
  val design: ReferenceRaster,
  val reference: ReferenceRaster,
  val samplesPerDp: Float,
  /** The grid rectangle the reference is placed over; nothing outside it is compared. */
  val coverage: RasterRect = RasterRect(0, 0, reference.width, reference.height),
) {
  init {
    require(design.width == reference.width && design.height == reference.height) {
      "the design and the reference must share a grid"
    }
  }

  fun toGrid(dp: ReferenceBox): RasterRect =
    RasterRect(
      floor(dp.left * samplesPerDp).toInt(),
      floor(dp.top * samplesPerDp).toInt(),
      ceil(dp.right * samplesPerDp).toInt(),
      ceil(dp.bottom * samplesPerDp).toInt(),
    )

  fun toDp(grid: RasterRect): ReferenceBox =
    ReferenceBox(
      grid.left / samplesPerDp,
      grid.top / samplesPerDp,
      grid.right / samplesPerDp,
      grid.bottom / samplesPerDp,
    )
}

/** Where a match's target came from — which is also how far to trust it. */
enum class ReferenceMatchSource(val label: String) {
  /** A box the operator drew round the thing in the reference. Their word, so always trusted. */
  BoxMark("the box you drew"),
  /** A frame the imported SVG was laid out with. */
  LayoutBox("the SVG's layout box"),
  /** A search of the reference's pixels for the layer's own picture. */
  Pixels("the reference's pixels"),
}

/** One layer, as the canvas laid it out: what [matchLayer] needs to know about it. */
data class ReferenceLayer(
  val nodeId: String,
  /** The node's box in frame dp. */
  val bounds: ReferenceBox,
  /** Whether the node draws text, which is compared by its ink and resized by its font. */
  val text: Boolean,
  /**
   * The box inside the node's own padding, which is what grows with its type. Padding does not
   * scale with a font size, so a move that scaled it would overshoot by the padding's share.
   */
  val content: ReferenceBox = bounds,
)

/**
 * A layer lined up against the reference. [current] and [target] are the same kind of box (ink or
 * layout bounds), so their difference is the correction; [scale] is the implied text scale (else
 * 1).
 */
data class ReferenceLayerMatch(
  val nodeId: String,
  val source: ReferenceMatchSource,
  val current: ReferenceBox,
  val target: ReferenceBox,
  /** The node's content box — inside its padding — which the move is anchored to. */
  val bounds: ReferenceBox,
  val scale: Float,
  val confident: Boolean,
  val text: Boolean,
)

/**
 * Line [layer] up against the reference, from the best evidence available, in order of trust:
 *
 * 1. A box the operator drew overlapping the layer (narrowed to the ink inside it, for text).
 * 2. An SVG layout box that plainly matches (Figma exports one per frame).
 * 3. The pixels: a search near the layer's position, at a range of scales for text. This can fail,
 *    so it reports whether it is [confident][ReferenceLayerMatch.confident].
 *
 * Null when there is no box, no layout box and no texture in the layer.
 */
fun matchLayer(
  frame: ReferenceMeasureFrame,
  layer: ReferenceLayer,
  boxMarks: List<ReferenceBox>,
  layoutBoxes: List<ReferenceBox>,
  /**
   * Whether the reference's pixels may be searched; false when the picture is not placed dp for dp
   * ([ReferenceFacts.pixelComparable]), since a stretched search would misread the size.
   */
  pixelSearch: Boolean = true,
): ReferenceLayerMatch? {
  val bounds = layer.bounds
  val nodeGrid = frame.toGrid(bounds)
  val currentInk =
    if (layer.text) inkBox(frame.design, nodeGrid)?.let(frame::toDp) ?: bounds else bounds

  boxMarks
    .filter { it.overlaps(bounds) }
    .maxByOrNull { it.overlapRatio(bounds) }
    ?.let { mark ->
      val target =
        if (layer.text) inkBox(frame.reference, frame.toGrid(mark))?.let(frame::toDp) ?: mark
        else mark
      return ReferenceLayerMatch(
        nodeId = layer.nodeId,
        source = ReferenceMatchSource.BoxMark,
        current = currentInk,
        target = target,
        bounds = layer.content,
        scale = if (layer.text) safeRatio(target.height, currentInk.height) else 1f,
        confident = true,
        text = layer.text,
      )
    }

  layoutBoxes
    .map { it to it.overlapRatio(bounds) }
    .filter { it.second >= LAYOUT_BOX_MIN_OVERLAP }
    .maxByOrNull { it.second }
    ?.let { (box, _) ->
      return ReferenceLayerMatch(
        nodeId = layer.nodeId,
        source = ReferenceMatchSource.LayoutBox,
        current = bounds,
        target = box,
        bounds = bounds,
        scale = if (layer.text) safeRatio(box.height, bounds.height) else 1f,
        confident = true,
        text = layer.text,
      )
    }

  if (!pixelSearch) return null
  // Text is searched for by its ink, so the line-height padding a mock does not draw cannot pull
  // the answer up or down; anything else by its whole box, background and all.
  val patch = if (layer.text) frame.toGrid(currentInk) else nodeGrid
  val searchDp = (min(bounds.width, bounds.height) * 0.5f).coerceIn(MIN_SEARCH_DP, MAX_SEARCH_DP)
  val found =
    matchPatch(
      design = frame.design,
      reference = frame.reference,
      rect = patch,
      searchPx = (searchDp * frame.samplesPerDp).roundToInt(),
      scales = if (layer.text) TEXT_SCALES else listOf(1f),
      coverage = frame.coverage,
    ) ?: return null
  val current = frame.toDp(patch)
  val dx = found.dx / frame.samplesPerDp
  val dy = found.dy / frame.samplesPerDp
  return ReferenceLayerMatch(
    nodeId = layer.nodeId,
    source = ReferenceMatchSource.Pixels,
    current = current,
    target =
      ReferenceBox(
        current.left + dx,
        current.top + dy,
        current.left + dx + current.width * found.scale,
        current.top + dy + current.height * found.scale,
      ),
    bounds = layer.content,
    scale = found.scale,
    confident = found.confident,
    text = layer.text,
  )
}

/**
 * The edit that would make a layer agree with its match: whole dp for position and size, half sp
 * for type (Material's precision). Corrections smaller than the rounding are omitted.
 */
data class ReferenceAlignment(
  val nodeId: String,
  val moveXDp: Int = 0,
  val moveYDp: Int = 0,
  val fontSizeSp: Float? = null,
  val widthDp: Int? = null,
  val heightDp: Int? = null,
) {
  val empty: Boolean
    get() =
      moveXDp == 0 && moveYDp == 0 && fontSizeSp == null && widthDp == null && heightDp == null

  /** What it would do, one clause per change, for the panel. */
  fun describe(): List<String> = buildList {
    if (moveXDp != 0) add("move ${if (moveXDp > 0) "right" else "left"} ${abs(moveXDp)} dp")
    if (moveYDp != 0) add("move ${if (moveYDp > 0) "down" else "up"} ${abs(moveYDp)} dp")
    fontSizeSp?.let { add("font size ${it.spLabel()} sp") }
    widthDp?.let { add("width $it dp") }
    heightDp?.let { add("height $it dp") }
  }
}

/**
 * The correction for [match], given the layer's current font size (null if unknown or not text).
 * Anchored to the node's top-left, accounting for the ink moving as the font grows.
 */
fun alignmentFor(match: ReferenceLayerMatch, currentFontSizeSp: Float?): ReferenceAlignment {
  val scaleText =
    match.text && currentFontSizeSp != null && abs(match.scale - 1f) >= MIN_FONT_SCALE_CHANGE
  val scale = if (scaleText) match.scale else 1f
  val anchor = match.bounds
  val landedLeft = anchor.left + (match.current.left - anchor.left) * scale
  val landedTop = anchor.top + (match.current.top - anchor.top) * scale
  val fontSize =
    if (scaleText && currentFontSizeSp != null) {
      ((currentFontSizeSp * scale) * 2f).roundToInt() / 2f
    } else null
  // Only a source that states a box can state a size: a pixel search finds where something is,
  // and a layer that looks the same elsewhere has not been measured for width.
  val sized = !match.text && match.source != ReferenceMatchSource.Pixels
  return ReferenceAlignment(
    nodeId = match.nodeId,
    moveXDp = (match.target.left - landedLeft).wholeDp(),
    moveYDp = (match.target.top - landedTop).wholeDp(),
    fontSizeSp = fontSize?.takeIf { it != currentFontSizeSp && it > 0f },
    widthDp =
      if (sized && abs(match.target.width - match.current.width) >= MIN_SIZE_CHANGE_DP)
        match.target.width.roundToInt().coerceAtLeast(1)
      else null,
    heightDp =
      if (sized && abs(match.target.height - match.current.height) >= MIN_SIZE_CHANGE_DP)
        match.target.height.roundToInt().coerceAtLeast(1)
      else null,
  )
}

/** A move under three quarters of a dp rounds to nothing, and is nothing. */
private fun Float.wholeDp(): Int = if (abs(this) < 0.75f) 0 else roundToInt()

private fun safeRatio(target: Float, current: Float): Float =
  if (current <= 0f || target <= 0f) 1f else (target / current).coerceIn(0.25f, 4f)

private fun Float.spLabel(): String =
  if (this == this.toInt().toFloat()) this.toInt().toString() else toString()

/** Overlap (intersection over union) at which an SVG box is taken to be the same thing. */
private const val LAYOUT_BOX_MIN_OVERLAP = 0.5f

private const val MIN_SEARCH_DP = 8f
private const val MAX_SEARCH_DP = 32f
private const val MIN_SIZE_CHANGE_DP = 1.5f

/** Type within 3% of the mock is the mock: one step of anti-aliasing, not a different size. */
private const val MIN_FONT_SCALE_CHANGE = 0.03f

/** 0.7× to 1.45×: from a body label down to a caption, up to a title, and nothing sillier. */
private val TEXT_SCALES: List<Float> = (0..15).map { 0.7f + it * 0.05f }
