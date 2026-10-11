package ee.schimke.composeai.uibuilder.reference

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.io.encoding.Base64
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The reference stack, drawn over the design frame in the mode the operator chose, above everything
 * the document draws.
 *
 * Takes pointer input **only while a tool is in hand** ([ReferenceTool.None] is the default), so
 * selection, catalog drags and preview taps still reach the canvas underneath.
 */
@Composable
internal fun ReferenceOverlayCanvas(
  reference: ReferenceOverlayState,
  onMarkDrawn: (ReferenceMarkupKind, List<Float>) -> Unit = { _, _ -> },
  onPieceMoved: (String, Float, Float) -> Unit = { _, _, _ -> },
  modifier: Modifier = Modifier,
  /** The picture measured against this frame; null draws every fit as [ReferenceFit.Contain]. */
  facts: ReferenceFacts? = null,
  /** What the last measurement found, outlined over the frame; see [ReferenceFindings]. */
  findings: ReferenceFindings? = null,
) {
  if (!reference.drawing) return
  val settings = reference.settings
  val density = LocalDensity.current
  val textMeasurer = rememberTextMeasurer()
  // Keyed on the host-minted identity, not the bytes, so the expensive decode happens once per
  // import.
  val bitmap = remember(reference.image?.id) { reference.image?.let(::decodeReferenceImage) }
  val pieceBitmaps =
    remember(reference.pieces.map { it.image.id }) {
      reference.pieces.associate { it.id to decodeReferenceImage(it.image) }
    }
  // The stroke in progress, in frame fractions; local so pointer samples don't each update the
  // reducer.
  var drafting by remember(reference.tool) { mutableStateOf<List<Float>>(emptyList()) }

  Canvas(
    modifier
      .fillMaxSize()
      .clearAndSetSemantics {}
      .referenceToolInput(
        reference = reference,
        onDraft = { drafting = it },
        onMarkDrawn = { kind, points ->
          drafting = emptyList()
          onMarkDrawn(kind, points)
        },
        onPieceMoved = onPieceMoved,
      )
  ) {
    drawReferenceStack(
      reference = reference,
      density = density,
      baseBitmap = bitmap,
      pieceBitmaps = pieceBitmaps,
      selectionHandles = reference.tool == ReferenceTool.MovePiece,
      textMeasurer = textMeasurer,
      facts = facts,
    )
    findings?.let { drawFindings(it) }
    if (drafting.size >= 4) {
      drawMark(
        ReferenceMark(
          id = DRAFT_MARK_ID,
          kind = reference.tool.markupKind ?: ReferenceMarkupKind.Pen,
          points = drafting,
          colorArgb = reference.markupColorArgb,
          text = reference.markupText,
        ),
        density,
        textMeasurer,
      )
    }
  }
}

/**
 * The whole stack, in one place, shared by the live overlay and the flattener so a flatten matches
 * what was on screen.
 */
