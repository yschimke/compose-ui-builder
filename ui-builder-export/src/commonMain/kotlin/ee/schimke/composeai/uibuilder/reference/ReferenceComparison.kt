package ee.schimke.composeai.uibuilder.reference

import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * Comparing a design with its reference, for any host.
 *
 * The editor calls this with a photograph of its own canvas; the serve host calls it with the
 * design's PNG export or native render to answer an agent. Both pass pixels as [ReferenceRaster]s
 * and layout as [ReferenceLayer]s in frame dp, so the two answer the same question the same way —
 * an agent told "move right 12 dp" and a person shown it on the canvas are reading one result.
 */

/** How the base picture sits over the frame: the overlay settings, in frame dp. */
data class ReferencePlacementSpec(
  val fit: ReferenceFit = ReferenceFit.Contain,
  val scale: Float = 1f,
  val offsetXDp: Float = 0f,
  val offsetYDp: Float = 0f,
)

/**
 * Everything one comparison needs.
 *
 * [design] is the design drawn across the frame's width at any density — its height follows from
 * its aspect. [reference] is the picture at its own size; [placement] puts it on the frame.
 * [boxMarks] are boxes an operator drew over the reference, and [layoutBoxes] an SVG's own frames
 * as fractions of the placed picture, both optional evidence for [compareLayer].
 */
class ReferenceComparisonInput(
  val design: ReferenceRaster,
  val reference: ReferenceRaster,
  val frameWidthDp: Float,
  val frameHeightDp: Float,
  val placement: ReferencePlacementSpec = ReferencePlacementSpec(),
  val facts: ReferenceFacts? = null,
  val layers: List<ReferenceLayer> = emptyList(),
  val boxMarks: List<ReferenceBox> = emptyList(),
  val layoutBoxes: List<ReferenceBox> = emptyList(),
)

/** What a comparison found, or [message] saying why it found nothing. */
data class ReferenceComparison(
  val diff: ReferenceDiffSummary? = null,
  val match: ReferenceLayerMatch? = null,
  val alignment: ReferenceAlignment? = null,
  /** The type size the alignment started from and where it was read, as a phrase. */
  val fontSizeSource: String? = null,
  val message: String? = null,
)

/** A [ReferenceDiffReport] in frame dp, each region with the layer it most likely belongs to. */
data class ReferenceDiffSummary(
  val coverage: Float,
  val mismatch: Float,
  val regions: List<ReferenceDiffRegionDp>,
)

data class ReferenceDiffRegionDp(val rect: ReferenceBox, val mismatch: Float, val nodeId: String?)

/**
 * Grid pixels to a dp for a frame this size: two where the frame is phone-sized, so a 14 sp label
 * has enough rows of ink to be measured, one for a tablet, so the grid stays under a million
 * pixels.
 */
fun referenceSamplesPerDp(frameWidthDp: Float, frameHeightDp: Float): Float =
  if (frameWidthDp * frameHeightDp <= 250_000f) 2f else 1f

/** Where the base picture sits, in frame dp. */
fun ReferenceComparisonInput.placedDp(): ReferenceBox =
  referencePlacement(
    frameWidth = frameWidthDp,
    frameHeight = frameHeightDp,
    imageWidthPx = reference.width.toFloat(),
    imageHeightPx = reference.height.toFloat(),
    scale = placement.scale,
    offsetX = placement.offsetXDp,
    offsetY = placement.offsetYDp,
    fit = placement.fit,
    facts = facts,
  )

/**
 * The design and the placed reference on one grid — placed by [referencePlacement], the rule the
 * overlay draws with, so what is measured is what a person is looking at.
 */
