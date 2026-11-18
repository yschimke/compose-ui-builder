package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.canvas.WffGradient
import ee.schimke.composeai.uibuilder.canvas.wffArcSweep
import ee.schimke.composeai.uibuilder.svg.JvmDocumentRasterizer
import ee.schimke.composeai.uibuilder.svg.JvmSkiaStructuredSvgRecorder
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image

/**
 * What the editor canvas and the PNG and SVG exports draw for the parts of a watch face the issues
 * found missing: gradients in a `<Fill>` or `<Stroke>` (yschimke/watchface-tools#23), an `<Arc>`'s
 * angles (#24), a rotated `<Group>` in the SVG (#25), and where a `<Stroke>` sits (#26).
 */
class WatchFaceFidelityTest {
  /** Canvas pixels to a face pixel: a 450px face drawn at 227dp, 2px to the dp. */
  private val k = 454f / 450f

  private fun export(document: ee.schimke.composeai.uibuilder.export.UiBuilderDocument): Bitmap {
    val png = JvmDocumentRasterizer.renderPng(document)
    return Bitmap.makeFromImage(Image.makeFromEncoded(png))
  }

  private fun svg(document: ee.schimke.composeai.uibuilder.export.UiBuilderDocument, name: String) =
    JvmSkiaStructuredSvgRecorder.record(document).svg.also {
      File("build/watchface-renders").mkdirs()
      File("build/watchface-renders/$name.svg").writeText(it)
    }

  /** The canvas pixel at face pixel ([x], [y]). */
  private fun Bitmap.at(x: Float, y: Float) = argb((x * k).roundToInt(), (y * k).roundToInt())

  /** The face pixel [radius] from the centre at WFF angle [degrees] (12 o'clock, clockwise). */
  private fun polar(radius: Float, degrees: Float): Pair<Float, Float> {
    val radians = degrees * PI / 180
    return (225f + radius * sin(radians).toFloat()) to (225f - radius * cos(radians).toFloat())
  }

  private fun Bitmap.atPolar(radius: Float, degrees: Float) =
    polar(radius, degrees).let { (x, y) -> at(x, y) }

  private fun close(expected: List<Int>, actual: List<Int>, tolerance: Int = 24) =
    expected.zip(actual).all { (a, b) -> abs(a - b) <= tolerance }

  /** A part over the whole face holding one `wff/shape` with a fill or stroke of [paint]. */
  private fun paintedPart(
    id: String,
    shape: Map<String, Any>,
    paint: String,
    paintProps: Map<String, Any>,
    gradientXml: String?,
  ): List<String> = buildList {
    add(
      wffNode(
        id,
        "wff/part-draw",
        mapOf("x" to 0, "y" to 0, "width" to 450, "height" to 450, "shape" to "none"),
        mapOf("content" to listOf("$id-shape")),
      ),
    )
    add(wffNode("$id-shape", "wff/shape", shape, mapOf("content" to listOf("$id-paint"))))
    add(
      wffNode(
        "$id-paint",
        paint,
        paintProps,
        if (gradientXml == null) emptyMap() else mapOf("content" to listOf("$id-gradient")),
      ),
    )
    if (gradientXml != null) add(wffNode("$id-gradient", "wff/raw", mapOf("xml" to gradientXml)))
  }

  private val wholeEllipse =
    mapOf("shape" to "ellipse", "x" to 0, "y" to 0, "width" to 450, "height" to 450)

  private fun gradientFace(gradientXml: String) =
    wffFace(
      listOf("dial"),
      paintedPart("dial", wholeEllipse, "wff/fill", mapOf("color" to "#ff101214"), gradientXml)
        .joinToString(","),
    )

  // yschimke/watchface-tools#23

  @Test
  fun `a radial gradient in a fill is drawn instead of the flat colour, in the canvas and the SVG`() {
    val face =
      gradientFace(
        """<RadialGradient centerX="190" centerY="160" radius="300" """ +
          """colors="#ffff0000 #ff00ff00 #ff0000ff" positions="0 0.55 1"/>""",
      )
    for ((name, bitmap) in
      listOf("editor" to renderWffFace(face, "gradient-radial"), "export" to export(face))) {
      // The centre is the first colour, 0.55 of the radius out the second, the rim near the last.
      assertTrue(
        close(listOf(255, 255, 0, 0), bitmap.at(190f, 160f)),
        "$name ${bitmap.at(190f, 160f)}",
      )
      assertTrue(
        close(listOf(255, 0, 255, 0), bitmap.at(190f, 160f + 165f), 40),
        "$name ${bitmap.at(190f, 325f)}",
      )
      // 0.81 of the radius out, past the second colour towards the third.
      val outer = bitmap.at(330f, 360f)
      assertTrue(outer[3] > outer[2] && outer[1] < 30, "$name outer $outer")
    }
    val svg = svg(face, "gradient-radial")
    assertTrue("<radialGradient" in svg, svg)
    assertFalse("#101214" in svg, "the flat fill colour is not painted under the gradient")
  }