internal fun DrawScope.drawReferenceStack(
  reference: ReferenceOverlayState,
  density: Density,
  baseBitmap: ImageBitmap?,
  pieceBitmaps: Map<String, ImageBitmap?>,
  selectionHandles: Boolean,
  /** Draws the words on a text mark; without one the mark is drawn and the words are not. */
  textMeasurer: TextMeasurer?,
  /** Flattening bakes the base in at full strength; see `flattenReference`. */
  baseAlphaOverride: Float? = null,
  /** The picture measured against the frame, for the fits that need its size in dp. */
  facts: ReferenceFacts? = null,
) {
  val settings = reference.settings
  val target =
    referenceTargetRect(
      frame = size,
      imageWidthPx = (baseBitmap?.width ?: reference.image?.widthPx ?: 0).toFloat(),
      imageHeightPx = (baseBitmap?.height ?: reference.image?.heightPx ?: 0).toFloat(),
      scale = settings.scale,
      offsetXPx = with(density) { settings.offsetXDp.dp.toPx() },
      offsetYPx = with(density) { settings.offsetYDp.dp.toPx() },
      fit = settings.fit,
      facts = facts,
    )
  when (settings.mode) {
    ReferenceDiffMode.Overlay ->
      baseBitmap?.let { drawReference(it, target, alpha = baseAlphaOverride ?: settings.opacity) }
    ReferenceDiffMode.Difference ->
      // Matching pixels subtract to black, so every non-black pixel is a difference. Fully opaque
      // on purpose: blending a difference at half strength answers no question at all.
      baseBitmap?.let {
        drawReference(
          it,
          target,
          alpha = 1f,
          blendMode = if (baseAlphaOverride == null) BlendMode.Difference else BlendMode.SrcOver,
        )
      }
    ReferenceDiffMode.Split -> {
      val divider = size.width * settings.splitFraction
      clipRect(right = divider) { baseBitmap?.let { drawReference(it, target, alpha = 1f) } }
      if (baseAlphaOverride == null) {
        drawLine(
          color = SPLIT_DIVIDER,
          start = Offset(divider, 0f),
          end = Offset(divider, size.height),
          strokeWidth = 2f,
        )
      }
    }
    ReferenceDiffMode.Boxes -> drawLayoutBoxes(reference.layoutBoxes, target)
  }
  if (settings.alwaysShowBoxes && settings.mode != ReferenceDiffMode.Boxes) {
    drawLayoutBoxes(reference.layoutBoxes, target)
  }
  // Pieces and marks draw over the base mode and are never blended.
  reference.pieces.forEach { piece ->
    val bounds = piece.frameRect(size)
    pieceBitmaps[piece.id]?.let { drawReference(it, bounds, alpha = piece.opacity) }
    if (selectionHandles && piece.id == reference.selectedPieceId) {
      drawRect(
        color = PIECE_SELECTION,
        topLeft = bounds.topLeft,
        size = bounds.size,
        style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
      )
    }
  }
  reference.marks.forEach { drawMark(it, density, textMeasurer) }
}

/** The pointer behaviour of the active tool, or nothing at all when there is none. */
private fun Modifier.referenceToolInput(
  reference: ReferenceOverlayState,
  onDraft: (List<Float>) -> Unit,
  onMarkDrawn: (ReferenceMarkupKind, List<Float>) -> Unit,
  onPieceMoved: (String, Float, Float) -> Unit,
): Modifier =
  when (reference.tool) {
    ReferenceTool.None -> this
    ReferenceTool.MovePiece -> {
      val pieceId = reference.selectedPieceId
      if (pieceId == null) this
      else
        pointerInput(pieceId) {
          detectDragGestures { change, dragAmount ->
            change.consume()
            onPieceMoved(pieceId, dragAmount.x / size.width, dragAmount.y / size.height)
          }
        }
    }
    else -> {
      val kind = reference.tool.markupKind ?: ReferenceMarkupKind.Pen
      pointerInput(kind, reference.markupColorArgb) {
        var points = mutableListOf<Float>()
        detectDragGestures(
          onDragStart = { start ->
            points = mutableListOf(start.x / size.width, start.y / size.height)
            onDraft(points.toList())
          },
          onDrag = { change, _ ->
            change.consume()
            val x = change.position.x / size.width
            val y = change.position.y / size.height
            // A shape with two ends keeps only its ends: dragging a box is choosing its opposite
            // corners, and accumulating every sample between them would make an arrow a scribble.
            if (kind.freehand) {
              points += listOf(x, y)
            } else if (points.size >= 4) {
              points[2] = x
              points[3] = y
            } else {
              points += listOf(x, y)
            }
            onDraft(points.toList())
          },
          onDragEnd = {
            // The reducer assigns ids and colours; it knows what's on the canvas, and point hashes
            // would collide for identical strokes.
            val drawn = points.toList()
            if (drawn.size >= 4) onMarkDrawn(kind, drawn) else onDraft(emptyList())
            points = mutableListOf()
          },
          onDragCancel = {
            points = mutableListOf()
            onDraft(emptyList())
          },
        )
      }
    }
  }

/** A piece's rectangle in the frame's own pixels. */
internal fun ReferencePiece.frameRect(frame: Size): Rect =
  Rect(left * frame.width, top * frame.height, right * frame.width, bottom * frame.height)