fun ReferenceComparisonInput.measureFrame(): ReferenceMeasureFrame {
  val samples = referenceSamplesPerDp(frameWidthDp, frameHeightDp)
  val gridWidth = (frameWidthDp * samples).roundToInt().coerceAtLeast(1)
  val gridHeight = (frameHeightDp * samples).roundToInt().coerceAtLeast(1)
  val designHeight = gridWidth.toFloat() * design.height / design.width.coerceAtLeast(1)
  val placed = placedDp()
  val target =
    ReferenceBox(
      placed.left * samples,
      placed.top * samples,
      placed.right * samples,
      placed.bottom * samples,
    )
  return ReferenceMeasureFrame(
    design = resampleOnto(design, gridWidth, gridHeight, 0f, 0f, gridWidth.toFloat(), designHeight),
    reference =
      resampleOnto(
        reference,
        gridWidth,
        gridHeight,
        target.left,
        target.top,
        target.right,
        target.bottom,
      ),
    samplesPerDp = samples,
    coverage =
      RasterRect(
          target.left.roundToInt(),
          target.top.roundToInt(),
          target.right.roundToInt(),
          target.bottom.roundToInt(),
        )
        .clampedTo(gridWidth, gridHeight),
  )
}

/**
 * Whether pixels may be compared at all under this placement; see [ReferenceFacts.pixelComparable].
 */
val ReferenceComparisonInput.comparable: Boolean
  get() = facts?.pixelComparable(placement.fit) ?: true

/** Where, across the frame, the design and the reference disagree. */
fun compareDifferences(input: ReferenceComparisonInput): ReferenceComparison {
  if (!input.comparable) {
    return ReferenceComparison(
      message =
        "Not measured: " +
          (input.facts?.advice(input.placement.fit) ?: "this picture is not placed dp for dp.")
    )
  }
  val frame = input.measureFrame()
  val report =
    diffRasters(
      frame.design,
      frame.reference,
      cellPx = (8 * frame.samplesPerDp).roundToInt(),
      coverage = frame.coverage,
    )
  return ReferenceComparison(diff = report.inDp(frame, input.layers))
}

/**
 * How the layer [nodeId] has to change to agree with the reference, from the best evidence the
 * input carries — see [matchLayer] — and the edit that would do it.
 *
 * [node] supplies the type size for a text layer; without it a text match still moves the layer but
 * proposes no font size.
 */
fun compareLayer(
  input: ReferenceComparisonInput,
  nodeId: String,
  node: UiBuilderNode?,
): ReferenceComparison {
  val layer =
    input.layers.firstOrNull { it.nodeId == nodeId }
      ?: return ReferenceComparison(
        message = "Layer `$nodeId` has no box in this render, so it cannot be matched."
      )
  val comparable = input.comparable
  val placed = input.placedDp()
  val match =
    matchLayer(
      frame = input.measureFrame(),
      layer = layer,
      boxMarks = input.boxMarks,
      layoutBoxes =
        input.layoutBoxes.map { box ->
          ReferenceBox(
            placed.left + box.left * placed.width,
            placed.top + box.top * placed.height,
            placed.left + box.right * placed.width,
            placed.top + box.bottom * placed.height,
          )
        },
      pixelSearch = comparable,
    )
      ?: return ReferenceComparison(
        message =
          if (!comparable)
            ("Draw a box round where it should be: " +
                (input.facts?.advice(input.placement.fit) ?: ""))
              .trim()
          else
            "Nothing to match: the layer is a flat colour. Draw a box round where it should be " +
              "in the reference, then match again."
      )
  val font = if (layer.text) node?.referenceFontSize() else null
  return ReferenceComparison(
    match = match,
    alignment = alignmentFor(match, font?.sp),
    fontSizeSource = font?.let { "${it.sp.spText()} sp, ${it.source}" },
    message =
      if (match.confident) null
      else
        "Low confidence: the closest place in the reference still differs. If the words or the " +
          "content differ from the mock, draw a box round the target instead.",
  )
}

