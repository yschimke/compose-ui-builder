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
