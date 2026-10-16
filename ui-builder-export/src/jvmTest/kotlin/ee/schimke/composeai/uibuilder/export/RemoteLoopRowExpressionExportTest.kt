package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A loop row's field read inside an expression is a Remote-typed row property, each row supplying
 * its literal, so the player computes with it like any other operand.
 */
class RemoteLoopRowExpressionExportTest {
  @kotlin.test.BeforeTest
  fun requireExperimentalBuild() {
    org.junit.Assume.assumeTrue(
      "Enable with -PuiBuilderRemoteCompose=true",
      UiBuilderBuildFeatures.remoteCompose,
    )
  }

  private fun label(text: JsonObject) =
    UiBuilderNode(
      "label",
      REMOTE_TEXT_COMPONENT_ID,
      properties = JsonObject(mapOf("text" to text)),
      modifiers = JsonArray(emptyList()),
    )

  private fun emit(text: JsonObject, rows: String): Pair<String, List<String>> =
    emit(rows, "label", label(text))

  private fun emit(
    rows: String,
    template: String,
    vararg nodes: UiBuilderNode,
  ): Pair<String, List<String>> {
    val refusals = mutableListOf<String>()
    val emitter =
      RemoteContentEmitter(
        UiBuilderDocument(
          schema = "ui-builder-design-v1",
          id = "rows",
          title = "Rows",
          revision = 1,
          catalogPin = JsonObject(emptyMap()),
          environment = JsonObject(emptyMap()),
          stateVariables =
            JsonObject(
              mapOf(
                "count" to
                  JsonObject(
                    mapOf(
                      "type" to JsonPrimitive("value"),
                      "valueType" to JsonPrimitive("float"),
                      "initialValue" to JsonPrimitive(2),
                    )
                  )
              )
            ),
          roots = listOf("loop"),
          nodes =
            mapOf(
              "loop" to
                UiBuilderNode(
                  "loop",
                  "layout/for-each",
                  properties =
                    JsonObject(mapOf("data" to Json.parseToJsonElement(rows).jsonObject)),
                  slots = mapOf("template" to listOf(template)),
                )
            ) + nodes.associateBy { it.id },
        ),
        refusals,
      )
    return emitter.emit("loop", 1).joinToString("\n") to refusals
  }

  private val rows =
    """{"type":"list","values":[
      {"type":"object","fields":{"name":{"type":"string","value":"Steps"},
        "goal":{"type":"float","value":10}}},
      {"type":"object","fields":{"name":{"type":"string","value":"Floors"},
        "goal":{"type":"float","value":4}}}]}"""

  @Test
  fun `row fields inside an expression are Remote row properties`() {
    val (source, refusals) =
      emit(
        UiExpressions.parseFormula(
          "concat(@name, \": \", format(@goal * count, 0))",
          setOf("count"),
        ),
        rows,
      )

    assertTrue(refusals.isEmpty(), refusals.toString())
    assertContains(
      source,
      "val argument0: androidx.compose.remote.creation.compose.state.RemoteString",
    )
    assertContains(
      source,
      "val argument1: androidx.compose.remote.creation.compose.state.RemoteFloat",
    )
    assertContains(source, "\"Steps\".rs")
    assertContains(source, "uiRow0.argument0")
    assertContains(source, "uiRow0.argument1")
  }

  @Test
  fun `a field whose rows disagree on its kind is not readable`() {
    val mixed =
      """{"type":"list","values":[
        {"type":"object","fields":{"goal":{"type":"float","value":10}}},
        {"type":"object","fields":{"goal":{"type":"string","value":"ten"}}}]}"""
    val (_, refusals) = emit(UiExpressions.parseFormula("concat(@goal, \"\")", emptySet()), mixed)

    assertTrue(refusals.any { "goal" in it }, refusals.toString())
  }

  @Test
  fun `index is each row's position when the template reads it`() {
    val (source, refusals) =
      emit(
        UiExpressions.parseFormula("concat(@name, \" \", format(@index + 1, 0))", emptySet()),
        rows,
      )

    assertTrue(refusals.isEmpty(), refusals.toString())
    assertContains(source, "\"Steps\".rs, 0.rf")
    assertContains(source, "\"Floors\".rs, 1.rf")
  }

  @Test
  fun `an authored index field is the row's own`() {
    val authored =
      """{"type":"list","values":[
        {"type":"object","fields":{"index":{"type":"float","value":7}}}]}"""
    val (source, refusals) =
      emit(UiExpressions.parseFormula("format(@index, 0)", emptySet()), authored)

    assertTrue(refusals.isEmpty(), refusals.toString())
    assertContains(source, "UiRows0(7.rf)")
  }

  @Test
  fun `a field read directly and inside an expression is two row properties`() {
    val column =
      UiBuilderNode(
        "column",
        "layout/column",
        properties =
          JsonObject(
            mapOf(
              "verticalSpacingDp" to
                JsonObject(
                  mapOf("type" to JsonPrimitive("binding"), "value" to JsonPrimitive("goal"))
                )
            )
          ),
        slots = mapOf("children" to listOf("label")),
      )
    val (source, refusals) =
      emit(
        rows,
        "column",
        column,
        label(UiExpressions.parseFormula("format(@goal, 0)", emptySet())),
      )

    assertTrue(refusals.isEmpty(), refusals.toString())
    assertContains(source, "val argument0: kotlin.Float")
    assertContains(
      source,
      "val argument1: androidx.compose.remote.creation.compose.state.RemoteFloat",
    )
    assertContains(source, "UiRows0(10.0f, 10.rf")
  }

  @Test
  fun `the editor and validator scope reads the enclosing loop's row fields`() {
    val document =
      UiBuilderDocument(
        schema = "ui-builder-design-v1",
        id = "rows",
        title = "Rows",
        revision = 1,
        catalogPin = JsonObject(emptyMap()),
        environment = JsonObject(emptyMap()),
        stateVariables = JsonObject(emptyMap()),
        roots = listOf("loop"),
        nodes =
          mapOf(
            "loop" to
              UiBuilderNode(
                "loop",
                "layout/for-each",
                properties = JsonObject(mapOf("data" to Json.parseToJsonElement(rows).jsonObject)),
                slots = mapOf("template" to listOf("label")),
              ),
            "label" to label(JsonObject(emptyMap())),
          ),
      )
    val kinds = UiDrawing.indexKinds(document, "label")

    assertEquals(UiValueKind.STRING, kinds("name"))
    assertEquals(UiValueKind.FLOAT, kinds("goal"))
    assertEquals(UiValueKind.FLOAT, kinds("index"))
    assertEquals(null, UiDrawing.indexKinds(document, "loop")("name"))
  }
}
