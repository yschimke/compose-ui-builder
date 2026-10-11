package ee.schimke.composeai.uibuilder.reference

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM

/**
 * Skia decode for an imported reference (see the `expect` declaration). Failure-tolerant:
 * undecodable clipboard bytes leave the editor working without a reference.
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
 * An SVG at a fixed pixel size, letterboxed: the intrinsic size picks a contain scale and the
 * canvas is translated to centre it (`setContainerSize` alone would stretch). Without an intrinsic
 * size (only a `viewBox`), Skia's container fallback applies.
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
