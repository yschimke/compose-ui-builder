package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * A long press and a double tap on a layout are `combinedClickable`'s other two actions; touch
 * down, up and cancel are their own modifiers.
 */
class RemoteCombinedClickExportTest {
  @Test
  fun `long press and double tap write combinedClickable`() {
    val document =
      Json.decodeFromJsonElement<UiBuilderDocument>(
        Json.parseToJsonElement(
          """
          {"schema":"ui-builder-design-v1","id":"taps","title":"Taps","revision":1,
           "catalogPin":{},"environment":{},
           "stateVariables":{"count":{"type":"value","valueType":"int","initialValue":0,
             "nullable":false,"persistence":"session"}},
           "roots":["host"],
           "nodes":{
            "host":{"id":"host","componentId":"remote-m3/widget-container-small",
              "slots":{"content":["box"]}},
            "box":{"id":"box","componentId":"layout/box",
              "eventBindings":{
                "click":[{"type":"set","variable":"count","value":1}],
                "longClick":[{"type":"set","variable":"count","value":0}]}}}}
          """
        ) as JsonObject
      )

    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(WearWidgetCodeExporter.export(document))
        .source

    assertContains(source, "combinedClickable(onClick = ")
    assertContains(source, ", onLongClick = ")
    assertTrue("onDoubleClick" !in source, source)
    assertContains(
      source,
      "import androidx.compose.remote.creation.compose.modifier.combinedClickable",
    )
  }

  @Test
  fun `touch down, up and cancel write their modifiers after the click`() {
    val document =
      Json.decodeFromJsonElement<UiBuilderDocument>(
        Json.parseToJsonElement(
          """
          {"schema":"ui-builder-design-v1","id":"touches","title":"Touches","revision":1,
           "catalogPin":{},"environment":{},
           "stateVariables":{"pressed":{"type":"value","valueType":"bool","initialValue":false,
             "nullable":false,"persistence":"session"}},
           "roots":["host"],
           "nodes":{
            "host":{"id":"host","componentId":"remote-m3/widget-container-small",
              "slots":{"content":["box"]}},
            "box":{"id":"box","componentId":"layout/box",
              "eventBindings":{
                "touchUp":[{"type":"set","variable":"pressed","value":false}],
                "touchDown":[{"type":"set","variable":"pressed","value":true}],
                "touchCancel":[{"type":"set","variable":"pressed","value":false}]}}}}
          """
        ) as JsonObject
      )

    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(WearWidgetCodeExporter.export(document))
        .source

    // In down, up, cancel order whatever order the design lists them.
    val down = source.indexOf(".onTouchDown(")
    val up = source.indexOf(".onTouchUp(")
    val cancel = source.indexOf(".onTouchCancel(")
    assertTrue(down in 0 until up && up < cancel, source)
    assertTrue("clickable" !in source, source)
    for (call in listOf("onTouchDown", "onTouchUp", "onTouchCancel")) {
      assertContains(source, "import androidx.compose.remote.creation.compose.modifier.$call")
    }
  }
}
