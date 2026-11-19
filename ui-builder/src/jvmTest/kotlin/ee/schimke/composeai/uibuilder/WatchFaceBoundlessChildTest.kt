package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter
import ee.schimke.composeai.uibuilder.svg.JvmDocumentRasterizer
import ee.schimke.composeai.uibuilder.svg.JvmSkiaStructuredSvgRecorder
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image

/**
 * yschimke/watchface-tools#59: a part with no `x`/`y`/`width`/`height` of its own fills the box it
 * sits in. A `wff/time-text` in a `wff/digital-clock` at 115,181 220×70 was drawn 450×450 from the
 * clock's corner, past its bottom right and cut off by the face, while the XML export wrote it 0,0
 * 220×70.
 */
class WatchFaceBoundlessChildTest {
  /** Canvas pixels to a face pixel: a 450px face drawn at 227dp, 2px to the dp. */
  private val k = 454f / 450f

  /** The issue's clock, at [x],[y] 220×70, holding a [component] with no bounds of its own. */
  private fun face(container: String, slot: String, component: String, x: Int = 115, y: Int = 181) =
    wffFace(
      listOf("clock"),
      listOf(
          wffNode(
            "clock",
            container,
            mapOf("x" to x, "y" to y, "width" to 220, "height" to 70) +
              // A `<Group>` must be named.
              if (container == WatchFaceXmlExporter.GROUP) mapOf("name" to "clock") else emptyMap(),
            mapOf(slot to listOf("text")),
          ),
          wffNode(
            "text",
            component,
            if (component == WatchFaceXmlExporter.TIME_TEXT)
              mapOf("format" to "hh:mm", "fontSize" to 48, "color" to "#ffffffff")
            else
              mapOf(
                "text" to "%s:%s",
                "parameters" to "[HOUR_0_23]\n[MINUTE_Z]",
                "fontSize" to 48,
                "color" to "#ffffffff",
              ),
          ),
        )
        .joinToString(","),
    )

  private val clock =
    face(WatchFaceXmlExporter.DIGITAL_CLOCK, "texts", WatchFaceXmlExporter.TIME_TEXT)

  /** The XML [document] exports to; a refusal fails with its reasons. */
  private fun xml(document: ee.schimke.composeai.uibuilder.export.UiBuilderDocument): String =
    when (val result = WatchFaceXmlExporter.export(document)) {
      is WatchFaceXmlExporter.Result.Emitted -> result.xml
      is WatchFaceXmlExporter.Result.Refused -> error("refused: ${result.reasons}")
    }

  /** The box of the pixels lit in [bitmap], canvas pixels: left, top, right, bottom. */
  private fun lit(bitmap: Bitmap): List<Int>? {
    var left = Int.MAX_VALUE
    var top = Int.MAX_VALUE
    var right = -1
    var bottom = -1
    for (py in 0 until bitmap.height) for (px in 0 until bitmap.width) {
      val (_, r, g, b) = bitmap.argb(px, py)
      if (r + g + b > 3 * 96) {
        left = minOf(left, px)
        top = minOf(top, py)
        right = maxOf(right, px)
        bottom = maxOf(bottom, py)
      }
    }
    return if (right < 0) null else listOf(left, top, right, bottom)
  }

  /** The text's lit box lies inside the clock's 115,181 220×70, and near its middle. */
  private fun assertInsideClock(bitmap: Bitmap, what: String) {
    val box = assertNotNull(lit(bitmap), "$what: no text drawn")
    val (left, top, right, bottom) = box
    val clock =
      listOf(floor(115 * k), floor(181 * k), ceil(335 * k), ceil(251 * k)).map { it.toInt() }
    assertTrue(
      left >= clock[0] && top >= clock[1] && right <= clock[2] && bottom <= clock[3],
      "$what: text at $box, outside the clock at $clock",
    )
    // Centred in the clock, as `align` CENTER puts it: the clock's centre is 225,216.
    assertEquals(225 * k, (left + right) / 2f, 12f, "$what: centre x of $box")
    assertEquals(216 * k, (top + bottom) / 2f, 12f, "$what: centre y of $box")
  }

  @Test
  fun `a bound-less TimeText fills its DigitalClock on the canvas`() {
    assertInsideClock(renderWffFace(clock, "issue59-time-text"), "canvas")
  }

  @Test
  fun `a bound-less TimeText fills its DigitalClock in the PNG export`() {
    val png = JvmDocumentRasterizer.renderPng(clock)
    File("build/watchface-renders").mkdirs()
    File("build/watchface-renders/issue59-time-text-export.png").writeBytes(png)
    assertInsideClock(Bitmap.makeFromImage(Image.makeFromEncoded(png)), "PNG export")
  }

  @Test
  fun `a bound-less TimeText is laid out at its DigitalClock's box in the SVG export`() {
    val bounds = JvmSkiaStructuredSvgRecorder.measuredBoundsDp(clock)
    val dp = 227 / 450.0
    val text = bounds.getValue("text")
    assertEquals(115 * dp, text.x, 0.6)
    assertEquals(181 * dp, text.y, 0.6)
    assertEquals(220 * dp, text.width, 0.6)
    assertEquals(70 * dp, text.height, 0.6)
  }

  @Test
  fun `the exported TimeText is at 0,0 in its clock, at the clock's size`() {
    val xml = xml(clock)
    val timeText = assertNotNull(Regex("<TimeText\\b[^>]*>").find(xml)?.value, xml)
    for ((name, value) in
      listOf("x" to "0", "y" to "0", "width" to "220", "height" to "70")) assertTrue(
      Regex("\\b$name=\"$value\"").containsMatchIn(timeText),
      "$name in $timeText",
    )
  }

  @Test
  fun `a bound-less part fills its Group, drawn and exported alike`() {
    val group = face(WatchFaceXmlExporter.GROUP, "content", WatchFaceXmlExporter.PART_TEXT)
    assertInsideClock(renderWffFace(group, "issue59-group-part-text"), "canvas")
    val xml = xml(group)
    val part = assertNotNull(Regex("<PartText\\b[^>]*>").find(xml)?.value, xml)
    for ((name, value) in
      listOf("x" to "0", "y" to "0", "width" to "220", "height" to "70")) assertTrue(
      Regex("\\b$name=\"$value\"").containsMatchIn(part),
      "$name in $part",
    )
  }
}
