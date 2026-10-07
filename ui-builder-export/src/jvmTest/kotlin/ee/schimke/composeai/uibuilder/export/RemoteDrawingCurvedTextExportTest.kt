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
 * Text may curve round a circle (`drawTextOnCircle`, as `RemoteTimeText` draws the time) or run
 * along a path in dp (`drawTextOnPath`, inside a density scale so the glyphs keep their size).
 */
class RemoteDrawingCurvedTextExportTest {
  private fun document(op: String): UiBuilderDocument =
    Json.decodeFromJsonElement(
      Json.parseToJsonElement(
        """
        {"schema":"ui-builder-design-v1","id":"curves","title":"Curves","revision":1,
         "catalogPin":{},"environment":{},
         "stateVariables":{"steps":{"type":"value","valueType":"int","initialValue":4200,
           "nullable":false,"persistence":"session"}},
         "roots":["host"],
         "nodes":{
          "host":{"id":"host","componentId":"remote-m3/widget-container-large",
            "slots":{"content":["canvas"]}},
          "canvas":{"id":"canvas","componentId":"draw/canvas",
            "modifiers":[{"type":"size","widthDp":96,"heightDp":96}],
            "slots":{"ops":["label"]}},
          "label":$op}}
        """
      ) as JsonObject
    )

  private fun exported(document: UiBuilderDocument, name: String): String =
    assertIs<WearWidgetCodeExporter.Result.Emitted>(
        WearWidgetCodeExporter.export(document, packageName = "proof.draw"),
        (WearWidgetCodeExporter.export(document) as? WearWidgetCodeExporter.Result.Refused)
          ?.reasons
          ?.toString(),
      )
      .source
      .also { source ->
        File("build/draw-proof")
          .apply { mkdirs() }
          .resolve("${name}Widget.kt")
          .writeText(source.replace("CurvesWidget", "${name}Widget"))
      }

  @Test
  fun `text on a circle is drawTextOnCircle, centred at the top by default`() {
    val source =
      exported(
        document(
          """{"id":"label","componentId":"draw/text-circle","properties":{
            "text":{"type":"expr","op":"concat","args":[
              {"type":"state","variable":"steps"},{"type":"string","value":" steps"}]},
            "color":{"type":"color","value":"#FFFFFFFF"}}}"""
        ),
        "TextCircle",
      )

    assertContains(source, "drawTextOnCircle(")
    assertContains(source, "(min(96.rdp.toPx(), 96.rdp.toPx()) / 2.rf - 14.rsp.toPx())")
    assertContains(source, "270.rf,")
    assertContains(source, "textSize = 14.rsp.toPx()")
  }

  @Test
  fun `text on a path is drawn in dp, with its text size divided back out`() {
    val source =
      exported(
        document(
          """{"id":"label","componentId":"draw/text-path","properties":{
            "text":{"type":"string","value":"along the way"},
            "pathData":{"type":"string","value":"M8 80 Q48 8 88 80"},
            "startDp":{"type":"float","value":4},
            "color":{"type":"color","value":"#FFFFFFFF"}}}"""
        ),
        "TextPath",
      )

    assertContains(
      source,
      "withTransform({ scale(1.rdp.toPx(), 1.rdp.toPx(), RemoteOffset(0.rf, 0.rf)) }) {",
    )
    assertContains(
      source,
      "drawTextOnPath(\"along the way\".rs, RemotePath(\"M8 80 Q48 8 88 80\"), " +
        "hOffset = 4.rf, vOffset = 0.rf, paint = paintLabel)",
    )
    assertContains(source, "textSize = 14.rsp.toPx() / 1.rdp.toPx()")
  }

  @Test
  fun `text on a path needs a path`() {
    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(
        WearWidgetCodeExporter.export(
          document(
            """{"id":"label","componentId":"draw/text-path","properties":{
              "text":{"type":"string","value":"lost"}}}"""
          )
        )
      )

    assertTrue(refused.reasons.any { "label.pathData" in it }, refused.reasons.toString())
  }
}
