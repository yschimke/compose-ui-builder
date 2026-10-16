package ee.schimke.composeai.uibuilder.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * `dragX` / `dragY` are the player's touch expression, written inside the canvas whose area takes
 * the touch.
 */
class RemoteDrawingDragExportTest {
  private fun formula(text: String) = UiExpressions.parseFormula(text, emptySet()).toString()

  private fun document(centerX: String, label: String? = null): UiBuilderDocument =
    Json.decodeFromJsonElement(
      Json.parseToJsonElement(
        """
        {"schema":"ui-builder-design-v1","id":"sliders","title":"Sliders","revision":1,
         "catalogPin":{},"environment":{},"stateVariables":{},
         "roots":["host"],
         "nodes":{
          "host":{"id":"host","componentId":"remote-m3/widget-container-large",
            "slots":{"content":["column"]}},
          "column":{"id":"column","componentId":"layout/column",
            "slots":{"children":["canvas"${if (label != null) ",\"label\"" else ""}]}},
          "canvas":{"id":"canvas","componentId":"draw/canvas",
            "modifiers":[{"type":"size","widthDp":96,"heightDp":96}],
            "slots":{"ops":["knob","track"]}},
          "knob":{"id":"knob","componentId":"draw/circle","properties":{
            "centerXDp":$centerX,
            "radiusDp":{"type":"float","value":8},
            "color":{"type":"color","value":"#FF6750A4"}}},
          "track":{"id":"track","componentId":"draw/line","properties":{
            "startXDp":{"type":"float","value":0},
            "endXDp":$centerX,
            "startYDp":{"type":"float","value":48},
            "endYDp":{"type":"float","value":48}}}
          ${if (label != null) ",\"label\":{\"id\":\"label\",\"componentId\":\"$REMOTE_TEXT_COMPONENT_ID\",\"properties\":{\"text\":$label}}" else ""}}}
        """
      ) as JsonObject
    )

  @Test
  fun `a drag is one touch expression in the canvas, read by every operation that uses it`() {
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          WearWidgetCodeExporter.export(
            document(formula("dragX(48, 8, 88, 1)")),
            packageName = "proof.draw",
          ),
          (WearWidgetCodeExporter.export(document(formula("dragX(48, 8, 88, 1)")))
              as? WearWidgetCodeExporter.Result.Refused)
            ?.reasons
            ?.toString(),
        )
        .source
    File("build/draw-proof")
      .apply { mkdirs() }
      .resolve("DragWidget.kt")
      .writeText(source.replace("SlidersWidget", "DragWidget"))

    assertEquals(1, Regex("document\\.addTouch\\(").findAll(source).count(), source)
    assertContains(
      source,
      "@Suppress(\"RestrictedApi\") val drag0 = androidx.compose.remote.creation.compose.state." +
        "RemoteFloat(document.addTouch(48f, 8f, 88f, 0, Float.NaN, 0, null, null, " +
        "androidx.compose.remote.core.RemoteContext.FLOAT_TOUCH_POS_X, 1f, " +
        "androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression.MUL, " +
        "androidx.compose.remote.core.RemoteContext.FLOAT_DENSITY, " +
        "androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression.DIV))",
    )
    // Declared inside the draw block, ahead of the operations that read it.
    assertTrue(source.indexOf("RemoteCanvas(") < source.indexOf("val drag0"), source)
    assertTrue(source.indexOf("val drag0") < source.indexOf("drawCircle"), source)
  }

  @Test
  fun `a drag is refused outside a canvas and with computed or reversed bounds`() {
    val outside =
      assertIs<WearWidgetCodeExporter.Result.Refused>(
        WearWidgetCodeExporter.export(
          document(
            """{"type":"float","value":48}""",
            label = formula("format(dragY(0, 0, 10, 0.1), 1)"),
          )
        )
      )
    assertTrue(outside.reasons.any { "inside a draw/canvas" in it }, outside.reasons.toString())

    for ((text, reason) in
      listOf(
        "dragX(48, 88, 8, 1)" to "minimum must be below its maximum",
        "dragX(time.hour, 0, 96, 1)" to "takes literal numbers",
      )) {
      val issue =
        UiExpressions.check(
          UiExpressions.parseFormula(text, emptySet()),
          UiExpressions.Scope(emptyMap()),
          "x",
        )
      assertIs<UiExpressions.Checked.Issue>(issue, text)
      assertContains(issue.message, reason)
    }
  }

  @Test
  fun `the preview shows a drag where the player starts it`() {
    val checked =
      assertIs<UiExpressions.Checked.Ok>(
        UiExpressions.check(
          UiExpressions.parseFormula("dragY(120, 0, 100, 1)", emptySet()),
          UiExpressions.Scope(emptyMap()),
          "x",
        )
      )
    assertEquals(100.0, UiExpressions.evaluate(checked.expr, UiExpressions.Environment()))
    // The player starts from the default unclamped, so the export clamps it as the preview does.
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          WearWidgetCodeExporter.export(document(formula("dragX(120, 8, 88, 1)")))
        )
        .source
    assertContains(source, "document.addTouch(88f, 8f, 88f, 0,")
  }
}
