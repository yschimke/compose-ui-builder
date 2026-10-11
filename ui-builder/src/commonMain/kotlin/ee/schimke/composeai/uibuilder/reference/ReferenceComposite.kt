package ee.schimke.composeai.uibuilder.reference

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.io.encoding.Base64

/**
 * Bake the whole reference stack (base picture, placed pieces, every mark) into one PNG, and hand
 * it back as the next base picture, so each round is measured against what was just agreed.
 *
 * Draws through [drawReferenceStack], like the live overlay, with two deliberate differences: the
 * base at full strength (so repeated flattens don't fade it) and no split divider (editor
 * furniture). Transparent where nothing was drawn. Null when the platform can't encode a PNG or
 * there's nothing to flatten.
 */
internal fun flattenReference(
  reference: ReferenceOverlayState,
  widthPx: Int,
  heightPx: Int,
  id: String,
  /** The editor's own measurer, so a flattened label is set in the font it was drawn in. */
  textMeasurer: TextMeasurer?,
  name: String = "Flattened reference",
  /**
   * The base picture measured against the frame, so a width or actual-size fit flattens as drawn.
   */
  facts: ReferenceFacts? = null,
): ReferenceImage? {
  if (!reference.hasContent) return null
  val width = widthPx.coerceIn(1, MAX_FLATTEN_PX)
  val height = heightPx.coerceIn(1, MAX_FLATTEN_PX)
  val bitmap = ImageBitmap(width, height)
  val baseBitmap = reference.image?.let(::decodeReferenceImage)
  val pieceBitmaps = reference.pieces.associate { it.id to decodeReferenceImage(it.image) }
  // Density 1, so a dp of stroke is a pixel at the authored size; the result is fitted like any
  // import.
  CanvasDrawScope().draw(
    Density(1f),
    LayoutDirection.Ltr,
    Canvas(bitmap),
    Size(width.toFloat(), height.toFloat()),
  ) {
    drawReferenceStack(
      reference = reference,
      density = Density(1f),
      baseBitmap = baseBitmap,
      pieceBitmaps = pieceBitmaps,
      selectionHandles = false,
      textMeasurer = textMeasurer,
      baseAlphaOverride = 1f,
      facts = facts,
    )
  }
  val png = encodeReferencePng(bitmap) ?: return null
  return ReferenceImage(
    id = id,
    name = name,
    mediaType = "image/png",
    base64 = Base64.Default.encode(png),
    widthPx = width,
    heightPx = height,
    sourceUrl = reference.image?.sourceUrl,
  )
}

/**
 * The ceiling on a flattened picture's edge: a comfortable 2× frame on the largest screen offered
 * (3840dp), and small enough to stay under the reference store's byte limit.
 */
private const val MAX_FLATTEN_PX = 4096

/**
 * A sub-rectangle of [this], as its own bitmap, drawn (accelerated, config-agnostic) rather than
 * copied. Used by the component capture to trim.
 */
internal fun ImageBitmap.cropped(left: Int, top: Int, width: Int, height: Int): ImageBitmap {
  if (width <= 0 || height <= 0) return this
  val target = ImageBitmap(width, height)
  CanvasDrawScope().draw(
    Density(1f),
    LayoutDirection.Ltr,
    Canvas(target),
    Size(width.toFloat(), height.toFloat()),
  ) {
    drawImage(
      image = this@cropped,
      srcOffset = IntOffset(left, top),
      srcSize = IntSize(width, height),
      dstOffset = IntOffset.Zero,
      dstSize = IntSize(width, height),
    )
  }
  return target
}
