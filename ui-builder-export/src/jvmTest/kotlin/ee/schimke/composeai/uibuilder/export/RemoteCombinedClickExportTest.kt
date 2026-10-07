package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** A long press and a double tap on a layout are `combinedClickable`'s other two actions. */
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
}