  @Test
  fun `a linear gradient in a fill runs from its start to its end point`() {
    val face =
      gradientFace(
        """<LinearGradient startX="0" startY="225" endX="450" endY="225" """ +
          """colors="#ffff0000 #ff0000ff"/>""",
      )
    for (bitmap in listOf(renderWffFace(face, "gradient-linear"), export(face))) {
      val left = bitmap.at(20f, 225f)
      val right = bitmap.at(430f, 225f)
      assertTrue(left[1] > 200 && left[3] < 50, "left $left")
      assertTrue(right[3] > 200 && right[1] < 50, "right $right")
    }
    assertTrue("<linearGradient" in svg(face, "gradient-linear"))
  }

  @Test
  fun `a sweep gradient turns clockwise from its start angle at twelve o'clock`() {
    val face =
      gradientFace(
        """<SweepGradient centerX="225" centerY="225" startAngle="0" endAngle="360" """ +
          """colors="#ffff0000 #ff0000ff"/>""",
      )
    for (bitmap in listOf(renderWffFace(face, "gradient-sweep"), export(face))) {
      // Just past twelve it is the first colour, just before it the last, half way the mix.
      val start = bitmap.atPolar(150f, 5f)
      val end = bitmap.atPolar(150f, 355f)
      val half = bitmap.atPolar(150f, 180f)
      assertTrue(start[1] > 220 && start[3] < 35, "start $start")
      assertTrue(end[3] > 220 && end[1] < 35, "end $end")
      assertTrue(half[1] in 90..165 && half[3] in 90..165, "half $half")
    }
    // SVG has no sweep gradient, and Skia's SVG canvas drops one, leaving a black dial: the export
    // draws it as wedges of its colours instead.
    val svg = svg(face, "gradient-sweep")
    assertFalse(Regex("<ellipse(?![^>]*fill=)").containsMatchIn(svg), "an unpainted dial: $svg")
    assertTrue(Regex("<path").findAll(svg).count() >= 200, "wedges")
    val fills =
      Regex("fill=\"#([0-9A-Fa-f]{6})\"").findAll(svg).map { it.groupValues[1].toInt(16) }.toList()
    assertTrue(fills.any { it shr 16 > 0xf0 && it and 0xff < 0x20 }, "the first colour, red")
    assertTrue(fills.any { it and 0xff > 0xf0 && it shr 16 < 0x20 }, "the last colour, blue")
  }

  @Test
  fun `a translucent sweep is exported without bands where its wedges meet`() {
    // A constant half-transparent red: over the black scene it is ≈128 red everywhere. Wedges that
    // overlapped would paint the overlap twice, ≈191 red, in bands round the dial.
    val face =
      gradientFace(
        """<SweepGradient centerX="225" centerY="225" startAngle="0" endAngle="360" """ +
          """colors="#80ff0000 #80ff0000"/>""",
      )
    val bitmap = export(face)
    val reds = (0 until 720).map { bitmap.atPolar(150f, it / 2f)[1] }
    // At most the one coat everywhere. Where two translucent wedges meet edge to edge,
    // antialiasing leaves a faint seam a little under it (≈111), never a second coat.
    assertTrue(reds.all { it in 100..138 }, "reds ${reds.min()}..${reds.max()}")
  }

