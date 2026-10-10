package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import ee.schimke.composeai.uibuilder.editor.UiBuilderVariantPane
import ee.schimke.composeai.uibuilder.editor.currentFramePane
import ee.schimke.composeai.uibuilder.editor.mobileTabLabel
import ee.schimke.composeai.uibuilder.editor.screenEnvironmentSettings
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSurfaceModeV2
import kotlin.test.Test
import kotlin.test.assertEquals
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

  /**
   * Switching device tabs never builds a frame. The tab opened first is built for it, the others
   * behind it once it has drawn, and from then on a tap shows a frame that is already standing.
   * Before, every tap built a new frame and composed the whole design into it, even for the tab
   * visited a moment ago.
   */
  @Test
  fun `switching device tabs shows frames already built`() =
    runDesktopComposeUiTest(width = 412, height = 915) {
      var deviceFrames = 0
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            devicePresets = listOf(phone, tablet),
            canvasRenderer = { _, surface, _, _, _, _ ->
              if (surface.mode == UiBuilderRendererSurfaceModeV2.DEVICE) remember { deviceFrames++ }
              Box(Modifier.fillMaxSize())
            },
          )
        }
      }
      waitForIdle()
      assertEquals(0, deviceFrames, "no device frame while the editor is all that shows")

      onNodeWithText("Pixel 7").performClick()
      waitForIdle()
      mainClock.advanceTimeBy(2_000)
      waitForIdle()
      // The design's own frame, Pixel 7 and Pixel Tablet: every tab, each built once.
      assertEquals(3, deviceFrames, "every device tab built behind the one opened")

      for (device in listOf("Pixel Tablet", "Preview", "Pixel 7", "Pixel Tablet")) {
        onNodeWithText(device).performClick()
        waitForIdle()
      }
      assertEquals(3, deviceFrames, "a tap shows a standing frame rather than building one")
      // Only the tab showing is in the semantics tree; the kept ones are hidden from it too.
      onNodeWithText("Pixel Tablet · 1280×800dp · 2×").assertExists()
      onNodeWithText("Pixel 7 · 411×914dp · 2.625×").assertDoesNotExist()
    }

  /**
   * Every dock the wide rail has is reachable on a phone: Issues is a button of its own, and the
   * rest are behind More, by the rail's names. Before, Theme, Screen, Issues, Talk and History had
   * no way in at all on a phone.
   */
  @Test
  fun `a phone reaches every inspector panel from the dock`() =
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

      onNodeWithContentDescription("Open issues panel").performClick()
      waitForIdle()
      onNodeWithText("What the export would refuse").assertExists()
      onNodeWithContentDescription("Close issues panel").assertExists()

      for ((label, supporting) in
        listOf(
          "Theme" to "Colours, type and shape for the whole design",
          "Screen" to "Frame, density and reference",
          "Talk" to "What people and agents have said",
          "History" to "What has been done, newest first",
        )) {
        onNodeWithContentDescription("More panels", substring = true).performClick()
        waitForIdle()
        onNodeWithContentDescription("Open ${label.lowercase()} panel").performClick()
        waitForIdle()
        onNodeWithText(supporting).assertExists()
        onNodeWithContentDescription("More panels, $label open").assertExists()
      }

      // A sheet's own close button closes it, whichever inspector it is showing.
      onNodeWithContentDescription("More panels", substring = true).performClick()
      waitForIdle()
      onNodeWithContentDescription("Open theme panel").performClick()
      waitForIdle()
      onNodeWithContentDescription("Close Theme").performClick()
      waitForIdle()
      onNodeWithText("Colours, type and shape for the whole design").assertDoesNotExist()

      // Properties shows the properties again, not whichever inspector was open last.
      onNodeWithContentDescription("Open properties panel").performClick()
      waitForIdle()
      onNodeWithContentDescription("Close properties panel").assertExists()
      onNodeWithText("What has been done, newest first").assertDoesNotExist()
    }

  /**
   * A device is named alone, its size and density being on the frame's own label; a widget host's
   * size is what tells two tabs apart, so it stays.
   */
  @Test
  fun `tab labels keep what tells two panes apart`() {
    val design = UiBuilderReducer.replay(fixture).document
    fun pane(id: String, label: String) = UiBuilderVariantPane(id, label, 192f, 192f, design)

    assertEquals("Preview", design.currentFramePane("Preview").mobileTabLabel())
    assertEquals(
      "Pixel 7",
      pane("variant-device-id:pixel_7", "Pixel 7 · 411×914dp · 2.625×").mobileTabLabel(),
    )
    assertEquals(
      listOf("Rectangular · Small", "Rectangular · Large"),
      listOf(
          pane("preview-widget-rectangular-small", "Rectangular · Small"),
          pane("preview-widget-rectangular-large", "Rectangular · Large"),
        )
        .map { it.mobileTabLabel() },
    )
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
