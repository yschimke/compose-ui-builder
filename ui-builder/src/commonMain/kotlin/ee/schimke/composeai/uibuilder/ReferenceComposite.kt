package ee.schimke.composeai.uibuilder

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.io.encoding.Base64

/**
 * Bake the whole reference stack — base picture, placed pieces, every mark — into one PNG, and hand
 * it back as the picture to build against next.
 *
 * This is what makes the loop close. Snapshot the design, circle what is wrong, drop the component
 * that should be there into place, and then *flatten*: the annotated composite becomes the new
 * base, the pieces and marks are gone, and the next round of adjustment is measured against what
 * was just agreed rather than against the mock from three rounds ago.
 *
 * It draws through [drawReferenceStack], the same code the live overlay draws through, so the
 * flattened picture is the picture the operator was looking at. Two differences, both deliberate:
 * the base goes in at full strength (an overlay at 50% flattened at 50% would fade a little more
 * every round until there was nothing left), and the split divider — a piece of editor furniture
 * rather than content — is left out.
 *
 * Transparent where nothing was drawn, so the flattened reference still lets the design show
 * through underneath it. Null when the platform cannot encode a PNG, or when there is nothing to
 * flatten.
 */
internal fun flattenReference(
  reference: ReferenceOverlayState,
  widthPx: Int,
  heightPx: Int,
  id: String,
  /** The editor's own measurer, so a flattened label is set in the font it was drawn in. */
  textMeasurer: TextMeasurer?,
  name: String = "Flattened reference",
): ReferenceImage? {
  if (!reference.hasContent) return null
  val width = widthPx.coerceIn(1, MAX_FLATTEN_PX)
  val height = heightPx.coerceIn(1, MAX_FLATTEN_PX)
  val bitmap = ImageBitmap(width, height)
  val baseBitmap = reference.image?.let(::decodeReferenceImage)
  val pieceBitmaps = reference.pieces.associate { it.id to decodeReferenceImage(it.image) }
  // Density 1, so a dp of stroke width is a pixel of stroke width in the flattened picture at the
  // size it was authored against. The result is then fitted like any other import, which puts the
  // strokes back where they were drawn.
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
 * The ceiling on a flattened picture's edge.
 *
 * 4096 because that is a comfortable frame at 2× on the largest screen the builder offers (3840 dp
 * wide), and because the result is stored: a flatten that produced a 30-megapixel PNG would push
 * the design's reference past the store's byte limit and the operator would learn about it as a
 * refusal after the work rather than before it.
 */
private const val MAX_FLATTEN_PX = 4096