  @Test
  fun `a sweep on a line's stroke is exported as wedges too`() {
    val face =
      wffFace(
        listOf("hand"),
        paintedPart(
            "hand",
            mapOf("shape" to "line", "startX" to 225, "startY" to 225, "endX" to 225, "endY" to 40),
            "wff/stroke",
            mapOf("color" to "#ffffffff", "thickness" to 12),
            """<SweepGradient centerX="225" centerY="225" startAngle="0" endAngle="360" """ +
              """colors="#ffff0000 #ff0000ff"/>""",
          )
          .joinToString(","),
      )
    val svg = svg(face, "gradient-sweep-line")
    val fills =
      Regex("fill=\"#([0-9A-Fa-f]{6})\"").findAll(svg).map { it.groupValues[1].toInt(16) }.toList()
    // The line points to twelve, where the sweep starts and ends: red on one side, blue the other.
    assertTrue(fills.any { it shr 16 > 0xf0 && it and 0xff < 0x20 }, "red: $svg")
    assertTrue(fills.any { it and 0xff > 0xf0 && it shr 16 < 0x20 }, "blue")
    // And the PNG draws the line, not a black stroke.
    val pixel = export(face).at(222f, 120f)
    assertTrue(pixel[1] > 150 || pixel[3] > 150, "line $pixel")
  }

  @Test
  fun `a sweep stroke as wide as its shape is exported as wedges, not dropped`() {
    // A 60px stroke on a 100px rectangle leaves no hole; it still paints the sweep in the SVG.
    val face =
      wffFace(
        listOf("block"),
        paintedPart(
            "block",
            mapOf("shape" to "rectangle", "x" to 175, "y" to 175, "width" to 100, "height" to 100),
            "wff/stroke",
            mapOf("color" to "#ffffffff", "thickness" to 60),
            """<SweepGradient centerX="225" centerY="225" startAngle="0" endAngle="360" """ +
              """colors="#ffff0000 #ff0000ff"/>""",
          )
          .joinToString(","),
      )
    val svg = svg(face, "gradient-sweep-thick")
    assertTrue(Regex("<path").findAll(svg).count() >= 200, "wedges")
    val fills =
      Regex("fill=\"#([0-9A-Fa-f]{6})\"").findAll(svg).map { it.groupValues[1].toInt(16) }.toList()
    assertTrue(fills.any { it shr 16 > 0xf0 && it and 0xff < 0x20 }, "red")
    // The exported PNG paints it too, and nothing outside the rectangle.
    val bitmap = export(face)
    val inside = bitmap.at(200f, 200f)
    assertTrue(inside[1] > 60 || inside[3] > 60, "inside $inside")
    assertEquals(listOf(255, 0, 0, 0), bitmap.at(150f, 150f))
  }

  @Test
  fun `two sweep stops at one position make a hard edge, whichever way it turns`() {
    // Red for the first half turn, blue for the second, from three o'clock (WFF 90°).
    for (direction in listOf("CLOCKWISE", "COUNTER_CLOCKWISE")) {
      val face =
        gradientFace(
          """<SweepGradient centerX="225" centerY="225" startAngle="90" endAngle="450" """ +
            """direction="$direction" colors="#ffff0000 #ffff0000 #ff0000ff #ff0000ff" """ +
            """positions="0 0.5 0.5 1"/>""",
        )
      // Clockwise the first half is three to nine o'clock through six; counter-clockwise through
      // twelve.
      val redSide = if (direction == "CLOCKWISE") 180f else 0f
      val blueSide = if (direction == "CLOCKWISE") 0f else 180f
      for ((name, bitmap) in listOf("editor" to renderWffFace(face), "export" to export(face))) {
        for (offset in listOf(-60f, 0f, 60f)) {
          val red = bitmap.atPolar(150f, redSide + offset)
          val blue = bitmap.atPolar(150f, blueSide + offset)
          assertTrue(red[1] > 230 && red[3] < 25, "$direction $name red side $offset: $red")
          assertTrue(blue[3] > 230 && blue[1] < 25, "$direction $name blue side $offset: $blue")
        }
      }
    }
  }

  @Test
  fun `a sweep-stroked round rectangle has the editor's corners in the export`() {
    // 100px square, corner radius 20, stroke 10: the editor's outside corner is radius 25.
    val face =
      wffFace(
        listOf("card"),
        paintedPart(
            "card",
            mapOf(
              "shape" to "round-rectangle",
              "x" to 175,
              "y" to 175,
              "width" to 100,
              "height" to 100,
              "cornerRadiusX" to 20,
              "cornerRadiusY" to 20,
            ),
            "wff/stroke",
            mapOf("color" to "#ffffffff", "thickness" to 10),
            """<SweepGradient centerX="225" centerY="225" startAngle="0" endAngle="360" """ +
              """colors="#ffffffff #ffffffff"/>""",
          )
          .joinToString(","),
      )
    val editor = renderWffFace(face)
    val exported = export(face)
    // Along the corner's diagonal, from just outside the outer curve to inside the hole.
    var differing = 0
    for (step in 0..30) {
      val d = 175f + step
      val a = editor.at(d, d)[1]
      val b = exported.at(d, d)[1]
      if (abs(a - b) > 60) differing++
    }
    assertTrue(differing <= 1, "$differing pixels differ along the corner")
  }