/** Where the base reference lands inside the frame, in its pixels: [referencePlacement]. */
internal fun referenceTargetRect(
  frame: Size,
  imageWidthPx: Float,
  imageHeightPx: Float,
  scale: Float,
  offsetXPx: Float,
  offsetYPx: Float,
  fit: ReferenceFit = ReferenceFit.Contain,
  facts: ReferenceFacts? = null,
): Rect =
  referencePlacement(
      frameWidth = frame.width,
      frameHeight = frame.height,
      imageWidthPx = imageWidthPx,
      imageHeightPx = imageHeightPx,
      scale = scale,
      offsetX = offsetXPx,
      offsetY = offsetYPx,
      fit = fit,
      facts = facts,
    )
    .let { Rect(it.left, it.top, it.right, it.bottom) }

private fun DrawScope.drawReference(
  bitmap: ImageBitmap,
  target: Rect,
  alpha: Float,
  blendMode: BlendMode = BlendMode.SrcOver,
) {
  val width = target.width.roundToInt()
  val height = target.height.roundToInt()
  if (width <= 0 || height <= 0) return
  drawImage(
    image = bitmap,
    srcOffset = IntOffset.Zero,
    srcSize = IntSize(bitmap.width, bitmap.height),
    dstOffset = IntOffset(target.left.roundToInt(), target.top.roundToInt()),
    dstSize = IntSize(width, height),
    alpha = alpha,
    blendMode = blendMode,
  )
}

/**
 * The SVG's own rectangles, stroked over the frame, in two colours by depth since the outermost is
 * usually the artboard.
 */
private fun DrawScope.drawLayoutBoxes(boxes: List<ReferenceLayoutBox>, target: Rect) {
  boxes.forEach { box ->
    val width = box.width * target.width
    val height = box.height * target.height
    if (width <= 0f || height <= 0f) return@forEach
    drawRect(
      color = if (box.depth <= 1) BOX_FRAME else BOX_CONTENT,
      topLeft = Offset(target.left + box.left * target.width, target.top + box.top * target.height),
      size = Size(width, height),
      style = Stroke(width = 1f),
    )
  }
}

/**
 * One annotation, in the frame's pixels. Drawn identically whether it is finished or in progress.
 */
