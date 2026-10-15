package ee.schimke.composeai.uibuilder.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * A clip may follow an SVG path rather than a rectangle, and a `draw/morph` tweens between two
 * paths of the same shape as the player's `drawTweenPath` does.
 */
class RemoteDrawingPathExportTest {
  private fun document(nodes: String, ops: String): UiBuilderDocument =
    Json.decodeFromJsonElement(
      Json.parseToJsonElement(
        """
        {"schema":"ui-builder-design-v1","id":"paths","title":"Paths","revision":1,
         "catalogPin":{},"environment":{},
         "stateVariables":{"open":{"type":"value","valueType":"float","initialValue":0.25,
           "nullable":false,"persistence":"session"}},
         "roots":["host"],
         "nodes":{
          "host":{"id":"host","componentId":"remote-m3/widget-container-large",
            "slots":{"content":["canvas"]}},
          "canvas":{"id":"canvas","componentId":"draw/canvas",
            "modifiers":[{"type":"size","widthDp":96,"heightDp":96}],
            "slots":{"ops":[$ops]}},
          $nodes}}
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
          .writeText(source.replace("PathsWidget", "${name}Widget"))
      }

  private val clip =
    document(
      """
      "lens":{"id":"lens","componentId":"draw/clip","properties":{
        "pathData":{"type":"string","value":"M12 2 L22 22 L2 22 Z"}},
        "slots":{"ops":["fill"]}},
      "fill":{"id":"fill","componentId":"draw/rect","properties":{
        "color":{"type":"color","value":"#FF6750A4"}}}
      """,
      "\"lens\"",
    )

  private val morph =
    document(
      """
      "mouth":{"id":"mouth","componentId":"draw/morph","properties":{
        "pathData":{"type":"string","value":"M4 12 Q12 12 20 12"},
        "toPathData":{"type":"string","value":"M4 12 Q12 22 20 12"},
        "progress":{"type":"state","variable":"open"},
        "style":{"type":"enum","value":"stroke"},
        "strokeWidthDp":{"type":"float","value":2},
        "color":{"type":"color","value":"#FFFFFFFF"}}}
      """,
      "\"mouth\"",
    )

  @Test
  fun `a path clip scales into its viewport, clips, and scales back out`() {
    val source = exported(clip, "PathClip")

    assertContains(
      source,
      "withTransform({ scale(96.rdp.toPx() / 24.rf, 96.rdp.toPx() / 24.rf); " +
        "clipPath(remotePathOf(\"M12 2 L22 22 L2 22 Z\")); " +
        "scale(24.rf / 96.rdp.toPx(), 24.rf / 96.rdp.toPx()) }) {",
    )
    assertContains(source, "import androidx.compose.remote.creation.compose.path.RemotePath")
  }

  @Test
  fun `an excluding path clip is a Difference`() {
    val excluded =
      clip.copy(
        nodes =
          clip.nodes +
            ("lens" to
              clip.nodes
                .getValue("lens")
                .copy(
                  properties =
                    JsonObject(
                      clip.nodes.getValue("lens").properties +
                        ("exclude" to Json.parseToJsonElement("""{"type":"bool","value":true}"""))
                    )
                ))
      )

    assertContains(exported(excluded, "PathClipOut"), "Z\"), ClipOp.Difference); ")
  }

  @Test
  fun `a path's gradient spans its viewport, which the path's scale maps onto the canvas`() {
    val source =
      exported(
        document(
          """"leaf":{"id":"leaf","componentId":"draw/path","properties":{
            "pathData":{"type":"string","value":"M2 2 L22 2 L12 22 Z"},
            "gradient":{"type":"enum","value":"horizontal"},
            "gradientColor":{"type":"color","value":"#FF00FF00"},
            "color":{"type":"color","value":"#FFFF0000"}}}""",
          "\"leaf\"",
        ),
        "PathGradient",
      )

    assertContains(source, "applyTo(this@RemotePaint, RemoteSize(24.rf, 24.rf))")
  }

  @Test
  fun `a path is built as the RemotePath the draw scope takes, once per file`() {
    val source =
      exported(
        document(
          """"leaf":{"id":"leaf","componentId":"draw/path","properties":{
            "pathData":{"type":"string","value":"M2 2 A10 10 0 0 1 22 2 Z"},
            "color":{"type":"color","value":"#FFFF0000"}}},
          "stem":{"id":"stem","componentId":"draw/path","properties":{
            "pathData":{"type":"string","value":"M12 2 L12 22"},
            "color":{"type":"color","value":"#FF00FF00"}}}""",
          "\"leaf\", \"stem\"",
        ),
        "PathTypes",
      )

    // `RemoteDrawScope.drawPath` takes the compose path's `RemotePath`, which has no string
    // constructor; the core writer's `androidx.compose.remote.creation.RemotePath` does, and an
    // export that wrote it did not compile.
    assertContains(source, "drawPath(remotePathOf(\"M2 2 A10 10 0 0 1 22 2 Z\"), ")
    assertContains(source, "import androidx.compose.remote.creation.compose.path.RemotePath")
    assertContains(source, "import androidx.compose.ui.graphics.vector.PathParser")
    assertContains(source, "import androidx.compose.ui.graphics.PathSegment")
    assertTrue("import androidx.compose.remote.creation.RemotePath\n" !in source, source)
    assertEquals(1, Regex("private fun remotePathOf\\(").findAll(source).count(), source)
  }

  @Test
  fun `a file with no path carries no path helper`() {
    val source =
      exported(
        document(
          """"dot":{"id":"dot","componentId":"draw/circle","properties":{
            "color":{"type":"color","value":"#FFFF0000"}}}""",
          "\"dot\"",
        ),
        "NoPath",
      )

    assertTrue("remotePathOf" !in source, source)
    assertTrue("PathParser" !in source, source)
  }

  @Test
  fun `a morph is a drawTweenPath driven by its progress`() {
    val source = exported(morph, "Morph")

    assertContains(
      source,
      "drawTweenPath(remotePathOf(\"M4 12 Q12 12 20 12\"), remotePathOf(\"M4 12 Q12 22 20 12\"), " +
        "tween = clamp(open, 0.rf, 1.rf), paint = paintMouth)",
    )
  }

  @Test
  fun `a morph between paths of different shapes is refused`() {
    val mismatched =
      morph.copy(
        nodes =
          morph.nodes +
            ("mouth" to
              morph.nodes
                .getValue("mouth")
                .copy(
                  properties =
                    JsonObject(
                      morph.nodes.getValue("mouth").properties +
                        ("toPathData" to
                          Json.parseToJsonElement("""{"type":"string","value":"M4 12 L20 12"}"""))
                    )
                ))
      )

    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(WearWidgetCodeExporter.export(mismatched))
    assertTrue(refused.reasons.any { "mouth.toPathData" in it }, refused.reasons.toString())
  }

  @Test
  fun `tweening interpolates numbers and keeps commands`() {
    assertEquals(
      "M4.0 12.0 Q12.0 17.0 20.0 12.0",
      UiDrawing.tweenPathData("M4 12 Q12 12 20 12", "M4 12 Q12 22 20 12", 0.5f)?.trim(),
    )
    assertNull(UiDrawing.tweenPathData("M4 12 L20 12", "M4 12 Q12 22 20 12", 0.5f))
    assertNull(UiDrawing.tweenPathData("M4 12 L20 12", "M4 12 C20 12", 0.5f))
  }

  /**
   * A path's stroke divides its viewport-to-canvas scale back out, so strokeWidthDp is dp on the
   * canvas as on every other operation, rather than scaled with the path.
   */
  @Test
  fun `a stroked path divides its viewport scale out of its stroke`() {
    val source =
      exported(
        document(
          """
          "outline":{"id":"outline","componentId":"draw/path","properties":{
            "pathData":{"type":"string","value":"M2 2 L22 22"},
            "style":{"type":"enum","value":"stroke"},
            "strokeWidthDp":{"type":"float","value":2}}}
          """,
          "\"outline\"",
        ),
        "StrokedPath",
      )

    assertContains(
      source,
      "strokeWidth = (2.rdp.toPx() / ((96.rdp.toPx() / 24.rf + 96.rdp.toPx() / 24.rf) / 2.rf))",
    )
  }
}