  @Test
  fun `a sweep-stroked oval that is not a circle has the editor's outline in the export`() {
    // A flat oval with a thick stroke: its edges are offset curves, not ovals.
    val face =
      wffFace(
        listOf("lozenge"),
        paintedPart(
            "lozenge",
            mapOf("shape" to "ellipse", "x" to 25, "y" to 145, "width" to 400, "height" to 160),
            "wff/stroke",
            mapOf("color" to "#ffffffff", "thickness" to 50),
            """<SweepGradient centerX="225" centerY="225" startAngle="0" endAngle="360" """ +
              """colors="#ffffffff #ffffffff"/>""",
          )
          .joinToString(","),
      )
    val editor = renderWffFace(face)
    val exported = export(face)
    var differing = 0
    var lit = 0
    for (y in 0 until editor.height step 2) for (x in 0 until editor.width step 2) {
      val a = editor.argb(x, y)[1]
      val b = exported.argb(x, y)[1]
      if (a > 128) lit++
      if ((a > 128) != (b > 128)) differing++
    }
    // Only antialiased edge pixels may disagree; concentric ovals missed by thousands.
    assertTrue(lit > 2000, "lit $lit")
    assertTrue(differing < lit / 50, "$differing of $lit pixels differ")
  }

  @Test
  fun `a gradient in a stroke paints the stroke`() {
    val face =
      wffFace(
        listOf("ring"),
        paintedPart(
            "ring",
            wholeEllipse,
            "wff/stroke",
            mapOf("color" to "#ffffffff", "thickness" to 20),
            """<LinearGradient startX="0" startY="0" endX="450" endY="0" colors="#ffff0000 #ff0000ff"/>""",
          )
          .joinToString(","),
      )
    val bitmap = renderWffFace(face, "gradient-stroke")
    val left = bitmap.at(10f, 225f)
    val right = bitmap.at(440f, 225f)
    assertTrue(left[1] > 200 && left[3] < 50, "left $left")
    assertTrue(right[3] > 200 && right[1] < 50, "right $right")
    // Inside the ring the part draws nothing.
    assertEquals(listOf(255, 0, 0, 0), bitmap.at(225f, 225f))
  }

  @Test
  fun `a gradient whose colours are configuration references falls back to the flat colour`() {
    assertEquals(
      null,
      WffGradient.parse(
        """<RadialGradient centerX="1" centerY="1" radius="3" colors="[CONFIGURATION.a] #ff000000"/>""",
      ),
    )
    val face =
      gradientFace(
        """<RadialGradient centerX="1" centerY="1" radius="3" colors="[CONFIGURATION.a] #ff000000"/>""",
      )
    assertTrue(close(listOf(255, 0x10, 0x12, 0x14), renderWffFace(face).at(225f, 225f), 2))
  }

  @Test
  fun `positions are spread evenly when absent and ignored when they do not match the colours`() {
    val even =
      assertNotNull(
        WffGradient.parse("""<LinearGradient colors="#ff000000 #ff111111 #ff222222"/>"""),
      )
    assertEquals(listOf(0f, 0.5f, 1f), even.positions)
    val mismatched =
      assertNotNull(
        WffGradient.parse("""<LinearGradient colors="#ff000000 #ff111111" positions="0 0.2 1"/>"""),
      )
    assertEquals(listOf(0f, 1f), mismatched.positions)
  }

  // yschimke/watchface-tools#24

  /** The issue's repro: a part over the face with an inline `arc`, 0° to 90°. */
  private fun legacyArc(extra: Map<String, Any> = emptyMap()) =
    wffFace(
      listOf("arc"),
      wffNode(
        "arc",
        "wff/part-draw",
        mapOf(
          "shape" to "arc",
          "x" to 0,
          "y" to 0,
          "width" to 450,
          "height" to 450,
          "centerX" to 225,
          "centerY" to 225,
          "shapeWidth" to 401,
          "shapeHeight" to 401,
          "startAngle" to 0,
          "endAngle" to 90,
          "strokeColor" to "#ff00a19c",
          "strokeWidth" to 25,
        ) + extra,
      ),
    )