/** The report in dp, each region assigned the smallest layer that contains its centre. */
fun ReferenceDiffReport.inDp(
  frame: ReferenceMeasureFrame,
  layers: List<ReferenceLayer>,
): ReferenceDiffSummary =
  ReferenceDiffSummary(
    coverage = coverage,
    mismatch = mismatch,
    regions =
      regions.map { region ->
        val rect = frame.toDp(region.rect)
        ReferenceDiffRegionDp(
          rect = rect,
          mismatch = region.mismatch,
          nodeId =
            layers
              .filter { it.bounds.contains(rect.centerX, rect.centerY) }
              .minByOrNull { it.bounds.width * it.bounds.height }
              ?.nodeId,
        )
      },
  )

/** A text layer's type size and where it was read. */
data class ReferenceFontSize(val sp: Float, val source: String)

/**
 * The type size a text node draws at, and where that number came from.
 *
 * Its own `fontSizeSp` when it sets one. Otherwise the Material 3 type scale for its `style`, or
 * `bodyLarge` — what an unstyled Material `Text` reads from `LocalTextStyle` — which is right for
 * the baseline theme and is said out loud so a design with its own type scale can see why a
 * suggestion is off.
 */
fun UiBuilderNode.referenceFontSize(): ReferenceFontSize? {
  properties[REFERENCE_FONT_SIZE_PROPERTY]
    .numberOrNull()
    ?.takeIf { it > 0f }
    ?.let {
      return ReferenceFontSize(it, "its font size")
    }
  val style = properties["style"].stringOrNull()
  val size = M3_TYPE_SCALE_SP[style ?: "bodyLarge"] ?: return null
  return ReferenceFontSize(size, "the Material ${style ?: "bodyLarge"} size")
}

/**
 * [bounds] less this node's own padding — every `padding` in its chain, which is what a renderer
 * applies outside the content — so a text node's content box is where its glyphs are laid out.
 */
fun UiBuilderNode.contentBounds(bounds: ReferenceBox): ReferenceBox {
  var start = 0f
  var top = 0f
  var end = 0f
  var bottom = 0f
  modifiers.forEach { element ->
    val modifier = element as? JsonObject ?: return@forEach
    if ((modifier["type"] as? JsonPrimitive)?.contentOrNull != "padding") return@forEach
    fun edge(name: String) = (modifier[name] as? JsonPrimitive)?.floatOrNull ?: 0f
    start += edge("startDp")
    top += edge("topDp")
    end += edge("endDp")
    bottom += edge("bottomDp")
  }
  val content =
    ReferenceBox(bounds.left + start, bounds.top + top, bounds.right - end, bounds.bottom - bottom)
  return if (content.width > 0f && content.height > 0f) content else bounds
}

private fun JsonElement?.numberOrNull(): Float? =
  when (this) {
    is JsonPrimitive -> if (isString) contentOrNull?.toFloatOrNull() else floatOrNull
    is JsonObject ->
      (get("value") as? JsonPrimitive)?.let {
        if (it.isString) it.content.toFloatOrNull() else it.floatOrNull
      }
    else -> null
  }

private fun JsonElement?.stringOrNull(): String? =
  when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonObject -> (get("value") as? JsonPrimitive)?.contentOrNull
    else -> null
  }

private fun Float.spText(): String =
  if (this == toInt().toFloat()) toInt().toString() else toString()

/** The Material 3 baseline type scale, in sp. */
private val M3_TYPE_SCALE_SP: Map<String, Float> =
  mapOf(
    "displayLarge" to 57f,
    "displayMedium" to 45f,
    "displaySmall" to 36f,
    "headlineLarge" to 32f,
    "headlineMedium" to 28f,
    "headlineSmall" to 24f,
    "titleLarge" to 22f,
    "titleMedium" to 16f,
    "titleSmall" to 14f,
    "bodyLarge" to 16f,
    "bodyMedium" to 14f,
    "bodySmall" to 12f,
    "labelLarge" to 14f,
    "labelMedium" to 12f,
    "labelSmall" to 11f,
  )
