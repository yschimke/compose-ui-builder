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
 * A `draw/polyline` is a path built while the document is written, one segment per point, its
 * coordinates live formulas over the point's index.
 */
class RemoteDrawingPolylineExportTest {
  private fun formula(text: String) = UiExpressions.parseFormula(text, setOf("level")).toString()

  private fun document(properties: String): UiBuilderDocument =
    Json.decodeFromJsonElement(
      Json.parseToJsonElement(
        """
        {"schema":"ui-builder-design-v1","id":"plots","title":"Plots","revision":1,
         "catalogPin":{},"environment":{},
         "stateVariables":{"level":{"type":"value","valueType":"float","initialValue":0.5,
           "nullable":false,"persistence":"session"}},
         "roots":["host"],
         "nodes":{
          "host":{"id":"host","componentId":"remote-m3/widget-container-large",
            "slots":{"content":["canvas"]}},
          "canvas":{"id":"canvas","componentId":"draw/canvas",
            "modifiers":[{"type":"size","widthDp":96,"heightDp":96}],
            "slots":{"ops":["wave"]}},
          "wave":{"id":"wave","componentId":"draw/polyline","properties":{$properties}}}}
        """
      ) as JsonObject
    )

  private val wave =
    document(
      """
      "count":{"type":"float","value":12},
      "pointXDp":${formula("@i * 8")},
      "pointYDp":${formula("48 + sin(@i + time.continuousSecond) * 40 * level")},
      "closed":{"type":"bool","value":true},
      "style":{"type":"enum","value":"stroke"},
      "strokeWidthDp":{"type":"float","value":2},
      "color":{"type":"color","value":"#FF6750A4"}
      """
    )

  @Test
  fun `points are a path built per index with live coordinates`() {
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          WearWidgetCodeExporter.export(wave, packageName = "proof.draw")
        )
        .source
    File("build/draw-proof")
      .apply { mkdirs() }
      .resolve("PolylineWidget.kt")
      .writeText(source.replace("PlotsWidget", "PolylineWidget"))

    assertContains(source, "val pathWave = remotePath {")
    assertContains(source, "for (i in 0 until 12) {")
    assertContains(source, "if (i == 0) moveTo(point) else lineTo(point)")
    assertContains(source, "i.rf")
    assertContains(source, "close()")
    assertContains(source, "drawPath(pathWave, paintWave)")
    assertContains(source, "import androidx.compose.remote.creation.compose.state.remotePath")
  }

  @Test
  fun `a polyline needs a literal count and both coordinates`() {
    for ((properties, reason) in
      listOf(
        """"count":{"type":"float","value":1},"pointXDp":${formula("@i")},"pointYDp":${formula("@i")}""" to
          "literal count of points from 2",
        """"count":${formula("level * 10")},"pointXDp":${formula("@i")},"pointYDp":${formula("@i")}""" to
          "literal count of points from 2",
        """"count":{"type":"float","value":4},"pointXDp":${formula("@i")}""" to
          "pointYDp: a draw/polyline needs a formula",
      )) {
      val refused =
        assertIs<WearWidgetCodeExporter.Result.Refused>(
          WearWidgetCodeExporter.export(document(properties))
        )
      assertTrue(refused.reasons.any { reason in it }, refused.reasons.toString())
    }
  }
}
