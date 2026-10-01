package ee.schimke.composeai.uibuilder.reference

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/** A bitmap's pixels as a [ReferenceRaster]. */
internal fun ImageBitmap.toReferenceRaster(): ReferenceRaster {
  val buffer = IntArray(width * height)
  readPixels(buffer)
  return ReferenceRaster(width, height, buffer)
}

/**
 * Grid pixels to a dp for a frame this size: two where the frame is phone-sized, so a 14 sp label
 * has enough rows of ink to be measured, one for a tablet, so the grid stays under a million
 * pixels.
 */
internal fun measureSamplesPerDp(frameWidthDp: Float, frameHeightDp: Float): Float =
  if (frameWidthDp * frameHeightDp <= 250_000f) 2f else 1f

/**
 * The design and the base reference on one grid, the reference placed exactly as the overlay places
 * it — the same [referenceTargetRect], the same fit, scale and nudge — so that what is measured is
 * what the operator is looking at.
 */
internal fun buildMeasureFrame(
  design: ImageBitmap,
  reference: ImageBitmap,
  settings: ReferenceOverlaySettings,
  facts: ReferenceFacts?,
  frameWidthDp: Float,
  frameHeightDp: Float,
): ReferenceMeasureFrame {
  val samples = measureSamplesPerDp(frameWidthDp, frameHeightDp)
  val gridWidth = (frameWidthDp * samples).roundToInt().coerceAtLeast(1)
  val gridHeight = (frameHeightDp * samples).roundToInt().coerceAtLeast(1)
  val designRaster = design.toReferenceRaster()
  // The capture is the frame at its width; its height is whatever the frame was when captured.
  val designHeight = gridWidth.toFloat() * design.height / design.width.coerceAtLeast(1)
  val target =
    referenceTargetRect(
      frame = Size(gridWidth.toFloat(), gridHeight.toFloat()),
      imageWidthPx = reference.width.toFloat(),
      imageHeightPx = reference.height.toFloat(),
      scale = settings.scale,
      offsetXPx = settings.offsetXDp * samples,
      offsetYPx = settings.offsetYDp * samples,
      fit = settings.fit,
      facts = facts,
    )
  return ReferenceMeasureFrame(
    design =
      resampleOnto(designRaster, gridWidth, gridHeight, 0f, 0f, gridWidth.toFloat(), designHeight),
    reference =
      resampleOnto(
        reference.toReferenceRaster(),
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

/** Where the base reference sits in frame dp, for turning its SVG layout boxes into dp. */
internal fun referenceTargetDp(
  reference: ReferenceOverlayState,
  bitmap: ImageBitmap,
  facts: ReferenceFacts?,
  frameWidthDp: Float,
  frameHeightDp: Float,
): Rect =
  referenceTargetRect(
    frame = Size(frameWidthDp, frameHeightDp),
    imageWidthPx = bitmap.width.toFloat(),
    imageHeightPx = bitmap.height.toFloat(),
    scale = reference.settings.scale,
    offsetXPx = reference.settings.offsetXDp,
    offsetYPx = reference.settings.offsetYDp,
    fit = reference.settings.fit,
    facts = facts,
  )

/**
 * What the last measurement found, in frame dp, for the panel to list and the overlay to outline.
 *
 * Tied to the [documentRevision] and [referenceId] it was measured at: a finding about a design
 * that has since been edited is a finding about a different design, and the panel says so rather
 * than outlining regions that may have been fixed.
 */
data class ReferenceFindings(
  val documentRevision: Int,
  val referenceId: String?,
  val frameWidthDp: Float,
  val diff: ReferenceDiffSummary? = null,
  val match: ReferenceLayerMatch? = null,
  val alignment: ReferenceAlignment? = null,
  /** The font size the alignment was computed from, and where it was read. */
  val fontSizeSource: String? = null,
  /** A sentence instead of a result: why nothing could be measured. */
  val message: String? = null,
)

/** A [ReferenceDiffReport] in dp, each region with the layer it most likely belongs to. */
data class ReferenceDiffSummary(
  val coverage: Float,
  val mismatch: Float,
  val regions: List<ReferenceDiffRegionDp>,
)

data class ReferenceDiffRegionDp(val rect: Rect, val mismatch: Float, val nodeId: String?)

/** The report in dp, each region assigned the smallest layer that contains its centre. */
internal fun ReferenceDiffReport.inDp(
  frame: ReferenceMeasureFrame,
  layers: List<ReferenceLayer>,
): ReferenceDiffSummary =
  ReferenceDiffSummary(
    coverage = coverage,
    mismatch = mismatch,
    regions =
      regions.map { region ->
        val rect = frame.toDp(region.rect)
        val centre = rect.center
        ReferenceDiffRegionDp(
          rect = rect,
          mismatch = region.mismatch,
          nodeId =
            layers
              .filter { it.bounds.contains(centre) }
              .minByOrNull { it.bounds.width * it.bounds.height }
              ?.nodeId,
        )
      },
  )

/** The outlines of [findings] over the frame: differing regions, and a match's two boxes. */
internal fun DrawScope.drawFindings(findings: ReferenceFindings) {
  if (findings.frameWidthDp <= 0f) return
  val perDp = size.width / findings.frameWidthDp
  fun Rect.px() = Rect(left * perDp, top * perDp, right * perDp, bottom * perDp)
  val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
  findings.diff?.regions?.forEach { region ->
    val r = region.rect.px()
    drawRect(FINDING_REGION.copy(alpha = 0.12f), r.topLeft, r.size)
    drawRect(FINDING_REGION, r.topLeft, r.size, style = Stroke(width = 1.5f, pathEffect = dash))
  }
  findings.match?.let { match ->
    val current = match.current.px()
    val target = match.target.px()
    drawRect(FINDING_CURRENT, current.topLeft, current.size, style = Stroke(width = 1.5f))
    drawRect(
      if (match.confident) FINDING_TARGET else FINDING_REGION,
      target.topLeft,
      target.size,
      style = Stroke(width = 2f, pathEffect = dash),
    )
    drawLine(FINDING_TARGET, current.topLeft, target.topLeft, strokeWidth = 1.5f)
    drawCircle(FINDING_TARGET, radius = 3f, center = target.topLeft)
  }
}

private val FINDING_REGION = Color(0xFFFF6D00)
private val FINDING_CURRENT = Color(0xFF40C4FF)
private val FINDING_TARGET = Color(0xFF00E676)

/**
 * The type size a text node draws at, and where that number came from.
 *
 * Its own `fontSizeSp` when it sets one. Otherwise the Material 3 type scale for its `style`, or
 * `bodyLarge` — what an unstyled Material `Text` reads from `LocalTextStyle` — which is right for
 * the baseline theme and is said out loud so a design with its own type scale can see why a
 * suggestion is off.
 */
internal fun UiBuilderNode.referenceFontSize(): Pair<Float, String>? {
  (properties["fontSizeSp"])
    .numberOrNull()
    ?.takeIf { it > 0f }
    ?.let {
      return it to "its font size"
    }
  val style = (properties["style"]).stringOrNull()
  val size = M3_TYPE_SCALE_SP[style ?: "bodyLarge"] ?: return null
  return size to "the Material ${style ?: "bodyLarge"} size"
}

private fun kotlinx.serialization.json.JsonElement?.numberOrNull(): Float? =
  when (this) {
    is JsonPrimitive -> if (isString) contentOrNull?.toFloatOrNull() else floatOrNull
    is JsonObject ->
      (get("value") as? JsonPrimitive)?.let {
        if (it.isString) it.content.toFloatOrNull() else it.floatOrNull
      }
    else -> null
  }

private fun kotlinx.serialization.json.JsonElement?.stringOrNull(): String? =
  when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonObject -> (get("value") as? JsonPrimitive)?.contentOrNull
    else -> null
  }

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

/** One photograph of the whole design; a new [sequence] is a new photograph. */
internal data class DesignCaptureRequest(val sequence: Int, val widthDp: Float, val heightDp: Float)

/**
 * Draws the whole design off screen at the frame's size and hands back its pixels — the "design"
 * half of every measurement.
 *
 * The same renderer, theme and catalog the canvas draws with, because it is mounted where
 * [NodeCapture] is and for the same reason: a measurement of a picture the canvas does not show is
 * a measurement of nothing the operator can fix. No editor overlay, no selection, no reference —
 * those are what is being measured *against*, not part of it.
 */
@Composable
internal fun DesignCapture(
  request: DesignCaptureRequest?,
  document: UiBuilderDocument,
  onCaptured: (DesignCaptureRequest, ImageBitmap?) -> Unit,
) {
  if (request == null) return
  val layer = rememberGraphicsLayer()
  Box(
    Modifier.wrapContentSize(unbounded = true, align = Alignment.TopStart)
      .requiredSize(request.widthDp.dp, request.heightDp.dp)
      .drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
      }
  ) {
    UiBuilderSurface(document = document, editorOverlay = false)
  }
  LaunchedEffect(request) {
    repeat(2) { withFrameNanos {} }
    val bitmap =
      try {
        layer.toImageBitmap()
      } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        null
      }
    onCaptured(request, bitmap)
  }
}