  @Test
  fun `an arc is drawn from twelve to three o'clock, not as a whole ring`() {
    val face = legacyArc()
    val teal = listOf(255, 0x00, 0xa1, 0x9c)
    // The stroke sits inside the arc's 401px oval, as an ellipse's does: radius 200.5 - 12.5.
    val ring = 188f
    for ((name, bitmap) in
      listOf("editor" to renderWffFace(face, "arc"), "export" to export(face))) {
      for (degrees in listOf(10f, 45f, 80f)) assertTrue(
        close(teal, bitmap.atPolar(ring, degrees)),
        "$name $degrees° ${bitmap.atPolar(ring, degrees)}",
      )
      for (degrees in listOf(135f, 180f, 270f, 315f, 350f)) assertEquals(
        listOf(255, 0, 0, 0),
        bitmap.atPolar(ring, degrees),
        "$name $degrees°",
      )
    }
    val svg = svg(face, "arc")
    assertFalse("<ellipse" in svg, "an arc is not exported as a closed ellipse")
    assertTrue(Regex("<path[^>]*stroke=\"#00A19C\"").containsMatchIn(svg), svg)
  }

  @Test
  fun `a counter-clockwise arc runs the other way round from its start to its end`() {
    val face = legacyArc(mapOf("direction" to "COUNTER_CLOCKWISE"))
    val bitmap = renderWffFace(face, "arc-ccw")
    val teal = listOf(255, 0x00, 0xa1, 0x9c)
    for (degrees in listOf(135f, 180f, 270f, 350f)) assertTrue(
      close(teal, bitmap.atPolar(188f, degrees)),
      "$degrees° ${bitmap.atPolar(188f, degrees)}",
    )
    assertEquals(listOf(255, 0, 0, 0), bitmap.atPolar(188f, 45f))
  }

  @Test
  fun `a wff shape arc reads its oval from its centre and size`() {
    val face =
      wffFace(
        listOf("gauge"),
        paintedPart(
            "gauge",
            mapOf(
              "shape" to "arc",
              "centerX" to 225,
              "centerY" to 225,
              "width" to 300,
              "height" to 300,
              "startAngle" to -150,
              "endAngle" to 150,
            ),
            "wff/stroke",
            mapOf("color" to "#ffffffff", "thickness" to 20),
            null,
          )
          .joinToString(","),
      )
    val bitmap = renderWffFace(face, "arc-gauge")
    // The format's own gauge: 300° through twelve o'clock, the gap at six.
    for (degrees in listOf(-140f, -90f, 0f, 90f, 140f)) assertEquals(
      listOf(255, 255, 255, 255),
      bitmap.atPolar(140f, degrees),
      "$degrees°",
    )
    assertEquals(listOf(255, 0, 0, 0), bitmap.atPolar(140f, 180f))
  }

  @Test
  fun `arc sweeps follow the WFF conventions`() {
    assertEquals(90f, wffArcSweep(0f, 90f, clockwise = true))
    assertEquals(300f, wffArcSweep(-150f, 150f, clockwise = true))
    assertEquals(360f, wffArcSweep(0f, 360f, clockwise = true))
    assertEquals(270f, wffArcSweep(90f, 0f, clockwise = true))
    assertEquals(-270f, wffArcSweep(0f, 90f, clockwise = false))
    assertEquals(-90f, wffArcSweep(90f, 0f, clockwise = false))
    assertEquals(0f, wffArcSweep(-150f, -150f, clockwise = true))
    // A whole turn written either way round still draws the whole ring.
    assertEquals(-360f, wffArcSweep(0f, 360f, clockwise = false))
    assertEquals(360f, wffArcSweep(360f, 0f, clockwise = true))
    assertEquals(0f, wffArcSweep(90f, 90f, clockwise = false))
  }

  // yschimke/watchface-tools#25

