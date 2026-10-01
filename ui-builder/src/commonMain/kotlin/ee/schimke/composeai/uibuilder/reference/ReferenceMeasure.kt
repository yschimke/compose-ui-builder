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

/** A bitmap's pixels as a [ReferenceRaster]. */
internal fun ImageBitmap.toReferenceRaster(): ReferenceRaster {
  val buffer = IntArray(width * height)
  readPixels(buffer)
  return ReferenceRaster(width, height, buffer)
}

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

/** The outlines of [findings] over the frame: differing regions, and a match's two boxes. */
internal fun DrawScope.drawFindings(findings: ReferenceFindings) {
  if (findings.frameWidthDp <= 0f) return
  val perDp = size.width / findings.frameWidthDp
  fun ReferenceBox.px() = Rect(left * perDp, top * perDp, right * perDp, bottom * perDp)
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
 * Run one measurement through the shared engine ([compareDifferences], [compareLayer]). Pure apart
 * from reading pixels, and slow enough (tens of milliseconds) that the editor calls it off the main
 * dispatcher.
 */
internal fun measureAgainstReference(input: ReferenceMeasureInput): ReferenceFindings {
  val comparison =
    ReferenceComparisonInput(
      design = input.design.toReferenceRaster(),
      reference = input.referenceBitmap.toReferenceRaster(),
      frameWidthDp = input.frameWidthDp,
      frameHeightDp = input.frameHeightDp,
      placement =
        ReferencePlacementSpec(
          fit = input.reference.settings.fit,
          scale = input.reference.settings.scale,
          offsetXDp = input.reference.settings.offsetXDp,
          offsetYDp = input.reference.settings.offsetYDp,
        ),
      facts = input.facts,
      layers = input.layers,
      boxMarks =
        input.reference.marks
          .filter {
            it.drawable &&
              (it.kind == ReferenceMarkupKind.Rectangle ||
                it.kind == ReferenceMarkupKind.RoundedRectangle)
          }
          .map { mark ->
            ReferenceBox(
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
          .map { ReferenceBox(it.left, it.top, it.right, it.bottom) },
    )
  val result =
    when (val kind = input.kind) {
      ReferenceMeasureKind.Differences -> compareDifferences(comparison)
      is ReferenceMeasureKind.Layer ->
        compareLayer(comparison, kind.nodeId, input.nodes[kind.nodeId])
    }
  return ReferenceFindings(
    documentRevision = input.documentRevision,
    referenceId = input.reference.image?.id,
    frameWidthDp = input.frameWidthDp,
    diff = result.diff,
    match = result.match,
    alignment = result.alignment,
    fontSizeSource = result.fontSizeSource,
    message = result.message,
  )
}