internal fun DrawScope.drawMark(
  mark: ReferenceMark,
  density: Density,
  textMeasurer: TextMeasurer?,
) {
  if (!mark.drawable) return
  val color = Color(mark.colorArgb.toInt())
  val stroke = with(density) { mark.strokeWidthDp.dp.toPx() }.coerceAtLeast(1f)
  fun point(index: Int) = Offset(mark.x(index) * size.width, mark.y(index) * size.height)
  val start = point(0)
  val end = point(mark.pointCount - 1)
  val bounds =
    Rect(
      minOf(start.x, end.x),
      minOf(start.y, end.y),
      maxOf(start.x, end.x),
      maxOf(start.y, end.y),
    )
  when (mark.kind) {
    ReferenceMarkupKind.Pen -> {
      val path = Path()
      path.moveTo(start.x, start.y)
      for (index in 1 until mark.pointCount) path.lineTo(point(index).x, point(index).y)
      drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
    ReferenceMarkupKind.Rectangle ->
      drawRect(color, bounds.topLeft, bounds.size, style = Stroke(width = stroke))
    ReferenceMarkupKind.RoundedRectangle ->
      drawRoundRect(
        color = color,
        topLeft = bounds.topLeft,
        size = bounds.size,
        cornerRadius =
          CornerRadius(with(density) { MARKUP_CORNER_RADIUS_DP.dp.toPx() }).coerceInside(bounds),
        style = Stroke(width = stroke),
      )
    ReferenceMarkupKind.Ellipse ->
      drawOval(color, bounds.topLeft, bounds.size, style = Stroke(width = stroke))
    // Filled, and with no outline: an erase that outlined itself would leave a border where the
    // whole point is that there is nothing there.
    ReferenceMarkupKind.Fill -> drawRect(color, bounds.topLeft, bounds.size)
    ReferenceMarkupKind.Arrow -> {
      drawLine(color, start, end, strokeWidth = stroke, cap = StrokeCap.Round)
      val angle = atan2(end.y - start.y, end.x - start.x)
      val head = (stroke * 4f).coerceAtLeast(8f)
      listOf(angle - ARROW_SPREAD, angle + ARROW_SPREAD).forEach { barb ->
        drawLine(
          color,
          end,
          Offset(end.x - head * cos(barb), end.y - head * sin(barb)),
          strokeWidth = stroke,
          cap = StrokeCap.Round,
        )
      }
    }
    ReferenceMarkupKind.Text -> drawMarkupText(mark.text, bounds, color, density, textMeasurer)
    ReferenceMarkupKind.ImagePlaceholder -> {
      drawRect(color, bounds.topLeft, bounds.size, style = Stroke(width = stroke))
      // The crossed box, which has meant "a picture belongs here" since long before this editor.
      drawLine(color, bounds.topLeft, bounds.bottomRight, strokeWidth = stroke)
      drawLine(color, bounds.topRight, bounds.bottomLeft, strokeWidth = stroke)
      if (!mark.text.isNullOrBlank()) {
        drawMarkupText(mark.text, bounds, color, density, textMeasurer)
      }
    }
  }
}

/**
 * A label inside [bounds], centred, or the bounds alone when there's no measurer. The measurer is
 * passed in since it needs a composition's font resolver (the flattener gets the editor's).
 */
private fun DrawScope.drawMarkupText(
  text: String?,
  bounds: Rect,
  color: Color,
  density: Density,
  textMeasurer: TextMeasurer?,
) {
  val words = text?.takeIf { it.isNotBlank() } ?: return
  if (textMeasurer == null) return
  val measured =
    textMeasurer.measure(
      text = words,
      style =
        TextStyle(
          color = color,
          fontSize = with(density) { MARKUP_TEXT_SIZE_DP.dp.toSp() },
          fontWeight = FontWeight.Bold,
        ),
      constraints = Constraints(maxWidth = bounds.width.roundToInt().coerceAtLeast(1)),
    )
  drawText(
    textLayoutResult = measured,
    topLeft =
      Offset(
        bounds.left + (bounds.width - measured.size.width) / 2f,
        bounds.top + (bounds.height - measured.size.height) / 2f,
      ),
  )
}

/** A radius no larger than the box it rounds, so a small drag does not draw a lozenge. */
private fun CornerRadius.coerceInside(bounds: Rect): CornerRadius {
  val limit = minOf(bounds.width, bounds.height) / 2f
  return CornerRadius(x.coerceAtMost(limit), y.coerceAtMost(limit))
}

private const val ARROW_SPREAD = 0.5f
private const val DRAFT_MARK_ID = "draft"

private val SPLIT_DIVIDER = Color(0xFFFFAB40)
private val BOX_FRAME = Color(0x99FFAB40)
private val BOX_CONTENT = Color(0xCC40C4FF)
private val PIECE_SELECTION = Color(0xFF40C4FF)

/**
 * Decodes an import once. Raster decodes at natural size and scales when drawn; SVG has no natural
 * size, so it is rasterised once at a fixed generous square ([RASTER_TARGET_PX]) rather than per
 * frame size.
 */
internal fun decodeReferenceImage(image: ReferenceImage): ImageBitmap? {
  val bytes =
    try {
      Base64.Default.decode(image.base64)
    } catch (_: IllegalArgumentException) {
      return null
    }
  return decodeReferenceBitmap(bytes, image.isVector, RASTER_TARGET_PX, RASTER_TARGET_PX)
}

private const val RASTER_TARGET_PX = 2048

/**
 * Bytes to pixels, per platform. Both targets are Skia and the implementations are identical; a
 * shared `skikoMain` source set would be a build-shape change. Keep them in step.
 */
internal expect fun decodeReferenceBitmap(
  bytes: ByteArray,
  vector: Boolean,
  targetWidthPx: Int,
  targetHeightPx: Int,
): ImageBitmap?

/** PNG bytes for a composed bitmap, or null where the platform will not encode one. */
internal expect fun encodeReferencePng(bitmap: ImageBitmap): ByteArray?
