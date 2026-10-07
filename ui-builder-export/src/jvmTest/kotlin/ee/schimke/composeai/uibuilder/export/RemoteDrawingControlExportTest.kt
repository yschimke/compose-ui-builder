package ee.schimke.composeai.uibuilder.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The drawing containers that decide *whether*, *where* and *how often* their operations draw — a
 * clip, a conditional and a loop — are written as the `RemoteDrawScope` blocks the player runs, and
 * a loop's index is a value its operations read by name.
 */
class RemoteDrawingControlExportTest {
  private fun document(json: String = DIAL): UiBuilderDocument =
    Json.decodeFromJsonElement(Json.parseToJsonElement(json) as JsonObject)

  private fun exported(document: UiBuilderDocument = document()): String =
    assertIs<WearWidgetCodeExporter.Result.Emitted>(
        WearWidgetCodeExporter.export(document, packageName = "proof.draw"),
        (WearWidgetCodeExporter.export(document) as? WearWidgetCodeExporter.Result.Refused)
          ?.reasons
          ?.toString(),
      )
      .source
      .also { source ->
        // Kept for compiling against the released libraries, as `RemoteDrawingExportTest` keeps
        // its.
        File("build/draw-proof").apply { mkdirs() }.resolve("DialWidget.kt").writeText(source)
      }

  private fun refusals(document: UiBuilderDocument): List<String> =
    assertIs<WearWidgetCodeExporter.Result.Refused>(WearWidgetCodeExporter.export(document)).reasons

  private fun UiBuilderDocument.withProperties(
    id: String,
    vararg properties: Pair<String, String>,
  ): UiBuilderDocument {
    val node = nodes.getValue(id)
    val changed =
      properties.fold(node.properties.toMap()) { held, (name, value) ->
        if (value.isEmpty()) held - name else held + (name to Json.parseToJsonElement(value))
      }
    return copy(nodes = nodes + (id to node.copy(properties = JsonObject(changed))))
  }

  @Test
  fun `a repeat is a loop whose index its operations read by name`() {
    val source = exported()

    // One block whose parameter is the index: `{ i -> {` would compile to a lambda nobody calls.
    assertContains(source, "loop(0.rf, 12.rf, 1.rf) { i ->\n")
    // `@i * 30` turns each tick a twelfth of the way round.
    assertContains(source, "rotate((i * 30.rf), ")
    // The tick is drawn inside the loop, so it is drawn once per index.
    assertTrue(source.indexOf("loop(") < source.indexOf("val paintMark"), source)
  }

  @Test
  fun `a clip is a clipRect over its box, and excluding flips it`() {
    val source = exported()

    assertContains(
      source,
      "clipRect(0.rf, 48.rdp.toPx(), 96.rdp.toPx(), (48.rdp.toPx() + 48.rdp.toPx())) {",
    )

    val excluded =
      exported(document().withProperties("window", "exclude" to """{"type":"bool","value":true}"""))
    assertContains(excluded, ", clipOp = ClipOp.Difference) {")
    assertContains(excluded, "import androidx.compose.ui.graphics.ClipOp")
  }

  @Test
  fun `a conditional draws while its state is true`() {
    val source = exported()

    assertContains(source, "drawConditionally(alarmOn) {")
    assertContains(source, "val alarmOn = rememberMutableRemoteBoolean(true)")
  }

  @Test
  fun `a direct binding to the index is the index`() {
    val source =
      exported(document().withProperties("tick", "rotate" to """{"type":"binding","value":"i"}"""))

    assertContains(source, "rotate(i, ")
  }

  @Test
  fun `a repeat refuses what the player cannot run`() {
    val dial = document()

    assertTrue(
      refusals(dial.withProperties("ticks", "until" to "")).any { "ticks.until" in it },
      "no until",
    )
    assertTrue(
      refusals(dial.withProperties("ticks", "step" to """{"type":"float","value":0}""")).any {
        "ticks.step" in it
      },
      "a zero step never ends",
    )
    assertTrue(
      refusals(dial.withProperties("ticks", "index" to """{"type":"string","value":"2nd"}""")).any {
        "ticks.index" in it
      },
      "not a name",
    )
    // The player cannot check a step it computes, and one that reaches 0 never ends.
    assertTrue(
      refusals(dial.withProperties("ticks", "step" to """{"type":"state","variable":"alarmOn"}"""))
        .any { "ticks.step" in it },
      "a dynamic step",
    )
    // The index names the lambda parameter, so it is fixed when the loop is written.
    assertTrue(
      refusals(dial.withProperties("ticks", "index" to """{"type":"state","variable":"alarmOn"}"""))
        .any { "ticks.index" in it },
      "a dynamic index",
    )
  }

  @Test
  fun `a clip's ClipOp is fixed when it is written`() {
    val refused =
      refusals(
        document()
          .withProperties("window", "exclude" to """{"type":"state","variable":"alarmOn"}""")
      )

    assertTrue(refused.any { "window.exclude" in it }, refused.toString())
  }

