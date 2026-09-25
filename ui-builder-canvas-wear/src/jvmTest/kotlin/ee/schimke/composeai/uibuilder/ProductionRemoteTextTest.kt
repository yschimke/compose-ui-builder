package ee.schimke.composeai.uibuilder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.export.REMOTE_TEXT_COMPONENT_ID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A published `remote-m3` widget's text in a production PNG export.
 *
 * preview.coo.ee serves wear-m3-catalog's published `remote-m3`, whose text component is
 * `remote-m3/remote-text`; the frozen golden this renderer reads predates it. Every widget exported
 * as a PNG therefore drew each of its labels as a red "Unsupported component" box, and the title
 * cards around them dropped the slot mapping the editor provides.
 */
@OptIn(ExperimentalTestApi::class)
class ProductionRemoteTextTest {
  private val document = decodeProductionRendererDocument(WIDGET_DOCUMENT)

  @Test
  fun `remote text is drawn by the Wear text adapter`() {
    val catalog = assertNotNull(productionPreviewCatalog(document))

    assertEquals("wear-m3/text", catalog.productionCanvasAdapterIds()[REMOTE_TEXT_COMPONENT_ID])
    // The golden's own adapters are untouched.
    assertEquals("wear-m3/button", catalog.productionCanvasAdapterIds()["remote-m3/remote-button"])
    assertEquals(
      mapOf("fontSizeSp" to "fontSize"),
      catalog.productionCanvasAdapterMappings().getValue(REMOTE_TEXT_COMPONENT_ID).properties,
    )
  }

  @Test
  fun `a widget's text renders in a production export rather than as an error`() =
    runDesktopComposeUiTest(width = 600, height = 400) {
      setContent { ProductionUiBuilderSurface(document) }

      onNodeWithText("Alarm").assertExists()
      onNodeWithText("2:58").assertExists()
      // Fails before the fix: both labels drew "Unsupported component: remote-m3/remote-text".
      onNodeWithText("Unsupported component", substring = true).assertDoesNotExist()
    }

  private companion object {
    val WIDGET_DOCUMENT =
      """
      {"schema":"compose-ui-builder-document/v1-candidate","id":"golden-tiles-alarm",
       "title":"Golden Tiles · Alarm","revision":5,
       "catalogPin":{"systemId":"remote-m3","catalogRevision":"candidate",
         "capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
       "environment":{"widthDp":216,"heightDp":124,"density":2.0,"theme":"dark",
         "locale":"en-US","fontScale":1.0,"layoutDirection":"ltr"},
       "stateVariables":{},"roots":["widget"],
       "nodes":{
        "widget":{"id":"widget","componentId":"remote-m3/widget-container-large",
          "properties":{},"modifiers":[],
          "slots":{"background":[],"content":["column"]},"eventBindings":{}},
        "column":{"id":"column","componentId":"layout/column","properties":{},
          "modifiers":[],"slots":{"children":["header","time"]},"eventBindings":{}},
        "header":{"id":"header","componentId":"remote-m3/remote-text",
          "properties":{"text":{"type":"string","value":"Alarm"},
            "style":{"type":"enum","value":"labelMedium"}},
          "modifiers":[],"slots":{},"eventBindings":{}},
        "time":{"id":"time","componentId":"remote-m3/remote-text",
          "properties":{"text":{"type":"string","value":"2:58"},
            "style":{"type":"enum","value":"numeralSmall"}},
          "modifiers":[],"slots":{},"eventBindings":{}}}}
      """
        .trimIndent()
  }
}
