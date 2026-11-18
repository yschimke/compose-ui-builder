package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.svg.JvmSkiaStructuredSvgRecorder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM

/**
 * One face with each thing the watch face fidelity issues found: a radial-gradient dial (the
 * issue's own), a sweep-gradient gauge, an inline arc, and twelve indices each turned by its own
 * `wff/group`. Drawn by the editor canvas and by its SVG export rasterised again, and kept under
 * `build/watchface-renders/` for a pull request to show.
 */
class WatchFaceFidelityShowcaseTest {
  private fun face() =
    wffFace(
      listOf("dial", "gauge", "arc") + (0 until 12).map { "tick-group-$it" },
      buildList {
          add(
            wffNode(
              "dial",
              "wff/part-draw",
              mapOf("x" to 0, "y" to 0, "width" to 450, "height" to 450, "shape" to "none"),
              mapOf("content" to listOf("dial-shape")),
            ),
          )
          add(
            wffNode(
              "dial-shape",
              "wff/shape",
              mapOf("shape" to "ellipse", "x" to 0, "y" to 0, "width" to 450, "height" to 450),
              mapOf("content" to listOf("dial-fill")),
            ),
          )
          add(
            wffNode(
              "dial-fill",
              "wff/fill",
              mapOf("color" to "#ff101214"),
              mapOf("content" to listOf("dial-gradient")),
            ),
          )
          add(
            wffNode(
              "dial-gradient",
              "wff/raw",
              mapOf(
                "xml" to
                  """<RadialGradient centerX="190" centerY="160" radius="300" """ +
                    """colors="#ff5a6470 #ff1a1d21 #ff07080a" positions="0 0.55 1"/>""",
              ),
            ),
          )
          add(
            wffNode(
              "gauge",
              "wff/part-draw",
              mapOf("x" to 0, "y" to 0, "width" to 450, "height" to 450, "shape" to "none"),
              mapOf("content" to listOf("gauge-shape")),
            ),
          )
          add(
            wffNode(
              "gauge-shape",
              "wff/shape",
              mapOf(
                "shape" to "arc",
                "centerX" to 225,
                "centerY" to 225,
                "width" to 430,
                "height" to 430,
                "startAngle" to -150,
                "endAngle" to 150,
              ),
              mapOf("content" to listOf("gauge-stroke")),
            ),
          )
          add(
            wffNode(
              "gauge-stroke",
              "wff/stroke",
              mapOf("color" to "#ffffffff", "thickness" to 16),
              mapOf("content" to listOf("gauge-gradient")),
            ),
          )
          add(
            wffNode(
              "gauge-gradient",
              "wff/raw",
              mapOf(
                "xml" to
                  """<SweepGradient centerX="225" centerY="225" startAngle="-150" endAngle="150" """ +
                    """colors="#ff00a19c #ffffc107 #ffff5252"/>""",
              ),
            ),
          )
          add(
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
                "shapeWidth" to 250,
                "shapeHeight" to 250,
                "startAngle" to 0,
                "endAngle" to 90,
                "strokeColor" to "#ff00a19c",
                "strokeWidth" to 14,
              ),
            ),
          )
          for (i in 0 until 12) {
            add(
              wffNode(
                "tick-group-$i",
                "wff/group",
                mapOf(
                  "x" to 0,
                  "y" to 0,
                  "width" to 450,
                  "height" to 450,
                  "angle" to i * 30,
                  "pivotX" to 0.5,
                  "pivotY" to 0.5,
                ),
                mapOf("content" to listOf("tick-$i")),
              ),
            )
            add(
              wffNode(
                "tick-$i",
                "wff/part-draw",
                mapOf(
                  "x" to 220,
                  "y" to 36,
                  "width" to 10,
                  "height" to if (i % 3 == 0) 40 else 24,
                  "shape" to "rectangle",
                  "fillColor" to "#ffeeeeee",
                ),
              ),
            )
          }
        }
        .joinToString(","),
      clipShape = "CIRCLE",
    )

  @Test
  fun `the showcase face is drawn by the canvas and its SVG export`() {
    val face = face()
    renderWffFace(face, "showcase-canvas")
    val svg = JvmSkiaStructuredSvgRecorder.record(face).svg
    val out = File("build/watchface-renders").apply { mkdirs() }
    File(out, "showcase.svg").writeText(svg)
    val dom =
      SVGDOM(Data.makeFromBytes(svg.encodeToByteArray())).apply { setContainerSize(454f, 454f) }
    val surface = Surface.makeRasterN32Premul(454, 454)
    try {
      dom.render(surface.canvas)
      val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)
      File(out, "showcase-svg.png").writeBytes(checkNotNull(png).bytes)
    } finally {
      surface.close()
    }
    assertTrue(svg.startsWith("<?xml"))
  }
}