/** What the operator asked the measurement for. */
internal sealed interface ReferenceMeasureKind {
  /** Where, across the whole frame, the design and the reference disagree. */
  data object Differences : ReferenceMeasureKind

  /** How one layer has to change to agree with the reference. */
  data class Layer(val nodeId: String) : ReferenceMeasureKind
}

/**
 * Everything a measurement needs, gathered on the UI thread so the arithmetic can leave it.
 *
 * [layers] are the canvas's own layout, already in frame dp; [nodes] give each layer's component
 * and properties, for its type size.
 */
internal class ReferenceMeasureInput(
  val kind: ReferenceMeasureKind,
  val design: ImageBitmap,
  val referenceBitmap: ImageBitmap,
  val reference: ReferenceOverlayState,
  val facts: ReferenceFacts?,
  val frameWidthDp: Float,
  val frameHeightDp: Float,
  val layers: List<ReferenceLayer>,
  val nodes: Map<String, UiBuilderNode>,
  val documentRevision: Int,
)

/**
 * Run one measurement. Pure apart from reading pixels, and slow enough (tens of milliseconds) that
 * the editor calls it off the main dispatcher.
 */
internal fun measureAgainstReference(input: ReferenceMeasureInput): ReferenceFindings {
  val base =
    ReferenceFindings(
      documentRevision = input.documentRevision,
      referenceId = input.reference.image?.id,
      frameWidthDp = input.frameWidthDp,
    )
  val fit = input.reference.settings.fit
  val comparable = input.facts?.pixelComparable(fit) ?: true
  val frame =
    buildMeasureFrame(
      design = input.design,
      reference = input.referenceBitmap,
      settings = input.reference.settings,
      facts = input.facts,
      frameWidthDp = input.frameWidthDp,
      frameHeightDp = input.frameHeightDp,
    )
  return when (val kind = input.kind) {
    ReferenceMeasureKind.Differences -> {
      if (!comparable) {
        return base.copy(
          message =
            "Not measured: ${input.facts?.advice(fit) ?: "this picture is not placed dp for dp."}"
        )
      }
      val report =
        diffRasters(
          frame.design,
          frame.reference,
          cellPx = (8 * frame.samplesPerDp).roundToInt(),
          coverage = frame.coverage,
        )
      base.copy(diff = report.inDp(frame, input.layers))
    }
    is ReferenceMeasureKind.Layer -> {
      val layer =
        input.layers.firstOrNull { it.nodeId == kind.nodeId }
          ?: return base.copy(
            message = "That layer is not on the canvas, so it has no box to match."
          )
      val target =
        referenceTargetDp(
          input.reference,
          input.referenceBitmap,
          input.facts,
          input.frameWidthDp,
          input.frameHeightDp,
        )
      val match =
        matchLayer(
          frame = frame,
          layer = layer,
          boxMarks =
            input.reference.marks
              .filter {
                it.drawable &&
                  (it.kind == ReferenceMarkupKind.Rectangle ||
                    it.kind == ReferenceMarkupKind.RoundedRectangle)
              }
              .map { mark ->
                Rect(
                  minOf(mark.x(0), mark.x(mark.pointCount - 1)) * input.frameWidthDp,
                  minOf(mark.y(0), mark.y(mark.pointCount - 1)) * input.frameHeightDp,
                  maxOf(mark.x(0), mark.x(mark.pointCount - 1)) * input.frameWidthDp,
                  maxOf(mark.y(0), mark.y(mark.pointCount - 1)) * input.frameHeightDp,
                )
              },
          // The artboard is not a layout box anybody wants a layer matched to.
          layoutBoxes =
            input.reference.layoutBoxes
              .filter { it.depth > 1 }
              .map { box ->
                Rect(
                  target.left + box.left * target.width,
                  target.top + box.top * target.height,
                  target.left + box.right * target.width,
                  target.top + box.bottom * target.height,
                )
              },
          pixelSearch = comparable,
        )
          ?: return base.copy(
            message =
              if (!comparable)
                "Draw a box round where it should be: ${input.facts?.advice(fit) ?: ""}".trim()
              else
                "Nothing to match: the layer is a flat colour. Draw a box round where it should " +
                  "be in the reference, then match again."
          )
      val font = if (layer.text) input.nodes[layer.nodeId]?.referenceFontSize() else null
      base.copy(
        match = match,
        alignment = alignmentFor(match, font?.first),
        fontSizeSource = font?.let { "${it.first.spText()} sp, ${it.second}" },
        message =
          if (match.confident) null
          else
            "Low confidence: the closest place in the reference still differs. If the words " +
              "or the content differ from the mock, draw a box round the target instead.",
      )
    }
  }
}

private fun Float.spText(): String =
  if (this == toInt().toFloat()) toInt().toString() else toString()

/**
 * [bounds] less this node's own padding — every `padding` in its chain, which is what a renderer
 * applies outside the content — so a text node's content box is where its glyphs are laid out.
 */
internal fun UiBuilderNode.contentBounds(bounds: Rect): Rect {
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
    Rect(bounds.left + start, bounds.top + top, bounds.right - end, bounds.bottom - bottom)
  return if (content.width > 0f && content.height > 0f) content else bounds
}
