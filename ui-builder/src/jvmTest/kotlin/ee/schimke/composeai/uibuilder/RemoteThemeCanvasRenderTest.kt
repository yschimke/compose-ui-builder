package ee.schimke.composeai.uibuilder

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.export.UiRemoteTheme
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `remote-m3/remote-material-theme` is on the remote-m3 palette, and the canvas draws its child
 * under the roles it overrides: a button, which fills with `primary`, turns red.
 */
@OptIn(ExperimentalTestApi::class)
class RemoteThemeCanvasRenderTest {
  @Test
  fun `the palette offers the theme`() {
    val catalog =
      CapabilityCatalogParser.parse(
        checkNotNull(javaClass.getResource("/remote-m3-capabilities-v1.json")).readText()
      )

    assertNotNull(catalog.componentsById[UiRemoteTheme.ID])
  }

  @Test
  fun `the child reads the overridden primary`() {
    val themed = red(document(primary = "#FFFF0000"))
    val stock = red(document(primary = null))

    assertTrue(themed > 2_000, "the themed button is not red ($themed red pixels)")
    assertTrue(stock < themed / 10, "the stock button is red too ($stock red pixels)")
  }

  private fun red(json: String): Int {
    var count = 0
    runDesktopComposeUiTest(width = 600, height = 400) {
      setContent { ProductionUiBuilderSurface(decodeProductionRendererDocument(json)) }
      val pixels = onRoot().captureToImage().toPixelMap()
      for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
        val pixel = pixels[x, y]
        if (pixel.red > 0.8f && pixel.green < 0.3f && pixel.blue < 0.3f) count++
      }
    }
    return count
  }

  private fun document(primary: String?): String {
    val properties =
      primary?.let { """{"themePrimaryColor":{"type":"color","value":"$it"}}""" } ?: "{}"
    return """
      {"schema":"compose-ui-builder-document/v1-candidate","id":"themed","title":"Themed",
       "revision":1,
       "catalogPin":{"systemId":"remote-m3","catalogRevision":"candidate",
         "capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
       "environment":{"widthDp":216,"heightDp":124,"density":2.0,"theme":"dark",
         "locale":"en-US","fontScale":1.0,"layoutDirection":"ltr"},
       "stateVariables":{},"roots":["widget"],
       "nodes":{
        "widget":{"id":"widget","componentId":"remote-m3/widget-container-large",
          "properties":{},"modifiers":[],
          "slots":{"background":[],"content":["theme"]},"eventBindings":{}},
        "theme":{"id":"theme","componentId":"remote-m3/remote-material-theme",
          "properties":$properties,"modifiers":[],
          "slots":{"children":["button"]},"eventBindings":{}},
        "button":{"id":"button","componentId":"remote-m3/remote-button",
          "properties":{},"modifiers":[{"type":"size","widthDp":120,"heightDp":48}],
          "slots":{},"eventBindings":{}}}}
      """
      .trimIndent()
  }
}
