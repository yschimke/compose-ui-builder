package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.svg.JvmDocumentRasterizer
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * A `wff/part-image` (or a `wff/clock-hand`) whose `resource` is a design asset draws that bitmap,
 * stretched to its box and tinted by `tintColor`, rather than a placeholder with the resource name:
 * yschimke/watchface-tools#71, part 2.
 */
class WffPartImageAssetTest {
  /** A 4×4 PNG: the left half [left], the right half white. */
  private fun png(left: Int): ByteArray {
    val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(4, 4)) }
    for (y in 0 until 4) for (x in 0 until 4) {
      bitmap.erase(
        if (x < 2) left else 0xFFFFFFFF.toInt(),
        org.jetbrains.skia.IRect.makeXYWH(x, y, 1, 1),
      )
    }
    return Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!.bytes
  }

  private fun asset(bytes: ByteArray) =
    JsonObject(
      mapOf(
        "mediaType" to JsonPrimitive("image/png"),
        "contentDigest" to JsonPrimitive("sha256:${bytes.contentHashCode()}"),
        "source" to
          JsonObject(
            mapOf(
              "type" to JsonPrimitive("embedded"),
              "base64" to JsonPrimitive(Base64.Default.encode(bytes)),
            ),
          ),
      ),
    )

  private fun face(tint: String?): UiBuilderDocument =
    wffFace(
        listOf("logo"),
        wffNode(
          "logo",
          "wff/part-image",
          mapOf("x" to 100, "y" to 100, "width" to 250, "height" to 250, "resource" to "logo") +
            (tint?.let { mapOf("tintColor" to it) } ?: emptyMap()),
        ),
      )
      .copy(assets = JsonObject(mapOf("logo" to asset(png(0xFFFF0000.toInt())))))

  @Test
  fun `a part image draws its asset's pixels`() {
    val bitmap = renderWffFace(face(tint = null), "part-image-asset")
    // 100..225 is the red half, 225..350 the white one, in face pixels (≈1.009 canvas px each).
    assertEquals(listOf(255, 255, 0, 0), bitmap.argb(165, 227))
    assertEquals(listOf(255, 255, 255, 255), bitmap.argb(290, 227))
  }

  @Test
  fun `the PNG export draws the asset too`() {
    val png = JvmDocumentRasterizer.renderPng(face(tint = null))
    val bitmap = Bitmap.makeFromImage(Image.makeFromEncoded(png))
    assertEquals(listOf(255, 255, 0, 0), bitmap.argb(165, 227))
  }

  @Test
  fun `a tint colour multiplies the bitmap`() {
    val bitmap = renderWffFace(face(tint = "#ff00ff00"), "part-image-asset-tinted")
    // White takes the tint; red times green is black.
    assertEquals(listOf(255, 0, 255, 0), bitmap.argb(290, 227))
    assertEquals(listOf(255, 0, 0, 0), bitmap.argb(165, 227))
  }

  @Test
  fun `a part image with no asset keeps the named placeholder`() {
    val bitmap = renderWffFace(face(tint = null).copy(assets = JsonObject(emptyMap())))
    var red = 0
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
      if (bitmap.argb(x, y) == listOf(255, 255, 0, 0)) red++
    }
    assertEquals(0, red)
  }

  @Test
  fun `a clock hand draws its asset turned to the time`() {
    // 10:08:32: the hour hand points a little past ten, up and to the left of the centre.
    val document =
      wffFace(
          listOf("clock"),
          wffNode(
            "clock",
            "wff/analog-clock",
            mapOf("x" to 0, "y" to 0, "width" to 450, "height" to 450),
            mapOf("hands" to listOf("hour")),
          ) +
            "," +
            wffNode(
              "hour",
              "wff/clock-hand",
              mapOf(
                "type" to "hour",
                "resource" to "hour_hand",
                "x" to 205,
                "y" to 75,
                "width" to 40,
                "height" to 150,
                "pivotX" to 0.5,
                "pivotY" to 1.0,
              ),
            ),
        )
        .copy(assets = JsonObject(mapOf("hour_hand" to asset(png(0xFF0000FF.toInt())))))
    val bitmap = renderWffFace(document, "clock-hand-asset")
    var blue = 0
    var sumX = 0L
    var sumY = 0L
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
      if (bitmap.argb(x, y) == listOf(255, 0, 0, 255)) {
        blue++
        sumX += x
        sumY += y
      }
    }
    assertTrue(blue > 500, "the hand's bitmap is not drawn: $blue blue pixels")
    // Its middle is up and to the left of the face's centre: turned towards ten o'clock.
    val centre = bitmap.width / 2
    assertTrue(sumX / blue < centre - 40 && sumY / blue < centre - 20, "not turned to ten")
  }
}
