package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * A page indicator's `RemotePageIndicatorState` is built from its two properties, so the dots can
 * follow a design's Int state.
 */
class RemotePageIndicatorExportTest {
  private fun export(selectedPage: String): WearWidgetCodeExporter.Result =
    WearWidgetCodeExporter.export(
      Json.decodeFromJsonElement<UiBuilderDocument>(
        Json.parseToJsonElement(
          """
          {"schema":"ui-builder-design-v1","id":"pages","title":"Pages","revision":1,
           "catalogPin":{},"environment":{},
           "stateVariables":{"page":{"type":"value","valueType":"int","initialValue":1,
             "nullable":false,"persistence":"session"}},
           "roots":["host"],
           "nodes":{
            "host":{"id":"host","componentId":"remote-m3/widget-container-small",
              "slots":{"content":["dots"]}},
            "dots":{"id":"dots","componentId":"$REMOTE_HORIZONTAL_PAGE_INDICATOR_ID",
              "properties":{"pageCount":{"type":"int","value":5},"selectedPage":$selectedPage,
                "selectedColor":{"type":"colorToken","value":"primary"}}}}}
          """
        ) as JsonObject
      )
    )

  @Test
  fun `a selected page read from state follows it`() {
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          export("""{"type":"state","variable":"page"}""")
        )
        .source

    assertContains(source, "RemoteHorizontalPageIndicator(")
    assertContains(source, "rememberRemotePageIndicatorState(pageCount = 5, selectedPage = page)")
    assertContains(source, "selectedColor = RemoteMaterialTheme.colorScheme.primary")
    assertContains(source, "val page = rememberMutableRemoteInt(1)")
    assertContains(
      source,
      "import androidx.wear.compose.remote.material3.rememberRemotePageIndicatorState",
    )
  }

  @Test
  fun `a literal selected page is an Int literal`() {
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(export("""{"type":"int","value":2}""")).source

    assertContains(source, "selectedPage = 2.ri")
    assertTrue("import androidx.compose.remote.creation.compose.state.ri" in source, source)
  }
}
