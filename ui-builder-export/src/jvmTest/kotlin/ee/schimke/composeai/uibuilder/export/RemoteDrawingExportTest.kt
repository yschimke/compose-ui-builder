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
 * A `draw/canvas` is written as `RemoteCanvas { }` with each operation as its `RemoteDrawScope`
 * call, and its geometry and paint in the player's terms: pixels from dp, theme colours read above
 * the draw lambda, computed values as live expressions.
 */
class RemoteDrawingExportTest {
  private val document: UiBuilderDocument =
    Json.decodeFromJsonElement(Json.parseToJsonElement(GAUGE) as JsonObject)

  private fun exported(): String =
    assertIs<WearWidgetCodeExporter.Result.Emitted>(
        WearWidgetCodeExporter.export(document, packageName = "proof.draw")
      )
      .source
      .also { source ->
        // Kept for compiling against the released libraries; see RemoteDrawingCompileProof.
        File("build/draw-proof").apply { mkdirs() }.resolve("GaugeWidget.kt").writeText(source)
      }

  @Test
  fun `a canvas is a RemoteCanvas with its operations in order`() {
    val source = exported()

    // The frame-filling root keeps the `fillMaxSize` every widget body starts with.
    assertContains(
      source,
      "RemoteCanvas(modifier = RemoteModifier.fillMaxSize().size(96.rdp, 96.rdp)) {",
    )
    assertTrue(source.indexOf("drawArc(") < source.indexOf("withTransform("), source)
    assertContains(source, "import androidx.compose.remote.creation.compose.layout.RemoteCanvas")
  }

  @Test
  fun `a stroked arc with a defaulted box is inset by half its stroke`() {
    val source = exported()

    assertContains(source, "style = PaintingStyle.Stroke")
    assertContains(source, "strokeWidth = 8.rdp.toPx()")
    assertContains(source, "strokeCap = StrokeCap.Round")
    assertContains(source, "topLeft = RemoteOffset((8.rdp.toPx() / 2.rf), (8.rdp.toPx() / 2.rf))")
    assertContains(source, "size = RemoteSize((96.rdp.toPx() - (8.rdp.toPx() / 2.rf) * 2.rf)")
    assertTrue("import androidx.compose.remote.creation.compose.state.ri\n" !in source, source)
  }

  @Test
  fun `a theme colour is read above the canvas, where composition is`() {
    val source = exported()

    val read = source.indexOf("= RemoteMaterialTheme.colorScheme.outlineVariant")
    assertTrue(read in 0 until source.indexOf("RemoteCanvas("), source)
  }

  @Test
  fun `a computed sweep and a clock-driven hand are live expressions`() {
    val source = exported()

    assertContains(source, "(progress * 360.rf)")
    assertContains(
      source,
      "rotate(((RemoteTime().Seconds() % 60.rf) * 6.rf), RemoteOffset((96.rdp.toPx() / 2.rf), (96.rdp.toPx() / 2.rf)))",
    )
    assertContains(source, "val progress = rememberMutableRemoteFloat(0.65f)")
  }

  @Test
  fun `text sizes are sp and transparent is a literal`() {
    fun text(id: String, properties: String) =
      Json.decodeFromString<UiBuilderNode>(
        """{"id":"$id","componentId":"draw/text","properties":$properties}"""
      )
    val canvas = document.nodes.getValue("canvas")
    val labelled =
      document.copy(
        nodes =
          document.nodes +
            ("whole" to
              text(
                "whole",
                """{"text":{"type":"string","value":"A"},
                  "textSizeSp":{"type":"float","value":16},
                  "color":{"type":"colorToken","value":"transparent"}}""",
              )) +
            ("half" to
              text(
                "half",
                """{"text":{"type":"string","value":"B"},
                  "textSizeSp":{"type":"float","value":14.5}}""",
              )) +
            ("live" to
              text(
                "live",
                """{"text":{"type":"string","value":"C"},
                  "textSizeSp":{"type":"expr","op":"mul","args":[
                    {"type":"state","variable":"progress"},{"type":"int","value":20}]}}""",
              )) +
            ("canvas" to
              canvas.copy(
                slots =
                  mapOf("ops" to canvas.slots.getValue("ops") + listOf("whole", "half", "live"))
              ))
      )

    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(WearWidgetCodeExporter.export(labelled))
        .source

    assertContains(source, "textSize = 16.rsp.toPx()")
    assertContains(source, "textSize = 14.5f.sp.asRemoteTextUnit().toPx()")
    assertContains(source, "textSize = ((progress * 20.rf)) * 1.rsp.toPx()")
    assertContains(source, "color = Color(0x00000000).rc")
    assertTrue("colorScheme.transparent" !in source, source)
  }

  @Test
  fun `an operation outside a canvas is refused by name`() {
    val stray =
      document.copy(
        nodes =
          document.nodes +
            ("host" to
              document.nodes.getValue("host").copy(slots = mapOf("content" to listOf("track"))))
      )

    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(WearWidgetCodeExporter.export(stray))

    assertTrue(
      refused.reasons.any { "track" in it && "draw/canvas" in it },
      refused.reasons.toString(),
    )
  }

  companion object {
    /** A progress ring and a seconds hand over a 96dp canvas. */
    val GAUGE =
      """
      {"schema":"ui-builder-design-v1","id":"gauge","title":"Gauge","revision":1,
       "catalogPin":{},"environment":{},
       "stateVariables":{"progress":{"type":"value","valueType":"float","initialValue":0.65,
         "nullable":false,"persistence":"session"}},
       "roots":["host"],
       "nodes":{
        "host":{"id":"host","componentId":"remote-m3/widget-container-large",
          "slots":{"content":["canvas"]}},
        "canvas":{"id":"canvas","componentId":"draw/canvas",
          "modifiers":[{"type":"size","widthDp":96,"heightDp":96}],
          "slots":{"ops":["track","sweep","hand"]}},
        "track":{"id":"track","componentId":"draw/arc","properties":{
          "style":{"type":"enum","value":"stroke"},
          "strokeWidthDp":{"type":"float","value":8},
          "color":{"type":"colorToken","value":"outlineVariant"}}},
        "sweep":{"id":"sweep","componentId":"draw/arc","properties":{
          "style":{"type":"enum","value":"stroke"},
          "strokeWidthDp":{"type":"float","value":8},
          "strokeCap":{"type":"enum","value":"round"},
          "startAngle":{"type":"float","value":-90},
          "sweepAngle":{"type":"expr","op":"mul","args":[
            {"type":"state","variable":"progress"},{"type":"int","value":360}]},
          "color":{"type":"color","value":"#FF6750A4"}}},
        "hand":{"id":"hand","componentId":"draw/group","properties":{
          "rotate":{"type":"expr","op":"mul","args":[
            {"type":"expr","op":"mod","args":[
              {"type":"system","value":"time.secondOfHour"},{"type":"int","value":60}]},
            {"type":"int","value":6}]}},
          "slots":{"ops":["needle"]}},
        "needle":{"id":"needle","componentId":"draw/line","properties":{
          "startXDp":{"type":"float","value":48},"startYDp":{"type":"float","value":48},
          "endXDp":{"type":"float","value":48},"endYDp":{"type":"float","value":12},
          "strokeWidthDp":{"type":"float","value":2},
          "color":{"type":"color","value":"#FFFFFFFF"}}}}}
      """
        .trimIndent()
  }
}
