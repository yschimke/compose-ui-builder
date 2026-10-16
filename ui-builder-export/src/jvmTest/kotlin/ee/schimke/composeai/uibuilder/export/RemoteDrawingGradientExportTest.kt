package ee.schimke.composeai.uibuilder.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * A draw operation's paint may be a gradient: a `RemoteBrush` from its `color` to its
 * `gradientColor`, applied to the `RemotePaint` over the whole canvas.
 */
class RemoteDrawingGradientExportTest {
  private fun exported(gradient: String, extra: String = ""): String {
    val gauge =
      RemoteDrawingExportTest.GAUGE.replace(
        """"color":{"type":"colorToken","value":"outlineVariant"}}}""",
        """"color":{"type":"colorToken","value":"outlineVariant"},
          "gradient":{"type":"enum","value":"$gradient"},
          "gradientColor":{"type":"color","value":"#FF00FF00"}$extra}}""",
      )
    check(gauge != RemoteDrawingExportTest.GAUGE)
    val document: UiBuilderDocument =
      Json.decodeFromJsonElement(Json.parseToJsonElement(gauge) as JsonObject)
    return assertIs<WearWidgetCodeExporter.Result.Emitted>(
        WearWidgetCodeExporter.export(document, packageName = "proof.draw"),
        (WearWidgetCodeExporter.export(document) as? WearWidgetCodeExporter.Result.Refused)
          ?.reasons
          ?.toString(),
      )
      .source
      .also { source ->
        File("build/draw-proof")
          .apply { mkdirs() }
          .resolve("Gradient${gradient.replaceFirstChar(Char::uppercaseChar)}Widget.kt")
          .writeText(source.replace("GaugeWidget", "Gradient${gradient}Widget"))
      }
  }

  @Test
  fun `each gradient is the RemoteBrush of that name, over the canvas`() {
    UiDrawing.GRADIENTS.forEach { gradient ->
      val source = exported(gradient)

      assertContains(
        source,
        "import androidx.compose.remote.creation.compose.shaders.${gradient}Gradient",
      )
      assertContains(
        source,
        "with(RemoteBrush.${gradient}Gradient(listOf(drawColor, Color(0xFF00FF00).rc))) { " +
          "applyTo(this@RemotePaint, RemoteSize(96.rdp.toPx(), 96.rdp.toPx())) }",
      )
    }
  }

  @Test
  fun `alpha fades both ends, and an absent end colour is transparent`() {
    val faded = exported("sweep", extra = ""","alpha":{"type":"float","value":0.5}""")
    assertContains(
      faded,
      "listOf(drawColor.let { it.copy(alpha = it.alpha * 0.5f.rf) }, " +
        "Color(0xFF00FF00).rc.let { it.copy(alpha = it.alpha * 0.5f.rf) })",
    )

    val document: UiBuilderDocument =
      Json.decodeFromJsonElement(
        Json.parseToJsonElement(
          RemoteDrawingExportTest.GAUGE.replace(
            """"color":{"type":"colorToken","value":"outlineVariant"}}}""",
            """"color":{"type":"colorToken","value":"outlineVariant"},
              "gradient":{"type":"enum","value":"radial"}}}""",
          )
        ) as JsonObject
      )
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(WearWidgetCodeExporter.export(document))
        .source
    assertContains(source, "listOf(drawColor, Color(0x00000000).rc)")
  }

  @Test
  fun `a gradient's ends, centre, radius and tile mode are its brush's arguments`() {
    val horizontal =
      exported(
        "horizontal",
        extra =
          ""","gradientStartDp":{"type":"float","value":8},
            "gradientEndDp":{"type":"float","value":40},
            "gradientTileMode":{"type":"enum","value":"mirror"}""",
      )
    assertContains(
      horizontal,
      "RemoteBrush.horizontalGradient(listOf(drawColor, Color(0xFF00FF00).rc), " +
        "startX = 8.rdp.toPx(), endX = 40.rdp.toPx(), tileMode = TileMode.Mirror)",
    )
    assertTrue("import androidx.compose.ui.graphics.TileMode" in horizontal.lines(), horizontal)

    val radial =
      exported(
        "radial",
        extra =
          ""","gradientCenterXDp":{"type":"float","value":20},
            "gradientRadiusDp":{"type":"float","value":30}""",
      )
    assertContains(
      radial,
      "RemoteBrush.radialGradient(listOf(drawColor, Color(0xFF00FF00).rc), " +
        "center = RemoteOffset(20.rdp.toPx(), (96.rdp.toPx() / 2.rf)), radius = 30.rdp.toPx())",
    )
  }

  @Test
  fun `listed stops pair each colour with its place, and the ends default to the edges`() {
    val source =
      exported(
        "vertical",
        extra =
          ""","gradientColors":{"type":"string","value":"#FF0000, #00FF00, #0000FF"},
            "gradientStops":{"type":"string","value":"0, 0.2, 1"}""",
      )
    assertContains(
      source,
      "RemoteBrush.verticalGradient(0.rf to Color(0xFFFF0000).rc, 0.2f.rf to Color(0xFF00FF00).rc, " +
        "1.rf to Color(0xFF0000FF).rc, startY = null, endY = null)",
    )
  }

  @Test
  fun `a sweep's tile mode, or stops that do not match the colours, are refused by name`() {
    fun refused(gradient: String, extra: String): List<String> {
      val gauge =
        RemoteDrawingExportTest.GAUGE.replace(
          """"color":{"type":"colorToken","value":"outlineVariant"}}}""",
          """"color":{"type":"colorToken","value":"outlineVariant"},
            "gradient":{"type":"enum","value":"$gradient"}$extra}}""",
        )
      val document: UiBuilderDocument =
        Json.decodeFromJsonElement(Json.parseToJsonElement(gauge) as JsonObject)
      return assertIs<WearWidgetCodeExporter.Result.Refused>(
          WearWidgetCodeExporter.export(document)
        )
        .reasons
    }
    val tiled = refused("sweep", ""","gradientTileMode":{"type":"enum","value":"repeated"}""")
    assertTrue(tiled.any { "nodes.track.gradientTileMode" in it }, tiled.toString())
    val stops =
      refused(
        "horizontal",
        ""","gradientColors":{"type":"string","value":"#FF0000, #0000FF"},
          "gradientStops":{"type":"string","value":"0, 0.5, 1"}""",
      )
    assertTrue(stops.any { "nodes.track.gradientStops" in it }, stops.toString())
    val outside =
      refused(
        "horizontal",
        ""","gradientColors":{"type":"string","value":"#FF0000, #0000FF"},
          "gradientStops":{"type":"string","value":"0, 2"}""",
      )
    assertTrue(outside.any { "nodes.track.gradientStops" in it }, outside.toString())
    val flat = refused("radial", ""","gradientRadiusDp":{"type":"float","value":0}""")
    assertTrue(flat.any { "nodes.track.gradientRadiusDp" in it }, flat.toString())
  }

  @Test
  fun `a shape without a gradient keeps its plain colour`() {
    val document: UiBuilderDocument =
      Json.decodeFromJsonElement(
        Json.parseToJsonElement(RemoteDrawingExportTest.GAUGE) as JsonObject
      )
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(WearWidgetCodeExporter.export(document))
        .source

    assertTrue("RemoteBrush" !in source, source)
  }
}