  private fun rotatedTickFace() =
    wffFace(
      listOf("group"),
      listOf(
          wffNode(
            "group",
            "wff/group",
            mapOf(
              "x" to 0,
              "y" to 0,
              "width" to 450,
              "height" to 450,
              "angle" to 90,
              "pivotX" to 0.5,
              "pivotY" to 0.5,
            ),
            mapOf("content" to listOf("tick")),
          ),
          wffNode(
            "tick",
            "wff/part-draw",
            mapOf(
              "x" to 215,
              "y" to 10,
              "width" to 20,
              "height" to 60,
              "shape" to "rectangle",
              "fillColor" to "#ffffffff",
            ),
          ),
        )
        .joinToString(","),
    )

  @Test
  fun `a rotated group is drawn rotated by the editor and both exports`() {
    val face = rotatedTickFace()
    // The tick is authored at twelve o'clock and the group turns it a quarter to three.
    for ((name, bitmap) in
      listOf("editor" to renderWffFace(face, "group-rotated"), "export" to export(face))) {
      assertEquals(listOf(255, 255, 255, 255), bitmap.at(400f, 225f), "$name three o'clock")
      assertEquals(listOf(255, 0, 0, 0), bitmap.at(225f, 40f), "$name twelve o'clock")
    }
  }

  @Test
  fun `an export turns a group with a canvas transform the SVG keeps, not a layer`() {
    val face = rotatedTickFace()
    val svg = svg(face, "group-rotated")
    // A quarter turn: matrix(0 1 -1 0 …), however Skia rounds the zeros.
    val rect = Regex("<rect fill=\"white\"[^>]*>").find(svg)?.value
    assertNotNull(rect, svg)
    val matrix =
      Regex("matrix\\(([^)]*)\\)").find(rect)?.groupValues?.get(1)?.split(Regex("[ ,]+"))?.map {
        it.toFloat()
      }
    assertNotNull(matrix, rect)
    assertEquals(listOf(0f, 1f, -1f, 0f), matrix.take(4).map { (it * 1000).roundToInt() / 1000f })
    // The tick's laid-out box is where it was authored: the turn is drawn, not a layer's, so a
    // recorder that drops layer transforms still gets it.
    val tick = JvmSkiaStructuredSvgRecorder.measuredBoundsDp(face).getValue("tick")
    assertEquals(215 * 227 / 450.0, tick.x, 0.6)
    assertEquals(10 * 227 / 450.0, tick.y, 0.6)
  }

  @Test
  fun `a scaled group is drawn scaled by the editor and both exports`() {
    // The tick at 215..235 × 10..70, halved about the face's centre, lands at 220..230 × 117..147.
    val face =
      wffFace(
        listOf("group"),
        listOf(
            wffNode(
              "group",
              "wff/group",
              mapOf(
                "x" to 0,
                "y" to 0,
                "width" to 450,
                "height" to 450,
                "scaleX" to 0.5,
                "scaleY" to 0.5,
                "pivotX" to 0.5,
                "pivotY" to 0.5,
              ),
              mapOf("content" to listOf("tick")),
            ),
            wffNode(
              "tick",
              "wff/part-draw",
              mapOf(
                "x" to 215,
                "y" to 10,
                "width" to 20,
                "height" to 60,
                "shape" to "rectangle",
                "fillColor" to "#ffffffff",
              ),
            ),
          )
          .joinToString(","),
      )
    for ((name, bitmap) in listOf("editor" to renderWffFace(face), "export" to export(face))) {
      assertEquals(listOf(255, 255, 255, 255), bitmap.at(225f, 132f), "$name scaled tick")
      assertEquals(listOf(255, 0, 0, 0), bitmap.at(225f, 40f), "$name unscaled place")
    }
  }

  // yschimke/watchface-tools#26

  @Test
  fun `an ellipse's stroke lies inside its bounds, as the format's samples lay out rings`() {
    // As `Weather`'s BackgroundContour: a 10px stroke on the 450px ellipse, its content group at
    // x=10. The ring covers radius 215 to 225, not 220 to 230.
    val face =
      wffFace(
        listOf("ring"),
        paintedPart(
            "ring",
            wholeEllipse,
            "wff/stroke",
            mapOf("color" to "#ffffffff", "thickness" to 10),
            null,
          )
          .joinToString(","),
      )
    for (bitmap in listOf(renderWffFace(face, "stroke-inset"), export(face))) {
      assertEquals(listOf(255, 255, 255, 255), bitmap.atPolar(218f, 45f))
      assertEquals(listOf(255, 255, 255, 255), bitmap.atPolar(222f, 135f))
      assertEquals(listOf(255, 0, 0, 0), bitmap.atPolar(211f, 45f))
    }
  }
}
