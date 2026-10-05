package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.canvas.UiBuilderDevicePreset
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.screenEnvironmentSettings
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The compact layout's view tabs: the editor, then one tab per frame the wide layout's Preview pane
 * draws. A phone has no room for that pane beside the canvas, so these are how it sees a device.
 */
@OptIn(ExperimentalTestApi::class)
class MobileViewTabsTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val phone = UiBuilderDevicePreset("id:pixel_7", "Pixel 7", "Phones", 411, 914, 2.625)
  private val tablet =
    UiBuilderDevicePreset("id:pixel_tablet", "Pixel Tablet", "Tablets", 1280, 800, 2.0)

  /** The fixture, targeting both devices. */
  private val document =
    UiBuilderReducer.replay(fixture).document.let { base ->
      val initial = reducer.initial(base, selectedNodeId = null)
      val settings = initial.document.screenEnvironmentSettings()
      reducer
        .reduce(
          initial,
          UiBuilderEditorEvent.UpdateEnvironment(
            settings.copy(exportDevices = listOf(phone.id, tablet.id))
          ),
        )
        .document
    }

  @Test
  fun `a phone switches between the editor and each device`() =
    runDesktopComposeUiTest(width = 412, height = 915) {
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            devicePresets = listOf(phone, tablet),
          )
        }
      }
      waitForIdle()

      onNodeWithText("Editor").assertIsSelected()
      onNodeWithText("Preview").assertExists()
      onNodeWithText("Pixel Tablet").assertExists()

      onNodeWithText("Pixel 7").performClick()
      waitForIdle()
      onNodeWithText("Pixel 7").assertIsSelected()
      // The device's frame, under its full label, and only that one.
      onNodeWithText("Pixel 7 · 411×914dp · 2.625×").assertExists()
      onNodeWithText("Pixel Tablet · 1280×800dp · 2×").assertDoesNotExist()

      onNodeWithText("Editor").performClick()
      waitForIdle()
      onNodeWithText("Pixel 7 · 411×914dp · 2.625×").assertDoesNotExist()
    }

  /** Every dock panel edits or feeds the canvas, so opening one leaves the preview for it. */
  @Test
  fun `a dock panel opened on a device tab returns to the editor`() =
    runDesktopComposeUiTest(width = 412, height = 915) {
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            devicePresets = listOf(phone, tablet),
          )
        }
      }
      waitForIdle()

      onNodeWithText("Pixel Tablet").performClick()
      waitForIdle()
      onNodeWithText("Pixel Tablet · 1280×800dp · 2×").assertExists()

      onNodeWithContentDescription("Open layers panel").performClick()
      waitForIdle()
      onNodeWithText("Editor").assertIsSelected()
      onNodeWithText("Pixel Tablet · 1280×800dp · 2×").assertDoesNotExist()
      onNodeWithContentDescription("Close layers panel").assertExists()
    }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private companion object {
    private val fixture =
      Json.parseToJsonElement(
          checkNotNull(
              MobileViewTabsTest::class.java.getResource("/jetcaster-discover-operations-v1.json")
            )
            .readText()
        )
        .jsonObject
  }
}
