package ee.schimke.composeai.uibuilder

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM

/**
 * Skia decode for an imported reference. See the `expect` declaration for why this exists twice.
 *
 * Everything here is failure-tolerant by construction: the bytes came from an operator's clipboard
 * and a picture that will not decode must leave the editor working, without a reference, rather
 * than take the canvas down with it.
 */
internal actual fun decodeReferenceBitmap(
  bytes: ByteArray,
  vector: Boolean,
  targetWidthPx: Int,
  targetHeightPx: Int,
): ImageBitmap? =
  try {
    if (vector) rasterizeSvg(bytes, targetWidthPx, targetHeightPx)
    else Image.makeFromEncoded(bytes).toComposeImageBitmap()
  } catch (_: Throwable) {
    null
  }

/**
 * An SVG at a fixed pixel size, letterboxed the way the overlay will letterbox it anyway.
 *
 * `setContainerSize` alone would stretch a portrait artboard across a square surface, so the
 * intrinsic size decides a contain scale first and the canvas is translated to centre what is
 * drawn. An SVG with no intrinsic size (a `viewBox` and nothing else) falls back to the container,
 * which is Skia's own behaviour and the only sensible one.
 */
private fun rasterizeSvg(bytes: ByteArray, widthPx: Int, heightPx: Int): ImageBitmap? {
  val dom = SVGDOM(Data.makeFromBytes(bytes))
  val root = dom.root ?: return null
  val intrinsicWidth = root.width.value.takeIf { it > 0f }
  val intrinsicHeight = root.height.value.takeIf { it > 0f }
  val surface = Surface.makeRasterN32Premul(widthPx, heightPx)
  return try {
    if (intrinsicWidth != null && intrinsicHeight != null) {
      val scale = minOf(widthPx / intrinsicWidth, heightPx / intrinsicHeight)
      dom.setContainerSize(intrinsicWidth, intrinsicHeight)
      surface.canvas.translate(
        (widthPx - intrinsicWidth * scale) / 2f,
        (heightPx - intrinsicHeight * scale) / 2f,
      )
      surface.canvas.scale(scale, scale)
    } else {
      dom.setContainerSize(widthPx.toFloat(), heightPx.toFloat())
    }
    dom.render(surface.canvas)
    surface.makeImageSnapshot().toComposeImageBitmap()
  } finally {
    surface.close()
  }
}

/** A composed bitmap as PNG bytes. Null rather than throwing, for the reason above. */
internal actual fun encodeReferencePng(bitmap: ImageBitmap): ByteArray? =
  try {
    Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData()?.bytes
  } catch (_: Throwable) {
    null
  }
