package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * An action may write a formula: the player computes it when the action runs, over the state as it
 * is then, so `x = x + 40` moves `x` on from wherever it has got to rather than to a value fixed at
 * export.
 */
class RemoteFormulaActionExportTest {
  private fun export(actions: String): WearWidgetCodeExporter.Result =
    WearWidgetCodeExporter.export(
      Json.decodeFromJsonElement<UiBuilderDocument>(
        Json.parseToJsonElement(
          """
          {"schema":"ui-builder-design-v1","id":"steps","title":"Steps","revision":1,
           "catalogPin":{},"environment":{},
           "stateVariables":{
             "x":{"type":"value","valueType":"float","initialValue":40,
               "nullable":false,"persistence":"session"},
             "count":{"type":"value","valueType":"int","initialValue":0,
               "nullable":false,"persistence":"session"},
             "label":{"type":"value","valueType":"string","initialValue":"",
               "nullable":false,"persistence":"session"}},
           "roots":["host"],
           "nodes":{
            "host":{"id":"host","componentId":"remote-m3/widget-container-small",
              "slots":{"content":["box"]}},
            "box":{"id":"box","componentId":"layout/box",
              "eventBindings":{"click":$actions}}}}
          """
        ) as JsonObject
      )
    )

  @Test
  fun `a click sets state to a formula over state`() {
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          export(
            """[{"type":"set","variable":"x","value":{"type":"expr","op":"add","args":[
                 {"type":"state","variable":"x"},{"type":"int","value":40}]}},
               {"type":"set","variable":"label","value":{"type":"expr","op":"concat","args":[
                 {"type":"state","variable":"count"},{"type":"string","value":" taps"}]}}]"""
          )
        )
        .source

    assertContains(source, "valueChange(x, (x + 40.rf))")
    assertContains(source, "valueChange(label, (count.toRemoteString() + \" taps\".rs))")
    assertContains(source, "combinedAction(")
  }

  @Test
  fun `an increment may add a formula`() {
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          export(
            """[{"type":"increment","variable":"count","amount":{"type":"expr","op":"add","args":[
                 {"type":"state","variable":"count"},{"type":"int","value":1}]}}]"""
          )
        )
        .source

    assertContains(source, "valueChange(count, (count + (count + 1.ri)))")
  }

  @Test
  fun `a formula the variable cannot hold is refused where it is`() {
    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(
        export(
          """[{"type":"set","variable":"count","value":{"type":"expr","op":"add","args":[
               {"type":"state","variable":"x"},{"type":"int","value":1}]}}]"""
        )
      )

    assertTrue(refused.reasons.any { "box" in it && "click" in it }, refused.reasons.toString())
  }
}