  @Test
  fun `a loop index never shadows state of the same name`() {
    val source =
      exported(
        document()
          .let { dial ->
            dial.copy(
              stateVariables =
                JsonObject(
                  dial.stateVariables +
                    ("i" to
                      Json.parseToJsonElement(
                        """{"type":"value","valueType":"float","initialValue":2,
                          "nullable":false,"persistence":"session"}"""
                      ))
                )
            )
          }
          .withProperties(
            "mark",
            "endYDp" to
              """{"type":"expr","op":"add","args":[{"type":"state","variable":"i"},{"type":"int","value":10}]}""",
          )
      )

    assertContains(source, "loop(0.rf, 12.rf, 1.rf) { i_ ->\n")
    assertContains(source, "rotate((i_ * 30.rf), ")
    assertContains(source, "(i + 10.rf)")
  }

  @Test
  fun `an index read outside its loop is refused`() {
    val stray =
      document().let { dial ->
        dial.copy(
          nodes =
            dial.nodes +
              ("dot" to
                dial.nodes
                  .getValue("dot")
                  .copy(
                    properties =
                      JsonObject(
                        dial.nodes.getValue("dot").properties +
                          ("radiusDp" to
                            Json.parseToJsonElement(
                              """{"type":"expr","op":"mul","args":[{"type":"binding","value":"i"},{"type":"int","value":2}]}"""
                            ))
                      )
                  ))
        )
      }

    assertTrue(refusals(stray).any { "dot.radiusDp" in it }, refusals(stray).toString())
  }

  @Test
  fun `the scope a formula is checked against knows the loops around it`() {
    val dial = document()

    assertTrue(UiDrawing.loopIndices(dial, "mark") == listOf("i"))
    assertTrue(UiDrawing.loopIndices(dial, "dot").isEmpty())
    val formula = UiExpressions.parseFormula("@i * 30", dial.stateVariables.keys)
    assertIs<UiExpressions.Checked.Ok>(
      UiExpressions.check(formula, UiDrawing.expressionScope(dial, "tick"))
    )
    assertIs<UiExpressions.Checked.Issue>(
      UiExpressions.check(formula, UiDrawing.expressionScope(dial, "dot"))
    )
    assertTrue(
      JsonPrimitive("i") ==
        (formula as JsonObject)["args"]
          ?.let { (it as kotlinx.serialization.json.JsonArray)[0] as JsonObject }
          ?.get("value")
    )
  }

  companion object {
    /** Twelve ticks round a dial, the lower half shaded through a clip, and an alarm dot. */
    val DIAL =
      """
      {"schema":"ui-builder-design-v1","id":"dial","title":"Dial","revision":1,
       "catalogPin":{},"environment":{},
       "stateVariables":{"alarmOn":{"type":"value","valueType":"bool","initialValue":true,
         "nullable":false,"persistence":"session"}},
       "roots":["host"],
       "nodes":{
        "host":{"id":"host","componentId":"remote-m3/widget-container-large",
          "slots":{"content":["canvas"]}},
        "canvas":{"id":"canvas","componentId":"draw/canvas",
          "modifiers":[{"type":"size","widthDp":96,"heightDp":96}],
          "slots":{"ops":["ticks","window","alarm"]}},
        "ticks":{"id":"ticks","componentId":"draw/repeat","properties":{
          "until":{"type":"float","value":12}},
          "slots":{"ops":["tick"]}},
        "tick":{"id":"tick","componentId":"draw/group","properties":{
          "rotate":{"type":"expr","op":"mul","args":[
            {"type":"binding","value":"i"},{"type":"int","value":30}]}},
          "slots":{"ops":["mark"]}},
        "mark":{"id":"mark","componentId":"draw/line","properties":{
          "startXDp":{"type":"float","value":48},"startYDp":{"type":"float","value":4},
          "endXDp":{"type":"float","value":48},"endYDp":{"type":"float","value":10},
          "strokeWidthDp":{"type":"float","value":2},
          "color":{"type":"color","value":"#FFFFFFFF"}}},
        "window":{"id":"window","componentId":"draw/clip","properties":{
          "yDp":{"type":"float","value":48},"heightDp":{"type":"float","value":48}},
          "slots":{"ops":["shade"]}},
        "shade":{"id":"shade","componentId":"draw/circle","properties":{
          "color":{"type":"color","value":"#FF6750A4"}}},
        "alarm":{"id":"alarm","componentId":"draw/if","properties":{
          "condition":{"type":"state","variable":"alarmOn"}},
          "slots":{"ops":["dot"]}},
        "dot":{"id":"dot","componentId":"draw/circle","properties":{
          "centerXDp":{"type":"float","value":72},"centerYDp":{"type":"float","value":24},
          "radiusDp":{"type":"float","value":4},
          "color":{"type":"color","value":"#FFFF0000"}}}}}
      """
        .trimIndent()
  }
}
